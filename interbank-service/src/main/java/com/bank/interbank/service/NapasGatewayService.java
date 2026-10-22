package com.bank.interbank.service;

import com.bank.interbank.dto.NapasResult;
import com.bank.interbank.entity.InboundTransfer;
import com.bank.interbank.entity.OutboundTransfer;

import java.util.Optional;

/**
 * Kết nối Napas 247. Một bean duy nhất, chọn bằng {@code bank.interbank.gateway.provider} ({@code mock} | {@code http}).
 * Napas idempotent theo {@code transactionRef}: gửi lại cùng lệnh không chuyển tiền hai lần.
 */
public interface NapasGatewayService {

    /**
     * Gửi lệnh chuyển. Không bao giờ ném exception vì lỗi kết nối: trả {@code PENDING} để tra soát — tiền có thể đã
     * sang ngân hàng kia dù không nhận được phản hồi.
     */
    NapasResult transfer(OutboundTransfer transfer);

    /** Tra soát trạng thái lệnh đã gửi; {@code NOT_FOUND} nghĩa là Napas chưa nhận lệnh (an toàn để gửi lại). */
    NapasResult inquiry(String transactionRef);

    /**
     * Tra tên chủ tài khoản ở ngân hàng khác; rỗng nếu tài khoản không tồn tại.
     *
     * @throws com.bank.interbank.exception.NapasGatewayException khi không kết nối được Napas
     */
    Optional<String> nameInquiry(String bankCode, String accountNumber);

    /**
     * Báo Napas kết quả tiền đến (SETTLED / RETURNED).
     *
     * @throws com.bank.interbank.exception.NapasGatewayException khi lỗi — job gửi lại
     */
    void notifyInboundResult(InboundTransfer inbound);
}
