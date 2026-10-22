package com.bank.interbank.service;

import com.bank.common.dto.NameInquiryResponse;
import com.bank.common.exception.BadRequestException;
import com.bank.common.exception.ResourceNotFoundException;
import com.bank.interbank.dto.BankResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * Tra tên chủ tài khoản ở ngân hàng khác qua Napas. Bắt buộc trước khi chuyển đi để khách xác nhận đúng người nhận;
 * transaction-service tra lại phía server và lưu tên vào giao dịch.
 */
@Service
@RequiredArgsConstructor
public class NameInquiryService {

    private static final Pattern ACCOUNT_PATTERN = Pattern.compile("^[0-9A-Za-z]{6,34}$");

    private final BankDirectoryService bankDirectoryService;
    private final NapasGatewayService napasGateway;

    @Value("${bank.interbank.own-bank-code:970999}")
    private String ownBankCode;

    public NameInquiryResponse inquire(String bankCode, String accountNumber) {
        if (bankCode == null || accountNumber == null || !ACCOUNT_PATTERN.matcher(accountNumber).matches()) {
            throw new BadRequestException("INVALID_BENEFICIARY", "Mã ngân hàng / số tài khoản người nhận không hợp lệ");
        }
        if (ownBankCode.equals(bankCode)) {
            throw new BadRequestException("INTERBANK_SAME_BANK",
                    "Người nhận cùng ngân hàng — dùng chuyển khoản nội bộ");
        }
        BankResponse bank = bankDirectoryService.requireActive(bankCode);
        String name = napasGateway.nameInquiry(bankCode, accountNumber)
                .orElseThrow(() -> new ResourceNotFoundException("BENEFICIARY_NOT_FOUND",
                        "Không tìm thấy tài khoản người nhận tại " + bank.shortName()));
        return new NameInquiryResponse(bank.bankCode(), bank.shortName(), accountNumber, name);
    }
}
