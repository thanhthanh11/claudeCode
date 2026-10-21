package com.bank.notification.service;

import com.bank.common.dto.AccountInfoResponse;
import com.bank.common.dto.CustomerInfoResponse;
import com.bank.common.dto.PageResponse;
import com.bank.common.event.EventEnvelope;
import com.bank.common.event.EventSerializer;
import com.bank.common.event.EventTypes;
import com.bank.common.event.TransactionNotificationPayload;
import com.bank.common.security.SecurityUtils;
import com.bank.common.util.MaskingUtils;
import com.bank.notification.dto.NotificationResponse;
import com.bank.notification.dto.NotificationTemplateResponse;
import com.bank.notification.entity.Notification;
import com.bank.notification.enums.ChannelEnum;
import com.bank.notification.enums.NotificationStatusEnum;
import com.bank.notification.enums.NotificationTemplateCodeEnum;
import com.bank.notification.exception.NotificationSendException;
import com.bank.notification.repository.NotificationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
public class NotificationService {

    private static final Locale VIETNAMESE = Locale.of("vi", "VN");
    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");
    private static final int MAX_ERROR_LENGTH = 1000;
    /** Hậu tố eventId cho thông báo người nhận — unique (EVENT_ID, CHANNEL) vẫn chống gửi trùng. */
    private static final String RECIPIENT_EVENT_SUFFIX = ":to";

    private final NotificationRepository notificationRepository;
    private final NotificationTemplateService templateService;
    private final NotificationSenderService senderService;
    private final AccountLookupService accountLookupService;
    private final CustomerLookupService customerLookupService;
    private final ObjectMapper objectMapper;
    private final int maxRetries;

    public NotificationService(NotificationRepository notificationRepository,
                               NotificationTemplateService templateService,
                               NotificationSenderService senderService,
                               AccountLookupService accountLookupService,
                               CustomerLookupService customerLookupService,
                               ObjectMapper objectMapper,
                               @Value("${bank.notification.max-retries:3}") int maxRetries) {
        this.notificationRepository = notificationRepository;
        this.templateService = templateService;
        this.senderService = senderService;
        this.accountLookupService = accountLookupService;
        this.customerLookupService = customerLookupService;
        this.objectMapper = objectMapper;
        this.maxRetries = maxRetries;
    }

    /**
     * Thông báo kết quả giao dịch:
     * <ul>
     *   <li>Chủ tài khoản bị trừ tiền (chuyển khoản, rút tiền) hoặc được nộp tiền: thành công → SMS + email,
     *       thất bại → email. Mẫu riêng theo loại giao dịch (TRANSFER_* / DEPOSIT_* / WITHDRAWAL_*).</li>
     *   <li>Người nhận chuyển khoản thành công: SMS + email theo mẫu TRANSFER_RECEIVED.</li>
     * </ul>
     */
    public void handleTransactionEvent(EventEnvelope envelope) {
        TransactionNotificationPayload payload =
                EventSerializer.payload(objectMapper, envelope, TransactionNotificationPayload.class);
        boolean success = EventTypes.TRANSACTION_COMPLETED.equals(envelope.eventType());
        String type = payload.transactionType();
        String primaryAccount = "DEPOSIT".equals(type) ? payload.toAccountNumber() : payload.fromAccountNumber();
        List<ChannelEnum> channels = success ? List.of(ChannelEnum.SMS, ChannelEnum.EMAIL) : List.of(ChannelEnum.EMAIL);

        notifyAccountOwner(envelope.eventId(), primaryAccount, templateFor(type, success), channels, payload);
        if (success && "TRANSFER".equals(type) && payload.toAccountNumber() != null) {
            notifyAccountOwner(envelope.eventId() + RECIPIENT_EVENT_SUFFIX, payload.toAccountNumber(),
                    NotificationTemplateCodeEnum.TRANSFER_RECEIVED, List.of(ChannelEnum.SMS, ChannelEnum.EMAIL), payload);
        }
    }

    private void notifyAccountOwner(String eventId, String accountNumber, NotificationTemplateCodeEnum templateCode,
                                    List<ChannelEnum> channels, TransactionNotificationPayload payload) {
        AccountInfoResponse account = accountLookupService.getAccount(accountNumber);
        CustomerInfoResponse customer = customerLookupService.getById(account.customerId());
        Map<String, String> values = templateValues(payload, account);

        for (ChannelEnum channel : channels) {
            if (notificationRepository.existsByEventIdAndChannel(eventId, channel)) {
                continue;
            }
            NotificationTemplateResponse template = templateService.findActive(templateCode.name(), channel);
            if (template == null) {
                log.warn("Không có mẫu thông báo {} / {} đang hoạt động", templateCode, channel);
                continue;
            }
            String recipient = channel == ChannelEnum.SMS ? customer.phone() : customer.email();
            if (recipient == null || recipient.isBlank()) {
                continue;
            }
            Notification notification = new Notification();
            notification.setEventId(eventId);
            notification.setCustomerId(customer.id());
            notification.setTemplateCode(templateCode.name());
            notification.setChannel(channel);
            notification.setRecipient(recipient);
            notification.setSubject(render(template.subject(), values));
            notification.setContent(render(template.body(), values));
            try {
                notificationRepository.saveAndFlush(notification);
            } catch (DataIntegrityViolationException e) {
                continue; // đã có instance khác xử lý cùng event
            }
            dispatch(notification);
        }
    }

    static NotificationTemplateCodeEnum templateFor(String transactionType, boolean success) {
        return switch (transactionType) {
            case "DEPOSIT" -> success ? NotificationTemplateCodeEnum.DEPOSIT_SUCCESS : NotificationTemplateCodeEnum.DEPOSIT_FAILED;
            case "WITHDRAWAL" -> success ? NotificationTemplateCodeEnum.WITHDRAWAL_SUCCESS
                    : NotificationTemplateCodeEnum.WITHDRAWAL_FAILED;
            default -> success ? NotificationTemplateCodeEnum.TRANSFER_SUCCESS : NotificationTemplateCodeEnum.TRANSFER_FAILED;
        };
    }

    /**
     * Gửi và cập nhật trạng thái; lỗi tạm thời được ghi nhận để job retry xử lý, lỗi vĩnh viễn (người nhận bị
     * nhà cung cấp từ chối) đặt luôn RETRY_COUNT = max-retries để không gửi lại.
     */
    public void dispatch(Notification notification) {
        try {
            senderService.send(notification.getChannel(), notification.getRecipient(), notification.getSubject(),
                    notification.getContent());
            notification.setStatus(NotificationStatusEnum.SENT);
            notification.setSentAt(LocalDateTime.now());
            notification.setErrorMessage(null);
        } catch (Exception e) {
            notification.setStatus(NotificationStatusEnum.FAILED);
            boolean retryable = !(e instanceof NotificationSendException sendError) || sendError.isRetryable();
            notification.setRetryCount(retryable ? notification.getRetryCount() + 1
                    : Math.max(maxRetries, notification.getRetryCount() + 1));
            notification.setErrorMessage(truncateError(e));
            log.warn("Gửi thông báo #{} tới {} thất bại: {}", notification.getId(),
                    MaskingUtils.maskRecipient(notification.getRecipient()), e.getMessage());
        }
        notificationRepository.save(notification);
    }

    public PageResponse<NotificationResponse> getMyNotifications(Pageable pageable) {
        Long customerId = customerLookupService.getCustomerIdByUserId(SecurityUtils.requireCurrentUser().userId());
        return getByCustomer(customerId, pageable);
    }

    public PageResponse<NotificationResponse> getByCustomer(Long customerId, Pageable pageable) {
        return PageResponse.from(notificationRepository.findByCustomerId(customerId, pageable)
                .map(NotificationService::toResponse));
    }

    private Map<String, String> templateValues(TransactionNotificationPayload payload, AccountInfoResponse account) {
        Map<String, String> values = new HashMap<>();
        values.put("accountNumber", MaskingUtils.maskAccountNumber(account.accountNumber()));
        values.put("amount", formatMoney(payload.amount()));
        values.put("fee", formatMoney(payload.fee() == null ? BigDecimal.ZERO : payload.fee()));
        values.put("currency", payload.currency());
        values.put("transactionRef", payload.transactionRef());
        values.put("balance", formatMoney(account.availableBalance()));
        values.put("reason", payload.reason() == null ? "" : payload.reason());
        Instant occurredAt = payload.occurredAt() == null ? Instant.now() : payload.occurredAt();
        values.put("time", TIME_FORMAT.format(occurredAt.atZone(VIETNAM_ZONE)));
        return values;
    }

    static String render(String text, Map<String, String> values) {
        if (text == null) {
            return null;
        }
        String result = text;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    static String formatMoney(BigDecimal amount) {
        if (amount == null) {
            return "";
        }
        NumberFormat format = NumberFormat.getNumberInstance(VIETNAMESE);
        format.setMaximumFractionDigits(4);
        return format.format(amount);
    }

    static String truncateError(Exception e) {
        String message = String.valueOf(e.getMessage());
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }

    private static NotificationResponse toResponse(Notification notification) {
        return new NotificationResponse(notification.getId(), notification.getTemplateCode(),
                notification.getChannel(), notification.getRecipient(), notification.getSubject(),
                notification.getContent(), notification.getStatus(), notification.getRetryCount(),
                notification.getSentAt(), notification.getCreatedAt());
    }
}
