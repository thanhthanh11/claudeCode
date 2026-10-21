package com.bank.transaction.service;

import com.bank.common.dto.AccountInfoResponse;
import com.bank.common.exception.BadRequestException;
import com.bank.common.exception.ConflictException;
import com.bank.common.exception.ResourceNotFoundException;
import com.bank.common.security.SecurityUtils;
import com.bank.transaction.dto.BeneficiaryRequest;
import com.bank.transaction.dto.BeneficiaryResponse;
import com.bank.transaction.entity.Beneficiary;
import com.bank.transaction.repository.BeneficiaryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Danh bạ người nhận của khách hàng đang đăng nhập. Chỉ lưu số tài khoản + tên gợi nhớ, không lưu thông tin
 * chủ tài khoản nhận (tra lại khi chuyển tiền).
 */
@Service
@RequiredArgsConstructor
public class BeneficiaryService {

    private static final int MAX_BENEFICIARIES = 100;

    private final BeneficiaryRepository beneficiaryRepository;
    private final CustomerLookupService customerLookupService;
    private final AccountLookupService accountLookupService;

    @Transactional(readOnly = true)
    public List<BeneficiaryResponse> listMine() {
        return beneficiaryRepository.findByCustomerIdOrderByNicknameAsc(currentCustomerId()).stream()
                .map(BeneficiaryService::toResponse)
                .toList();
    }

    @Transactional
    public BeneficiaryResponse add(BeneficiaryRequest request) {
        Long customerId = currentCustomerId();
        if (beneficiaryRepository.existsByCustomerIdAndAccountNumber(customerId, request.accountNumber())) {
            throw new ConflictException("BENEFICIARY_EXISTS", "Tài khoản đã có trong danh bạ");
        }
        if (beneficiaryRepository.countByCustomerId(customerId) >= MAX_BENEFICIARIES) {
            throw new BadRequestException("BENEFICIARY_LIMIT", "Danh bạ tối đa " + MAX_BENEFICIARIES + " người nhận");
        }
        AccountInfoResponse account = accountLookupService.getAccount(request.accountNumber());
        if (!account.isActive() || "INTERNAL".equals(account.accountType())) {
            throw new BadRequestException("ACCOUNT_NOT_ACTIVE", "Tài khoản người nhận không hợp lệ");
        }
        Beneficiary beneficiary = new Beneficiary();
        beneficiary.setCustomerId(customerId);
        beneficiary.setAccountNumber(account.accountNumber());
        beneficiary.setNickname(request.nickname().trim());
        try {
            beneficiaryRepository.saveAndFlush(beneficiary);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("BENEFICIARY_EXISTS", "Tài khoản đã có trong danh bạ");
        }
        return toResponse(beneficiary);
    }

    @Transactional
    public void delete(Long id) {
        Beneficiary beneficiary = beneficiaryRepository.findByIdAndCustomerId(id, currentCustomerId())
                .orElseThrow(() -> new ResourceNotFoundException("BENEFICIARY_NOT_FOUND", "Không tìm thấy người nhận #" + id));
        beneficiaryRepository.delete(beneficiary);
    }

    private Long currentCustomerId() {
        return customerLookupService.getCustomerIdByUserId(SecurityUtils.requireCurrentUser().userId());
    }

    private static BeneficiaryResponse toResponse(Beneficiary beneficiary) {
        return new BeneficiaryResponse(beneficiary.getId(), beneficiary.getAccountNumber(), beneficiary.getNickname(),
                beneficiary.getCreatedAt());
    }
}
