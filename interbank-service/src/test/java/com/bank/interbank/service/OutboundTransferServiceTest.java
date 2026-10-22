package com.bank.interbank.service;

import com.bank.common.audit.AuditPublisher;
import com.bank.common.event.InterbankTransferPayload;
import com.bank.interbank.dto.NapasResult;
import com.bank.interbank.entity.OutboundTransfer;
import com.bank.interbank.enums.NapasResultStatusEnum;
import com.bank.interbank.enums.OutboundStatusEnum;
import com.bank.interbank.repository.OutboundTransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboundTransferServiceTest {

    private static final String REF = "TX2026102200000001";

    @Mock
    private OutboundTransferRepository repository;
    @Mock
    private NapasGatewayService gateway;
    @Mock
    private AuditPublisher auditPublisher;

    private OutboundTransferService service;

    @BeforeEach
    void setUp() {
        service = new OutboundTransferService(repository, gateway, auditPublisher, 20, 3, Duration.ofSeconds(30));
    }

    @Test
    void shouldStoreNewTransferRequest() {
        when(repository.findByTransactionRefForUpdate(REF)).thenReturn(Optional.empty());

        service.accept(payload());

        ArgumentCaptor<OutboundTransfer> saved = ArgumentCaptor.forClass(OutboundTransfer.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(OutboundStatusEnum.NEW);
        assertThat(saved.getValue().getAmount()).isEqualByComparingTo("500000");
    }

    @Test
    void duplicateRequestWhilePendingShouldBeIgnored() {
        OutboundTransfer pending = transfer(OutboundStatusEnum.UNKNOWN);
        when(repository.findByTransactionRefForUpdate(REF)).thenReturn(Optional.of(pending));

        service.accept(payload());

        verify(repository, never()).saveAndFlush(any());
        assertThat(pending.getResultPublished()).isTrue(); // chưa có kết quả → không publish lại
    }

    @Test
    void duplicateRequestAfterResultShouldRepublishResult() {
        OutboundTransfer done = transfer(OutboundStatusEnum.COMPLETED);
        done.setResultPublished(true);
        when(repository.findByTransactionRefForUpdate(REF)).thenReturn(Optional.of(done));

        service.accept(payload());

        assertThat(done.getResultPublished()).isFalse();
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void acceptedByNapasShouldCompleteAndQueueResult() {
        OutboundTransfer transfer = transfer(OutboundStatusEnum.NEW);
        when(gateway.transfer(transfer)).thenReturn(new NapasResult(NapasResultStatusEnum.ACCEPTED, "NP1", null));

        service.process(transfer);

        assertThat(transfer.getStatus()).isEqualTo(OutboundStatusEnum.COMPLETED);
        assertThat(transfer.getNapasRef()).isEqualTo("NP1");
        assertThat(transfer.getResultPublished()).isFalse();
    }

    @Test
    void noResponseShouldBecomeUnknownAndBeInquiredLaterNotRejected() {
        OutboundTransfer transfer = transfer(OutboundStatusEnum.NEW);
        when(gateway.transfer(transfer)).thenReturn(NapasResult.pending("timeout"));

        service.process(transfer);

        assertThat(transfer.getStatus()).isEqualTo(OutboundStatusEnum.UNKNOWN);
        assertThat(transfer.getNextAttemptAt()).isAfter(LocalDateTime.now().plusSeconds(20));
        assertThat(transfer.getCompletedAt()).isNull();
    }

    @Test
    void unknownTransferShouldUseInquiryNotResend() {
        OutboundTransfer transfer = transfer(OutboundStatusEnum.UNKNOWN);
        when(gateway.inquiry(REF)).thenReturn(new NapasResult(NapasResultStatusEnum.REJECTED, "NP1", "Sai tài khoản"));

        service.process(transfer);

        verify(gateway, never()).transfer(any());
        assertThat(transfer.getStatus()).isEqualTo(OutboundStatusEnum.REJECTED);
        assertThat(transfer.getReason()).isEqualTo("Sai tài khoản");
    }

    @Test
    void inquiryNotFoundShouldResendTransfer() {
        OutboundTransfer transfer = transfer(OutboundStatusEnum.UNKNOWN);
        when(gateway.inquiry(REF)).thenReturn(new NapasResult(NapasResultStatusEnum.NOT_FOUND, null, null));

        service.process(transfer);

        assertThat(transfer.getStatus()).isEqualTo(OutboundStatusEnum.NEW);
    }

    @Test
    void dispatchShouldProcessLockedBatch() {
        OutboundTransfer transfer = transfer(OutboundStatusEnum.NEW);
        when(repository.lockDueBatch(3, 20)).thenReturn(List.of(transfer));
        when(gateway.transfer(transfer)).thenReturn(new NapasResult(NapasResultStatusEnum.ACCEPTED, "NP1", null));

        service.dispatchDue();

        assertThat(transfer.getStatus()).isEqualTo(OutboundStatusEnum.COMPLETED);
        assertThat(transfer.getAttempts()).isEqualTo(1);
    }

    @Test
    void backoffShouldGrowAndBeCapped() {
        assertThat(service.backoff(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(service.backoff(4)).isEqualTo(Duration.ofMinutes(2));
        assertThat(service.backoff(100)).isEqualTo(Duration.ofMinutes(10));
    }

    private static InterbankTransferPayload payload() {
        return new InterbankTransferPayload(REF, "100000000001", "970436", "0011001234567", "TRAN THI B",
                new BigDecimal("500000"), "VND", "Tra no");
    }

    private static OutboundTransfer transfer(OutboundStatusEnum status) {
        OutboundTransfer transfer = new OutboundTransfer();
        transfer.setTransactionRef(REF);
        transfer.setBankCode("970436");
        transfer.setBeneficiaryAccount("0011001234567");
        transfer.setAmount(new BigDecimal("500000"));
        transfer.setCurrency("VND");
        transfer.setStatus(status);
        transfer.setResultPublished(true);
        return transfer;
    }
}
