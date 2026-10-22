package com.bank.account.controller;

import com.bank.account.dto.PhoneAliasResponse;
import com.bank.account.service.PhoneAliasService;
import com.bank.common.dto.ApiResponse;
import com.bank.common.dto.PhoneAliasLookupResponse;
import com.bank.common.util.MaskingUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Chuyển tiền qua số điện thoại: khách hàng đăng ký / hủy alias cho tài khoản của mình; người chuyển tra tên
 * người nhận (đã che) trước khi chuyển.
 */
@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class PhoneAliasController {

    private final PhoneAliasService phoneAliasService;

    @PutMapping("/{accountNumber}/phone-alias")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<PhoneAliasResponse> register(@PathVariable String accountNumber) {
        return ApiResponse.ok("Đăng ký nhận tiền qua số điện thoại thành công",
                phoneAliasService.register(accountNumber));
    }

    @DeleteMapping("/{accountNumber}/phone-alias")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<Void> unregister(@PathVariable String accountNumber) {
        phoneAliasService.unregister(accountNumber);
        return ApiResponse.ok("Đã hủy nhận tiền qua số điện thoại", null);
    }

    @GetMapping("/phone-alias")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<PhoneAliasResponse> getMine() {
        return ApiResponse.ok(phoneAliasService.getMine().orElse(null));
    }

    /** Tra người nhận theo số điện thoại: chỉ trả tên và số tài khoản đã che. */
    @GetMapping("/phone-alias/lookup")
    @PreAuthorize("hasAuthority('TRANSFER_CREATE')")
    public ApiResponse<PhoneAliasLookupResponse> lookup(@RequestParam String phone) {
        PhoneAliasLookupResponse resolved = phoneAliasService.resolve(phone);
        return ApiResponse.ok(new PhoneAliasLookupResponse(MaskingUtils.maskAccountNumber(resolved.accountNumber()),
                resolved.currency(), resolved.maskedName()));
    }
}
