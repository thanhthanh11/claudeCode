package com.bank.account.controller;

import com.bank.account.dto.ReconciliationIssueResponse;
import com.bank.account.dto.ReconciliationRunResponse;
import com.bank.account.dto.ResolveIssueRequest;
import com.bank.account.service.LedgerReconciliationService;
import com.bank.common.dto.ApiResponse;
import com.bank.common.dto.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Đối soát số dư tài khoản với sổ cái (vận hành).
 */
@RestController
@RequestMapping("/api/admin/ledger-reconciliation")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('OPERATION_MANAGE')")
public class LedgerReconciliationController {

    private final LedgerReconciliationService reconciliationService;

    @PostMapping("/run")
    public ApiResponse<ReconciliationRunResponse> run() {
        return ApiResponse.ok("Đã chạy đối soát", reconciliationService.run());
    }

    @GetMapping("/issues")
    public ApiResponse<PageResponse<ReconciliationIssueResponse>> listIssues(
            @RequestParam(defaultValue = "false") Boolean resolved,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(reconciliationService.listIssues(resolved, pageable));
    }

    @PostMapping("/issues/{id}/resolve")
    public ApiResponse<ReconciliationIssueResponse> resolve(@PathVariable Long id,
                                                            @Valid @RequestBody ResolveIssueRequest request) {
        return ApiResponse.ok("Đã đánh dấu xử lý", reconciliationService.resolve(id, request.note()));
    }
}
