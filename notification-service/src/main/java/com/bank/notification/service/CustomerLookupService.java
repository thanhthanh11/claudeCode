package com.bank.notification.service;

import com.bank.common.dto.CustomerInfoResponse;
import com.bank.common.dto.CustomerLookupRequest;
import com.bank.common.redis.RedisActions;
import com.bank.common.redis.RedisRequestReplyClient;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Tra cứu khách hàng qua Redis request-reply tới customer-service, cache ngắn hạn trong Hazelcast.
 */
@Service
@RequiredArgsConstructor
public class CustomerLookupService {

    public static final String CUSTOMER_CONTACT_CACHE = "customer-contact";
    public static final String CUSTOMER_ID_BY_USER_CACHE = "customer-id-by-user";

    private final RedisRequestReplyClient redisClient;

    @Cacheable(cacheNames = CUSTOMER_CONTACT_CACHE, key = "#customerId")
    public CustomerInfoResponse getById(Long customerId) {
        return redisClient.send(RedisActions.CUSTOMER_SERVICE, RedisActions.CUSTOMER_GET_BY_ID,
                CustomerLookupRequest.byId(customerId), CustomerInfoResponse.class);
    }

    @Cacheable(cacheNames = CUSTOMER_ID_BY_USER_CACHE, key = "#userId")
    public Long getCustomerIdByUserId(Long userId) {
        return redisClient.send(RedisActions.CUSTOMER_SERVICE, RedisActions.CUSTOMER_GET_BY_USER_ID,
                CustomerLookupRequest.byUserId(userId), CustomerInfoResponse.class).id();
    }
}
