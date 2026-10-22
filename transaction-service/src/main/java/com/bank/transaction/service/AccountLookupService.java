package com.bank.transaction.service;

import com.bank.common.dto.AccountInfoResponse;
import com.bank.common.dto.AccountLookupRequest;
import com.bank.common.dto.PhoneAliasLookupRequest;
import com.bank.common.dto.PhoneAliasLookupResponse;
import com.bank.common.redis.RedisActions;
import com.bank.common.redis.RedisRequestReplyClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Tra cứu tài khoản / alias số điện thoại qua Redis request-reply tới account-service. Không cache vì là dữ liệu
 * tài chính (alias có thể bị hủy hoặc mất hiệu lực khi khách đổi số điện thoại).
 */
@Service
@RequiredArgsConstructor
public class AccountLookupService {

    private final RedisRequestReplyClient redisClient;

    public AccountInfoResponse getAccount(String accountNumber) {
        return redisClient.send(RedisActions.ACCOUNT_SERVICE, RedisActions.ACCOUNT_GET_BY_NUMBER,
                new AccountLookupRequest(accountNumber), AccountInfoResponse.class);
    }

    /** Phân giải số điện thoại người nhận → tài khoản (alias đã đăng ký ở account-service). */
    public PhoneAliasLookupResponse resolvePhoneAlias(String phone) {
        return redisClient.send(RedisActions.ACCOUNT_SERVICE, RedisActions.ACCOUNT_RESOLVE_PHONE_ALIAS,
                new PhoneAliasLookupRequest(phone), PhoneAliasLookupResponse.class);
    }
}
