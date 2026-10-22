package com.bank.interbank.controller;

import com.bank.common.dto.ApiResponse;
import com.bank.common.dto.NameInquiryResponse;
import com.bank.interbank.dto.BankResponse;
import com.bank.interbank.service.BankDirectoryService;
import com.bank.interbank.service.NameInquiryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Danh mục ngân hàng Napas và tra tên người nhận trước khi chuyển tiền liên ngân hàng. */
@RestController
@RequestMapping("/api/interbank")
@RequiredArgsConstructor
public class InterbankController {

    private final BankDirectoryService bankDirectoryService;
    private final NameInquiryService nameInquiryService;

    @GetMapping("/banks")
    public ApiResponse<List<BankResponse>> banks() {
        return ApiResponse.ok(bankDirectoryService.listActive());
    }

    @GetMapping("/name-inquiry")
    @PreAuthorize("hasAuthority('TRANSFER_CREATE')")
    public ApiResponse<NameInquiryResponse> nameInquiry(@RequestParam String bankCode,
                                                        @RequestParam String accountNumber) {
        return ApiResponse.ok(nameInquiryService.inquire(bankCode, accountNumber));
    }
}
