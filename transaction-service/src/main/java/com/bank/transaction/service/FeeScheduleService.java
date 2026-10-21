package com.bank.transaction.service;

import com.bank.common.audit.AuditActions;
import com.bank.common.audit.AuditPublisher;
import com.bank.common.exception.BadRequestException;
import com.bank.common.exception.ResourceNotFoundException;
import com.bank.common.util.CacheEvictionUtils;
import com.bank.transaction.dto.FeeScheduleResponse;
import com.bank.transaction.dto.UpdateFeeScheduleRequest;
import com.bank.transaction.entity.FeeSchedule;
import com.bank.transaction.enums.TransactionTypeEnum;
import com.bank.transaction.repository.FeeScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Biểu phí giao dịch. Biểu phí ít thay đổi nên cache trong Hazelcast; cập nhật thì xóa cache sau khi commit.
 */
@Service
@RequiredArgsConstructor
public class FeeScheduleService {

    public static final String FEE_SCHEDULE_CACHE = "fee-schedules";

    private final FeeScheduleRepository feeScheduleRepository;
    private final CacheManager cacheManager;
    private final AuditPublisher auditPublisher;

    /** Biểu phí áp dụng; {@link FeeScheduleResponse#NONE} nếu không thu phí. */
    @Cacheable(cacheNames = FEE_SCHEDULE_CACHE, key = "#type.name() + ':' + #currency")
    @Transactional(readOnly = true)
    public FeeScheduleResponse findSchedule(TransactionTypeEnum type, String currency) {
        return feeScheduleRepository.findByTransactionTypeAndCurrency(type, currency)
                .map(FeeScheduleService::toResponse)
                .orElse(FeeScheduleResponse.NONE);
    }

    @Transactional(readOnly = true)
    public List<FeeScheduleResponse> listAll() {
        return feeScheduleRepository.findAllByOrderByTransactionTypeAscCurrencyAsc().stream()
                .map(FeeScheduleService::toResponse)
                .toList();
    }

    @Transactional
    public FeeScheduleResponse update(Long id, UpdateFeeScheduleRequest request) {
        if (request.maxFee() != null && request.maxFee().compareTo(request.minFee()) < 0) {
            throw new BadRequestException("INVALID_FEE", "Phí tối đa phải lớn hơn hoặc bằng phí tối thiểu");
        }
        FeeSchedule schedule = feeScheduleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("FEE_SCHEDULE_NOT_FOUND", "Không tìm thấy biểu phí #" + id));
        schedule.setFixedFee(request.fixedFee());
        schedule.setPercentRate(request.percentRate());
        schedule.setMinFee(request.minFee());
        schedule.setMaxFee(request.maxFee());
        schedule.setActive(request.active());
        CacheEvictionUtils.clearAfterCommit(cacheManager, FEE_SCHEDULE_CACHE);
        auditPublisher.publish(AuditActions.FEE_SCHEDULE_UPDATED, "FEE_SCHEDULE", id,
                schedule.getTransactionType() + "/" + schedule.getCurrency() + ": fixed=" + request.fixedFee().toPlainString()
                        + ", rate=" + request.percentRate().toPlainString() + ", min=" + request.minFee().toPlainString()
                        + ", max=" + (request.maxFee() == null ? "-" : request.maxFee().toPlainString())
                        + ", active=" + request.active());
        return toResponse(schedule);
    }

    private static FeeScheduleResponse toResponse(FeeSchedule schedule) {
        return new FeeScheduleResponse(schedule.getId(), schedule.getTransactionType(), schedule.getCurrency(),
                schedule.getFixedFee(), schedule.getPercentRate(), schedule.getMinFee(), schedule.getMaxFee(),
                schedule.getActive());
    }
}
