package com.bank.account.service;

import com.bank.account.dto.AccountHoldResponse;
import com.bank.account.dto.PlaceHoldRequest;
import com.bank.account.entity.Account;
import com.bank.account.entity.AccountHold;
import com.bank.account.enums.AccountStatusEnum;
import com.bank.account.enums.HoldStatusEnum;
import com.bank.account.exception.AccountNotFoundException;
import com.bank.account.repository.AccountHoldRepository;
import com.bank.account.repository.AccountRepository;
import com.bank.common.audit.AuditActions;
import com.bank.common.audit.AuditPublisher;
import com.bank.common.exception.BadRequestException;
import com.bank.common.exception.ResourceNotFoundException;
import com.bank.common.security.SecurityUtils;
import com.bank.common.util.MaskingUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Tạm giữ tiền: hold ACTIVE làm giảm AVAILABLE_BALANCE (BALANCE giữ nguyên), nên tiền đang giữ không thể bị trừ
 * bởi giao dịch. Đặt/giải tỏa hold luôn khóa dòng tài khoản (SELECT FOR UPDATE) như các thao tác số dư khác.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountHoldService {

    private static final String SYSTEM_USER = "system";

    private final AccountRepository accountRepository;
    private final AccountHoldRepository holdRepository;
    private final AccountService accountService;
    private final AuditPublisher auditPublisher;
    private final TransactionTemplate transactionTemplate;

    @Transactional
    public AccountHoldResponse placeHold(String accountNumber, PlaceHoldRequest request) {
        Account account = lockAccount(accountNumber);
        if (account.getStatus() == AccountStatusEnum.CLOSED) {
            throw new BadRequestException("ACCOUNT_CLOSED", "Tài khoản đã đóng");
        }
        if (account.getAvailableBalance().compareTo(request.amount()) < 0) {
            throw new BadRequestException("INSUFFICIENT_BALANCE", "Số dư khả dụng không đủ để tạm giữ");
        }
        account.hold(request.amount());

        AccountHold hold = new AccountHold();
        hold.setAccountId(account.getId());
        hold.setAmount(request.amount());
        hold.setReason(request.reason());
        hold.setExpiresAt(request.expiresAt());
        hold.setCreatedBy(SecurityUtils.requireCurrentUser().username());
        holdRepository.save(hold);
        log.info("Tạm giữ #{} trên tài khoản {}", hold.getId(), MaskingUtils.maskAccountNumber(accountNumber));
        auditPublisher.publish(AuditActions.ACCOUNT_HOLD_PLACED, "ACCOUNT", account.getId(),
                "holdId=" + hold.getId() + ", amount=" + request.amount().toPlainString() + ", reason=" + request.reason());
        return toResponse(hold, accountNumber);
    }

    @Transactional
    public AccountHoldResponse releaseHold(String accountNumber, Long holdId) {
        Account account = lockAccount(accountNumber);
        AccountHold hold = holdRepository.findByIdAndAccountIdForUpdate(holdId, account.getId())
                .orElseThrow(() -> new ResourceNotFoundException("HOLD_NOT_FOUND", "Không tìm thấy khoản tạm giữ #" + holdId));
        if (hold.getStatus() != HoldStatusEnum.ACTIVE) {
            throw new BadRequestException("HOLD_NOT_ACTIVE", "Khoản tạm giữ đã được giải tỏa");
        }
        release(account, hold, SecurityUtils.requireCurrentUser().username());
        auditPublisher.publish(AuditActions.ACCOUNT_HOLD_RELEASED, "ACCOUNT", account.getId(), "holdId=" + holdId);
        return toResponse(hold, accountNumber);
    }

    @Transactional(readOnly = true)
    public List<AccountHoldResponse> listHolds(String accountNumber) {
        Account account = accountService.findAccessibleAccount(accountNumber);
        return holdRepository.findByAccountIdOrderByIdDesc(account.getId()).stream()
                .map(hold -> toResponse(hold, accountNumber))
                .toList();
    }

    /** Giải tỏa hold quá hạn, mỗi hold một transaction để một lỗi không chặn cả lô. */
    @Scheduled(fixedDelayString = "${bank.hold.expiry-check-interval:60s}")
    public void releaseExpiredHolds() {
        List<Long> expiredIds = holdRepository.findExpiredIds(HoldStatusEnum.ACTIVE, LocalDateTime.now());
        for (Long holdId : expiredIds) {
            try {
                transactionTemplate.executeWithoutResult(status -> releaseExpired(holdId));
            } catch (RuntimeException e) {
                log.error("Không giải tỏa được hold hết hạn #{}: {}", holdId, e.getMessage());
            }
        }
    }

    private void releaseExpired(Long holdId) {
        AccountHold snapshot = holdRepository.findById(holdId).orElse(null);
        if (snapshot == null) {
            return;
        }
        // Khóa tài khoản trước rồi mới khóa hold (cùng thứ tự với releaseHold) để tránh deadlock
        Account account = accountRepository.findByIdForUpdate(snapshot.getAccountId()).orElseThrow();
        AccountHold hold = holdRepository.findByIdAndAccountIdForUpdate(holdId, account.getId()).orElseThrow();
        if (hold.getStatus() == HoldStatusEnum.ACTIVE) {
            release(account, hold, SYSTEM_USER);
            log.info("Tự giải tỏa hold #{} hết hạn", holdId);
        }
    }

    private void release(Account account, AccountHold hold, String releasedBy) {
        account.releaseHold(hold.getAmount());
        hold.setStatus(HoldStatusEnum.RELEASED);
        hold.setReleasedBy(releasedBy);
        hold.setReleasedAt(LocalDateTime.now());
    }

    private Account lockAccount(String accountNumber) {
        return accountRepository.findByAccountNumberForUpdate(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException(accountNumber));
    }

    private static AccountHoldResponse toResponse(AccountHold hold, String accountNumber) {
        return new AccountHoldResponse(hold.getId(), accountNumber, hold.getAmount(), hold.getReason(),
                hold.getStatus(), hold.getExpiresAt(), hold.getCreatedBy(), hold.getReleasedBy(),
                hold.getReleasedAt(), hold.getCreatedAt());
    }
}
