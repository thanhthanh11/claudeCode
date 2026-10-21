package com.bank.customer.service;

import com.bank.common.dto.CustomerInfoResponse;
import com.bank.common.util.CacheEvictionUtils;
import com.bank.customer.entity.Customer;
import com.bank.customer.exception.CustomerNotFoundException;
import com.bank.customer.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thông tin khách hàng rút gọn cho service khác, cache trong Hazelcast. Mọi thay đổi hồ sơ
 * phải gọi {@link #evict(Customer)}.
 */
@Service
@RequiredArgsConstructor
public class CustomerInfoService {

    public static final String CACHE_BY_ID = "customer-info";
    public static final String CACHE_BY_USER_ID = "customer-info-by-user";

    private final CustomerRepository customerRepository;
    private final CacheManager cacheManager;

    @Cacheable(cacheNames = CACHE_BY_ID, key = "#customerId")
    @Transactional(readOnly = true)
    public CustomerInfoResponse getById(Long customerId) {
        return customerRepository.findById(customerId)
                .map(CustomerInfoService::toInfo)
                .orElseThrow(() -> new CustomerNotFoundException("Không tìm thấy khách hàng #" + customerId));
    }

    @Cacheable(cacheNames = CACHE_BY_USER_ID, key = "#userId")
    @Transactional(readOnly = true)
    public CustomerInfoResponse getByUserId(Long userId) {
        return customerRepository.findByUserId(userId)
                .map(CustomerInfoService::toInfo)
                .orElseThrow(() -> new CustomerNotFoundException("Người dùng chưa có hồ sơ khách hàng"));
    }

    /** Xóa cache sau khi transaction commit để không nạp lại dữ liệu cũ vào cache. */
    public void evict(Customer customer) {
        Long customerId = customer.getId();
        Long userId = customer.getUserId();
        CacheEvictionUtils.runAfterCommit(() -> {
            evictKey(CACHE_BY_ID, customerId);
            if (userId != null) {
                evictKey(CACHE_BY_USER_ID, userId);
            }
        });
    }

    private void evictKey(String cacheName, Object key) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.evict(key);
        }
    }

    static CustomerInfoResponse toInfo(Customer customer) {
        return new CustomerInfoResponse(customer.getId(), customer.getUserId(), customer.getCustomerCode(),
                customer.getFullName(), customer.getPhone(), customer.getEmail(),
                customer.getStatus().name(), customer.getKycStatus().name());
    }
}
