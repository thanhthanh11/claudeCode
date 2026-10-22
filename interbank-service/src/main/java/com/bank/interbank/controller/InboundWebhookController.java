package com.bank.interbank.controller;

import com.bank.common.dto.ApiResponse;
import com.bank.interbank.dto.InboundTransferResponse;
import com.bank.interbank.service.InboundTransferService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Webhook Napas báo tiền đến. Không dùng JWT (công khai ở gateway và SecurityConfig) — xác thực bằng chữ ký HMAC
 * trên body gốc nên nhận body dạng String, parse sau khi xác thực.
 */
@RestController
@RequiredArgsConstructor
public class InboundWebhookController {

    static final String TIMESTAMP_HEADER = "X-Napas-Timestamp";
    static final String SIGNATURE_HEADER = "X-Napas-Signature";

    private final InboundTransferService inboundTransferService;

    @PostMapping("/api/interbank/inbound")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<InboundTransferResponse> receive(
            @RequestHeader(value = TIMESTAMP_HEADER, required = false) String timestamp,
            @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature,
            @RequestBody String body) {
        return ApiResponse.ok("Đã tiếp nhận", inboundTransferService.receive(timestamp, signature, body));
    }
}
