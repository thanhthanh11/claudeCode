package com.bank.transaction.controller;

import com.bank.common.dto.ApiResponse;
import com.bank.common.dto.PageResponse;
import com.bank.transaction.dto.DeadLetterEventResponse;
import com.bank.transaction.dto.OutboxEventResponse;
import com.bank.transaction.dto.ReconciliationIssueResponse;
import com.bank.transaction.dto.ReconciliationRunResponse;
import com.bank.transaction.dto.ResolveIssueRequest;
import com.bank.transaction.dto.TransactionResponse;
import com.bank.transaction.enums.DeadLetterStatusEnum;
import com.bank.transaction.enums.OutboxStatusEnum;
import com.bank.transaction.enums.TransactionStatusEnum;
import com.bank.transaction.service.DeadLetterService;
import com.bank.transaction.service.TransactionOperationService;
import com.bank.transaction.service.TransactionReconciliationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Back-office vận hành: giao dịch cần xử lý thủ công, outbox lỗi, message DLT, đối soát giao dịch với sổ cái.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('OPERATION_MANAGE')")
public class TransactionOperationController {

    private final TransactionOperationService operationService;
    private final DeadLetterService deadLetterService;
    private final TransactionReconciliationService reconciliationService;

    @GetMapping("/transactions")
    public ApiResponse<PageResponse<TransactionResponse>> listTransactions(
            @RequestParam(defaultValue = "MANUAL_REVIEW") TransactionStatusEnum status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(operationService.listByStatus(status, pageable));
    }

    @PostMapping("/transactions/{transactionRef}/retry")
    public ApiResponse<TransactionResponse> retry(@PathVariable String transactionRef) {
        return ApiResponse.ok("Đã gửi lại lệnh", operationService.retry(transactionRef));
    }

    @GetMapping("/outbox")
    public ApiResponse<PageResponse<OutboxEventResponse>> listOutbox(
            @RequestParam(defaultValue = "FAILED") OutboxStatusEnum status,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(operationService.listOutbox(status, pageable));
    }

    @PostMapping("/outbox/{id}/replay")
    public ApiResponse<OutboxEventResponse> replayOutbox(@PathVariable Long id) {
        return ApiResponse.ok("Đã đưa event về hàng đợi", operationService.replayOutbox(id));
    }

    @GetMapping("/dead-letters")
    public ApiResponse<PageResponse<DeadLetterEventResponse>> listDeadLetters(
            @RequestParam(defaultValue = "NEW") DeadLetterStatusEnum status,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(deadLetterService.list(status, pageable));
    }

    @PostMapping("/dead-letters/{id}/replay")
    public ApiResponse<DeadLetterEventResponse> replayDeadLetter(@PathVariable Long id) {
        return ApiResponse.ok("Đã replay message", deadLetterService.replay(id));
    }

    @PostMapping("/dead-letters/{id}/ignore")
    public ApiResponse<DeadLetterEventResponse> ignoreDeadLetter(@PathVariable Long id) {
        return ApiResponse.ok("Đã bỏ qua message", deadLetterService.ignore(id));
    }

    /** Đối soát giao dịch kết thúc trong [from, to] (theo ngày, gồm cả hai đầu). */
    @PostMapping("/transaction-reconciliation/run")
    public ApiResponse<ReconciliationRunResponse> runReconciliation(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok("Đã chạy đối soát",
                reconciliationService.run(from.atStartOfDay(), to.plusDays(1).atStartOfDay()));
    }

    @GetMapping("/transaction-reconciliation/issues")
    public ApiResponse<PageResponse<ReconciliationIssueResponse>> listIssues(
            @RequestParam(defaultValue = "false") Boolean resolved,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(reconciliationService.listIssues(resolved, pageable));
    }

    @PostMapping("/transaction-reconciliation/issues/{id}/resolve")
    public ApiResponse<ReconciliationIssueResponse> resolveIssue(@PathVariable Long id,
                                                                 @Valid @RequestBody ResolveIssueRequest request) {
        return ApiResponse.ok("Đã đánh dấu xử lý", reconciliationService.resolve(id, request.note()));
    }
}
