package com.bank.transaction.controller;

import com.bank.common.dto.ApiResponse;
import com.bank.transaction.dto.FeeScheduleResponse;
import com.bank.transaction.dto.TransactionLimitResponse;
import com.bank.transaction.dto.UpdateFeeScheduleRequest;
import com.bank.transaction.dto.UpdateTransactionLimitRequest;
import com.bank.transaction.service.FeeScheduleService;
import com.bank.transaction.service.TransactionLimitService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Hạn mức giao dịch và biểu phí. Mọi người dùng đăng nhập xem được (để biết trước phí / hạn mức); chỉ ADMIN sửa.
 */
@RestController
@RequiredArgsConstructor
public class TransactionPolicyController {

    private final TransactionLimitService limitService;
    private final FeeScheduleService feeScheduleService;

    @GetMapping("/api/transaction-limits")
    @PreAuthorize("hasAuthority('TRANSACTION_READ')")
    public ApiResponse<List<TransactionLimitResponse>> listLimits() {
        return ApiResponse.ok(limitService.listAll());
    }

    @PutMapping("/api/transaction-limits/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<TransactionLimitResponse> updateLimit(@PathVariable Long id,
                                                             @Valid @RequestBody UpdateTransactionLimitRequest request) {
        return ApiResponse.ok("Cập nhật hạn mức thành công", limitService.update(id, request));
    }

    @GetMapping("/api/fee-schedules")
    @PreAuthorize("hasAuthority('TRANSACTION_READ')")
    public ApiResponse<List<FeeScheduleResponse>> listFees() {
        return ApiResponse.ok(feeScheduleService.listAll());
    }

    @PutMapping("/api/fee-schedules/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<FeeScheduleResponse> updateFee(@PathVariable Long id,
                                                      @Valid @RequestBody UpdateFeeScheduleRequest request) {
        return ApiResponse.ok("Cập nhật biểu phí thành công", feeScheduleService.update(id, request));
    }
}
