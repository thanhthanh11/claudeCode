package com.bank.customer.service;

import com.bank.common.audit.AuditActions;
import com.bank.common.audit.AuditPublisher;
import com.bank.common.exception.ResourceNotFoundException;
import com.bank.common.security.SecurityUtils;
import com.bank.customer.dto.KycDocumentRequest;
import com.bank.customer.dto.KycDocumentResponse;
import com.bank.customer.dto.ReviewKycDocumentRequest;
import com.bank.customer.entity.Customer;
import com.bank.customer.entity.KycDocument;
import com.bank.customer.enums.DocumentStatusEnum;
import com.bank.customer.enums.DocumentTypeEnum;
import com.bank.customer.enums.KycStatusEnum;
import com.bank.customer.exception.CustomerNotFoundException;
import com.bank.customer.repository.CustomerRepository;
import com.bank.customer.repository.KycDocumentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Giấy tờ KYC. Khi bật {@code bank.kyc.auto-verify}, duyệt APPROVED giấy tờ cuối cùng trong bộ bắt buộc
 * (ID_FRONT, ID_BACK, PORTRAIT) sẽ tự chuyển KYC_STATUS của khách hàng sang VERIFIED.
 */
@Slf4j
@Service
public class KycDocumentService {

    private static final Set<DocumentTypeEnum> REQUIRED_DOCUMENTS =
            EnumSet.of(DocumentTypeEnum.ID_FRONT, DocumentTypeEnum.ID_BACK, DocumentTypeEnum.PORTRAIT);

    private final KycDocumentRepository kycDocumentRepository;
    private final CustomerRepository customerRepository;
    private final CustomerService customerService;
    private final CustomerInfoService customerInfoService;
    private final AuditPublisher auditPublisher;
    private final boolean autoVerify;

    public KycDocumentService(KycDocumentRepository kycDocumentRepository, CustomerRepository customerRepository,
                              CustomerService customerService, CustomerInfoService customerInfoService,
                              AuditPublisher auditPublisher,
                              @Value("${bank.kyc.auto-verify:true}") boolean autoVerify) {
        this.kycDocumentRepository = kycDocumentRepository;
        this.customerRepository = customerRepository;
        this.customerService = customerService;
        this.customerInfoService = customerInfoService;
        this.auditPublisher = auditPublisher;
        this.autoVerify = autoVerify;
    }

    @Transactional
    public KycDocumentResponse addDocument(Long customerId, KycDocumentRequest request) {
        Customer customer = customerService.getAccessibleCustomer(customerId);
        KycDocument document = new KycDocument();
        document.setCustomerId(customer.getId());
        document.setDocumentType(request.documentType());
        document.setFileUrl(request.fileUrl());
        kycDocumentRepository.save(document);
        return toResponse(document);
    }

    @Transactional(readOnly = true)
    public List<KycDocumentResponse> listDocuments(Long customerId) {
        Customer customer = customerService.getAccessibleCustomer(customerId);
        return kycDocumentRepository.findByCustomerIdOrderByCreatedAtDesc(customer.getId()).stream()
                .map(KycDocumentService::toResponse)
                .toList();
    }

    @Transactional
    public KycDocumentResponse review(Long customerId, Long documentId, ReviewKycDocumentRequest request) {
        KycDocument document = kycDocumentRepository.findByIdAndCustomerId(documentId, customerId)
                .orElseThrow(() -> new ResourceNotFoundException("KYC_DOCUMENT_NOT_FOUND", "Không tìm thấy giấy tờ KYC"));
        document.setStatus(request.status());
        document.setNote(request.note());
        document.setReviewedBy(SecurityUtils.requireCurrentUser().username());
        document.setReviewedAt(LocalDateTime.now());
        auditPublisher.publish(AuditActions.KYC_DOCUMENT_REVIEWED, "CUSTOMER", customerId,
                "documentId=" + documentId + ", type=" + document.getDocumentType() + ", status=" + request.status());
        if (autoVerify && request.status() == DocumentStatusEnum.APPROVED) {
            verifyIfComplete(customerId);
        }
        return toResponse(document);
    }

    private void verifyIfComplete(Long customerId) {
        kycDocumentRepository.flush();
        Set<DocumentTypeEnum> approved = kycDocumentRepository.findByCustomerIdOrderByCreatedAtDesc(customerId).stream()
                .filter(doc -> doc.getStatus() == DocumentStatusEnum.APPROVED)
                .map(KycDocument::getDocumentType)
                .collect(Collectors.toSet());
        if (!approved.containsAll(REQUIRED_DOCUMENTS)) {
            return;
        }
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new CustomerNotFoundException("Không tìm thấy khách hàng #" + customerId));
        if (customer.getKycStatus() == KycStatusEnum.VERIFIED) {
            return;
        }
        KycStatusEnum previous = customer.getKycStatus();
        customer.setKycStatus(KycStatusEnum.VERIFIED);
        customerInfoService.evict(customer);
        log.info("Khách hàng #{} đủ giấy tờ KYC đã duyệt → tự chuyển VERIFIED", customerId);
        auditPublisher.publish(AuditActions.CUSTOMER_KYC_STATUS_UPDATED, "CUSTOMER", customerId,
                previous + " -> VERIFIED (tự động sau khi duyệt đủ giấy tờ)");
    }

    private static KycDocumentResponse toResponse(KycDocument document) {
        return new KycDocumentResponse(document.getId(), document.getCustomerId(), document.getDocumentType(),
                document.getFileUrl(), document.getStatus(), document.getReviewedBy(), document.getReviewedAt(),
                document.getNote(), document.getCreatedAt());
    }
}
