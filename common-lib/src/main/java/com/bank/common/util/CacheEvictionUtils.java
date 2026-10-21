package com.bank.common.util;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Xóa cache sau khi transaction commit — nếu xóa trước commit, request khác có thể nạp lại dữ liệu cũ vào cache.
 */
public final class CacheEvictionUtils {

    private CacheEvictionUtils() {
    }

    public static void clearAfterCommit(CacheManager cacheManager, String cacheName) {
        runAfterCommit(() -> {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.clear();
            }
        });
    }

    public static void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
