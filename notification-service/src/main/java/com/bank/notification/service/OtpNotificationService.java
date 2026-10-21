package com.bank.notification.service;

import com.bank.common.dto.CustomerInfoResponse;
import com.bank.common.dto.OtpDeliveryRequest;
import com.bank.common.exception.BadRequestException;
import com.bank.notification.dto.NotificationTemplateResponse;
import com.bank.notification.entity.Notification;
import com.bank.notification.enums.ChannelEnum;
import com.bank.notification.enums.NotificationStatusEnum;
import com.bank.notification.enums.NotificationTemplateCodeEnum;
import com.bank.notification.exception.NotificationSendException;
import com.bank.notification.repository.NotificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Gửi OTP xác nhận giao dịch qua SMS. Nội dung lưu trong NOTIFICATIONS đã che mã OTP; thông báo OTP gửi lỗi
 * không được job retry gửi lại (nội dung lưu không còn mã) — transaction-service yêu cầu gửi OTP mới.
 */
@Slf4j
@Service
public class OtpNotificationService {

    private static final String MASKED_OTP = "******";

    private final NotificationRepository notificationRepository;
    private final NotificationTemplateService templateService;
    private final NotificationSenderService senderService;
    private final CustomerLookupService customerLookupService;
    private final int maxRetries;

    public OtpNotificationService(NotificationRepository notificationRepository,
                                  NotificationTemplateService templateService,
                                  NotificationSenderService senderService,
                                  CustomerLookupService customerLookupService,
                                  @Value("${bank.notification.max-retries:3}") int maxRetries) {
        this.notificationRepository = notificationRepository;
        this.templateService = templateService;
        this.senderService = senderService;
        this.customerLookupService = customerLookupService;
        this.maxRetries = maxRetries;
    }

    public void sendOtp(OtpDeliveryRequest request) {
        CustomerInfoResponse customer = customerLookupService.getById(request.customerId());
        if (customer.phone() == null || customer.phone().isBlank()) {
            throw new BadRequestException("NO_PHONE", "Khách hàng chưa có số điện thoại nhận OTP");
        }
        NotificationTemplateCodeEnum code = NotificationTemplateCodeEnum.TRANSACTION_OTP;
        NotificationTemplateResponse template = templateService.findActive(code.name(), ChannelEnum.SMS);
        if (template == null) {
            throw new IllegalStateException("Thiếu mẫu thông báo " + code + " / SMS");
        }
        Map<String, String> values = new HashMap<>();
        values.put("transactionRef", request.transactionRef());
        values.put("amount", NotificationService.formatMoney(request.amount()));
        values.put("currency", request.currency());
        values.put("ttlMinutes", String.valueOf(Math.max(1, request.ttlSeconds() / 60)));
        values.put("otp", MASKED_OTP);
        String storedContent = NotificationService.render(template.body(), values);
        values.put("otp", request.otp());
        String content = NotificationService.render(template.body(), values);

        Notification notification = new Notification();
        notification.setEventId("otp:" + request.transactionRef() + ":" + System.currentTimeMillis());
        notification.setCustomerId(customer.id());
        notification.setTemplateCode(code.name());
        notification.setChannel(ChannelEnum.SMS);
        notification.setRecipient(customer.phone());
        notification.setContent(storedContent);
        notificationRepository.save(notification);
        try {
            senderService.send(ChannelEnum.SMS, customer.phone(), null, content);
            notification.setStatus(NotificationStatusEnum.SENT);
            notification.setSentAt(LocalDateTime.now());
        } catch (Exception e) {
            notification.setStatus(NotificationStatusEnum.FAILED);
            notification.setRetryCount(maxRetries); // không để job retry gửi lại nội dung đã che
            notification.setErrorMessage(NotificationService.truncateError(e));
            notificationRepository.save(notification);
            throw new NotificationSendException("Gửi OTP thất bại", e);
        }
        notificationRepository.save(notification);
    }
}
