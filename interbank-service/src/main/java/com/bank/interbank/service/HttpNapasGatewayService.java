package com.bank.interbank.service;

import com.bank.common.resilience.RemoteCallGuard;
import com.bank.interbank.dto.NapasApi;
import com.bank.interbank.dto.NapasResult;
import com.bank.interbank.entity.InboundTransfer;
import com.bank.interbank.entity.OutboundTransfer;
import com.bank.interbank.enums.NapasResultStatusEnum;
import com.bank.interbank.exception.NapasGatewayException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.Optional;

/**
 * Gọi Napas qua HTTP ({@code bank.interbank.gateway.provider=http}): base URL {@code bank.interbank.gateway.url},
 * {@code Authorization: Bearer <api-key>}, hợp đồng ở {@link NapasApi}. Timeout theo {@code spring.http.client.*},
 * đi qua circuit breaker {@value #CIRCUIT_TARGET}.
 * <p>
 * Gửi lệnh chuyển: mọi trường hợp không chắc chắn (mất kết nối, timeout, 5xx, 408/429, mạch mở, body rỗng) trả
 * PENDING để tra soát — không bao giờ coi là từ chối, vì tiền có thể đã sang ngân hàng kia. Chỉ 4xx khác (lệnh bị
 * từ chối khi kiểm tra) mới là REJECTED. Không đưa body phản hồi lỗi vào log / lý do.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "bank.interbank.gateway", name = "provider", havingValue = "http")
public class HttpNapasGatewayService implements NapasGatewayService {

    static final String CIRCUIT_TARGET = "napas-gateway";

    private final RestClient restClient;
    private final RemoteCallGuard callGuard;

    public HttpNapasGatewayService(RestClient.Builder restClientBuilder, ObjectProvider<RemoteCallGuard> callGuard,
                                   @Value("${bank.interbank.gateway.url:}") String url,
                                   @Value("${bank.interbank.gateway.api-key:}") String apiKey) {
        if (!StringUtils.hasText(url) || !StringUtils.hasText(apiKey)) {
            throw new IllegalStateException(
                    "bank.interbank.gateway.provider=http cần bank.interbank.gateway.url và bank.interbank.gateway.api-key");
        }
        this.restClient = restClientBuilder
                .baseUrl(url)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();
        this.callGuard = callGuard.getIfAvailable(() -> RemoteCallGuard.NOOP);
    }

    @Override
    public NapasResult transfer(OutboundTransfer transfer) {
        NapasApi.TransferRequest request = new NapasApi.TransferRequest(transfer.getTransactionRef(),
                transfer.getBankCode(), transfer.getBeneficiaryAccount(), transfer.getBeneficiaryName(),
                transfer.getAmount(), transfer.getCurrency(), transfer.getDescription());
        try {
            return callGuard.call(CIRCUIT_TARGET, () -> restClient.post()
                    .uri("/transfers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .exchange((httpRequest, response) -> {
                        HttpStatusCode code = response.getStatusCode();
                        if (code.is2xxSuccessful()) {
                            return toResult(response.bodyTo(NapasApi.TransferResponse.class));
                        }
                        if (isUncertain(code)) {
                            return NapasResult.pending("Napas lỗi tạm thời: HTTP " + code.value());
                        }
                        return new NapasResult(NapasResultStatusEnum.REJECTED, null,
                                "Napas từ chối lệnh: HTTP " + code.value());
                    }));
        } catch (RuntimeException e) {
            log.warn("Gửi lệnh {} tới Napas không rõ kết quả: {}", transfer.getTransactionRef(),
                    e.getClass().getSimpleName());
            return NapasResult.pending("Không nhận được phản hồi từ Napas");
        }
    }

    @Override
    public NapasResult inquiry(String transactionRef) {
        try {
            return callGuard.call(CIRCUIT_TARGET, () -> restClient.get()
                    .uri("/transfers/{ref}", transactionRef)
                    .exchange((httpRequest, response) -> {
                        HttpStatusCode code = response.getStatusCode();
                        if (code.is2xxSuccessful()) {
                            return toResult(response.bodyTo(NapasApi.TransferResponse.class));
                        }
                        if (code.isSameCodeAs(HttpStatus.NOT_FOUND)) {
                            return new NapasResult(NapasResultStatusEnum.NOT_FOUND, null, null);
                        }
                        return NapasResult.pending("Tra soát lỗi: HTTP " + code.value());
                    }));
        } catch (RuntimeException e) {
            return NapasResult.pending("Không tra soát được Napas");
        }
    }

    @Override
    public Optional<String> nameInquiry(String bankCode, String accountNumber) {
        try {
            return callGuard.call(CIRCUIT_TARGET, () -> restClient.get()
                    .uri("/accounts/{bankCode}/{accountNumber}", bankCode, accountNumber)
                    .exchange((httpRequest, response) -> {
                        HttpStatusCode code = response.getStatusCode();
                        if (code.isSameCodeAs(HttpStatus.NOT_FOUND)) {
                            return Optional.<String>empty();
                        }
                        if (!code.is2xxSuccessful()) {
                            throw new NapasGatewayException("Napas trả lỗi khi tra tên: HTTP " + code.value());
                        }
                        NapasApi.AccountResponse body = response.bodyTo(NapasApi.AccountResponse.class);
                        return Optional.ofNullable(body == null ? null : body.accountName())
                                .filter(StringUtils::hasText);
                    }));
        } catch (NapasGatewayException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new NapasGatewayException("Không kết nối được Napas để tra tên người nhận");
        }
    }

    @Override
    public void notifyInboundResult(InboundTransfer inbound) {
        NapasApi.InboundResultRequest request = new NapasApi.InboundResultRequest(inbound.getStatus().name(),
                inbound.getTransactionRef(), inbound.getReason());
        HttpStatusCode code;
        try {
            code = callGuard.call(CIRCUIT_TARGET, () -> restClient.post()
                    .uri("/inbound/{napasRef}/result", inbound.getNapasRef())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .exchange((httpRequest, response) -> response.getStatusCode()));
        } catch (RuntimeException e) {
            throw new NapasGatewayException("Không kết nối được Napas để báo kết quả tiền đến");
        }
        if (!code.is2xxSuccessful()) {
            throw new NapasGatewayException("Napas không nhận kết quả tiền đến: HTTP " + code.value());
        }
    }

    private static boolean isUncertain(HttpStatusCode code) {
        return code.is5xxServerError() || code.isSameCodeAs(HttpStatus.REQUEST_TIMEOUT)
                || code.isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS);
    }

    static NapasResult toResult(NapasApi.TransferResponse body) {
        if (body == null || body.status() == null) {
            return NapasResult.pending("Napas trả phản hồi rỗng");
        }
        return switch (body.status()) {
            case "ACCEPTED" -> new NapasResult(NapasResultStatusEnum.ACCEPTED, body.napasRef(), null);
            case "REJECTED" -> new NapasResult(NapasResultStatusEnum.REJECTED, body.napasRef(), body.reason());
            default -> new NapasResult(NapasResultStatusEnum.PENDING, body.napasRef(), body.reason());
        };
    }
}
