package com.bank.transaction.service;

import com.bank.common.audit.AuditPublisher;
import com.bank.common.dto.AccountInfoResponse;
import com.bank.common.dto.CustomerInfoResponse;
import com.bank.common.event.EventTypes;
import com.bank.common.exception.BadRequestException;
import com.bank.common.security.AuthUser;
import com.bank.common.security.RoleCodes;
import com.bank.common.security.SecurityUtils;
import com.bank.transaction.config.OtpConfig;
import com.bank.transaction.dto.CashTransactionRequest;
import com.bank.transaction.dto.FeeScheduleResponse;
import com.bank.transaction.dto.TransactionLimitResponse;
import com.bank.transaction.dto.TransactionResponse;
import com.bank.transaction.dto.TransferRequest;
import com.bank.transaction.entity.Transaction;
import com.bank.transaction.enums.TransactionStatusEnum;
import com.bank.transaction.enums.TransactionTypeEnum;
import com.bank.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    private static final String FROM = "100000000001";
    private static final String TO = "100000000002";
    private static final String IDEMPOTENCY_KEY = "key-12345678";
    private static final FeeScheduleResponse TRANSFER_FEE = new FeeScheduleResponse(1L, TransactionTypeEnum.TRANSFER,
            "VND", BigDecimal.ZERO, new BigDecimal("0.0002"), new BigDecimal("2000"), new BigDecimal("20000"), true);

    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private TransactionPersistenceService persistenceService;
    @Mock
    private AccountLookupService accountLookupService;
    @Mock
    private CustomerLookupService customerLookupService;
    @Mock
    private FeeScheduleService feeScheduleService;
    @Mock
    private TransactionLimitService limitService;
    @Mock
    private TransactionOtpService otpService;
    @Mock
    private AuditPublisher auditPublisher;

    private final OtpConfig otpConfig = new OtpConfig();
    private TransactionService transactionService;

    @BeforeEach
    void setUp() {
        transactionService = new TransactionService(transactionRepository, persistenceService, accountLookupService,
                customerLookupService, feeScheduleService, limitService, otpService, otpConfig, auditPublisher);
        login(new AuthUser(9L, "teller", Set.of(RoleCodes.TELLER), Set.of()));
        lenient().when(transactionRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        lenient().when(accountLookupService.getAccount(FROM)).thenReturn(account(FROM, 1L, "PAYMENT"));
        lenient().when(accountLookupService.getAccount(TO)).thenReturn(account(TO, 2L, "PAYMENT"));
        lenient().when(customerLookupService.getCustomer(1L)).thenReturn(customer(1L, "ACTIVE"));
        lenient().when(feeScheduleService.findSchedule(any(), anyString())).thenReturn(FeeScheduleResponse.NONE);
        lenient().when(limitService.findLimit(any(), any(), anyString())).thenReturn(TransactionLimitResponse.NONE);
        lenient().when(persistenceService.createWithCommand(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(persistenceService.createPending(any(), any(), any())).thenAnswer(inv -> {
            Transaction transaction = inv.getArgument(0);
            transaction.setStatus(TransactionStatusEnum.PENDING);
            return transaction;
        });
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void transferShouldBeRejectedWhenSourceCustomerIsBlocked() {
        when(customerLookupService.getCustomer(1L)).thenReturn(customer(1L, "BLOCKED"));

        assertBadRequest(() -> transactionService.transfer(transfer("100000"), IDEMPOTENCY_KEY), "CUSTOMER_NOT_ACTIVE");
        verify(persistenceService, never()).createWithCommand(any(), any(), any(), any(), any());
    }

    @Test
    void withdrawalShouldBeRejectedWhenSourceCustomerIsInactive() {
        when(customerLookupService.getCustomer(1L)).thenReturn(customer(1L, "INACTIVE"));

        assertBadRequest(() -> transactionService.withdraw(
                new CashTransactionRequest(FROM, new BigDecimal("100000"), null), IDEMPOTENCY_KEY), "CUSTOMER_NOT_ACTIVE");
        verify(persistenceService, never()).createWithCommand(any(), any(), any(), any(), any());
    }

    @Test
    void transferShouldChargeFeeAndSendDebitCommand() {
        when(feeScheduleService.findSchedule(TransactionTypeEnum.TRANSFER, "VND")).thenReturn(TRANSFER_FEE);

        transactionService.transfer(transfer("50000000"), IDEMPOTENCY_KEY);

        ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
        verify(persistenceService).createWithCommand(saved.capture(), eq(EventTypes.ACCOUNT_DEBIT_REQUESTED), eq(FROM),
                eq(TransactionLimitResponse.NONE), eq(FROM));
        // 50.000.000 × 0,02% = 10.000 (trong khoảng 2.000 – 20.000)
        assertThat(saved.getValue().getFee()).isEqualByComparingTo("10000");
    }

    @Test
    void transferShouldBeRejectedWhenBalanceDoesNotCoverFee() {
        when(feeScheduleService.findSchedule(TransactionTypeEnum.TRANSFER, "VND")).thenReturn(TRANSFER_FEE);

        // Số dư 100.000.000: chuyển 99.990.000 + phí tối đa 20.000 vượt số dư
        assertBadRequest(() -> transactionService.transfer(transfer("99990000"), IDEMPOTENCY_KEY), "INSUFFICIENT_BALANCE");
    }

    @Test
    void transferToInternalAccountShouldBeRejected() {
        when(accountLookupService.getAccount(TO)).thenReturn(account(TO, 0L, "INTERNAL"));

        assertBadRequest(() -> transactionService.transfer(transfer("100000"), IDEMPOTENCY_KEY), "INTERNAL_ACCOUNT");
    }

    @Test
    void customerTransferAboveThresholdShouldWaitForOtp() {
        login(new AuthUser(5L, "customer", Set.of(RoleCodes.CUSTOMER), Set.of()));
        when(customerLookupService.getCustomerIdByUserId(5L)).thenReturn(1L);
        otpConfig.getThresholds().put("VND", new BigDecimal("100000"));

        TransactionResponse response = transactionService.transfer(transfer("100000"), IDEMPOTENCY_KEY);

        assertThat(response.status()).isEqualTo(TransactionStatusEnum.PENDING);
        verify(otpService).issue(any(), eq(1L));
        verify(persistenceService, never()).createWithCommand(any(), any(), any(), any(), any());
    }

    @Test
    void customerTransferBelowThresholdShouldNotNeedOtp() {
        login(new AuthUser(5L, "customer", Set.of(RoleCodes.CUSTOMER), Set.of()));
        when(customerLookupService.getCustomerIdByUserId(5L)).thenReturn(1L);

        TransactionResponse response = transactionService.transfer(transfer("100000"), IDEMPOTENCY_KEY);

        assertThat(response.status()).isEqualTo(TransactionStatusEnum.PROCESSING);
        verify(otpService, never()).issue(any(), any());
    }

    @Test
    void confirmWithValidOtpShouldStartSaga() {
        login(new AuthUser(5L, "customer", Set.of(RoleCodes.CUSTOMER), Set.of()));
        Transaction pending = pendingTransaction(5L);
        when(transactionRepository.findByTransactionRef("TX1")).thenReturn(Optional.of(pending));
        when(otpService.verify("TX1", "123456"))
                .thenReturn(new TransactionOtpService.Verification(TransactionOtpService.VerifyResult.VALID, 0));
        when(persistenceService.confirmPending("TX1")).thenReturn(pending);

        transactionService.confirm("TX1", "123456");

        verify(persistenceService).confirmPending("TX1");
    }

    @Test
    void confirmByAnotherUserShouldBeForbidden() {
        login(new AuthUser(6L, "other", Set.of(RoleCodes.CUSTOMER), Set.of()));
        when(transactionRepository.findByTransactionRef("TX1")).thenReturn(Optional.of(pendingTransaction(5L)));

        assertThatThrownBy(() -> transactionService.confirm("TX1", "123456"))
                .isInstanceOf(com.bank.common.exception.ForbiddenException.class);
        verify(otpService, never()).verify(any(), any());
    }

    @Test
    void tooManyWrongOtpShouldCancelTransaction() {
        login(new AuthUser(5L, "customer", Set.of(RoleCodes.CUSTOMER), Set.of()));
        when(transactionRepository.findByTransactionRef("TX1")).thenReturn(Optional.of(pendingTransaction(5L)));
        when(otpService.verify("TX1", "000000")).thenReturn(
                new TransactionOtpService.Verification(TransactionOtpService.VerifyResult.ATTEMPTS_EXCEEDED, 0));

        assertBadRequest(() -> transactionService.confirm("TX1", "000000"), "OTP_ATTEMPTS_EXCEEDED");
        verify(persistenceService).failPending(eq("TX1"), anyString());
    }

    private static Transaction pendingTransaction(Long initiatedBy) {
        Transaction transaction = new Transaction();
        transaction.setTransactionRef("TX1");
        transaction.setTransactionType(TransactionTypeEnum.TRANSFER);
        transaction.setFromAccountNumber(FROM);
        transaction.setToAccountNumber(TO);
        transaction.setAmount(new BigDecimal("100000"));
        transaction.setStatus(TransactionStatusEnum.PENDING);
        transaction.setInitiatedBy(initiatedBy);
        transaction.setCreatedAt(LocalDateTime.now());
        return transaction;
    }

    private static void assertBadRequest(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BadRequestException.class, e -> assertThat(e.getCode()).isEqualTo(code));
    }

    private static void login(AuthUser user) {
        SecurityContextHolder.getContext().setAuthentication(SecurityUtils.toAuthentication(user, "token"));
    }

    private static TransferRequest transfer(String amount) {
        return new TransferRequest(FROM, TO, new BigDecimal(amount), null);
    }

    private static AccountInfoResponse account(String number, Long customerId, String type) {
        return new AccountInfoResponse(1L, number, customerId, type, "VND", new BigDecimal("100000000"),
                new BigDecimal("100000000"), "ACTIVE");
    }

    private static CustomerInfoResponse customer(Long id, String status) {
        return new CustomerInfoResponse(id, 10L, "CUS261020123456", "Nguyễn Văn A", "0900000000", null, status,
                "VERIFIED");
    }
}
