package com.bank.notification.service;

import com.bank.notification.entity.Notification;
import com.bank.notification.repository.NotificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Định kỳ gửi lại thông báo FAILED cho tới khi đạt số lần thử tối đa. Lô được khóa (FOR UPDATE SKIP LOCKED) trong
 * transaction nên chạy nhiều instance không gửi trùng.
 */
@Slf4j
@Service
public class NotificationRetryService {

    private static final int BATCH_SIZE = 50;

    private final NotificationRepository notificationRepository;
    private final NotificationService notificationService;
    private final int maxRetries;

    public NotificationRetryService(NotificationRepository notificationRepository,
                                    NotificationService notificationService,
                                    @Value("${bank.notification.max-retries:3}") int maxRetries) {
        this.notificationRepository = notificationRepository;
        this.notificationService = notificationService;
        this.maxRetries = maxRetries;
    }

    @Scheduled(fixedDelayString = "${bank.notification.retry-interval:60s}")
    @Transactional
    public void retryFailed() {
        List<Notification> failed = notificationRepository.lockRetryBatch(maxRetries, BATCH_SIZE);
        if (!failed.isEmpty()) {
            log.info("Gửi lại {} thông báo thất bại", failed.size());
            failed.forEach(notificationService::dispatch);
        }
    }
}
