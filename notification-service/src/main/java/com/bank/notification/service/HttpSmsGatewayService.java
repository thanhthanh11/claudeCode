package com.bank.notification.service;

import com.bank.common.resilience.RemoteCallGuard;
import com.bank.notification.dto.SmsGatewayRequest;
import com.bank.notification.enums.ChannelEnum;
import com.bank.notification.exception.NotificationSendException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Gửi SMS qua HTTP API của nhà cung cấp brandname: {@code POST bank.notification.sms.url} với
 * {@code Authorization: Bearer <api-key>} và body {@link SmsGatewayRequest}. Bật bằng
 * {@code bank.notification.sms.provider=http}; nhà cung cấp có API khác thì viết thêm một
 * {@link NotificationGatewayService} cho kênh SMS.
 * <p>
 * Timeout theo {@code spring.http.client.*}; lời gọi đi qua circuit breaker {@value #CIRCUIT_TARGET} (chỉ
 * mất kết nối / 5xx tính là lỗi) để OTP trả lỗi ngay khi gateway sập thay vì chờ timeout.
 * 4xx (trừ 408/429) là lỗi vĩnh viễn. Không đưa body phản hồi vào message lỗi vì gateway có thể trả lại nội dung.
 */
@Service
@ConditionalOnProperty(prefix = "bank.notification.sms", name = "provider", havingValue = "http")
public class HttpSmsGatewayService implements NotificationGatewayService {

    static final String CIRCUIT_TARGET = "sms-gateway";
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    private final RestClient restClient;
    private final RemoteCallGuard callGuard;
    private final String sender;
    private final boolean stripDiacritics;

    public HttpSmsGatewayService(RestClient.Builder restClientBuilder,
                                 ObjectProvider<RemoteCallGuard> callGuard,
                                 @Value("${bank.notification.sms.url:}") String url,
                                 @Value("${bank.notification.sms.api-key:}") String apiKey,
                                 @Value("${bank.notification.sms.sender:}") String sender,
                                 @Value("${bank.notification.sms.strip-diacritics:true}") boolean stripDiacritics) {
        if (!StringUtils.hasText(url) || !StringUtils.hasText(apiKey)) {
            throw new IllegalStateException(
                    "bank.notification.sms.provider=http cần bank.notification.sms.url và bank.notification.sms.api-key");
        }
        this.restClient = restClientBuilder
                .baseUrl(url)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();
        this.callGuard = callGuard.getIfAvailable(() -> RemoteCallGuard.NOOP);
        this.sender = sender;
        this.stripDiacritics = stripDiacritics;
    }

    @Override
    public ChannelEnum channel() {
        return ChannelEnum.SMS;
    }

    @Override
    public void send(String recipient, String subject, String content) {
        SmsGatewayRequest request = new SmsGatewayRequest(recipient,
                stripDiacritics ? removeDiacritics(content) : content, sender);
        HttpStatusCode status;
        try {
            status = callGuard.call(CIRCUIT_TARGET, () -> restClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .exchange((httpRequest, response) -> {
                        HttpStatusCode code = response.getStatusCode();
                        if (code.is5xxServerError()) {
                            throw new NotificationSendException("SMS gateway lỗi: HTTP " + code.value(), true);
                        }
                        return code;
                    }));
        } catch (ResourceAccessException e) {
            throw new NotificationSendException("Không kết nối được SMS gateway", e, true);
        }
        if (status.is2xxSuccessful()) {
            return;
        }
        boolean retryable = status.isSameCodeAs(HttpStatus.REQUEST_TIMEOUT)
                || status.isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS);
        throw new NotificationSendException("SMS gateway từ chối yêu cầu: HTTP " + status.value(), retryable);
    }

    /** Bỏ dấu tiếng Việt: nhiều gateway brandname chỉ nhận ký tự không dấu (tin có dấu bị tính phí/độ dài khác). */
    static String removeDiacritics(String text) {
        String normalized = Normalizer.normalize(text.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD);
        return COMBINING_MARKS.matcher(normalized).replaceAll("");
    }
}
