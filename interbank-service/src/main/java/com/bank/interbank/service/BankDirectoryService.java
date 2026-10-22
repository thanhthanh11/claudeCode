package com.bank.interbank.service;

import com.bank.common.exception.BadRequestException;
import com.bank.interbank.dto.BankResponse;
import com.bank.interbank.entity.InterbankBank;
import com.bank.interbank.repository.InterbankBankRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/** Danh mục ngân hàng thành viên Napas (dữ liệu cấu hình, cache Hazelcast). */
@Service
@RequiredArgsConstructor
public class BankDirectoryService {

    public static final String BANK_CACHE = "interbank-banks";

    private final InterbankBankRepository bankRepository;

    @Cacheable(cacheNames = BANK_CACHE, key = "'active'")
    @Transactional(readOnly = true)
    public List<BankResponse> listActive() {
        return new ArrayList<>(bankRepository.findByActiveTrueOrderByShortNameAsc().stream()
                .map(BankDirectoryService::toResponse)
                .toList());
    }

    /** Ngân hàng đang hoạt động trên Napas; không có → INTERBANK_BANK_NOT_SUPPORTED (exception không bị cache). */
    @Cacheable(cacheNames = BANK_CACHE, key = "#bankCode")
    @Transactional(readOnly = true)
    public BankResponse requireActive(String bankCode) {
        return bankRepository.findById(bankCode)
                .filter(InterbankBank::getActive)
                .map(BankDirectoryService::toResponse)
                .orElseThrow(() -> new BadRequestException("INTERBANK_BANK_NOT_SUPPORTED",
                        "Ngân hàng " + bankCode + " không hỗ trợ chuyển tiền nhanh Napas"));
    }

    private static BankResponse toResponse(InterbankBank bank) {
        return new BankResponse(bank.getBankCode(), bank.getShortName(), bank.getFullName());
    }
}
