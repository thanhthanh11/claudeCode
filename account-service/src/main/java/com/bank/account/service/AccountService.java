package com.bank.account.service;

import com.bank.account.dto.AccountResponse;
import com.bank.account.dto.CreateAccountRequest;
import com.bank.account.dto.LedgerEntryResponse;
import com.bank.account.dto.UpdateAccountStatusRequest;
import com.bank.account.entity.Account;
import com.bank.account.entity.AccountLedgerEntry;
import com.bank.account.enums.AccountStatusEnum;
import com.bank.account.enums.AccountTypeEnum;
import com.bank.account.exception.AccountNotFoundException;
import com.bank.account.repository.AccountLedgerEntryRepository;
import com.bank.account.repository.AccountRepository;
import com.bank.common.audit.AuditActions;
import com.bank.common.audit.AuditPublisher;
import com.bank.common.dto.AccountInfoResponse;
import com.bank.common.dto.CustomerInfoResponse;
import com.bank.common.dto.PageResponse;
import com.bank.common.exception.BadRequestException;
import com.bank.common.exception.BusinessException;
import com.bank.common.exception.ForbiddenException;
import com.bank.common.security.AuthUser;
import com.bank.common.security.SecurityUtils;
import com.bank.common.util.MaskingUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountRepository accountRepository;
    private final AccountLedgerEntryRepository ledgerEntryRepository;
    private final CustomerClientService customerClientService;
    private final CustomerLookupService customerLookupService;
    private final AuditPublisher auditPublisher;

    /**
     * Mở tài khoản: kiểm tra khách hàng qua REST (dữ liệu mới nhất) — phải ACTIVE và đã KYC.
     */
    @Transactional
    public AccountResponse create(CreateAccountRequest request) {
        if (request.accountType() == AccountTypeEnum.INTERNAL) {
            throw new BadRequestException("INVALID_ACCOUNT_TYPE", "Không được mở tài khoản nội bộ qua API");
        }
        CustomerInfoResponse customer = customerClientService.getCustomer(request.customerId());
        if (!customer.isActive()) {
            throw new BadRequestException("CUSTOMER_INACTIVE", "Khách hàng không ở trạng thái hoạt động");
        }
        if (!customer.isKycVerified()) {
            throw new BadRequestException("KYC_NOT_VERIFIED", "Khách hàng chưa hoàn tất xác thực KYC");
        }

        Account account = new Account();
        account.setAccountNumber(generateAccountNumber(request));
        account.setCustomerId(customer.id());
        account.setAccountType(request.accountType());
        account.setCurrency(request.currencyOrDefault());
        account.setOpenedAt(LocalDateTime.now());
        accountRepository.save(account);
        log.info("Mở tài khoản {} cho khách hàng #{}", MaskingUtils.maskAccountNumber(account.getAccountNumber()),
                customer.id());
        auditPublisher.publish(AuditActions.ACCOUNT_OPENED, "ACCOUNT", account.getId(),
                "customerId=" + customer.id() + ", type=" + account.getAccountType() + ", currency=" + account.getCurrency());
        return toResponse(account);
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> getMyAccounts() {
        AuthUser currentUser = SecurityUtils.requireCurrentUser();
        Long customerId = customerLookupService.getCustomerIdByUserId(currentUser.userId());
        return accountRepository.findByCustomerIdOrderByIdAsc(customerId).stream()
                .map(AccountService::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> getAccountsOfCustomer(Long customerId) {
        return accountRepository.findByCustomerIdOrderByIdAsc(customerId).stream()
                .map(AccountService::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccount(String accountNumber) {
        Account account = findAccount(accountNumber);
        checkAccess(account, SecurityUtils.requireCurrentUser());
        return toResponse(account);
    }

    @Transactional(readOnly = true)
    public PageResponse<LedgerEntryResponse> getLedger(String accountNumber, Pageable pageable) {
        Account account = findAccount(accountNumber);
        checkAccess(account, SecurityUtils.requireCurrentUser());
        return PageResponse.from(ledgerEntryRepository.findByAccountId(account.getId(), pageable)
                .map(AccountService::toLedgerResponse));
    }

    @Transactional
    public AccountResponse updateStatus(String accountNumber, UpdateAccountStatusRequest request) {
        Account account = findAccount(accountNumber);
        if (account.getStatus() == AccountStatusEnum.CLOSED) {
            throw new BadRequestException("ACCOUNT_CLOSED", "Tài khoản đã đóng, không thể thay đổi trạng thái");
        }
        if (request.status() == AccountStatusEnum.CLOSED) {
            if (account.getBalance().compareTo(BigDecimal.ZERO) != 0) {
                throw new BadRequestException("BALANCE_NOT_ZERO", "Chỉ đóng được tài khoản có số dư bằng 0");
            }
            account.setClosedAt(LocalDateTime.now());
        }
        AccountStatusEnum previous = account.getStatus();
        account.setStatus(request.status());
        log.info("Cập nhật trạng thái tài khoản {} → {}", MaskingUtils.maskAccountNumber(accountNumber),
                request.status());
        auditPublisher.publish(AuditActions.ACCOUNT_STATUS_UPDATED, "ACCOUNT", account.getId(),
                previous + " -> " + request.status());
        return toResponse(account);
    }

    /**
     * Dùng cho Redis request-reply. Số dư chỉ trả về cho service nội bộ, nhân viên hoặc chủ tài khoản;
     * người khác (vd. người chuyển tiền tra tài khoản nhận) chỉ thấy thông tin cơ bản.
     */
    @Transactional(readOnly = true)
    public AccountInfoResponse getAccountInfo(String accountNumber, AuthUser caller) {
        Account account = findAccount(accountNumber);
        AccountInfoResponse info = new AccountInfoResponse(account.getId(), account.getAccountNumber(),
                account.getCustomerId(), account.getAccountType().name(), account.getCurrency(),
                account.getBalance(), account.getAvailableBalance(), account.getStatus().name());
        if (caller.isService() || caller.isStaff() || isOwner(account, caller)) {
            return info;
        }
        return info.withoutBalances();
    }

    /** Tài khoản mà người dùng hiện tại được truy cập (chủ tài khoản hoặc nhân viên). */
    public Account findAccessibleAccount(String accountNumber) {
        Account account = findAccount(accountNumber);
        checkAccess(account, SecurityUtils.requireCurrentUser());
        return account;
    }

    private void checkAccess(Account account, AuthUser user) {
        if (!user.isStaff() && !isOwner(account, user)) {
            throw new ForbiddenException("Không có quyền truy cập tài khoản này");
        }
    }

    private boolean isOwner(Account account, AuthUser user) {
        if (user.userId() == null) {
            return false;
        }
        try {
            return Objects.equals(account.getCustomerId(), customerLookupService.getCustomerIdByUserId(user.userId()));
        } catch (BusinessException e) {
            if (e.getStatus() == HttpStatus.NOT_FOUND) {
                return false; // người dùng chưa có hồ sơ khách hàng
            }
            throw e;
        }
    }

    private Account findAccount(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException(accountNumber));
    }

    /** 12 chữ số: 2 số đầu theo loại tài khoản + 10 số ngẫu nhiên. */
    private String generateAccountNumber(CreateAccountRequest request) {
        String accountNumber;
        do {
            accountNumber = request.accountType().getNumberPrefix()
                    + String.format("%010d", RANDOM.nextLong(10_000_000_000L));
        } while (accountRepository.existsByAccountNumber(accountNumber));
        return accountNumber;
    }

    static AccountResponse toResponse(Account account) {
        return new AccountResponse(account.getId(), account.getAccountNumber(), account.getCustomerId(),
                account.getAccountType(), account.getCurrency(), account.getBalance(), account.getAvailableBalance(),
                account.getStatus(), account.getOpenedAt(), account.getClosedAt());
    }

    static LedgerEntryResponse toLedgerResponse(AccountLedgerEntry entry) {
        return new LedgerEntryResponse(entry.getId(), entry.getTransactionRef(), entry.getEntryType(),
                entry.getAmount(), entry.getBalanceAfter(), entry.getDescription(), entry.getCreatedAt());
    }
}
