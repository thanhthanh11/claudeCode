package com.bank.account.service;

import com.bank.account.config.FeeAccountConfig;
import com.bank.account.entity.Account;
import com.bank.account.enums.AccountStatusEnum;
import com.bank.account.enums.AccountTypeEnum;
import com.bank.account.enums.EntryTypeEnum;
import com.bank.account.repository.AccountLedgerEntryRepository;
import com.bank.account.repository.AccountRepository;
import com.bank.common.event.AccountCommandPayload;
import com.bank.common.event.EventTypes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kiểm tra trên Oracle thật (Testcontainers): mapping JPA khớp DDL trong database/oracle (ddl-auto=validate),
 * và nhiều lệnh trừ tiền song song trên cùng tài khoản không làm số dư âm / không ghi trùng bút toán.
 * Chạy: {@code mvn -pl account-service -am verify -Pintegration-test} (cần Docker).
 */
@Testcontainers
@DataJpaTest(properties = "spring.config.location=classpath:/application-it.yml")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(ConfigurationPropertiesAutoConfiguration.class)
@Import({AccountBalanceService.class, FeeAccountConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AccountBalanceConcurrencyIT {

    @Container
    @ServiceConnection
    static final OracleContainer ORACLE = new OracleContainer("gvenzl/oracle-free:23-slim-faststart")
            .withUsername("bank_account")
            .withPassword("Test_Account1");

    @Autowired
    private AccountBalanceService balanceService;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private AccountLedgerEntryRepository ledgerEntryRepository;
    @Autowired
    private FeeAccountConfig feeAccountConfig;

    @BeforeAll
    static void createSchema() throws Exception {
        try (Connection connection = DriverManager.getConnection(ORACLE.getJdbcUrl(), ORACLE.getUsername(),
                ORACLE.getPassword())) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("../database/oracle/03_account_schema.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("../database/oracle/08_account_holds_reconciliation_fee.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("../database/oracle/12_account_fee_account_shards.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("../database/oracle/16_account_phone_aliases.sql"));
        }
    }

    @Test
    void concurrentDebitsShouldNeverOverdraw() throws Exception {
        Account account = newAccount("101000000001", "1000000");

        List<Callable<String>> debits = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String ref = "TXCONC" + i;
            debits.add(() -> balanceService.debit(command(ref, account.getAccountNumber(), "100000", null)).eventType());
        }
        List<String> results = runConcurrently(debits);

        assertThat(results).filteredOn(EventTypes.ACCOUNT_DEBITED::equals).hasSize(10);
        assertThat(results).filteredOn(EventTypes.ACCOUNT_DEBIT_FAILED::equals).hasSize(10);
        Account reloaded = accountRepository.findById(account.getId()).orElseThrow();
        assertThat(reloaded.getBalance()).isEqualByComparingTo("0");
        assertThat(reloaded.getAvailableBalance()).isEqualByComparingTo("0");
    }

    @Test
    void duplicateCommandDeliveredInParallelShouldBeAppliedOnce() throws Exception {
        Account account = newAccount("101000000002", "1000000");

        List<Callable<String>> duplicates = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            duplicates.add(() -> balanceService.debit(
                    command("TXDUP", account.getAccountNumber(), "100000", "2000")).eventType());
        }
        List<String> results = runConcurrently(duplicates);

        assertThat(results).containsOnly(EventTypes.ACCOUNT_DEBITED);
        assertThat(accountRepository.findById(account.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("898000");
        assertThat(ledgerEntryRepository.findByTransactionRefOrderByIdAsc("TXDUP"))
                .extracting(entry -> entry.getEntryType())
                .containsExactly(EntryTypeEnum.DEBIT, EntryTypeEnum.CREDIT);
        assertThat(ledgerEntryRepository.findByTransactionRefOnAccountType("TXDUP", EntryTypeEnum.CREDIT,
                AccountTypeEnum.INTERNAL)).singleElement()
                .satisfies(entry -> assertThat(entry.getAmount()).isEqualByComparingTo("2000"));
    }

    @Test
    void concurrentFeeDebitsShouldSpreadOverFeeAccountsAndRefundFromCollectingAccount() throws Exception {
        Account account = newAccount("101000000003", "10000000");
        BigDecimal feeBefore = totalFeeBalance();

        List<Callable<String>> debits = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            String ref = "TXFEE" + i;
            debits.add(() -> balanceService.debit(command(ref, account.getAccountNumber(), "100000", "1000")).eventType());
        }
        assertThat(runConcurrently(debits)).containsOnly(EventTypes.ACCOUNT_DEBITED);
        assertThat(totalFeeBalance().subtract(feeBefore)).isEqualByComparingTo("16000");
        assertThat(IntStream.range(0, 16).mapToObj(i -> collectingFeeAccountId("TXFEE" + i)).distinct().count())
                .isGreaterThan(1);

        Long collectingAccountId = collectingFeeAccountId("TXFEE0");
        BigDecimal collectingBefore = accountRepository.findById(collectingAccountId).orElseThrow().getBalance();
        assertThat(balanceService.refund(command("TXFEE0", account.getAccountNumber(), "100000", "1000")).eventType())
                .isEqualTo(EventTypes.ACCOUNT_REFUNDED);

        assertThat(accountRepository.findById(collectingAccountId).orElseThrow().getBalance())
                .isEqualByComparingTo(collectingBefore.subtract(new BigDecimal("1000")));
        assertThat(accountRepository.findById(account.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("8485000");
    }

    private Long collectingFeeAccountId(String ref) {
        return ledgerEntryRepository.findByTransactionRefOnAccountType(ref, EntryTypeEnum.CREDIT,
                AccountTypeEnum.INTERNAL).getFirst().getAccountId();
    }

    private BigDecimal totalFeeBalance() {
        return feeAccountConfig.accountsFor("VND").stream()
                .map(number -> accountRepository.findByAccountNumber(number).orElseThrow().getBalance())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Account newAccount(String number, String balance) {
        Account account = new Account();
        account.setAccountNumber(number);
        account.setCustomerId(1L);
        account.setAccountType(AccountTypeEnum.PAYMENT);
        account.setCurrency("VND");
        account.setBalance(new BigDecimal(balance));
        account.setAvailableBalance(new BigDecimal(balance));
        account.setStatus(AccountStatusEnum.ACTIVE);
        account.setOpenedAt(LocalDateTime.now());
        return accountRepository.saveAndFlush(account);
    }

    private static AccountCommandPayload command(String ref, String accountNumber, String amount, String fee) {
        return new AccountCommandPayload(ref, accountNumber, new BigDecimal(amount), "VND", "IT",
                fee == null ? null : new BigDecimal(fee));
    }

    private static List<String> runConcurrently(List<Callable<String>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            // Khóa FOR UPDATE tuần tự hóa các lệnh trên cùng tài khoản: lệnh đến sau thấy bút toán đã có (idempotent)
            // hoặc thấy số dư mới — không lệnh nào được ném exception
            List<String> results = new ArrayList<>();
            for (Future<String> future : executor.invokeAll(tasks)) {
                results.add(future.get());
            }
            return results;
        } finally {
            executor.shutdown();
        }
    }
}
