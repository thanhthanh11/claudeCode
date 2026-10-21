package com.bank.account.service;

import com.bank.common.dto.CustomerInfoResponse;
import com.bank.common.dto.CustomerLookupRequest;
import com.bank.common.redis.RedisActions;
import com.bank.common.redis.RedisRequestReplyClient;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Tra cứu customerId của người dùng qua Redis request-reply tới customer-service.
 * Ánh xạ userId → customerId không đổi nên được cache trong Hazelcast.
 */
@Service
@RequiredArgsConstructor
public class CustomerLookupService {

    public static final String CUSTOMER_ID_BY_USER_CACHE = "customer-id-by-user";

    private final RedisRequestReplyClient redisClient;

    @Cacheable(cacheNames = CUSTOMER_ID_BY_USER_CACHE, key = "#userId")
    public Long getCustomerIdByUserId(Long userId) {
        CustomerInfoResponse info = redisClient.send(RedisActions.CUSTOMER_SERVICE,
                RedisActions.CUSTOMER_GET_BY_USER_ID, CustomerLookupRequest.byUserId(userId),
                CustomerInfoResponse.class);
        return info.id();
    }
}
