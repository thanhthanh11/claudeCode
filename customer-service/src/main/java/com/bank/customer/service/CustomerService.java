package com.bank.customer.service;

import com.bank.common.audit.AuditActions;
import com.bank.common.audit.AuditPublisher;
import com.bank.common.crypto.BlindIndexer;
import com.bank.common.crypto.EncryptionKeyring;
import com.bank.common.dto.PageResponse;
import com.bank.common.exception.ForbiddenException;
import com.bank.common.security.AuthUser;
import com.bank.common.security.PermissionCodes;
import com.bank.common.security.SecurityUtils;
import com.bank.customer.dto.CreateCustomerRequest;
import com.bank.customer.dto.CustomerResponse;
import com.bank.customer.dto.UpdateCustomerRequest;
import com.bank.customer.dto.UpdateCustomerStatusRequest;
import com.bank.customer.dto.UpdateKycStatusRequest;
import com.bank.customer.entity.Customer;
import com.bank.customer.enums.CustomerStatusEnum;
import com.bank.customer.enums.IdTypeEnum;
import com.bank.customer.enums.KycStatusEnum;
import com.bank.customer.exception.CustomerAlreadyExistsException;
import com.bank.customer.exception.CustomerNotFoundException;
import com.bank.customer.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter CODE_DATE = DateTimeFormatter.ofPattern("yyMMdd");

    private final CustomerRepository customerRepository;
    private final CustomerInfoService customerInfoService;
    private final AuditPublisher auditPublisher;
    private final EncryptionKeyring keyring;

    @Transactional
    public CustomerResponse create(CreateCustomerRequest request) {
        AuthUser currentUser = SecurityUtils.requireCurrentUser();
        // Nhân viên tạo hồ sơ hộ khách (userId tùy chọn); khách hàng chỉ tạo được hồ sơ của chính mình
        Long userId = currentUser.hasPermission(PermissionCodes.CUSTOMER_MANAGE)
                ? request.userId()
                : currentUser.userId();

        if (userId != null && customerRepository.existsByUserId(userId)) {
            throw new CustomerAlreadyExistsException("Người dùng đã có hồ sơ khách hàng");
        }
        String idNumberHash = keyring.blindIndexer().hashIdNumber(request.idType().name(), request.idNumber());
        if (customerRepository.existsByIdNumberHash(idNumberHash)) {
            throw new CustomerAlreadyExistsException("Số giấy tờ đã được đăng ký");
        }

        Customer customer = new Customer();
        customer.setUserId(userId);
        customer.setCustomerCode(generateCustomerCode());
        customer.setFullName(request.fullName().trim());
        customer.setDateOfBirth(request.dateOfBirth());
        customer.setGender(request.gender());
        customer.setIdType(request.idType());
        customer.setIdNumber(request.idNumber());
        customer.setIdNumberHash(idNumberHash);
        customer.setPhone(request.phone());
        customer.setPhoneHash(keyring.blindIndexer().hashPhone(request.phone()));
        customer.setEmail(request.email());
        customer.setAddress(request.address());
        customerRepository.save(customer);
        log.info("Tạo hồ sơ khách hàng #{} ({})", customer.getId(), customer.getCustomerCode());
        if (currentUser.isStaff()) {
            auditPublisher.publish(AuditActions.CUSTOMER_CREATED, "CUSTOMER", customer.getId(),
                    "userId=" + (userId == null ? "-" : userId));
        }
        return toResponse(customer);
    }

    @Transactional(readOnly = true)
    public CustomerResponse getMyProfile() {
        AuthUser currentUser = SecurityUtils.requireCurrentUser();
        return customerRepository.findByUserId(currentUser.userId())
                .map(CustomerService::toResponse)
                .orElseThrow(() -> new CustomerNotFoundException("Bạn chưa có hồ sơ khách hàng"));
    }

    @Transactional(readOnly = true)
    public CustomerResponse getById(Long id) {
        return toResponse(getAccessibleCustomer(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<CustomerResponse> search(String keyword, Pageable pageable) {
        String normalized = keyword == null || keyword.isBlank() ? null : keyword.trim();
        BlindIndexer indexer = keyring.blindIndexer();
        String phoneHash = normalized == null ? null : indexer.hashPhone(normalized);
        // Không biết từ khóa thuộc loại giấy tờ nào → so với hash của mọi loại (danh sách không được rỗng)
        List<String> idNumberHashes = normalized == null ? List.of("-") : Arrays.stream(IdTypeEnum.values())
                .map(type -> indexer.hashIdNumber(type.name(), normalized))
                .toList();
        return PageResponse.from(customerRepository.search(normalized, phoneHash, idNumberHashes, pageable)
                .map(CustomerService::toResponse));
    }

    /**
     * Khách hàng chỉ tự sửa được địa chỉ. Họ tên (đã KYC), số điện thoại và email (kênh nhận OTP) chỉ nhân viên
     * có CUSTOMER_MANAGE được đổi, tại quầy sau khi xác minh — nếu không, kẻ chiếm phiên đăng nhập có thể đổi
     * số điện thoại rồi tự nhận OTP. Thao tác của nhân viên được ghi audit (chỉ tên trường, không ghi giá trị).
     */
    @Transactional
    public CustomerResponse update(Long id, UpdateCustomerRequest request) {
        AuthUser currentUser = SecurityUtils.requireCurrentUser();
        Customer customer = getAccessibleCustomer(id);
        boolean staff = currentUser.hasPermission(PermissionCodes.CUSTOMER_MANAGE);
        if (!staff && (request.fullName() != null || request.phone() != null || request.email() != null)) {
            throw new ForbiddenException("Đổi họ tên, số điện thoại, email phải thực hiện tại quầy giao dịch");
        }
        List<String> changed = new ArrayList<>();
        if (request.fullName() != null) {
            customer.setFullName(request.fullName().trim());
            changed.add("fullName");
        }
        if (request.phone() != null) {
            customer.setPhone(request.phone());
            customer.setPhoneHash(keyring.blindIndexer().hashPhone(request.phone()));
            changed.add("phone");
        }
        if (request.email() != null) {
            customer.setEmail(request.email());
            changed.add("email");
        }
        if (request.address() != null) {
            customer.setAddress(request.address());
            changed.add("address");
        }
        customerInfoService.evict(customer);
        if (staff && !changed.isEmpty()) {
            auditPublisher.publish(AuditActions.CUSTOMER_PROFILE_UPDATED, "CUSTOMER", id, "fields=" + changed);
        }
        return toResponse(customer);
    }

    @Transactional
    public CustomerResponse updateKycStatus(Long id, UpdateKycStatusRequest request) {
        Customer customer = findCustomer(id);
        KycStatusEnum previous = customer.getKycStatus();
        customer.setKycStatus(request.kycStatus());
        customerInfoService.evict(customer);
        log.info("Cập nhật KYC khách hàng #{} → {}", id, request.kycStatus());
        auditPublisher.publish(AuditActions.CUSTOMER_KYC_STATUS_UPDATED, "CUSTOMER", id, previous + " -> " + request.kycStatus());
        return toResponse(customer);
    }

    @Transactional
    public CustomerResponse updateStatus(Long id, UpdateCustomerStatusRequest request) {
        Customer customer = findCustomer(id);
        CustomerStatusEnum previous = customer.getStatus();
        customer.setStatus(request.status());
        customerInfoService.evict(customer);
        log.info("Cập nhật trạng thái khách hàng #{} → {}", id, request.status());
        auditPublisher.publish(AuditActions.CUSTOMER_STATUS_UPDATED, "CUSTOMER", id, previous + " -> " + request.status());
        return toResponse(customer);
    }

    /**
     * Khách hàng chỉ truy cập được hồ sơ của mình; nhân viên có CUSTOMER_MANAGE truy cập mọi hồ sơ.
     */
    public Customer getAccessibleCustomer(Long id) {
        AuthUser currentUser = SecurityUtils.requireCurrentUser();
        Customer customer = findCustomer(id);
        boolean isOwner = currentUser.userId() != null && Objects.equals(customer.getUserId(), currentUser.userId());
        if (!isOwner && !currentUser.hasPermission(PermissionCodes.CUSTOMER_MANAGE)) {
            throw new ForbiddenException("Không có quyền truy cập hồ sơ khách hàng này");
        }
        return customer;
    }

    private Customer findCustomer(Long id) {
        return customerRepository.findById(id)
                .orElseThrow(() -> new CustomerNotFoundException("Không tìm thấy khách hàng #" + id));
    }

    /** CUS + yyMMdd + 6 số ngẫu nhiên, vd. CUS261020123456. */
    private String generateCustomerCode() {
        String code;
        do {
            code = "CUS" + LocalDate.now().format(CODE_DATE) + String.format("%06d", RANDOM.nextInt(1_000_000));
        } while (customerRepository.existsByCustomerCode(code));
        return code;
    }

    static CustomerResponse toResponse(Customer customer) {
        return new CustomerResponse(customer.getId(), customer.getUserId(), customer.getCustomerCode(),
                customer.getFullName(), customer.getDateOfBirth(), customer.getGender(), customer.getIdType(),
                customer.getIdNumber(), customer.getPhone(), customer.getEmail(), customer.getAddress(),
                customer.getKycStatus(), customer.getStatus(), customer.getCreatedAt(), customer.getUpdatedAt());
    }
}
