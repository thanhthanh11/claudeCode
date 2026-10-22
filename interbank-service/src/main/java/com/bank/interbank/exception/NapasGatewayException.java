package com.bank.interbank.exception;

import com.bank.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

/**
 * Lỗi gọi Napas (tra tên / báo kết quả tiền đến). Không dùng cho gửi lệnh chuyển: lỗi ở đó trả về
 * {@code NapasResult} PENDING để tra soát, vì không biết Napas đã nhận lệnh hay chưa.
 */
public class NapasGatewayException extends BusinessException {

    public NapasGatewayException(String message) {
        super(HttpStatus.BAD_GATEWAY, "NAPAS_UNAVAILABLE", message);
    }
}
