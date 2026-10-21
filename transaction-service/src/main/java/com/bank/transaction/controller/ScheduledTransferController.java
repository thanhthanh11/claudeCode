package com.bank.transaction.controller;

import com.bank.common.dto.ApiResponse;
import com.bank.transaction.dto.CreateScheduledTransferRequest;
import com.bank.transaction.dto.ScheduledTransferResponse;
import com.bank.transaction.service.ScheduledTransferService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Lệnh chuyển tiền định kỳ của khách hàng đang đăng nhập.
 */
@RestController
@RequestMapping("/api/scheduled-transfers")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('TRANSFER_CREATE')")
public class ScheduledTransferController {

    private final ScheduledTransferService scheduledTransferService;

    @GetMapping
    public ApiResponse<List<ScheduledTransferResponse>> list() {
        return ApiResponse.ok(scheduledTransferService.listMine());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ScheduledTransferResponse> create(@Valid @RequestBody CreateScheduledTransferRequest request) {
        return ApiResponse.ok("Đã tạo lệnh chuyển tiền định kỳ", scheduledTransferService.create(request));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<ScheduledTransferResponse> cancel(@PathVariable Long id) {
        return ApiResponse.ok("Đã hủy lệnh chuyển tiền định kỳ", scheduledTransferService.cancel(id));
    }
}
