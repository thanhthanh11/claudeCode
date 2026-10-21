package com.bank.transaction.service;

import com.bank.common.audit.AuditActions;
import com.bank.common.audit.AuditPublisher;
import com.bank.common.exception.BadRequestException;
import com.bank.common.exception.ResourceNotFoundException;
import com.bank.common.util.CacheEvictionUtils;
import com.bank.transaction.dto.TransactionLimitResponse;
import com.bank.transaction.dto.UpdateTransactionLimitRequest;
import com.bank.transaction.entity.TransactionLimit;
import com.bank.transaction.enums.TransactionTypeEnum;
import com.bank.transaction.repository.TransactionLimitRepository;
import com.bank.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Hạn mức giao dịch theo lần và theo ngày (tính từ 00:00 theo giờ hệ thống) cho mỗi tài khoản.
 * Cấu hình hạn mức được cache; số đã dùng trong ngày luôn tính trực tiếp từ TRANSACTIONS, sau khi khóa dòng
 * ACCOUNT_LIMIT_LOCKS của tài khoản để hai request song song không cùng vượt hạn mức.
 */
@Service
@RequiredArgsConstructor
public class TransactionLimitService {

    public static final String LIMIT_CACHE = "transaction-limits";

    private final TransactionLimitRepository limitRepository;
    private final TransactionRepository transactionRepository;
    private final CacheManager cacheManager;
    private final AuditPublisher auditPublisher;

    /** Hạn mức riêng của loại tài khoản, nếu không có thì dùng hạn mức chung (ACCOUNT_TYPE null). */
    @Cacheable(cacheNames = LIMIT_CACHE, key = "#type.name() + ':' + #accountType + ':' + #currency")
    @Transactional(readOnly = true)
    public TransactionLimitResponse findLimit(TransactionTypeEnum type, String accountType, String currency) {
        return limitRepository.findByTransactionTypeAndAccountTypeAndCurrency(type, accountType, currency)
                .or(() -> limitRepository.findByTransactionTypeAndAccountTypeIsNullAndCurrency(type, currency))
                .map(TransactionLimitService::toResponse)
                .orElse(TransactionLimitResponse.NONE);
    }

    /**
     * Kiểm tra giao dịch mới có vượt hạn mức không. Phải chạy trong transaction tạo giao dịch: khóa theo tài khoản
     * được giữ tới khi giao dịch mới được ghi (commit), nên request đến sau thấy cả giao dịch này.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void checkAndLock(TransactionLimitResponse limit, TransactionTypeEnum type, String accountNumber,
                             BigDecimal amount) {
        if (limit.isUnlimited()) {
            return;
        }
        if (limit.perTxnMax() != null && amount.compareTo(limit.perTxnMax()) > 0) {
            throw new BadRequestException("LIMIT_PER_TRANSACTION_EXCEEDED",
                    "Số tiền vượt hạn mức mỗi giao dịch (" + plain(limit.perTxnMax()) + " " + limit.currency() + ")");
        }
        limitRepository.insertLockRowIfAbsent(accountNumber);
        limitRepository.lockAccount(accountNumber);

        List<Object[]> rows = type == TransactionTypeEnum.DEPOSIT
                ? transactionRepository.sumIncomingSince(accountNumber, type.name(), LocalDate.now().atStartOfDay())
                : transactionRepository.sumOutgoingSince(accountNumber, type.name(), LocalDate.now().atStartOfDay());
        Object[] usage = rows.getFirst();
        BigDecimal usedAmount = new BigDecimal(String.valueOf(usage[0]));
        long usedCount = ((Number) usage[1]).longValue();

        if (limit.dailyMax() != null && usedAmount.add(amount).compareTo(limit.dailyMax()) > 0) {
            throw new BadRequestException("LIMIT_DAILY_AMOUNT_EXCEEDED",
                    "Vượt hạn mức trong ngày (" + plain(limit.dailyMax()) + " " + limit.currency()
                            + "), đã dùng " + plain(usedAmount));
        }
        if (limit.dailyCountMax() != null && usedCount + 1 > limit.dailyCountMax()) {
            throw new BadRequestException("LIMIT_DAILY_COUNT_EXCEEDED",
                    "Vượt số giao dịch tối đa trong ngày (" + limit.dailyCountMax() + ")");
        }
    }

    @Transactional(readOnly = true)
    public List<TransactionLimitResponse> listAll() {
        return limitRepository.findAllByOrderByTransactionTypeAscCurrencyAscIdAsc().stream()
                .map(TransactionLimitService::toResponse)
                .toList();
    }

    @Transactional
    public TransactionLimitResponse update(Long id, UpdateTransactionLimitRequest request) {
        if (request.perTxnMax() != null && request.dailyMax() != null
                && request.perTxnMax().compareTo(request.dailyMax()) > 0) {
            throw new BadRequestException("INVALID_LIMIT", "Hạn mức mỗi giao dịch không được lớn hơn hạn mức ngày");
        }
        TransactionLimit limit = limitRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("LIMIT_NOT_FOUND", "Không tìm thấy hạn mức #" + id));
        limit.setPerTxnMax(request.perTxnMax());
        limit.setDailyMax(request.dailyMax());
        limit.setDailyCountMax(request.dailyCountMax());
        CacheEvictionUtils.clearAfterCommit(cacheManager, LIMIT_CACHE);
        auditPublisher.publish(AuditActions.TRANSACTION_LIMIT_UPDATED, "TRANSACTION_LIMIT", id,
                limit.getTransactionType() + "/" + (limit.getAccountType() == null ? "*" : limit.getAccountType()) + "/"
                        + limit.getCurrency() + ": perTxn=" + plain(request.perTxnMax()) + ", daily="
                        + plain(request.dailyMax()) + ", count=" + request.dailyCountMax());
        return toResponse(limit);
    }

    private static String plain(BigDecimal value) {
        return value == null ? "-" : value.stripTrailingZeros().toPlainString();
    }

    private static TransactionLimitResponse toResponse(TransactionLimit limit) {
        return new TransactionLimitResponse(limit.getId(), limit.getTransactionType(), limit.getAccountType(),
                limit.getCurrency(), limit.getPerTxnMax(), limit.getDailyMax(), limit.getDailyCountMax());
    }
}
