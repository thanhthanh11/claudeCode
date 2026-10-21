package com.bank.customer.controller;

import com.bank.common.dto.ApiResponse;
import com.bank.customer.dto.KycDocumentRequest;
import com.bank.customer.dto.KycDocumentResponse;
import com.bank.customer.dto.ReviewKycDocumentRequest;
import com.bank.customer.service.KycDocumentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/customers/{customerId}/kyc-documents")
@RequiredArgsConstructor
public class KycDocumentController {

    private final KycDocumentService kycDocumentService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<KycDocumentResponse> addDocument(@PathVariable Long customerId,
                                                        @Valid @RequestBody KycDocumentRequest request) {
        return ApiResponse.ok("Tải giấy tờ KYC thành công", kycDocumentService.addDocument(customerId, request));
    }

    @GetMapping
    public ApiResponse<List<KycDocumentResponse>> listDocuments(@PathVariable Long customerId) {
        return ApiResponse.ok(kycDocumentService.listDocuments(customerId));
    }

    @PutMapping("/{documentId}/review")
    @PreAuthorize("hasAuthority('CUSTOMER_MANAGE')")
    public ApiResponse<KycDocumentResponse> review(@PathVariable Long customerId, @PathVariable Long documentId,
                                                   @Valid @RequestBody ReviewKycDocumentRequest request) {
        return ApiResponse.ok("Duyệt giấy tờ KYC thành công", kycDocumentService.review(customerId, documentId, request));
    }
}
