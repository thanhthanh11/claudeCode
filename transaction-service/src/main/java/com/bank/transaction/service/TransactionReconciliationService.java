package com.bank.transaction.service;

import com.bank.common.audit.AuditActions;
import com.bank.common.audit.AuditPublisher;
import com.bank.common.dto.LedgerEntryInfo;
import com.bank.common.dto.LedgerLookupRequest;
import com.bank.common.dto.PageResponse;
import com.bank.common.exception.BadRequestException;
import com.bank.common.exception.BusinessException;
import com.bank.common.exception.ResourceNotFoundException;
import com.bank.common.redis.RedisActions;
import com.bank.common.redis.RedisRequestReplyClient;
import com.bank.common.util.MaskingUtils;
import com.bank.transaction.dto.ReconciliationIssueResponse;
import com.bank.transaction.dto.ReconciliationRunResponse;
import com.bank.transaction.entity.ReconciliationIssue;
import com.bank.transaction.entity.Transaction;
import com.bank.transaction.enums.ReconciliationIssueTypeEnum;
import com.bank.transaction.enums.SagaStepEnum;
import com.bank.transaction.enums.TransactionStatusEnum;
import com.bank.transaction.enums.TransactionTypeEnum;
import com.bank.transaction.repository.ReconciliationIssueRepository;
import com.bank.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Đối soát chéo giao dịch đã kết thúc với sổ cái của account-service (Redis action {@code account.ledger-by-ref}).
 * Bút toán kỳ vọng theo kết quả (A = nguồn, B = nhận, FEE = tài khoản thu phí nội bộ):
 * <ul>
 *   <li>TRANSFER COMPLETED: DEBIT A (amount + fee), CREDIT B (amount), CREDIT FEE (fee)</li>
 *   <li>WITHDRAWAL / INTERBANK_OUT COMPLETED: DEBIT A (amount + fee), CREDIT FEE (fee)</li>
 *   <li>DEPOSIT / INTERBANK_IN COMPLETED: CREDIT B (amount)</li>
 *   <li>COMPENSATED: DEBIT A + CREDIT A (amount + fee), CREDIT FEE + DEBIT FEE (fee)</li>
 *   <li>FAILED (không còn chờ hoàn tiền): không có bút toán nào</li>
 * </ul>
 * Sai lệch được ghi vào RECONCILIATION_ISSUES, mỗi giao dịch chỉ một issue chưa xử lý.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionReconciliationService {

    private static final String FEE_ACCOUNT = "<FEE>";
    private static final int MAX_DETAIL_LENGTH = 1000;
    private static final List<TransactionStatusEnum> FINAL_STATUSES = List.of(
            TransactionStatusEnum.COMPLETED, TransactionStatusEnum.COMPENSATED, TransactionStatusEnum.FAILED);

    private final TransactionRepository transactionRepository;
    private final ReconciliationIssueRepository issueRepository;
    private final RedisRequestReplyClient redisClient;
    private final AuditPublisher auditPublisher;

    /** Hằng ngày đối soát các giao dịch kết thúc trong ngày hôm trước. */
    @Scheduled(cron = "${bank.reconciliation.cron:0 0 2 * * *}")
    public void scheduledRun() {
        LocalDate today = LocalDate.now();
        ReconciliationRunResponse result = run(today.minusDays(1).atStartOfDay(), today.atStartOfDay());
        log.info("Đối soát giao dịch định kỳ: {} giao dịch, {} sai lệch, {} issue mới", result.checked(),
                result.mismatches(), result.newIssues());
    }

    public ReconciliationRunResponse run(LocalDateTime from, LocalDateTime to) {
        if (!from.isBefore(to)) {
            throw new BadRequestException("INVALID_PERIOD", "Thời điểm bắt đầu phải trước thời điểm kết thúc");
        }
        List<Transaction> transactions = transactionRepository.findCompletedBetween(from, to, FINAL_STATUSES);
        int checked = 0;
        int mismatches = 0;
        int newIssues = 0;
        for (Transaction transaction : transactions) {
            if (transaction.getStatus() == TransactionStatusEnum.FAILED
                    && transaction.getSagaStep() == SagaStepEnum.COMPENSATION_REQUESTED) {
                continue; // còn đang chờ hoàn tiền, chưa có kết quả cuối
            }
            checked++;
            List<String> expected = expectedEntries(transaction);
            List<String> actual;
            try {
                LedgerEntryInfo[] entries = redisClient.send(RedisActions.ACCOUNT_SERVICE,
                        RedisActions.ACCOUNT_LEDGER_BY_REF, new LedgerLookupRequest(transaction.getTransactionRef()),
                        LedgerEntryInfo[].class);
                actual = actualEntries(entries);
            } catch (BusinessException e) {
                mismatches++;
                newIssues += record(transaction.getTransactionRef(), ReconciliationIssueTypeEnum.LOOKUP_FAILED,
                        "Không tra được sổ cái: " + e.getMessage());
                continue;
            }
            if (!sameEntries(expected, actual)) {
                mismatches++;
                newIssues += record(transaction.getTransactionRef(), ReconciliationIssueTypeEnum.LEDGER_MISMATCH,
                        "Kỳ vọng " + mask(expected) + " nhưng sổ cái có " + mask(actual));
            }
        }
        auditPublisher.publish(AuditActions.TRANSACTION_RECONCILIATION_RUN, "RECONCILIATION", null,
                from + " → " + to + ": checked=" + checked + ", mismatches=" + mismatches);
        return new ReconciliationRunResponse(from, to, checked, mismatches, newIssues);
    }

    @Transactional(readOnly = true)
    public PageResponse<ReconciliationIssueResponse> listIssues(Boolean resolved, Pageable pageable) {
        return PageResponse.from(issueRepository.findByResolved(resolved, pageable)
                .map(TransactionReconciliationService::toResponse));
    }

    @Transactional
    public ReconciliationIssueResponse resolve(Long id, String note) {
        ReconciliationIssue issue = issueRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ISSUE_NOT_FOUND", "Không tìm thấy issue #" + id));
        if (Boolean.TRUE.equals(issue.getResolved())) {
            throw new BadRequestException("ISSUE_RESOLVED", "Issue đã được xử lý");
        }
        issue.setResolved(true);
        issue.setResolvedAt(LocalDateTime.now());
        issue.setNote(note);
        return toResponse(issue);
    }

    static List<String> expectedEntries(Transaction transaction) {
        BigDecimal fee = transaction.getFee() == null ? BigDecimal.ZERO : transaction.getFee();
        BigDecimal debitTotal = transaction.getAmount().add(fee);
        boolean hasFee = fee.signum() > 0;
        List<String> entries = new ArrayList<>();
        switch (transaction.getStatus()) {
            case COMPLETED -> {
                if (transaction.getTransactionType() == TransactionTypeEnum.DEPOSIT
                        || transaction.getTransactionType() == TransactionTypeEnum.INTERBANK_IN) {
                    entries.add(entry(transaction.getToAccountNumber(), "CREDIT", transaction.getAmount()));
                } else {
                    entries.add(entry(transaction.getFromAccountNumber(), "DEBIT", debitTotal));
                    if (hasFee) {
                        entries.add(entry(FEE_ACCOUNT, "CREDIT", fee));
                    }
                    if (transaction.getTransactionType() == TransactionTypeEnum.TRANSFER) {
                        entries.add(entry(transaction.getToAccountNumber(), "CREDIT", transaction.getAmount()));
                    }
                }
            }
            case COMPENSATED -> {
                entries.add(entry(transaction.getFromAccountNumber(), "DEBIT", debitTotal));
                entries.add(entry(transaction.getFromAccountNumber(), "CREDIT", debitTotal));
                if (hasFee) {
                    entries.add(entry(FEE_ACCOUNT, "CREDIT", fee));
                    entries.add(entry(FEE_ACCOUNT, "DEBIT", fee));
                }
            }
            default -> {
                // FAILED: không có bút toán
            }
        }
        return entries;
    }

    static List<String> actualEntries(LedgerEntryInfo[] entries) {
        if (entries == null) {
            return List.of();
        }
        return Arrays.stream(entries)
                .map(e -> entry(e.internalAccount() ? FEE_ACCOUNT : e.accountNumber(), e.entryType(), e.amount()))
                .toList();
    }

    static boolean sameEntries(List<String> expected, List<String> actual) {
        return expected.size() == actual.size() && expected.stream().sorted().toList()
                .equals(actual.stream().sorted().toList());
    }

    private int record(String transactionRef, ReconciliationIssueTypeEnum type, String detail) {
        log.error("ĐỐI SOÁT LỆCH giao dịch {}: {}", transactionRef, detail);
        if (issueRepository.existsByTransactionRefAndResolvedFalse(transactionRef)) {
            return 0;
        }
        ReconciliationIssue issue = new ReconciliationIssue();
        issue.setTransactionRef(transactionRef);
        issue.setIssueType(type);
        issue.setDetail(detail.length() <= MAX_DETAIL_LENGTH ? detail : detail.substring(0, MAX_DETAIL_LENGTH));
        issue.setDetectedAt(LocalDateTime.now());
        issueRepository.save(issue);
        return 1;
    }

    private static String entry(String accountNumber, String type, BigDecimal amount) {
        return accountNumber + "|" + type + "|" + amount.stripTrailingZeros().toPlainString();
    }

    /** Che số tài khoản trong mô tả sai lệch. */
    private static String mask(List<String> entries) {
        return entries.stream().map(entry -> {
            String[] parts = entry.split("\\|");
            String account = FEE_ACCOUNT.equals(parts[0]) ? parts[0] : MaskingUtils.maskAccountNumber(parts[0]);
            return account + " " + parts[1] + " " + parts[2];
        }).toList().toString();
    }

    private static ReconciliationIssueResponse toResponse(ReconciliationIssue issue) {
        return new ReconciliationIssueResponse(issue.getId(), issue.getTransactionRef(), issue.getIssueType(),
                issue.getDetail(), issue.getDetectedAt(), issue.getResolved(), issue.getResolvedAt(), issue.getNote());
    }
}
