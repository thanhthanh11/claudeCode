package com.bank.account.service;

import com.bank.account.config.FeeAccountConfig;
import com.bank.account.dto.BalanceOperationResponse;
import com.bank.account.entity.Account;
import com.bank.account.entity.AccountLedgerEntry;
import com.bank.account.enums.AccountStatusEnum;
import com.bank.account.enums.AccountTypeEnum;
import com.bank.account.enums.EntryTypeEnum;
import com.bank.account.repository.AccountLedgerEntryRepository;
import com.bank.account.repository.AccountRepository;
import com.bank.common.event.AccountCommandPayload;
import com.bank.common.event.AccountResultPayload;
import com.bank.common.event.EventTypes;
import com.bank.common.util.MaskingUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Thay đổi số dư theo lệnh từ Saga. Mỗi thao tác:
 * khóa dòng tài khoản (SELECT FOR UPDATE) → kiểm tra bút toán đã tồn tại (idempotent) → kiểm tra nghiệp vụ
 * → cập nhật số dư + ghi sổ cái trong cùng transaction.
 * Lỗi nghiệp vụ (không đủ số dư, tài khoản bị khóa...) không ném exception mà trả về event *_FAILED.
 * <p>
 * Phí giao dịch: lệnh trừ tiền trừ {@code amount + fee} ở tài khoản nguồn (một bút toán DEBIT) và cộng {@code fee}
 * vào tài khoản thu phí nội bộ trong cùng transaction. Mỗi loại tiền có thể có nhiều tài khoản thu phí
 * ({@code bank.fee.accounts.<currency>}), chọn theo hash {@code transactionRef} để các lệnh song song không cùng khóa
 * một dòng. Hoàn phí tìm tài khoản đã thu qua sổ cái (không tính lại hash) nên đổi danh sách cấu hình vẫn hoàn đúng.
 * Thứ tự khóa luôn là tài khoản khách hàng trước, tài khoản thu phí sau (tài khoản thu phí không bao giờ là nguồn)
 * nên không deadlock.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountBalanceService {

    private final AccountRepository accountRepository;
    private final AccountLedgerEntryRepository ledgerEntryRepository;
    private final FeeAccountConfig feeAccountConfig;

    @Transactional
    public BalanceOperationResponse debit(AccountCommandPayload command) {
        String failed = EventTypes.ACCOUNT_DEBIT_FAILED;
        if (!isPositive(command.amount()) || command.feeOrZero().signum() < 0) {
            return failure(failed, command, "Số tiền không hợp lệ");
        }
        Optional<Account> found = accountRepository.findByAccountNumberForUpdate(command.accountNumber());
        if (found.isEmpty()) {
            return failure(failed, command, "Tài khoản nguồn không tồn tại");
        }
        Account account = found.get();

        Optional<AccountLedgerEntry> existing = findEntry(account, command, EntryTypeEnum.DEBIT);
        if (existing.isPresent()) {
            return success(EventTypes.ACCOUNT_DEBITED, command, existing.get().getBalanceAfter());
        }
        if (account.getStatus() != AccountStatusEnum.ACTIVE) {
            return failure(failed, command, "Tài khoản nguồn không ở trạng thái hoạt động");
        }
        if (!account.getCurrency().equals(command.currency())) {
            return failure(failed, command, "Loại tiền không khớp với tài khoản nguồn");
        }
        BigDecimal total = command.totalAmount();
        if (account.getAvailableBalance().compareTo(total) < 0) {
            return failure(failed, command, "Số dư khả dụng không đủ");
        }
        Account feeAccount = null;
        if (command.feeOrZero().signum() > 0) {
            feeAccount = lockFeeAccount(command).orElse(null);
            if (feeAccount == null) {
                return failure(failed, command, "Chưa cấu hình tài khoản thu phí cho loại tiền " + command.currency());
            }
        }

        account.debit(total);
        saveEntry(account, command.transactionRef(), EntryTypeEnum.DEBIT, total, describe(command));
        if (feeAccount != null) {
            feeAccount.credit(command.fee());
            saveEntry(feeAccount, command.transactionRef(), EntryTypeEnum.CREDIT, command.fee(),
                    "Thu phí giao dịch " + command.transactionRef());
        }
        log.info("Trừ tiền tài khoản {} — giao dịch {}", MaskingUtils.maskAccountNumber(account.getAccountNumber()),
                command.transactionRef());
        return success(EventTypes.ACCOUNT_DEBITED, command, account.getBalance());
    }

    @Transactional
    public BalanceOperationResponse credit(AccountCommandPayload command) {
        String failed = EventTypes.ACCOUNT_CREDIT_FAILED;
        if (!isPositive(command.amount())) {
            return failure(failed, command, "Số tiền không hợp lệ");
        }
        Optional<Account> found = accountRepository.findByAccountNumberForUpdate(command.accountNumber());
        if (found.isEmpty()) {
            return failure(failed, command, "Tài khoản nhận không tồn tại");
        }
        Account account = found.get();

        Optional<AccountLedgerEntry> existing = findEntry(account, command, EntryTypeEnum.CREDIT);
        if (existing.isPresent()) {
            return success(EventTypes.ACCOUNT_CREDITED, command, existing.get().getBalanceAfter());
        }
        if (account.getStatus() != AccountStatusEnum.ACTIVE) {
            return failure(failed, command, "Tài khoản nhận không ở trạng thái hoạt động");
        }
        if (!account.getCurrency().equals(command.currency())) {
            return failure(failed, command, "Loại tiền không khớp với tài khoản nhận");
        }

        account.credit(command.amount());
        saveEntry(account, command.transactionRef(), EntryTypeEnum.CREDIT, command.amount(), command.description());
        log.info("Cộng tiền tài khoản {} — giao dịch {}", MaskingUtils.maskAccountNumber(account.getAccountNumber()),
                command.transactionRef());
        return success(EventTypes.ACCOUNT_CREDITED, command, account.getBalance());
    }

    /**
     * Bù trừ Saga: hoàn tiền (cả phí) về tài khoản nguồn và thu hồi phí từ tài khoản thu phí. Chỉ hoàn khi đã có
     * bút toán DEBIT tương ứng và không phụ thuộc trạng thái FROZEN (tài khoản bị phong tỏa vẫn nhận lại tiền của mình).
     */
    @Transactional
    public BalanceOperationResponse refund(AccountCommandPayload command) {
        String failed = EventTypes.ACCOUNT_REFUND_FAILED;
        Optional<Account> found = accountRepository.findByAccountNumberForUpdate(command.accountNumber());
        if (found.isEmpty()) {
            return failure(failed, command, "Tài khoản nguồn không tồn tại");
        }
        Account account = found.get();

        Optional<AccountLedgerEntry> existing = findEntry(account, command, EntryTypeEnum.CREDIT);
        if (existing.isPresent()) {
            return success(EventTypes.ACCOUNT_REFUNDED, command, existing.get().getBalanceAfter());
        }
        Optional<AccountLedgerEntry> debitEntry = findEntry(account, command, EntryTypeEnum.DEBIT);
        if (debitEntry.isEmpty()) {
            return failure(failed, command, "Không có bút toán trừ tiền để hoàn");
        }
        BigDecimal total = command.totalAmount();
        if (debitEntry.get().getAmount().compareTo(total) != 0) {
            return failure(failed, command, "Số tiền hoàn không khớp bút toán trừ tiền");
        }
        if (account.getStatus() == AccountStatusEnum.CLOSED) {
            return failure(failed, command, "Tài khoản nguồn đã đóng, cần xử lý thủ công");
        }
        Account feeAccount = null;
        if (command.feeOrZero().signum() > 0) {
            feeAccount = lockCollectedFeeAccount(command).orElse(null);
            if (feeAccount == null || feeAccount.getAvailableBalance().compareTo(command.fee()) < 0) {
                return failure(failed, command, "Không thu hồi được phí từ tài khoản thu phí, cần xử lý thủ công");
            }
        }

        account.credit(total);
        saveEntry(account, command.transactionRef(), EntryTypeEnum.CREDIT, total,
                "Hoàn tiền giao dịch " + command.transactionRef());
        if (feeAccount != null) {
            feeAccount.debit(command.fee());
            saveEntry(feeAccount, command.transactionRef(), EntryTypeEnum.DEBIT, command.fee(),
                    "Hoàn phí giao dịch " + command.transactionRef());
        }
        log.info("Hoàn tiền tài khoản {} — giao dịch {}", MaskingUtils.maskAccountNumber(account.getAccountNumber()),
                command.transactionRef());
        return success(EventTypes.ACCOUNT_REFUNDED, command, account.getBalance());
    }

    private Optional<Account> lockFeeAccount(AccountCommandPayload command) {
        String feeAccountNumber = feeAccountConfig.accountFor(command.currency(), command.transactionRef());
        if (feeAccountNumber == null) {
            return Optional.empty();
        }
        return accountRepository.findByAccountNumberForUpdate(feeAccountNumber);
    }

    /** Khóa tài khoản đã thu phí của giao dịch: bút toán CREDIT duy nhất trên tài khoản INTERNAL, đúng số phí. */
    private Optional<Account> lockCollectedFeeAccount(AccountCommandPayload command) {
        List<AccountLedgerEntry> feeEntries = ledgerEntryRepository.findByTransactionRefOnAccountType(
                command.transactionRef(), EntryTypeEnum.CREDIT, AccountTypeEnum.INTERNAL);
        if (feeEntries.size() != 1 || feeEntries.getFirst().getAmount().compareTo(command.fee()) != 0) {
            return Optional.empty();
        }
        return accountRepository.findByIdForUpdate(feeEntries.getFirst().getAccountId());
    }

    private Optional<AccountLedgerEntry> findEntry(Account account, AccountCommandPayload command, EntryTypeEnum type) {
        return ledgerEntryRepository.findByAccountIdAndTransactionRefAndEntryType(account.getId(),
                command.transactionRef(), type);
    }

    private void saveEntry(Account account, String transactionRef, EntryTypeEnum type, BigDecimal amount,
                           String description) {
        AccountLedgerEntry entry = new AccountLedgerEntry();
        entry.setAccountId(account.getId());
        entry.setTransactionRef(transactionRef);
        entry.setEntryType(type);
        entry.setAmount(amount);
        entry.setBalanceAfter(account.getBalance());
        entry.setDescription(description);
        ledgerEntryRepository.save(entry);
    }

    private static String describe(AccountCommandPayload command) {
        if (command.feeOrZero().signum() == 0) {
            return command.description();
        }
        String base = command.description() == null ? "Giao dịch " + command.transactionRef() : command.description();
        String described = base + " (phí " + command.fee().stripTrailingZeros().toPlainString() + ")";
        return described.length() <= 255 ? described : described.substring(0, 255);
    }

    private static boolean isPositive(BigDecimal amount) {
        return amount != null && amount.signum() > 0;
    }

    private static BalanceOperationResponse success(String eventType, AccountCommandPayload command,
                                                    BigDecimal balanceAfter) {
        return new BalanceOperationResponse(eventType, new AccountResultPayload(command.transactionRef(),
                command.accountNumber(), command.amount(), balanceAfter, null));
    }

    private static BalanceOperationResponse failure(String eventType, AccountCommandPayload command, String reason) {
        log.info("Giao dịch {} thất bại tại account-service: {}", command.transactionRef(), reason);
        return new BalanceOperationResponse(eventType, new AccountResultPayload(command.transactionRef(),
                command.accountNumber(), command.amount(), null, reason));
    }
}
