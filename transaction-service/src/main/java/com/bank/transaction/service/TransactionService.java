package com.bank.transaction.service;

import com.bank.common.audit.AuditActions;
import com.bank.common.audit.AuditPublisher;
import com.bank.common.dto.AccountInfoResponse;
import com.bank.common.dto.NameInquiryResponse;
import com.bank.common.dto.PageResponse;
import com.bank.common.dto.PhoneAliasLookupResponse;
import com.bank.common.event.EventTypes;
import com.bank.common.event.InterbankInboundPayload;
import com.bank.common.exception.BadRequestException;
import com.bank.common.exception.ForbiddenException;
import com.bank.common.security.AuthUser;
import com.bank.common.security.RoleCodes;
import com.bank.common.security.SecurityUtils;
import com.bank.common.util.MaskingUtils;
import com.bank.transaction.config.OtpConfig;
import com.bank.transaction.dto.CashTransactionRequest;
import com.bank.transaction.dto.InterbankTransferRequest;
import com.bank.transaction.dto.PhoneTransferRequest;
import com.bank.transaction.dto.TransactionLimitResponse;
import com.bank.transaction.dto.TransactionResponse;
import com.bank.transaction.dto.TransferRequest;
import com.bank.transaction.entity.Transaction;
import com.bank.transaction.enums.SagaStepEnum;
import com.bank.transaction.enums.TransactionStatusEnum;
import com.bank.transaction.enums.TransactionTypeEnum;
import com.bank.transaction.exception.IdempotencyConflictException;
import com.bank.transaction.exception.TransactionNotFoundException;
import com.bank.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Khởi tạo giao dịch và Saga. Kiểm tra tài khoản qua Redis (account-service), tính phí, kiểm tra hạn mức,
 * rồi lưu giao dịch PROCESSING kèm lệnh đầu tiên trong outbox; phần còn lại do {@link TransactionSagaService} xử lý.
 * Khách hàng tự chuyển khoản từ ngưỡng OTP trở lên: giao dịch được lưu PENDING và chỉ gửi lệnh sau khi xác nhận OTP.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionService {

    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{8,40}$");
    private static final DateTimeFormatter REF_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String INTERNAL_ACCOUNT_TYPE = "INTERNAL";
    private static final String INTERBANK_CURRENCY = "VND";
    private static final int MAX_DESCRIPTION_LENGTH = 255;

    private final TransactionRepository transactionRepository;
    private final TransactionPersistenceService persistenceService;
    private final AccountLookupService accountLookupService;
    private final CustomerLookupService customerLookupService;
    private final InterbankLookupService interbankLookupService;
    private final FeeScheduleService feeScheduleService;
    private final TransactionLimitService limitService;
    private final TransactionOtpService otpService;
    private final OtpConfig otpConfig;
    private final AuditPublisher auditPublisher;

    public TransactionResponse transfer(TransferRequest request, String idempotencyKey) {
        AuthUser currentUser = SecurityUtils.requireCurrentUser();
        String scopedKey = scopeKey(currentUser, idempotencyKey);
        Optional<Transaction> existing = transactionRepository.findByIdempotencyKey(scopedKey);
        if (existing.isPresent()) {
            return replay(existing.get(), TransactionTypeEnum.TRANSFER, request.fromAccountNumber(),
                    request.toAccountNumber(), request.amount());
        }
        boolean otpRequired = currentUser.hasRole(RoleCodes.CUSTOMER) && !currentUser.isStaff();
        TransactionResponse response = createTransfer(request, scopedKey, currentUser.userId(), from -> {
            if (!currentUser.isStaff()) {
                ensureOwner(from, currentUser);
            }
        }, otpRequired);
        if (currentUser.isStaff()) {
            // Nhân viên chuyển tiền giữa tài khoản bất kỳ, không cần OTP → bắt buộc có dấu vết audit
            auditPublisher.publish(AuditActions.STAFF_TRANSFER_CREATED, "TRANSACTION", response.transactionRef(),
                    response.amount().toPlainString() + " " + response.currency());
        }
        return response;
    }

    /**
     * Chuyển khoản thay mặt khách hàng (lệnh định kỳ): chạy không có người dùng đăng nhập (service token),
     * quyền sở hữu kiểm tra theo customerId của lệnh; khách hàng đã xác nhận khi tạo lệnh nên không cần OTP.
     */
    public TransactionResponse transferOnBehalf(Long customerId, Long initiatedBy, TransferRequest request,
                                                String scopedKey) {
        Optional<Transaction> existing = transactionRepository.findByIdempotencyKey(scopedKey);
        if (existing.isPresent()) {
            return replay(existing.get(), TransactionTypeEnum.TRANSFER, request.fromAccountNumber(),
                    request.toAccountNumber(), request.amount());
        }
        return createTransfer(request, scopedKey, initiatedBy, from -> {
            if (!Objects.equals(from.customerId(), customerId)) {
                throw new ForbiddenException("Tài khoản nguồn không còn thuộc khách hàng của lệnh định kỳ");
            }
        }, false);
    }

    /**
     * Chuyển tiền tới số điện thoại: phân giải alias ra tài khoản nhận rồi xử lý như chuyển khoản nội bộ.
     * Gửi lại cùng Idempotency-Key trả về giao dịch đã tạo theo tài khoản nhận lúc tạo (alias có thể đã đổi sau đó).
     */
    public TransactionResponse transferByPhone(PhoneTransferRequest request, String idempotencyKey) {
        AuthUser currentUser = SecurityUtils.requireCurrentUser();
        Optional<Transaction> existing = transactionRepository.findByIdempotencyKey(scopeKey(currentUser, idempotencyKey));
        if (existing.isPresent()) {
            return replay(existing.get(), TransactionTypeEnum.TRANSFER, request.fromAccountNumber(),
                    existing.get().getToAccountNumber(), request.amount());
        }
        PhoneAliasLookupResponse recipient = accountLookupService.resolvePhoneAlias(request.phone());
        return transfer(new TransferRequest(request.fromAccountNumber(), recipient.accountNumber(), request.amount(),
                request.description()), idempotencyKey);
    }

    /**
     * Chuyển tiền ra ngân hàng khác qua Napas: kiểm tra tài khoản nguồn như chuyển khoản, tra tên người nhận qua
     * interbank-service (lưu lại để đối chiếu), rồi chạy Saga DEBIT → INTERBANK_REQUESTED. Chỉ hỗ trợ VND.
     */
    public TransactionResponse interbankTransfer(InterbankTransferRequest request, String idempotencyKey) {
        AuthUser currentUser = SecurityUtils.requireCurrentUser();
        String scopedKey = scopeKey(currentUser, idempotencyKey);
        Optional<Transaction> existing = transactionRepository.findByIdempotencyKey(scopedKey);
        if (existing.isPresent()) {
            return replayInterbank(existing.get(), request);
        }
        AccountInfoResponse from = accountLookupService.getAccount(request.fromAccountNumber());
        if (!currentUser.isStaff()) {
            ensureOwner(from, currentUser);
        }
        requireCustomerAccount(from);
        requireActive(from, "nguồn");
        requireActiveCustomer(from);
        if (!INTERBANK_CURRENCY.equals(from.currency())) {
            throw new BadRequestException("INTERBANK_CURRENCY_NOT_SUPPORTED",
                    "Chuyển tiền liên ngân hàng chỉ hỗ trợ tài khoản " + INTERBANK_CURRENCY);
        }
        NameInquiryResponse beneficiary = interbankLookupService.nameInquiry(request.bankCode(),
                request.beneficiaryAccount());
        BigDecimal fee = feeScheduleService.findSchedule(TransactionTypeEnum.INTERBANK_OUT, from.currency())
                .calculateFee(request.amount(), from.currency());
        requireSufficientBalance(from, request.amount().add(fee));
        TransactionLimitResponse limit = limitService.findLimit(TransactionTypeEnum.INTERBANK_OUT, from.accountType(),
                from.currency());

        Transaction transaction = newTransaction(TransactionTypeEnum.INTERBANK_OUT, scopedKey, from.accountNumber(),
                null, request.amount(), fee, from.currency(), request.description(), currentUser.userId(),
                SagaStepEnum.DEBIT_REQUESTED);
        transaction.setCounterpartyBankCode(request.bankCode());
        transaction.setCounterpartyAccount(request.beneficiaryAccount());
        transaction.setCounterpartyName(beneficiary.accountName());
        boolean customerInitiated = currentUser.hasRole(RoleCodes.CUSTOMER) && !currentUser.isStaff();
        TransactionResponse response = customerInitiated && otpConfig.isRequired(request.amount(), from.currency())
                ? startPending(transaction, limit, from)
                : start(transaction, EventTypes.ACCOUNT_DEBIT_REQUESTED, from.accountNumber(), limit,
                from.accountNumber());
        if (currentUser.isStaff()) {
            auditPublisher.publish(AuditActions.STAFF_TRANSFER_CREATED, "TRANSACTION", response.transactionRef(),
                    "INTERBANK " + response.amount().toPlainString() + " " + response.currency());
        }
        return response;
    }

    /**
     * Tiền từ ngân hàng khác chuyển đến (interbank-service đã xác thực Napas): tạo giao dịch INTERBANK_IN và lệnh cộng
     * tiền, không phí, không hạn mức. Idempotent theo mã Napas — nhận lại cùng message trả về giao dịch đã tạo.
     * Tài khoản nhận không hợp lệ được account-service trả CREDIT_FAILED → Saga báo interbank-service trả tiền về.
     */
    public TransactionResponse createInboundTransfer(InterbankInboundPayload inbound) {
        String key = inboundIdempotencyKey(inbound.napasRef());
        Optional<Transaction> existing = transactionRepository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            return toResponse(existing.get());
        }
        Transaction transaction = newTransaction(TransactionTypeEnum.INTERBANK_IN, key, null,
                inbound.beneficiaryAccount(), inbound.amount(), BigDecimal.ZERO, inbound.currency(),
                truncate(inbound.description(), MAX_DESCRIPTION_LENGTH), null, SagaStepEnum.CREDIT_REQUESTED);
        transaction.setCounterpartyBankCode(inbound.sourceBankCode());
        transaction.setCounterpartyAccount(inbound.sourceAccount());
        transaction.setCounterpartyName(inbound.sourceName());
        transaction.setExternalRef(inbound.napasRef());
        try {
            Transaction saved = persistenceService.createWithCommand(transaction, EventTypes.ACCOUNT_CREDIT_REQUESTED,
                    inbound.beneficiaryAccount(), TransactionLimitResponse.NONE, inbound.beneficiaryAccount());
            log.info("Nhận tiền liên ngân hàng {} (Napas {}) vào tài khoản {}", saved.getTransactionRef(),
                    inbound.napasRef(), MaskingUtils.maskAccountNumber(inbound.beneficiaryAccount()));
            return toResponse(saved);
        } catch (DataIntegrityViolationException e) {
            return toResponse(transactionRepository.findByIdempotencyKey(key).orElseThrow(() -> e));
        }
    }

    static String inboundIdempotencyKey(String napasRef) {
        return "0:ibin-" + napasRef;
    }

    private TransactionResponse createTransfer(TransferRequest request, String scopedKey, Long initiatedBy,
                                               Consumer<AccountInfoResponse> ownershipCheck, boolean customerInitiated) {
        if (request.fromAccountNumber().equals(request.toAccountNumber())) {
            throw new BadRequestException("SAME_ACCOUNT", "Tài khoản nguồn và tài khoản nhận phải khác nhau");
        }
        AccountInfoResponse from = accountLookupService.getAccount(request.fromAccountNumber());
        AccountInfoResponse to = accountLookupService.getAccount(request.toAccountNumber());
        ownershipCheck.accept(from);
        requireCustomerAccount(from);
        requireCustomerAccount(to);
        requireActive(from, "nguồn");
        requireActive(to, "nhận");
        requireActiveCustomer(from);
        if (!from.currency().equals(to.currency())) {
            throw new BadRequestException("CURRENCY_MISMATCH", "Hai tài khoản khác loại tiền");
        }
        BigDecimal fee = feeScheduleService.findSchedule(TransactionTypeEnum.TRANSFER, from.currency())
                .calculateFee(request.amount(), from.currency());
        requireSufficientBalance(from, request.amount().add(fee));
        TransactionLimitResponse limit = limitService.findLimit(TransactionTypeEnum.TRANSFER, from.accountType(),
                from.currency());

        Transaction transaction = newTransaction(TransactionTypeEnum.TRANSFER, scopedKey, from.accountNumber(),
                to.accountNumber(), request.amount(), fee, from.currency(), request.description(), initiatedBy,
                SagaStepEnum.DEBIT_REQUESTED);
        if (customerInitiated && otpConfig.isRequired(request.amount(), from.currency())) {
            return startPending(transaction, limit, from);
        }
        return start(transaction, EventTypes.ACCOUNT_DEBIT_REQUESTED, from.accountNumber(), limit,
                from.accountNumber());
    }

    public TransactionResponse deposit(CashTransactionRequest request, String idempotencyKey) {
        AuthUser currentUser = SecurityUtils.requireCurrentUser();
        String scopedKey = scopeKey(currentUser, idempotencyKey);
        Optional<Transaction> existing = transactionRepository.findByIdempotencyKey(scopedKey);
        if (existing.isPresent()) {
            return replay(existing.get(), TransactionTypeEnum.DEPOSIT, null, request.accountNumber(), request.amount());
        }
        AccountInfoResponse account = accountLookupService.getAccount(request.accountNumber());
        requireCustomerAccount(account);
        requireActive(account, "nhận");
        TransactionLimitResponse limit = limitService.findLimit(TransactionTypeEnum.DEPOSIT, account.accountType(),
                account.currency());

        Transaction transaction = newTransaction(TransactionTypeEnum.DEPOSIT, scopedKey, null,
                account.accountNumber(), request.amount(), BigDecimal.ZERO, account.currency(), request.description(),
                currentUser.userId(), SagaStepEnum.CREDIT_REQUESTED);
        TransactionResponse response = start(transaction, EventTypes.ACCOUNT_CREDIT_REQUESTED,
                account.accountNumber(), limit, account.accountNumber());
        auditCash(response);
        return response;
    }

    public TransactionResponse withdraw(CashTransactionRequest request, String idempotencyKey) {
        AuthUser currentUser = SecurityUtils.requireCurrentUser();
        String scopedKey = scopeKey(currentUser, idempotencyKey);
        Optional<Transaction> existing = transactionRepository.findByIdempotencyKey(scopedKey);
        if (existing.isPresent()) {
            return replay(existing.get(), TransactionTypeEnum.WITHDRAWAL, request.accountNumber(), null,
                    request.amount());
        }
        AccountInfoResponse account = accountLookupService.getAccount(request.accountNumber());
        requireCustomerAccount(account);
        requireActive(account, "nguồn");
        requireActiveCustomer(account);
        BigDecimal fee = feeScheduleService.findSchedule(TransactionTypeEnum.WITHDRAWAL, account.currency())
                .calculateFee(request.amount(), account.currency());
        requireSufficientBalance(account, request.amount().add(fee));
        TransactionLimitResponse limit = limitService.findLimit(TransactionTypeEnum.WITHDRAWAL, account.accountType(),
                account.currency());

        Transaction transaction = newTransaction(TransactionTypeEnum.WITHDRAWAL, scopedKey, account.accountNumber(),
                null, request.amount(), fee, account.currency(), request.description(), currentUser.userId(),
                SagaStepEnum.DEBIT_REQUESTED);
        TransactionResponse response = start(transaction, EventTypes.ACCOUNT_DEBIT_REQUESTED,
                account.accountNumber(), limit, account.accountNumber());
        auditCash(response);
        return response;
    }

    /** Xác nhận giao dịch PENDING bằng OTP. Chỉ người tạo giao dịch được xác nhận. */
    public TransactionResponse confirm(String transactionRef, String otp) {
        Transaction transaction = requirePendingOwnedByCurrentUser(transactionRef);
        TransactionOtpService.Verification verification = otpService.verify(transactionRef, otp);
        return switch (verification.result()) {
            case VALID -> toResponse(persistenceService.confirmPending(transactionRef));
            case INVALID -> throw new BadRequestException("INVALID_OTP",
                    "Mã OTP không đúng, còn " + verification.remainingAttempts() + " lần thử");
            case ATTEMPTS_EXCEEDED -> {
                persistenceService.failPending(transactionRef, "Nhập sai OTP quá số lần cho phép");
                throw new BadRequestException("OTP_ATTEMPTS_EXCEEDED", "Nhập sai OTP quá số lần, giao dịch đã bị hủy");
            }
            case EXPIRED -> {
                if (transaction.getCreatedAt().plus(otpConfig.getPendingTimeout()).isBefore(LocalDateTime.now())) {
                    persistenceService.failPending(transactionRef, "Hết thời gian xác nhận OTP");
                }
                throw new BadRequestException("OTP_EXPIRED", "Mã OTP đã hết hạn, vui lòng yêu cầu gửi lại");
            }
        };
    }

    /** Gửi lại OTP cho giao dịch PENDING (giới hạn số lần). */
    public TransactionResponse resendOtp(String transactionRef) {
        Transaction transaction = requirePendingOwnedByCurrentUser(transactionRef);
        if (!otpService.tryConsumeResend(transactionRef)) {
            throw new BadRequestException("OTP_RESEND_LIMIT", "Đã vượt số lần gửi lại OTP");
        }
        Long customerId = accountLookupService.getAccount(transaction.getFromAccountNumber()).customerId();
        otpService.issue(transaction, customerId);
        return toResponse(transaction);
    }

    public TransactionResponse getByRef(String transactionRef) {
        Transaction transaction = transactionRepository.findByTransactionRef(transactionRef)
                .orElseThrow(() -> new TransactionNotFoundException(transactionRef));
        AuthUser currentUser = SecurityUtils.requireCurrentUser();
        if (!currentUser.isStaff()) {
            Long customerId = customerLookupService.getCustomerIdByUserId(currentUser.userId());
            boolean related = ownsAccount(transaction.getFromAccountNumber(), customerId)
                    || ownsAccount(transaction.getToAccountNumber(), customerId);
            if (!related) {
                throw new ForbiddenException("Không có quyền xem giao dịch này");
            }
        }
        return toResponse(transaction);
    }

    public PageResponse<TransactionResponse> history(String accountNumber, Pageable pageable) {
        AuthUser currentUser = SecurityUtils.requireCurrentUser();
        if (!currentUser.isStaff()) {
            ensureOwner(accountLookupService.getAccount(accountNumber), currentUser);
        }
        return PageResponse.from(transactionRepository.findByAccountNumber(accountNumber, pageable)
                .map(TransactionService::toResponse));
    }

    private TransactionResponse start(Transaction transaction, String commandType, String targetAccountNumber,
                                      TransactionLimitResponse limit, String limitAccountNumber) {
        try {
            Transaction saved = persistenceService.createWithCommand(transaction, commandType, targetAccountNumber,
                    limit, limitAccountNumber);
            log.info("Khởi tạo giao dịch {} ({}) từ {} đến {}", saved.getTransactionRef(), saved.getTransactionType(),
                    MaskingUtils.maskAccountNumber(saved.getFromAccountNumber()),
                    MaskingUtils.maskAccountNumber(saved.getToAccountNumber()));
            return toResponse(saved);
        } catch (DataIntegrityViolationException e) {
            return replayAfterRace(transaction, e);
        }
    }

    private TransactionResponse startPending(Transaction transaction, TransactionLimitResponse limit,
                                             AccountInfoResponse from) {
        Transaction saved;
        try {
            saved = persistenceService.createPending(transaction, limit, from.accountNumber());
        } catch (DataIntegrityViolationException e) {
            return replayAfterRace(transaction, e);
        }
        try {
            otpService.issue(saved, from.customerId());
        } catch (RuntimeException e) {
            // Giao dịch vẫn chờ xác nhận; khách hàng yêu cầu gửi lại OTP
            log.warn("Gửi OTP cho giao dịch {} thất bại: {}", saved.getTransactionRef(), e.getMessage());
        }
        return toResponse(saved);
    }

    /** Hai request cùng Idempotency-Key chạy song song: request đến sau trả về giao dịch đã tạo. */
    private TransactionResponse replayAfterRace(Transaction transaction, DataIntegrityViolationException e) {
        Transaction existing = transactionRepository.findByIdempotencyKey(transaction.getIdempotencyKey())
                .orElseThrow(() -> e);
        return replay(existing, transaction.getTransactionType(), transaction.getFromAccountNumber(),
                transaction.getToAccountNumber(), transaction.getAmount());
    }

    private Transaction requirePendingOwnedByCurrentUser(String transactionRef) {
        AuthUser currentUser = SecurityUtils.requireCurrentUser();
        Transaction transaction = transactionRepository.findByTransactionRef(transactionRef)
                .orElseThrow(() -> new TransactionNotFoundException(transactionRef));
        if (!Objects.equals(transaction.getInitiatedBy(), currentUser.userId())) {
            throw new ForbiddenException("Chỉ người tạo giao dịch được xác nhận giao dịch này");
        }
        if (transaction.getStatus() != TransactionStatusEnum.PENDING) {
            throw new BadRequestException("TRANSACTION_NOT_PENDING", "Giao dịch không ở trạng thái chờ xác nhận");
        }
        return transaction;
    }

    /** Nộp/rút tiền tại quầy là thao tác của nhân viên → ghi audit. */
    private void auditCash(TransactionResponse response) {
        auditPublisher.publish(AuditActions.CASH_TRANSACTION_CREATED, "TRANSACTION", response.transactionRef(),
                response.transactionType() + " " + response.amount().toPlainString() + " " + response.currency());
    }

    /** Request lặp lại với cùng Idempotency-Key phải có cùng nội dung, nếu không là xung đột. */
    private TransactionResponse replay(Transaction existing, TransactionTypeEnum type, String from, String to,
                                       BigDecimal amount) {
        boolean same = existing.getTransactionType() == type
                && Objects.equals(existing.getFromAccountNumber(), from)
                && Objects.equals(existing.getToAccountNumber(), to)
                && existing.getAmount().compareTo(amount) == 0;
        if (!same) {
            throw new IdempotencyConflictException();
        }
        return toResponse(existing);
    }

    private static TransactionResponse replayInterbank(Transaction existing, InterbankTransferRequest request) {
        boolean same = existing.getTransactionType() == TransactionTypeEnum.INTERBANK_OUT
                && Objects.equals(existing.getFromAccountNumber(), request.fromAccountNumber())
                && Objects.equals(existing.getCounterpartyBankCode(), request.bankCode())
                && Objects.equals(existing.getCounterpartyAccount(), request.beneficiaryAccount())
                && existing.getAmount().compareTo(request.amount()) == 0;
        if (!same) {
            throw new IdempotencyConflictException();
        }
        return toResponse(existing);
    }

    private void ensureOwner(AccountInfoResponse account, AuthUser user) {
        Long customerId = customerLookupService.getCustomerIdByUserId(user.userId());
        if (!Objects.equals(account.customerId(), customerId)) {
            throw new ForbiddenException("Không có quyền thao tác trên tài khoản này");
        }
    }

    private boolean ownsAccount(String accountNumber, Long customerId) {
        return accountNumber != null
                && Objects.equals(accountLookupService.getAccount(accountNumber).customerId(), customerId);
    }

    /** Khách hàng bị khóa (BLOCKED) hoặc ngừng hoạt động (INACTIVE) không được chuyển tiền / rút tiền đi. */
    private void requireActiveCustomer(AccountInfoResponse sourceAccount) {
        if (!customerLookupService.getCustomer(sourceAccount.customerId()).isActive()) {
            throw new BadRequestException("CUSTOMER_NOT_ACTIVE",
                    "Khách hàng chủ tài khoản nguồn không ở trạng thái hoạt động");
        }
    }

    /** Tài khoản nội bộ của ngân hàng (thu phí...) không được dùng trong giao dịch của khách hàng. */
    private static void requireCustomerAccount(AccountInfoResponse account) {
        if (INTERNAL_ACCOUNT_TYPE.equals(account.accountType())) {
            throw new BadRequestException("INTERNAL_ACCOUNT", "Không được giao dịch với tài khoản nội bộ");
        }
    }

    private static void requireActive(AccountInfoResponse account, String role) {
        if (!account.isActive()) {
            throw new BadRequestException("ACCOUNT_NOT_ACTIVE", "Tài khoản " + role + " không ở trạng thái hoạt động");
        }
    }

    /** Kiểm tra sớm để báo lỗi ngay; account-service vẫn kiểm tra lại khi trừ tiền (nguồn sự thật). */
    private static void requireSufficientBalance(AccountInfoResponse account, BigDecimal totalAmount) {
        if (account.availableBalance() != null && account.availableBalance().compareTo(totalAmount) < 0) {
            throw new BadRequestException("INSUFFICIENT_BALANCE", "Số dư khả dụng không đủ (gồm cả phí giao dịch)");
        }
    }

    private static String truncate(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static String scopeKey(AuthUser user, String idempotencyKey) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY_PATTERN.matcher(idempotencyKey).matches()) {
            throw new BadRequestException("INVALID_IDEMPOTENCY_KEY",
                    "Header Idempotency-Key bắt buộc, 8-40 ký tự (chữ, số, '-', '_')");
        }
        return scopeKey(user.userId(), idempotencyKey);
    }

    static String scopeKey(Long userId, String idempotencyKey) {
        return userId + ":" + idempotencyKey;
    }

    private static Transaction newTransaction(TransactionTypeEnum type, String idempotencyKey, String from, String to,
                                              BigDecimal amount, BigDecimal fee, String currency, String description,
                                              Long initiatedBy, SagaStepEnum firstStep) {
        Transaction transaction = new Transaction();
        transaction.setTransactionRef(generateRef());
        transaction.setIdempotencyKey(idempotencyKey);
        transaction.setTransactionType(type);
        transaction.setFromAccountNumber(from);
        transaction.setToAccountNumber(to);
        transaction.setAmount(amount);
        transaction.setFee(fee);
        transaction.setCurrency(currency);
        transaction.setDescription(description);
        transaction.setInitiatedBy(initiatedBy);
        transaction.setStatus(TransactionStatusEnum.PROCESSING);
        transaction.setSagaStep(firstStep);
        return transaction;
    }

    /** TX + yyyyMMddHHmmss + 8 số ngẫu nhiên. */
    private static String generateRef() {
        return "TX" + LocalDateTime.now().format(REF_TIME) + String.format("%08d", RANDOM.nextInt(100_000_000));
    }

    static TransactionResponse toResponse(Transaction transaction) {
        return new TransactionResponse(transaction.getId(), transaction.getTransactionRef(),
                transaction.getTransactionType(), transaction.getFromAccountNumber(), transaction.getToAccountNumber(),
                transaction.getAmount(), transaction.getFee(), transaction.getCurrency(), transaction.getStatus(),
                transaction.getSagaStep(), transaction.getDescription(), transaction.getFailureReason(),
                transaction.getCounterpartyBankCode(), transaction.getCounterpartyAccount(),
                transaction.getCounterpartyName(),
                transaction.getCreatedAt(), transaction.getCompletedAt());
    }
}
