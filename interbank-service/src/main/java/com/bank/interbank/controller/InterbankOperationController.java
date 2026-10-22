package com.bank.interbank.controller;

import com.bank.common.dto.ApiResponse;
import com.bank.common.dto.PageResponse;
import com.bank.interbank.dto.OutboundTransferResponse;
import com.bank.interbank.enums.OutboundStatusEnum;
import com.bank.interbank.service.OutboundTransferService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Vận hành lệnh chuyển đi liên ngân hàng: xem lệnh chưa rõ kết quả, tra soát thủ công. */
@RestController
@RequestMapping("/api/admin/interbank/outbound")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('OPERATION_MANAGE')")
public class InterbankOperationController {

    private final OutboundTransferService outboundTransferService;

    @GetMapping
    public ApiResponse<PageResponse<OutboundTransferResponse>> list(
            @RequestParam(required = false) OutboundStatusEnum status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(outboundTransferService.list(status, pageable));
    }

    @PostMapping("/{transactionRef}/inquiry")
    public ApiResponse<OutboundTransferResponse> inquire(@PathVariable String transactionRef) {
        return ApiResponse.ok("Đã tra soát Napas", outboundTransferService.inquireNow(transactionRef));
    }
}
