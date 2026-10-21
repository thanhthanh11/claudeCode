package com.bank.transaction.controller;

import com.bank.common.dto.ApiResponse;
import com.bank.transaction.dto.BeneficiaryRequest;
import com.bank.transaction.dto.BeneficiaryResponse;
import com.bank.transaction.service.BeneficiaryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Danh bạ người nhận của khách hàng đang đăng nhập.
 */
@RestController
@RequestMapping("/api/beneficiaries")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('TRANSFER_CREATE')")
public class BeneficiaryController {

    private final BeneficiaryService beneficiaryService;

    @GetMapping
    public ApiResponse<List<BeneficiaryResponse>> list() {
        return ApiResponse.ok(beneficiaryService.listMine());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<BeneficiaryResponse> add(@Valid @RequestBody BeneficiaryRequest request) {
        return ApiResponse.ok("Đã thêm người nhận", beneficiaryService.add(request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        beneficiaryService.delete(id);
        return ApiResponse.ok("Đã xóa người nhận", null);
    }
}
