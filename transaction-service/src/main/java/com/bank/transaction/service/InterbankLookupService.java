package com.bank.transaction.service;

import com.bank.common.dto.NameInquiryRequest;
import com.bank.common.dto.NameInquiryResponse;
import com.bank.common.redis.RedisActions;
import com.bank.common.redis.RedisRequestReplyClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Tra tên chủ tài khoản ở ngân hàng khác qua interbank-service (Redis request-reply → Napas). Không cache: tài
 * khoản đích có thể đóng hoặc đổi chủ.
 */
@Service
@RequiredArgsConstructor
public class InterbankLookupService {

    private final RedisRequestReplyClient redisClient;

    public NameInquiryResponse nameInquiry(String bankCode, String accountNumber) {
        return redisClient.send(RedisActions.INTERBANK_SERVICE, RedisActions.INTERBANK_NAME_INQUIRY,
                new NameInquiryRequest(bankCode, accountNumber), NameInquiryResponse.class);
    }
}
