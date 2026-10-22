package com.bank.interbank.service;

import com.bank.common.util.MaskingUtils;
import com.bank.interbank.dto.NapasResult;
import com.bank.interbank.entity.InboundTransfer;
import com.bank.interbank.entity.OutboundTransfer;
import com.bank.interbank.enums.NapasResultStatusEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Napas giả lập cho dev / test ({@code bank.interbank.gateway.provider=mock}, mặc định). Kết quả theo đuôi số
 * tài khoản người nhận:
 * <ul>
 *   <li>{@code ...000}: Napas từ chối (→ hoàn tiền)</li>
 *   <li>{@code ...999}: không có phản hồi lúc gửi (PENDING), tra soát sau đó trả thành công</li>
 *   <li>{@code ...404}: tra tên không thấy tài khoản</li>
 *   <li>còn lại: thành công</li>
 * </ul>
 * Trạng thái lưu trong bộ nhớ: restart thì tra soát trả NOT_FOUND và lệnh được gửi lại (Napas idempotent).
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "bank.interbank.gateway", name = "provider", havingValue = "mock", matchIfMissing = true)
public class MockNapasGatewayService implements NapasGatewayService {

    static final String REJECT_SUFFIX = "000";
    static final String TIMEOUT_SUFFIX = "999";
    static final String NOT_FOUND_SUFFIX = "404";

    private final Map<String, NapasResult> transfers = new ConcurrentHashMap<>();

    @Override
    public NapasResult transfer(OutboundTransfer transfer) {
        String account = transfer.getBeneficiaryAccount();
        NapasResult result = account.endsWith(REJECT_SUFFIX)
                ? new NapasResult(NapasResultStatusEnum.REJECTED, napasRef(transfer.getTransactionRef()),
                "Tài khoản người nhận không hợp lệ")
                : new NapasResult(NapasResultStatusEnum.ACCEPTED, napasRef(transfer.getTransactionRef()), null);
        NapasResult stored = transfers.putIfAbsent(transfer.getTransactionRef(), result);
        log.info("[MOCK NAPAS] chuyển {} tới {}/{}", transfer.getTransactionRef(), transfer.getBankCode(),
                MaskingUtils.maskAccountNumber(account));
        if (stored == null && account.endsWith(TIMEOUT_SUFFIX)) {
            return NapasResult.pending("Mock: không nhận được phản hồi");
        }
        return stored == null ? result : stored;
    }

    @Override
    public NapasResult inquiry(String transactionRef) {
        NapasResult result = transfers.get(transactionRef);
        return result == null ? new NapasResult(NapasResultStatusEnum.NOT_FOUND, null, null) : result;
    }

    @Override
    public Optional<String> nameInquiry(String bankCode, String accountNumber) {
        if (accountNumber.endsWith(NOT_FOUND_SUFFIX)) {
            return Optional.empty();
        }
        return Optional.of("KHACH HANG " + accountNumber.substring(Math.max(0, accountNumber.length() - 4)));
    }

    @Override
    public void notifyInboundResult(InboundTransfer inbound) {
        log.info("[MOCK NAPAS] kết quả tiền đến {}: {}", inbound.getNapasRef(), inbound.getStatus());
    }

    private static String napasRef(String transactionRef) {
        return "NP" + transactionRef;
    }
}
