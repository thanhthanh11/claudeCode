package com.bank.account.controller;

import com.bank.account.dto.AccountHoldResponse;
import com.bank.account.dto.PlaceHoldRequest;
import com.bank.account.service.AccountHoldService;
import com.bank.common.dto.ApiResponse;
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
 * Tạm giữ / giải tỏa tiền trên tài khoản (nhân viên). Chủ tài khoản xem được danh sách hold của mình.
 */
@RestController
@RequestMapping("/api/accounts/{accountNumber}/holds")
@RequiredArgsConstructor
public class AccountHoldController {

    private final AccountHoldService holdService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ACCOUNT_UPDATE')")
    public ApiResponse<AccountHoldResponse> placeHold(@PathVariable String accountNumber,
                                                      @Valid @RequestBody PlaceHoldRequest request) {
        return ApiResponse.ok("Tạm giữ tiền thành công", holdService.placeHold(accountNumber, request));
    }

    @PostMapping("/{holdId}/release")
    @PreAuthorize("hasAuthority('ACCOUNT_UPDATE')")
    public ApiResponse<AccountHoldResponse> releaseHold(@PathVariable String accountNumber, @PathVariable Long holdId) {
        return ApiResponse.ok("Giải tỏa tiền thành công", holdService.releaseHold(accountNumber, holdId));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ACCOUNT_READ')")
    public ApiResponse<List<AccountHoldResponse>> listHolds(@PathVariable String accountNumber) {
        return ApiResponse.ok(holdService.listHolds(accountNumber));
    }
}
