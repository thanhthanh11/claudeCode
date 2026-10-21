package com.bank.transaction.service;

import com.bank.common.dto.AccountInfoResponse;
import com.bank.common.exception.BadRequestException;
import com.bank.common.exception.ForbiddenException;
import com.bank.common.exception.ResourceNotFoundException;
import com.bank.common.security.AuthUser;
import com.bank.common.security.SecurityUtils;
import com.bank.transaction.dto.CreateScheduledTransferRequest;
import com.bank.transaction.dto.ScheduledTransferResponse;
import com.bank.transaction.entity.ScheduledTransfer;
import com.bank.transaction.enums.ScheduledTransferStatusEnum;
import com.bank.transaction.repository.ScheduledTransferRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Lệnh chuyển tiền định kỳ của khách hàng. Việc thực thi do {@link ScheduledTransferRunnerService} đảm nhận.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduledTransferService {

    private static final int MAX_LAST_ERROR_LENGTH = 500;

    private final ScheduledTransferRepository scheduledTransferRepository;
    private final CustomerLookupService customerLookupService;
    private final AccountLookupService accountLookupService;

    @Transactional
    public ScheduledTransferResponse create(CreateScheduledTransferRequest request) {
        AuthUser currentUser = SecurityUtils.requireCurrentUser();
        Long customerId = customerLookupService.getCustomerIdByUserId(currentUser.userId());
        if (request.fromAccountNumber().equals(request.toAccountNumber())) {
            throw new BadRequestException("SAME_ACCOUNT", "Tài khoản nguồn và tài khoản nhận phải khác nhau");
        }
        if (request.endDate() != null && request.endDate().isBefore(request.startDate())) {
            throw new BadRequestException("INVALID_PERIOD", "Ngày kết thúc phải sau ngày bắt đầu");
        }
        AccountInfoResponse from = accountLookupService.getAccount(request.fromAccountNumber());
        if (!Objects.equals(from.customerId(), customerId)) {
            throw new ForbiddenException("Không có quyền thao tác trên tài khoản này");
        }
        AccountInfoResponse to = accountLookupService.getAccount(request.toAccountNumber());
        if (!from.isActive() || !to.isActive() || "INTERNAL".equals(to.accountType())) {
            throw new BadRequestException("ACCOUNT_NOT_ACTIVE", "Tài khoản nguồn hoặc nhận không hợp lệ");
        }
        if (!from.currency().equals(to.currency())) {
            throw new BadRequestException("CURRENCY_MISMATCH", "Hai tài khoản khác loại tiền");
        }

        ScheduledTransfer schedule = new ScheduledTransfer();
        schedule.setCustomerId(customerId);
        schedule.setCreatedBy(currentUser.userId());
        schedule.setFromAccountNumber(from.accountNumber());
        schedule.setToAccountNumber(to.accountNumber());
        schedule.setAmount(request.amount());
        schedule.setDescription(request.description());
        schedule.setFrequency(request.frequency());
        schedule.setStartDate(request.startDate());
        schedule.setEndDate(request.endDate());
        schedule.setNextRunDate(request.startDate());
        scheduledTransferRepository.save(schedule);
        log.info("Tạo lệnh chuyển tiền định kỳ #{} ({}) cho khách hàng #{}", schedule.getId(), request.frequency(),
                customerId);
        return toResponse(schedule);
    }

    @Transactional(readOnly = true)
    public List<ScheduledTransferResponse> listMine() {
        Long customerId = customerLookupService.getCustomerIdByUserId(SecurityUtils.requireCurrentUser().userId());
        return scheduledTransferRepository.findByCustomerIdOrderByIdDesc(customerId).stream()
                .map(ScheduledTransferService::toResponse)
                .toList();
    }

    @Transactional
    public ScheduledTransferResponse cancel(Long id) {
        Long customerId = customerLookupService.getCustomerIdByUserId(SecurityUtils.requireCurrentUser().userId());
        ScheduledTransfer schedule = scheduledTransferRepository.findByIdAndCustomerId(id, customerId)
                .orElseThrow(() -> new ResourceNotFoundException("SCHEDULE_NOT_FOUND", "Không tìm thấy lệnh định kỳ #" + id));
        if (schedule.getStatus() != ScheduledTransferStatusEnum.ACTIVE) {
            throw new BadRequestException("SCHEDULE_NOT_ACTIVE", "Lệnh định kỳ không còn hoạt động");
        }
        schedule.setStatus(ScheduledTransferStatusEnum.CANCELLED);
        return toResponse(schedule);
    }

    /**
     * Ghi kết quả một kỳ và tính kỳ tiếp theo. Khóa dòng lệnh và chỉ cập nhật nếu kỳ chưa được instance khác ghi.
     * Các kỳ bị lỡ (service dừng nhiều ngày) được bỏ qua — chỉ thực hiện một lần cho kỳ cũ nhất.
     */
    @Transactional
    public void recordRun(Long id, LocalDate runDate, String transactionRef, String error) {
        ScheduledTransfer schedule = scheduledTransferRepository.findByIdForUpdate(id).orElse(null);
        if (schedule == null || !runDate.equals(schedule.getNextRunDate())) {
            return;
        }
        schedule.setLastRunDate(runDate);
        schedule.setRunCount(schedule.getRunCount() + 1);
        schedule.setLastTransactionRef(transactionRef);
        schedule.setLastError(error == null || error.length() <= MAX_LAST_ERROR_LENGTH
                ? error : error.substring(0, MAX_LAST_ERROR_LENGTH));
        LocalDate today = LocalDate.now();
        LocalDate next = schedule.getFrequency().next(runDate);
        while (next.isBefore(today)) {
            next = schedule.getFrequency().next(next);
        }
        schedule.setNextRunDate(next);
        if (schedule.getEndDate() != null && next.isAfter(schedule.getEndDate())) {
            schedule.setStatus(ScheduledTransferStatusEnum.COMPLETED);
        }
    }

    static ScheduledTransferResponse toResponse(ScheduledTransfer schedule) {
        return new ScheduledTransferResponse(schedule.getId(), schedule.getFromAccountNumber(),
                schedule.getToAccountNumber(), schedule.getAmount(), schedule.getDescription(),
                schedule.getFrequency(), schedule.getStartDate(), schedule.getEndDate(), schedule.getNextRunDate(),
                schedule.getStatus(), schedule.getRunCount(), schedule.getLastRunDate(),
                schedule.getLastTransactionRef(), schedule.getLastError(), schedule.getCreatedAt());
    }
}
