package com.bank.transaction.service;

import com.bank.common.dto.AccountInfoResponse;
import com.bank.common.dto.AccountLookupRequest;
import com.bank.common.redis.RedisActions;
import com.bank.common.redis.RedisRequestReplyClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Tra cứu tài khoản qua Redis request-reply tới account-service. Không cache vì là dữ liệu tài chính.
 */
@Service
@RequiredArgsConstructor
public class AccountLookupService {

    private final RedisRequestReplyClient redisClient;

    public AccountInfoResponse getAccount(String accountNumber) {
        return redisClient.send(RedisActions.ACCOUNT_SERVICE, RedisActions.ACCOUNT_GET_BY_NUMBER,
                new AccountLookupRequest(accountNumber), AccountInfoResponse.class);
    }
}
