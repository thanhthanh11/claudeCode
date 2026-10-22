package com.bank.interbank.dto;

import java.math.BigDecimal;

/**
 * Hợp đồng HTTP với Napas (qua {@code HttpNapasGatewayService}):
 * <ul>
 *   <li>{@code POST /transfers} {@link TransferRequest} → {@link TransferResponse}</li>
 *   <li>{@code GET /transfers/{transactionRef}} → {@link TransferResponse}; 404 = Napas chưa nhận lệnh</li>
 *   <li>{@code GET /accounts/{bankCode}/{accountNumber}} → {@link AccountResponse}; 404 = không có tài khoản</li>
 *   <li>{@code POST /inbound/{napasRef}/result} {@link InboundResultRequest} → 2xx</li>
 * </ul>
 */
public final class NapasApi {

    private NapasApi() {
    }

    public record TransferRequest(String transactionRef, String bankCode, String accountNumber, String accountName,
                                  BigDecimal amount, String currency, String description) {
    }

    /** {@code status}: ACCEPTED | REJECTED | PENDING. */
    public record TransferResponse(String status, String napasRef, String reason) {
    }

    public record AccountResponse(String accountName) {
    }

    /** {@code status}: SETTLED | RETURNED. */
    public record InboundResultRequest(String status, String transactionRef, String reason) {
    }
}
