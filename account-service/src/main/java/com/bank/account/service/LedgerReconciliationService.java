package com.bank.account.service;

import com.bank.account.dto.ReconciliationIssueResponse;
import com.bank.account.dto.ReconciliationRunResponse;
import com.bank.account.entity.ReconciliationIssue;
import com.bank.account.enums.ReconciliationIssueTypeEnum;
import com.bank.account.repository.AccountRepository;
import com.bank.account.repository.ReconciliationIssueRepository;
import com.bank.common.audit.AuditActions;
import com.bank.common.audit.AuditPublisher;
import com.bank.common.dto.PageResponse;
import com.bank.common.exception.BadRequestException;
import com.bank.common.exception.ResourceNotFoundException;
import com.bank.common.util.MaskingUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Đối soát số dư với sổ cái:
 * <ul>
 *   <li>BALANCE phải bằng tổng CREDIT − tổng DEBIT của mọi bút toán (tài khoản mở với số dư 0, mọi thay đổi số dư
 *       đều ghi sổ cái).</li>
 *   <li>AVAILABLE_BALANCE phải bằng BALANCE − tổng hold ACTIVE.</li>
 * </ul>
 * Sai lệch được ghi vào RECONCILIATION_ISSUES (mỗi tài khoản/loại sai lệch chỉ có một issue chưa xử lý)
 * và log mức ERROR. Không tự sửa số dư — nhân viên điều tra rồi đánh dấu đã xử lý.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerReconciliationService {

    private final AccountRepository accountRepository;
    private final ReconciliationIssueRepository issueRepository;
    private final AuditPublisher auditPublisher;

    @Scheduled(cron = "${bank.reconciliation.cron:0 30 1 * * *}")
    public void scheduledRun() {
        ReconciliationRunResponse result = run();
        log.info("Đối soát sổ cái định kỳ: {} sai lệch, {} issue mới", result.mismatches(), result.newIssues());
    }

    @Transactional
    public ReconciliationRunResponse run() {
        LocalDateTime now = LocalDateTime.now();
        List<Object[]> rows = accountRepository.findMismatchedBalances();
        int newIssues = 0;
        int mismatches = 0;
        for (Object[] row : rows) {
            Long accountId = ((Number) row[0]).longValue();
            String accountNumber = (String) row[1];
            BigDecimal balance = toBigDecimal(row[2]);
            BigDecimal ledgerBalance = toBigDecimal(row[3]);
            BigDecimal available = toBigDecimal(row[4]);
            BigDecimal expectedAvailable = toBigDecimal(row[5]);
            if (balance.compareTo(ledgerBalance) != 0) {
                mismatches++;
                newIssues += record(accountId, accountNumber, ReconciliationIssueTypeEnum.BALANCE_MISMATCH,
                        ledgerBalance, balance, now);
            }
            if (available.compareTo(expectedAvailable) != 0) {
                mismatches++;
                newIssues += record(accountId, accountNumber, ReconciliationIssueTypeEnum.AVAILABLE_BALANCE_MISMATCH,
                        expectedAvailable, available, now);
            }
        }
        auditPublisher.publish(AuditActions.LEDGER_RECONCILIATION_RUN, "RECONCILIATION", null,
                "mismatches=" + mismatches + ", newIssues=" + newIssues);
        return new ReconciliationRunResponse(now, mismatches, newIssues);
    }

    @Transactional(readOnly = true)
    public PageResponse<ReconciliationIssueResponse> listIssues(Boolean resolved, Pageable pageable) {
        return PageResponse.from(issueRepository.findByResolved(resolved, pageable)
                .map(LedgerReconciliationService::toResponse));
    }

    @Transactional
    public ReconciliationIssueResponse resolve(Long issueId, String note) {
        ReconciliationIssue issue = issueRepository.findById(issueId)
                .orElseThrow(() -> new ResourceNotFoundException("ISSUE_NOT_FOUND", "Không tìm thấy issue #" + issueId));
        if (Boolean.TRUE.equals(issue.getResolved())) {
            throw new BadRequestException("ISSUE_RESOLVED", "Issue đã được xử lý");
        }
        issue.setResolved(true);
        issue.setResolvedAt(LocalDateTime.now());
        issue.setNote(note);
        return toResponse(issue);
    }

    private int record(Long accountId, String accountNumber, ReconciliationIssueTypeEnum type,
                       BigDecimal expected, BigDecimal actual, LocalDateTime now) {
        log.error("ĐỐI SOÁT LỆCH {} tài khoản {}: kỳ vọng {}, thực tế {}", type,
                MaskingUtils.maskAccountNumber(accountNumber), expected.toPlainString(), actual.toPlainString());
        if (issueRepository.existsByAccountIdAndIssueTypeAndResolvedFalse(accountId, type)) {
            return 0;
        }
        ReconciliationIssue issue = new ReconciliationIssue();
        issue.setAccountId(accountId);
        issue.setAccountNumber(accountNumber);
        issue.setIssueType(type);
        issue.setExpectedAmount(expected);
        issue.setActualAmount(actual);
        issue.setDetectedAt(now);
        issueRepository.save(issue);
        return 1;
    }

    private static BigDecimal toBigDecimal(Object value) {
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(String.valueOf(value));
    }

    private static ReconciliationIssueResponse toResponse(ReconciliationIssue issue) {
        return new ReconciliationIssueResponse(issue.getId(), issue.getAccountNumber(), issue.getIssueType(),
                issue.getExpectedAmount(), issue.getActualAmount(), issue.getDetectedAt(), issue.getResolved(),
                issue.getResolvedAt(), issue.getNote());
    }
}
