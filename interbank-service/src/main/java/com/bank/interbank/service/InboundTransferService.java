package com.bank.interbank.service;

import com.bank.common.dto.AccountInfoResponse;
import com.bank.common.dto.AccountLookupRequest;
import com.bank.common.event.InterbankInboundResultPayload;
import com.bank.common.exception.BadRequestException;
import com.bank.common.exception.BusinessException;
import com.bank.common.exception.ConflictException;
import com.bank.common.redis.RedisActions;
import com.bank.common.redis.RedisRequestReplyClient;
import com.bank.common.util.MaskingUtils;
import com.bank.interbank.dto.InboundTransferRequest;
import com.bank.interbank.dto.InboundTransferResponse;
import com.bank.interbank.entity.InboundTransfer;
import com.bank.interbank.enums.CallbackStatusEnum;
import com.bank.interbank.enums.InboundStatusEnum;
import com.bank.interbank.repository.InboundTransferRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Tiền từ ngân hàng khác chuyển đến qua Napas.
 * <ol>
 *   <li>Webhook: xác thực chữ ký → kiểm tra dữ liệu → chống trùng theo {@code napasRef} (gửi lại cùng nội dung trả
 *       trạng thái hiện có, khác nội dung → 409).</li>
 *   <li>Kiểm tra sớm tài khoản nhận (không tồn tại / nội bộ / không hoạt động / sai loại tiền) → RETURNED ngay, không
 *       tạo giao dịch. Còn lại lưu RECEIVED; job publish báo transaction-service tạo giao dịch INTERBANK_IN.</li>
 *   <li>transaction-service báo SETTLED / RETURNED → {@link #applyResult} → job callback báo lại Napas.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InboundTransferService {

    private static final String SUPPORTED_CURRENCY = "VND";
    private static final int MAX_REASON_LENGTH = 500;

    private final InboundTransferRepository inboundRepository;
    private final NapasSignatureVerifier signatureVerifier;
    private final BankDirectoryService bankDirectoryService;
    private final RedisRequestReplyClient redisClient;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public InboundTransferResponse receive(String timestamp, String signature, String rawBody) {
        signatureVerifier.verify(timestamp, signature, rawBody);
        InboundTransferRequest request = parse(rawBody);

        Optional<InboundTransfer> existing = inboundRepository.findByNapasRef(request.napasRef());
        if (existing.isPresent()) {
            return replay(existing.get(), request);
        }
        bankDirectoryService.requireActive(request.sourceBankCode());
        InboundTransfer inbound = newInbound(request);
        String rejectReason = precheck(request);
        if (rejectReason != null) {
            inbound.setStatus(InboundStatusEnum.RETURNED);
            inbound.setReason(rejectReason);
            inbound.setEventPublished(true); // không cần tạo giao dịch
            inbound.setCallbackStatus(CallbackStatusEnum.PENDING);
            inbound.setNextCallbackAt(LocalDateTime.now());
        }
        try {
            inboundRepository.saveAndFlush(inbound);
        } catch (DataIntegrityViolationException e) {
            // Napas gửi lại song song cùng napasRef: bản kia đã ghi
            return replay(inboundRepository.findByNapasRef(request.napasRef()).orElseThrow(() -> e), request);
        }
        log.info("Nhận tiền đến Napas {} từ {} vào tài khoản {}: {}", request.napasRef(), request.sourceBankCode(),
                MaskingUtils.maskAccountNumber(request.beneficiaryAccount()), inbound.getStatus());
        return toResponse(inbound);
    }

    /** Kết quả cộng tiền từ transaction-service; áp dụng một lần, kết quả trùng bị bỏ qua. */
    @Transactional
    public void applyResult(InterbankInboundResultPayload payload, InboundStatusEnum result) {
        Optional<InboundTransfer> found = inboundRepository.findByNapasRefForUpdate(payload.napasRef());
        if (found.isEmpty()) {
            log.error("Nhận kết quả {} cho tiền đến không tồn tại: Napas {}", result, payload.napasRef());
            return;
        }
        InboundTransfer inbound = found.get();
        if (inbound.getStatus() != InboundStatusEnum.RECEIVED) {
            log.debug("Bỏ qua kết quả {} trùng cho tiền đến {} (đang {})", result, payload.napasRef(),
                    inbound.getStatus());
            return;
        }
        inbound.setStatus(result);
        inbound.setTransactionRef(payload.transactionRef());
        inbound.setReason(truncate(payload.reason()));
        inbound.setCallbackStatus(CallbackStatusEnum.PENDING);
        inbound.setNextCallbackAt(LocalDateTime.now());
        log.info("Tiền đến {} → {} (giao dịch {})", payload.napasRef(), result, payload.transactionRef());
    }

    /** Lý do trả lại ngay, hoặc null nếu chuyển cho transaction-service cộng tiền. */
    private String precheck(InboundTransferRequest request) {
        if (!SUPPORTED_CURRENCY.equals(request.currency())) {
            return "Chỉ nhận tiền " + SUPPORTED_CURRENCY;
        }
        AccountInfoResponse account;
        try {
            account = redisClient.sendAsService(RedisActions.ACCOUNT_SERVICE, RedisActions.ACCOUNT_GET_BY_NUMBER,
                    new AccountLookupRequest(request.beneficiaryAccount()), AccountInfoResponse.class);
        } catch (BusinessException e) {
            if (e.getStatus() == HttpStatus.NOT_FOUND) {
                return "Tài khoản nhận không tồn tại";
            }
            throw e; // lỗi kỹ thuật → Napas gửi lại webhook
        }
        if ("INTERNAL".equals(account.accountType())) {
            return "Tài khoản nhận không tồn tại";
        }
        if (!account.isActive()) {
            return "Tài khoản nhận không ở trạng thái hoạt động";
        }
        if (!SUPPORTED_CURRENCY.equals(account.currency())) {
            return "Tài khoản nhận không nhận được tiền " + SUPPORTED_CURRENCY;
        }
        return null;
    }

    private InboundTransferResponse replay(InboundTransfer existing, InboundTransferRequest request) {
        boolean same = existing.getAmount().compareTo(request.amount()) == 0
                && Objects.equals(existing.getBeneficiaryAccount(), request.beneficiaryAccount())
                && Objects.equals(existing.getSourceBankCode(), request.sourceBankCode());
        if (!same) {
            throw new ConflictException("NAPAS_REF_CONFLICT", "Mã Napas đã dùng cho giao dịch khác");
        }
        return toResponse(existing);
    }

    private InboundTransferRequest parse(String rawBody) {
        InboundTransferRequest request;
        try {
            request = objectMapper.readValue(rawBody, InboundTransferRequest.class);
        } catch (JsonProcessingException e) {
            throw new BadRequestException("INVALID_PAYLOAD", "Body webhook không hợp lệ");
        }
        Set<ConstraintViolation<InboundTransferRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            ConstraintViolation<InboundTransferRequest> first = violations.iterator().next();
            throw new BadRequestException("INVALID_PAYLOAD", first.getPropertyPath() + ": " + first.getMessage());
        }
        return request;
    }

    private static InboundTransfer newInbound(InboundTransferRequest request) {
        InboundTransfer inbound = new InboundTransfer();
        inbound.setNapasRef(request.napasRef());
        inbound.setSourceBankCode(request.sourceBankCode());
        inbound.setSourceAccount(request.sourceAccount());
        inbound.setSourceName(request.sourceName());
        inbound.setBeneficiaryAccount(request.beneficiaryAccount());
        inbound.setAmount(request.amount());
        inbound.setCurrency(request.currency());
        inbound.setDescription(request.description());
        return inbound;
    }

    private static String truncate(String reason) {
        return reason == null || reason.length() <= MAX_REASON_LENGTH ? reason : reason.substring(0, MAX_REASON_LENGTH);
    }

    private static InboundTransferResponse toResponse(InboundTransfer inbound) {
        return new InboundTransferResponse(inbound.getNapasRef(), inbound.getStatus(), inbound.getReason());
    }
}
