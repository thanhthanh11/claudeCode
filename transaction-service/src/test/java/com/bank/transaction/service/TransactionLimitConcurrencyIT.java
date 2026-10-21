package com.bank.transaction.service;

import com.bank.common.audit.AuditPublisher;
import com.bank.common.event.EventTypes;
import com.bank.common.exception.BadRequestException;
import com.bank.transaction.dto.TransactionLimitResponse;
import com.bank.transaction.entity.Transaction;
import com.bank.transaction.enums.SagaStepEnum;
import com.bank.transaction.enums.TransactionStatusEnum;
import com.bank.transaction.enums.TransactionTypeEnum;
import com.bank.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.bank.common.config.CommonCryptoConfig;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kiểm tra trên Oracle thật (Testcontainers): mapping JPA khớp DDL (04, 06, 09) và nhiều giao dịch song song
 * từ cùng tài khoản không vượt được hạn mức ngày (khóa ACCOUNT_LIMIT_LOCKS + tính lại từ TRANSACTIONS).
 * Chạy: {@code mvn -pl transaction-service -am verify -Pintegration-test} (cần Docker).
 */
@Testcontainers
@DataJpaTest(properties = "spring.config.location=classpath:/application-it.yml")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration({JacksonAutoConfiguration.class, CommonCryptoConfig.class})
@Import({TransactionPersistenceService.class, OutboxService.class, TransactionLimitService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TransactionLimitConcurrencyIT {

    private static final String ACCOUNT = "102000000001";

    @Container
    @ServiceConnection
    static final OracleContainer ORACLE = new OracleContainer("gvenzl/oracle-free:23-slim-faststart")
            .withUsername("bank_transaction")
            .withPassword("Test_Transaction1");

    @MockitoBean
    private CacheManager cacheManager;
    @MockitoBean
    private AuditPublisher auditPublisher;

    @Autowired
    private TransactionPersistenceService persistenceService;
    @Autowired
    private TransactionRepository transactionRepository;

    @BeforeAll
    static void createSchema() throws Exception {
        try (Connection connection = DriverManager.getConnection(ORACLE.getJdbcUrl(), ORACLE.getUsername(),
                ORACLE.getPassword())) {
            for (String script : List.of("04_transaction_schema.sql", "06_transaction_saga_timeout.sql",
                    "09_transaction_ops_limits_fees.sql", "11_transaction_outbox_trace.sql")) {
                ScriptUtils.executeSqlScript(connection, new FileSystemResource("../database/oracle/" + script));
            }
        }
    }

    @Test
    void concurrentTransfersShouldNotExceedDailyCountLimit() throws Exception {
        TransactionLimitResponse limit = new TransactionLimitResponse(1L, TransactionTypeEnum.TRANSFER, null, "VND",
                null, new BigDecimal("1000000"), 3);

        List<Callable<Boolean>> attempts = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            attempts.add(() -> {
                try {
                    persistenceService.createWithCommand(newTransfer("100000"), EventTypes.ACCOUNT_DEBIT_REQUESTED,
                            ACCOUNT, limit, ACCOUNT);
                    return true;
                } catch (BadRequestException e) {
                    return false;
                }
            });
        }

        assertThat(run(attempts)).filteredOn(Boolean::booleanValue).hasSize(3);
        Object[] usage = transactionRepository.sumOutgoingSince(ACCOUNT, "TRANSFER",
                LocalDate.now().atStartOfDay()).getFirst();
        assertThat(((Number) usage[1]).longValue()).isEqualTo(3);
    }

    @Test
    void failedTransactionsShouldNotCountTowardsDailyAmount() throws Exception {
        String account = "102000000002";
        Transaction failed = newTransfer("900000");
        failed.setFromAccountNumber(account);
        failed.setStatus(TransactionStatusEnum.FAILED);
        transactionRepository.saveAndFlush(failed);
        TransactionLimitResponse limit = new TransactionLimitResponse(1L, TransactionTypeEnum.TRANSFER, null, "VND",
                null, new BigDecimal("1000000"), null);

        Transaction next = newTransfer("900000");
        next.setFromAccountNumber(account);
        persistenceService.createWithCommand(next, EventTypes.ACCOUNT_DEBIT_REQUESTED, account, limit, account);

        assertThat(transactionRepository.findByTransactionRef(next.getTransactionRef())).isPresent();
    }

    private static Transaction newTransfer(String amount) {
        String ref = "TX" + UUID.randomUUID().toString().replace("-", "").substring(0, 22);
        Transaction transaction = new Transaction();
        transaction.setTransactionRef(ref);
        transaction.setIdempotencyKey("1:" + ref);
        transaction.setTransactionType(TransactionTypeEnum.TRANSFER);
        transaction.setFromAccountNumber(ACCOUNT);
        transaction.setToAccountNumber("102000000099");
        transaction.setAmount(new BigDecimal(amount));
        transaction.setCurrency("VND");
        transaction.setStatus(TransactionStatusEnum.PROCESSING);
        transaction.setSagaStep(SagaStepEnum.DEBIT_REQUESTED);
        return transaction;
    }

    private static List<Boolean> run(List<Callable<Boolean>> tasks) throws InterruptedException, ExecutionException {
        ExecutorService executor = Executors.newFixedThreadPool(10);
        try {
            List<Boolean> results = new ArrayList<>();
            for (Future<Boolean> future : executor.invokeAll(tasks)) {
                results.add(future.get());
            }
            return results;
        } finally {
            executor.shutdown();
        }
    }
}
