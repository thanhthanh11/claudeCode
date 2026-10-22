package com.bank.saga;

import com.bank.account.AccountServiceApplication;
import com.bank.common.event.EventTypes;
import com.bank.common.event.KafkaTopics;
import com.bank.common.security.AuthUser;
import com.bank.common.security.JwtTokenProvider;
import com.bank.customer.CustomerServiceApplication;
import com.bank.transaction.TransactionServiceApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.oracle.OracleContainer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Saga chuyển tiền end-to-end: customer-service, account-service, transaction-service chạy thật trong cùng JVM
 * (mỗi service một Spring context, cấu hình lấy từ chính config-repo) trên Oracle + Kafka + Redis của Testcontainers.
 * Luồng thật: REST → Redis request-reply → outbox → Kafka → account-service → Kafka → Saga.
 * Chạy: {@code mvn -pl saga-it -am verify -Pintegration-test} (cần Docker).
 */
@Testcontainers
class TransferSagaIT {

    private static final Path CONFIG_REPO = Path.of("..", "config-server", "src", "main", "resources", "config-repo")
            .toAbsolutePath().normalize();
    private static final Path SCRIPTS = Path.of("..", "database", "oracle").toAbsolutePath().normalize();
    private static final Set<String> TERMINAL = Set.of("COMPLETED", "FAILED", "COMPENSATED");

    @Container
    static final OracleContainer ORACLE = new OracleContainer("gvenzl/oracle-free:23-slim-faststart")
            .withStartupTimeout(Duration.ofMinutes(5));
    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<ConfigurableApplicationContext> CONTEXTS = new ArrayList<>();
    private static JdbcTemplate accountDb;
    private static JdbcTemplate transactionDb;
    private static RestClient transactionApi;
    private static String tellerToken;

    @BeforeAll
    static void startSystem() throws Exception {
        createSchemas();
        seedData();
        CONTEXTS.add(start(CustomerServiceApplication.class, "customer-service", "BANK_CUSTOMER", "ChangeMe_Customer1"));
        CONTEXTS.add(start(AccountServiceApplication.class, "account-service", "BANK_ACCOUNT", "ChangeMe_Account1"));
        ConfigurableApplicationContext transaction = start(TransactionServiceApplication.class, "transaction-service",
                "BANK_TRANSACTION", "ChangeMe_Transaction1");
        CONTEXTS.add(transaction);

        transactionApi = RestClient.create("http://localhost:" + transaction.getEnvironment().getProperty("local.server.port"));
        tellerToken = transaction.getBean(JwtTokenProvider.class).generateAccessToken(new AuthUser(900L, "saga.teller",
                Set.of("TELLER"), Set.of("ACCOUNT_READ", "TRANSFER_CREATE", "TRANSACTION_READ", "CUSTOMER_READ",
                "CUSTOMER_MANAGE"), null));
    }

    @AfterAll
    static void stopSystem() {
        CONTEXTS.reversed().forEach(ConfigurableApplicationContext::close);
    }

    @Test
    void shouldCompleteTransferAndPostFee() {
        BigDecimal feeBefore = internalFeeBalance();

        String ref = transfer("100302000011", "100302000012", "1000000", newKey()).path("transactionRef").asText();

        JsonNode tx = awaitTerminal(ref);
        assertThat(tx.path("status").asText()).isEqualTo("COMPLETED");
        BigDecimal fee = tx.path("fee").decimalValue();
        assertThat(fee).isEqualByComparingTo("2000");
        assertThat(balance("100302000011")).isEqualByComparingTo(new BigDecimal("100000000").subtract(new BigDecimal("1000000")).subtract(fee));
        assertThat(balance("100302000012")).isEqualByComparingTo("1000000");
        assertThat(internalFeeBalance().subtract(feeBefore)).isEqualByComparingTo(fee);
        // DEBIT nguồn (amount + fee), CREDIT đích, CREDIT tài khoản thu phí
        assertThat(ledgerCount(ref)).isEqualTo(3);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(transactionDb.queryForObject(
                "SELECT COUNT(*) FROM OUTBOX_EVENTS WHERE AGGREGATE_ID = ? AND STATUS <> 'PUBLISHED'",
                Integer.class, ref)).isZero());
    }

    @Test
    void shouldFailWithoutLedgerWhenBalanceDropsBeforeDebit() throws Exception {
        String source = "100302000021";
        String ref;
        // Giữ khóa dòng tài khoản nguồn: lệnh DEBIT phải chờ, trong lúc đó số dư giảm (mô phỏng giao dịch khác chen vào)
        try (Connection lock = lockAccount(source)) {
            ref = transfer(source, "100302000022", "1000000", newKey()).path("transactionRef").asText();
            try (var update = lock.prepareStatement("UPDATE ACCOUNTS SET BALANCE = 500, AVAILABLE_BALANCE = 500, "
                    + "VERSION = VERSION + 1 WHERE ACCOUNT_NUMBER = ?")) {
                update.setString(1, source);
                update.executeUpdate();
            }
            lock.commit();
        }

        JsonNode tx = awaitTerminal(ref);
        assertThat(tx.path("status").asText()).isEqualTo("FAILED");
        assertThat(ledgerCount(ref)).isZero();
        assertThat(balance(source)).isEqualByComparingTo("500");
        assertThat(balance("100302000022")).isEqualByComparingTo("0");
    }

    @Test
    void shouldCompensateWhenCreditFails() throws Exception {
        String source = "100302000031";
        String target = "100302000032";
        BigDecimal feeBefore = internalFeeBalance();
        String ref;
        // DEBIT chờ khóa; trong lúc đó tài khoản đích bị phong tỏa → CREDIT lỗi → hoàn tiền về nguồn
        try (Connection lock = lockAccount(source)) {
            ref = transfer(source, target, "2000000", newKey()).path("transactionRef").asText();
            try (var update = lock.prepareStatement(
                    "UPDATE ACCOUNTS SET STATUS = 'FROZEN', VERSION = VERSION + 1 WHERE ACCOUNT_NUMBER = ?")) {
                update.setString(1, target);
                update.executeUpdate();
            }
            lock.commit();
        }

        JsonNode tx = awaitTerminal(ref);
        assertThat(tx.path("status").asText()).isEqualTo("COMPENSATED");
        assertThat(balance(source)).isEqualByComparingTo("10000000");
        assertThat(balance(target)).isEqualByComparingTo("0");
        assertThat(internalFeeBalance()).isEqualByComparingTo(feeBefore);
        // DEBIT + CREDIT (hoàn) trên nguồn, CREDIT + DEBIT (hoàn phí) trên tài khoản thu phí
        assertThat(ledgerCount(ref)).isEqualTo(4);
    }

    @Test
    void shouldIgnoreDuplicateCommandAndEvent() {
        String source = "100302000041";
        String ref = transfer(source, "100302000042", "3000000", newKey()).path("transactionRef").asText();
        assertThat(awaitTerminal(ref).path("status").asText()).isEqualTo("COMPLETED");
        BigDecimal sourceBalance = balance(source);
        BigDecimal feeBalance = internalFeeBalance();

        // Gửi lại đúng message lệnh trừ tiền (cùng eventId) → account-service không trừ lần 2, chỉ trả lại kết quả cũ
        resend(KafkaTopics.ACCOUNT_COMMANDS, ref, EventTypes.ACCOUNT_DEBIT_REQUESTED);
        await().atMost(Duration.ofSeconds(30)).until(() ->
                records(KafkaTopics.TRANSACTION_EVENTS, ref, EventTypes.ACCOUNT_DEBITED).size() >= 2);
        // Gửi lại kết quả trừ tiền khi giao dịch đã COMPLETED → Saga bỏ qua, không gửi lệnh cộng tiền lần 2
        resend(KafkaTopics.TRANSACTION_EVENTS, ref, EventTypes.ACCOUNT_DEBITED);

        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(ledgerCount(ref)).isEqualTo(3);
            assertThat(balance(source)).isEqualByComparingTo(sourceBalance);
            assertThat(balance("100302000042")).isEqualByComparingTo("3000000");
            assertThat(internalFeeBalance()).isEqualByComparingTo(feeBalance);
            assertThat(getTransaction(ref).path("status").asText()).isEqualTo("COMPLETED");
        });
    }

    @Test
    void shouldReturnSameTransactionForSameIdempotencyKey() {
        String key = newKey();
        String ref = transfer("100302000051", "100302000052", "1000000", key).path("transactionRef").asText();

        assertThat(transfer("100302000051", "100302000052", "1000000", key).path("transactionRef").asText())
                .isEqualTo(ref);
        int conflict = transactionApi.post().uri("/api/transactions/transfer")
                .header("Authorization", "Bearer " + tellerToken)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .body(transferBody("100302000051", "100302000052", "2000000"))
                .exchange((request, response) -> response.getStatusCode().value());
        assertThat(conflict).isEqualTo(409);

        assertThat(awaitTerminal(ref).path("status").asText()).isEqualTo("COMPLETED");
        assertThat(transactionDb.queryForObject("SELECT COUNT(*) FROM TRANSACTIONS WHERE FROM_ACCOUNT_NUMBER = ?",
                Integer.class, "100302000051")).isEqualTo(1);
        assertThat(ledgerCount(ref)).isEqualTo(3);
    }

    // ---------------------------------------------------------------- hạ tầng

    private static void createSchemas() throws Exception {
        try (Connection system = DriverManager.getConnection(ORACLE.getJdbcUrl(), "system", ORACLE.getPassword())) {
            ScriptUtils.executeSqlScript(system, script("00_create_schemas.sql"));
        }
        runAs("BANK_CUSTOMER", "ChangeMe_Customer1", "02_customer_schema.sql", "13_customer_pii_encryption.sql");
        runAs("BANK_ACCOUNT", "ChangeMe_Account1", "03_account_schema.sql", "08_account_holds_reconciliation_fee.sql",
                "12_account_fee_account_shards.sql", "16_account_phone_aliases.sql");
        runAs("BANK_TRANSACTION", "ChangeMe_Transaction1", "04_transaction_schema.sql",
                "06_transaction_saga_timeout.sql", "09_transaction_ops_limits_fees.sql", "11_transaction_outbox_trace.sql",
                "17_transaction_interbank.sql");
        accountDb = new JdbcTemplate(new DriverManagerDataSource(ORACLE.getJdbcUrl(), "BANK_ACCOUNT", "ChangeMe_Account1"));
        transactionDb = new JdbcTemplate(new DriverManagerDataSource(ORACLE.getJdbcUrl(), "BANK_TRANSACTION",
                "ChangeMe_Transaction1"));
    }

    private static void runAs(String user, String password, String... scripts) throws Exception {
        try (Connection connection = DriverManager.getConnection(ORACLE.getJdbcUrl(), user, password)) {
            for (String name : scripts) {
                ScriptUtils.executeSqlScript(connection, script(name));
            }
        }
    }

    private static FileSystemResource script(String name) {
        return new FileSystemResource(SCRIPTS.resolve(name));
    }

    private static void seedData() {
        JdbcTemplate customerDb = new JdbcTemplate(new DriverManagerDataSource(ORACLE.getJdbcUrl(), "BANK_CUSTOMER",
                "ChangeMe_Customer1"));
        for (int id : new int[]{501, 502}) {
            customerDb.update("INSERT INTO CUSTOMERS (ID, USER_ID, CUSTOMER_CODE, FULL_NAME, DATE_OF_BIRTH, ID_TYPE, "
                    + "ID_NUMBER, PHONE, KYC_STATUS, STATUS) VALUES (?, ?, ?, 'Saga Test', DATE '1990-01-01', 'CCCD', ?, "
                    + "?, 'VERIFIED', 'ACTIVE')", id, 9000 + id, "CUSSAGA" + id, "SAGA000" + id, "0900000" + id);
        }
        // Mỗi test dùng một cặp tài khoản riêng (nguồn của khách 501, đích của khách 502)
        Map<String, String> sources = Map.of("100302000011", "100000000", "100302000021", "10000000",
                "100302000031", "10000000", "100302000041", "10000000", "100302000051", "10000000");
        sources.forEach((number, amount) -> {
            insertAccount(number, 501, amount);
            insertAccount(number.substring(0, 11) + "2", 502, "0");
        });
    }

    private static void insertAccount(String number, long customerId, String balance) {
        accountDb.update("INSERT INTO ACCOUNTS (ACCOUNT_NUMBER, CUSTOMER_ID, ACCOUNT_TYPE, CURRENCY, BALANCE, "
                + "AVAILABLE_BALANCE, STATUS) VALUES (?, ?, 'PAYMENT', 'VND', ?, ?, 'ACTIVE')",
                number, customerId, new BigDecimal(balance), new BigDecimal(balance));
    }

    /** Chạy một service thật với cấu hình config-repo; chỉ thay địa chỉ hạ tầng và tắt Eureka / config-server. */
    private static ConfigurableApplicationContext start(Class<?> application, String name, String dbUser,
                                                        String dbPassword) {
        String configLocation = CONFIG_REPO.resolve("application.yml").toUri() + ","
                + CONFIG_REPO.resolve(name + ".yml").toUri();
        return new SpringApplicationBuilder(application).run(
                "--spring.config.location=" + configLocation,
                "--spring.application.name=" + name,
                "--server.port=0",
                "--spring.main.banner-mode=off",
                "--spring.cloud.config.enabled=false",
                "--spring.cloud.config.import-check.enabled=false",
                "--eureka.client.enabled=false",
                "--management.tracing.enabled=false",
                "--management.otlp.tracing.export.enabled=false",
                "--logging.file.name=target/saga-it-logs/" + name + ".json",
                "--spring.datasource.url=" + ORACLE.getJdbcUrl(),
                "--spring.datasource.username=" + dbUser,
                "--spring.datasource.password=" + dbPassword,
                "--spring.data.redis.host=" + REDIS.getHost(),
                "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                "--bank.hazelcast.members=");
    }

    // ---------------------------------------------------------------- tiện ích

    private static Connection lockAccount(String accountNumber) throws Exception {
        Connection connection = DriverManager.getConnection(ORACLE.getJdbcUrl(), "BANK_ACCOUNT", "ChangeMe_Account1");
        connection.setAutoCommit(false);
        try (var select = connection.prepareStatement("SELECT ID FROM ACCOUNTS WHERE ACCOUNT_NUMBER = ? FOR UPDATE")) {
            select.setString(1, accountNumber);
            select.executeQuery().close();
        }
        return connection;
    }

    private static JsonNode transfer(String from, String to, String amount, String idempotencyKey) {
        JsonNode body = transactionApi.post().uri("/api/transactions/transfer")
                .header("Authorization", "Bearer " + tellerToken)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(transferBody(from, to, amount))
                .retrieve()
                .body(JsonNode.class);
        return body.path("data");
    }

    private static String transferBody(String from, String to, String amount) {
        return "{\"fromAccountNumber\":\"" + from + "\",\"toAccountNumber\":\"" + to + "\",\"amount\":" + amount
                + ",\"description\":\"saga-it\"}";
    }

    private static JsonNode getTransaction(String ref) {
        return transactionApi.get().uri("/api/transactions/{ref}", ref)
                .header("Authorization", "Bearer " + tellerToken)
                .retrieve()
                .body(JsonNode.class)
                .path("data");
    }

    private static JsonNode awaitTerminal(String ref) {
        return await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500))
                .until(() -> getTransaction(ref), tx -> TERMINAL.contains(tx.path("status").asText()));
    }

    private static BigDecimal balance(String accountNumber) {
        return accountDb.queryForObject("SELECT BALANCE FROM ACCOUNTS WHERE ACCOUNT_NUMBER = ?", BigDecimal.class,
                accountNumber);
    }

    /** Phí chia theo hash transactionRef vào nhiều tài khoản nội bộ → so sánh tổng số dư các tài khoản thu phí. */
    private static BigDecimal internalFeeBalance() {
        return accountDb.queryForObject("SELECT NVL(SUM(BALANCE), 0) FROM ACCOUNTS WHERE ACCOUNT_TYPE = 'INTERNAL' "
                + "AND CURRENCY = 'VND'", BigDecimal.class);
    }

    private static int ledgerCount(String ref) {
        return accountDb.queryForObject("SELECT COUNT(*) FROM ACCOUNT_LEDGER_ENTRIES WHERE TRANSACTION_REF = ?",
                Integer.class, ref);
    }

    private static String newKey() {
        return "saga-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    /** Gửi lại nguyên văn (cùng key, cùng eventId) message đầu tiên có eventType tương ứng của giao dịch. */
    private static void resend(String topic, String ref, String eventType) {
        ConsumerRecord<String, String> original = records(topic, ref, eventType).getFirst();
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props, new StringSerializer(),
                new StringSerializer())) {
            producer.send(new ProducerRecord<>(topic, original.key(), original.value())).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Không gửi lại được message " + eventType, e);
        }
    }

    private static List<ConsumerRecord<String, String>> records(String topic, String ref, String eventType) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        List<ConsumerRecord<String, String>> result = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props, new StringDeserializer(),
                new StringDeserializer())) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(info -> new TopicPartition(topic, info.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            while (partitions.stream().anyMatch(p -> consumer.position(p) < end.get(p))) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (ref.equals(record.key()) && eventType.equals(eventType(record.value()))) {
                        result.add(record);
                    }
                }
            }
        }
        return result;
    }

    private static String eventType(String value) {
        try {
            return MAPPER.readTree(value.getBytes(StandardCharsets.UTF_8)).path("eventType").asText();
        } catch (Exception e) {
            return "";
        }
    }
}
