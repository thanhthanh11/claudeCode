package com.bank.transaction.controller;

import com.bank.common.dto.ApiResponse;
import com.bank.common.dto.PageResponse;
import com.bank.transaction.dto.CashTransactionRequest;
import com.bank.transaction.dto.InterbankTransferRequest;
import com.bank.transaction.dto.PhoneTransferRequest;
import com.bank.transaction.dto.ConfirmTransactionRequest;
import com.bank.transaction.dto.TransactionResponse;
import com.bank.transaction.dto.TransferRequest;
import com.bank.transaction.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Các API tạo giao dịch trả về 202 Accepted với trạng thái PROCESSING (hoặc PENDING nếu cần xác nhận OTP:
 * gọi POST /{transactionRef}/confirm); kết quả cuối cùng
 * (COMPLETED / FAILED / COMPENSATED) tra cứu qua GET /api/transactions/{transactionRef}.
 */
@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final TransactionService transactionService;

    @PostMapping("/transfer")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('TRANSFER_CREATE')")
    public ApiResponse<TransactionResponse> transfer(@RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
                                                     @Valid @RequestBody TransferRequest request) {
        return ApiResponse.ok("Đã tiếp nhận lệnh chuyển tiền", transactionService.transfer(request, idempotencyKey));
    }

    /** Chuyển tiền tới số điện thoại đã đăng ký nhận tiền; xử lý như chuyển khoản nội bộ (phí, hạn mức, OTP). */
    @PostMapping("/transfer-by-phone")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('TRANSFER_CREATE')")
    public ApiResponse<TransactionResponse> transferByPhone(@RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
                                                            @Valid @RequestBody PhoneTransferRequest request) {
        return ApiResponse.ok("Đã tiếp nhận lệnh chuyển tiền", transactionService.transferByPhone(request, idempotencyKey));
    }

    /** Chuyển tiền ra ngân hàng khác qua Napas 247; kết quả cuối tra bằng GET /{transactionRef}. */
    @PostMapping("/interbank-transfer")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('TRANSFER_CREATE')")
    public ApiResponse<TransactionResponse> interbankTransfer(@RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
                                                              @Valid @RequestBody InterbankTransferRequest request) {
        return ApiResponse.ok("Đã tiếp nhận lệnh chuyển tiền liên ngân hàng",
                transactionService.interbankTransfer(request, idempotencyKey));
    }

    @PostMapping("/deposit")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('TRANSFER_CREATE') and (hasRole('TELLER') or hasRole('ADMIN'))")
    public ApiResponse<TransactionResponse> deposit(@RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
                                                    @Valid @RequestBody CashTransactionRequest request) {
        return ApiResponse.ok("Đã tiếp nhận lệnh nộp tiền", transactionService.deposit(request, idempotencyKey));
    }

    @PostMapping("/withdrawal")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('TRANSFER_CREATE') and (hasRole('TELLER') or hasRole('ADMIN'))")
    public ApiResponse<TransactionResponse> withdraw(@RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
                                                     @Valid @RequestBody CashTransactionRequest request) {
        return ApiResponse.ok("Đã tiếp nhận lệnh rút tiền", transactionService.withdraw(request, idempotencyKey));
    }

    /** Xác nhận giao dịch PENDING (chuyển khoản cần OTP) → 202 PROCESSING. */
    @PostMapping("/{transactionRef}/confirm")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('TRANSFER_CREATE')")
    public ApiResponse<TransactionResponse> confirm(@PathVariable String transactionRef,
                                                    @Valid @RequestBody ConfirmTransactionRequest request) {
        return ApiResponse.ok("Đã xác nhận giao dịch", transactionService.confirm(transactionRef, request.otp()));
    }

    @PostMapping("/{transactionRef}/otp")
    @PreAuthorize("hasAuthority('TRANSFER_CREATE')")
    public ApiResponse<TransactionResponse> resendOtp(@PathVariable String transactionRef) {
        return ApiResponse.ok("Đã gửi lại mã OTP", transactionService.resendOtp(transactionRef));
    }

    @GetMapping("/{transactionRef}")
    @PreAuthorize("hasAuthority('TRANSACTION_READ')")
    public ApiResponse<TransactionResponse> getByRef(@PathVariable String transactionRef) {
        return ApiResponse.ok(transactionService.getByRef(transactionRef));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('TRANSACTION_READ')")
    public ApiResponse<PageResponse<TransactionResponse>> history(
            @RequestParam String accountNumber,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(transactionService.history(accountNumber, pageable));
    }
}
