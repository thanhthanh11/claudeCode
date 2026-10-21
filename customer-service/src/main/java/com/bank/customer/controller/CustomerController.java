package com.bank.customer.controller;

import com.bank.common.dto.ApiResponse;
import com.bank.common.dto.PageResponse;
import com.bank.customer.dto.CreateCustomerRequest;
import com.bank.customer.dto.CustomerResponse;
import com.bank.customer.dto.UpdateCustomerRequest;
import com.bank.customer.dto.UpdateCustomerStatusRequest;
import com.bank.customer.dto.UpdateKycStatusRequest;
import com.bank.customer.service.CustomerService;
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

@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CUSTOMER') or hasAuthority('CUSTOMER_MANAGE')")
    public ApiResponse<CustomerResponse> create(@Valid @RequestBody CreateCustomerRequest request) {
        return ApiResponse.ok("Tạo hồ sơ khách hàng thành công", customerService.create(request));
    }

    @GetMapping("/me")
    public ApiResponse<CustomerResponse> getMyProfile() {
        return ApiResponse.ok(customerService.getMyProfile());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('CUSTOMER_READ', 'CUSTOMER_MANAGE')")
    public ApiResponse<CustomerResponse> getById(@PathVariable Long id) {
        return ApiResponse.ok(customerService.getById(id));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('CUSTOMER_MANAGE')")
    public ApiResponse<PageResponse<CustomerResponse>> search(
            @RequestParam(required = false) String keyword,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(customerService.search(keyword, pageable));
    }

    @PutMapping("/{id}")
    public ApiResponse<CustomerResponse> update(@PathVariable Long id, @Valid @RequestBody UpdateCustomerRequest request) {
        return ApiResponse.ok("Cập nhật hồ sơ thành công", customerService.update(id, request));
    }

    @PutMapping("/{id}/kyc-status")
    @PreAuthorize("hasAuthority('CUSTOMER_MANAGE')")
    public ApiResponse<CustomerResponse> updateKycStatus(@PathVariable Long id,
                                                         @Valid @RequestBody UpdateKycStatusRequest request) {
        return ApiResponse.ok("Cập nhật KYC thành công", customerService.updateKycStatus(id, request));
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasAuthority('CUSTOMER_MANAGE')")
    public ApiResponse<CustomerResponse> updateStatus(@PathVariable Long id,
                                                      @Valid @RequestBody UpdateCustomerStatusRequest request) {
        return ApiResponse.ok("Cập nhật trạng thái thành công", customerService.updateStatus(id, request));
    }
}
