package com.bank.transaction.service;

import com.bank.common.exception.BusinessException;
import com.bank.transaction.dto.TransactionResponse;
import com.bank.transaction.dto.TransferRequest;
import com.bank.transaction.entity.ScheduledTransfer;
import com.bank.transaction.enums.ScheduledTransferStatusEnum;
import com.bank.transaction.repository.ScheduledTransferRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Thực thi lệnh chuyển tiền định kỳ đến hạn. Thứ tự: tạo giao dịch (idempotent theo key
 * {@code sched-<id>-<yyyyMMdd>}) → ghi kết quả và tính kỳ sau. Nếu dừng giữa hai bước, lần chạy sau tạo lại cùng key
 * nên chỉ nhận lại giao dịch cũ, không chuyển tiền hai lần. Lỗi nghiệp vụ (không đủ số dư, vượt hạn mức...)
 * được ghi vào LAST_ERROR và lệnh vẫn chuyển sang kỳ sau.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduledTransferRunnerService {

    private static final DateTimeFormatter KEY_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final ScheduledTransferRepository scheduledTransferRepository;
    private final ScheduledTransferService scheduledTransferService;
    private final TransactionService transactionService;

    @Scheduled(cron = "${bank.scheduled-transfer.cron:0 */5 * * * *}")
    public void runDueTransfers() {
        LocalDate today = LocalDate.now();
        for (Long id : scheduledTransferRepository.findDueIds(ScheduledTransferStatusEnum.ACTIVE, today)) {
            try {
                runOne(id, today);
            } catch (RuntimeException e) {
                log.error("Lỗi khi chạy lệnh định kỳ #{}: {}", id, e.getMessage());
            }
        }
    }

    void runOne(Long id, LocalDate today) {
        ScheduledTransfer schedule = scheduledTransferRepository.findById(id).orElse(null);
        if (schedule == null || schedule.getStatus() != ScheduledTransferStatusEnum.ACTIVE
                || schedule.getNextRunDate().isAfter(today)) {
            return;
        }
        LocalDate runDate = schedule.getNextRunDate();
        String scopedKey = TransactionService.scopeKey(schedule.getCreatedBy(),
                "sched-" + schedule.getId() + "-" + KEY_DATE.format(runDate));
        TransferRequest request = new TransferRequest(schedule.getFromAccountNumber(), schedule.getToAccountNumber(),
                schedule.getAmount(), schedule.getDescription() == null
                ? "Chuyển tiền định kỳ #" + schedule.getId() : schedule.getDescription());
        String transactionRef = null;
        String error = null;
        try {
            TransactionResponse response = transactionService.transferOnBehalf(schedule.getCustomerId(),
                    schedule.getCreatedBy(), request, scopedKey);
            transactionRef = response.transactionRef();
            log.info("Lệnh định kỳ #{} kỳ {} → giao dịch {}", id, runDate, transactionRef);
        } catch (BusinessException e) {
            error = e.getCode() + ": " + e.getMessage();
            log.warn("Lệnh định kỳ #{} kỳ {} không thực hiện được: {}", id, runDate, error);
        }
        scheduledTransferService.recordRun(id, runDate, transactionRef, error);
    }
}
