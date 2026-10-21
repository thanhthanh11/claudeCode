package com.bank.account.controller;

import com.bank.account.dto.AccountResponse;
import com.bank.account.dto.CreateAccountRequest;
import com.bank.account.dto.LedgerEntryResponse;
import com.bank.account.dto.UpdateAccountStatusRequest;
import com.bank.account.service.AccountService;
import com.bank.common.dto.ApiResponse;
import com.bank.common.dto.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ACCOUNT_CREATE')")
    public ApiResponse<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        return ApiResponse.ok("Mở tài khoản thành công", accountService.create(request));
    }

    @GetMapping("/me")
    @PreAuthorize("hasAuthority('ACCOUNT_READ')")
    public ApiResponse<List<AccountResponse>> getMyAccounts() {
        return ApiResponse.ok(accountService.getMyAccounts());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ACCOUNT_READ') and (hasRole('TELLER') or hasRole('ADMIN'))")
    public ApiResponse<List<AccountResponse>> getAccountsOfCustomer(@RequestParam Long customerId) {
        return ApiResponse.ok(accountService.getAccountsOfCustomer(customerId));
    }

    @GetMapping("/{accountNumber}")
    @PreAuthorize("hasAuthority('ACCOUNT_READ')")
    public ApiResponse<AccountResponse> getAccount(@PathVariable String accountNumber) {
        return ApiResponse.ok(accountService.getAccount(accountNumber));
    }

    @GetMapping("/{accountNumber}/ledger")
    @PreAuthorize("hasAuthority('ACCOUNT_READ')")
    public ApiResponse<PageResponse<LedgerEntryResponse>> getLedger(
            @PathVariable String accountNumber,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(accountService.getLedger(accountNumber, pageable));
    }

    @PutMapping("/{accountNumber}/status")
    @PreAuthorize("hasAuthority('ACCOUNT_UPDATE')")
    public ApiResponse<AccountResponse> updateStatus(@PathVariable String accountNumber,
                                                     @Valid @RequestBody UpdateAccountStatusRequest request) {
        return ApiResponse.ok("Cập nhật trạng thái tài khoản thành công",
                accountService.updateStatus(accountNumber, request));
    }
}
