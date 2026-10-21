package com.bank.notification.service;

import com.bank.common.audit.AuditActions;
import com.bank.common.audit.AuditPublisher;
import com.bank.common.exception.ResourceNotFoundException;
import com.bank.common.util.CacheEvictionUtils;
import com.bank.notification.dto.NotificationTemplateResponse;
import com.bank.notification.dto.UpdateNotificationTemplateRequest;
import com.bank.notification.entity.NotificationTemplate;
import com.bank.notification.enums.ChannelEnum;
import com.bank.notification.repository.NotificationTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Mẫu thông báo ít thay đổi → cache trong Hazelcast, xóa cache khi cập nhật.
 */
@Service
@RequiredArgsConstructor
public class NotificationTemplateService {

    public static final String TEMPLATE_CACHE = "notification-templates";

    private final NotificationTemplateRepository templateRepository;
    private final CacheManager cacheManager;
    private final AuditPublisher auditPublisher;

    @Cacheable(cacheNames = TEMPLATE_CACHE, key = "#code + ':' + #channel", unless = "#result == null")
    @Transactional(readOnly = true)
    public NotificationTemplateResponse findActive(String code, ChannelEnum channel) {
        return templateRepository.findByCodeAndChannelAndActiveTrue(code, channel)
                .map(NotificationTemplateService::toResponse)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public List<NotificationTemplateResponse> listAll() {
        return templateRepository.findAllByOrderByCodeAscChannelAsc().stream()
                .map(NotificationTemplateService::toResponse)
                .toList();
    }

    /** Xóa cache sau khi commit để request khác không nạp lại mẫu cũ. */
    @Transactional
    public NotificationTemplateResponse update(Long id, UpdateNotificationTemplateRequest request) {
        NotificationTemplate template = templateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("TEMPLATE_NOT_FOUND", "Không tìm thấy mẫu thông báo #" + id));
        template.setSubject(request.subject());
        template.setBody(request.body());
        template.setActive(request.active());
        CacheEvictionUtils.clearAfterCommit(cacheManager, TEMPLATE_CACHE);
        auditPublisher.publish(AuditActions.NOTIFICATION_TEMPLATE_UPDATED, "NOTIFICATION_TEMPLATE", id,
                template.getCode() + "/" + template.getChannel() + ", active=" + request.active());
        return toResponse(template);
    }

    private static NotificationTemplateResponse toResponse(NotificationTemplate template) {
        return new NotificationTemplateResponse(template.getId(), template.getCode(), template.getChannel(),
                template.getSubject(), template.getBody(), template.getActive());
    }
}
