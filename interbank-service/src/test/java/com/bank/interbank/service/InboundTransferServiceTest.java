package com.bank.interbank.service;

import com.bank.common.dto.AccountInfoResponse;
import com.bank.common.dto.AccountLookupRequest;
import com.bank.common.event.InterbankInboundResultPayload;
import com.bank.common.exception.BusinessException;
import com.bank.common.exception.ConflictException;
import com.bank.common.exception.ResourceNotFoundException;
import com.bank.common.redis.RedisActions;
import com.bank.common.redis.RedisRequestReplyClient;
import com.bank.interbank.dto.BankResponse;
import com.bank.interbank.dto.InboundTransferResponse;
import com.bank.interbank.entity.InboundTransfer;
import com.bank.interbank.enums.CallbackStatusEnum;
import com.bank.interbank.enums.InboundStatusEnum;
import com.bank.interbank.repository.InboundTransferRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InboundTransferServiceTest {

    private static final String ACCOUNT = "100000000002";
    private static final String BODY = """
            {"napasRef":"NP000001","sourceBankCode":"970436","sourceAccount":"0011001234567",
             "sourceName":"TRAN THI B","beneficiaryAccount":"100000000002","amount":500000,
             "currency":"VND","description":"Chuyen tien"}""";

    @Mock
    private InboundTransferRepository repository;
    @Mock
    private BankDirectoryService bankDirectoryService;
    @Mock
    private RedisRequestReplyClient redisClient;

    private final NapasSignatureVerifier verifier = new NapasSignatureVerifier(
            Base64.getEncoder().encodeToString(new byte[32]), Duration.ofMinutes(5));
    private InboundTransferService service;

    @BeforeEach
    void setUp() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        service = new InboundTransferService(repository, verifier, bankDirectoryService, redisClient,
                new ObjectMapper(), validator);
        lenient().when(bankDirectoryService.requireActive("970436"))
                .thenReturn(new BankResponse("970436", "Vietcombank", "Vietcombank"));
        lenient().when(repository.findByNapasRef("NP000001")).thenReturn(Optional.empty());
    }

    @Test
    void validInboundShouldBeStoredAsReceivedForCrediting() {
        account("PAYMENT", "ACTIVE");

        InboundTransferResponse response = receive(BODY);

        assertThat(response.status()).isEqualTo(InboundStatusEnum.RECEIVED);
        InboundTransfer saved = saved();
        assertThat(saved.getEventPublished()).isFalse();
        assertThat(saved.getCallbackStatus()).isNull();
        assertThat(saved.getAmount()).isEqualByComparingTo("500000");
    }

    @Test
    void inboundToFrozenAccountShouldBeReturnedImmediately() {
        account("PAYMENT", "FROZEN");

        InboundTransferResponse response = receive(BODY);

        assertThat(response.status()).isEqualTo(InboundStatusEnum.RETURNED);
        InboundTransfer saved = saved();
        assertThat(saved.getEventPublished()).isTrue();
        assertThat(saved.getCallbackStatus()).isEqualTo(CallbackStatusEnum.PENDING);
    }

    @Test
    void inboundToMissingOrInternalAccountShouldBeReturned() {
        when(redisClient.sendAsService(any(), any(), any(), eq(AccountInfoResponse.class)))
                .thenThrow(new ResourceNotFoundException("ACCOUNT_NOT_FOUND", "x"));

        assertThat(receive(BODY).status()).isEqualTo(InboundStatusEnum.RETURNED);
    }

    @Test
    void duplicateNapasRefShouldReturnExistingWithoutSaving() {
        InboundTransfer existing = new InboundTransfer();
        existing.setNapasRef("NP000001");
        existing.setSourceBankCode("970436");
        existing.setBeneficiaryAccount(ACCOUNT);
        existing.setAmount(new BigDecimal("500000"));
        existing.setStatus(InboundStatusEnum.SETTLED);
        when(repository.findByNapasRef("NP000001")).thenReturn(Optional.of(existing));

        assertThat(receive(BODY).status()).isEqualTo(InboundStatusEnum.SETTLED);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void sameNapasRefWithDifferentAmountShouldConflict() {
        InboundTransfer existing = new InboundTransfer();
        existing.setSourceBankCode("970436");
        existing.setBeneficiaryAccount(ACCOUNT);
        existing.setAmount(new BigDecimal("100000"));
        when(repository.findByNapasRef("NP000001")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> receive(BODY)).isInstanceOf(ConflictException.class);
    }

    @Test
    void invalidSignatureShouldBeRejectedBeforeParsing() {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());

        assertThatThrownBy(() -> service.receive(timestamp, "00ff", BODY))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("INVALID_SIGNATURE");
        verify(repository, never()).findByNapasRef(any());
    }

    @Test
    void invalidPayloadShouldBeRejected() {
        String body = BODY.replace("\"amount\":500000", "\"amount\":-1");

        assertThatThrownBy(() -> receive(body))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("INVALID_PAYLOAD");
    }

    @Test
    void settledResultShouldScheduleCallbackOnce() {
        InboundTransfer inbound = new InboundTransfer();
        inbound.setNapasRef("NP000001");
        when(repository.findByNapasRefForUpdate("NP000001")).thenReturn(Optional.of(inbound));

        service.applyResult(new InterbankInboundResultPayload("NP000001", "TX1", null), InboundStatusEnum.SETTLED);
        service.applyResult(new InterbankInboundResultPayload("NP000001", "TX1", "dup"), InboundStatusEnum.RETURNED);

        assertThat(inbound.getStatus()).isEqualTo(InboundStatusEnum.SETTLED);
        assertThat(inbound.getTransactionRef()).isEqualTo("TX1");
        assertThat(inbound.getCallbackStatus()).isEqualTo(CallbackStatusEnum.PENDING);
        assertThat(inbound.getReason()).isNull();
    }

    private InboundTransferResponse receive(String body) {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        return service.receive(timestamp, verifier.signHex(timestamp, body), body);
    }

    private void account(String type, String status) {
        when(redisClient.sendAsService(eq(RedisActions.ACCOUNT_SERVICE), eq(RedisActions.ACCOUNT_GET_BY_NUMBER),
                eq(new AccountLookupRequest(ACCOUNT)), eq(AccountInfoResponse.class)))
                .thenReturn(new AccountInfoResponse(2L, ACCOUNT, 2L, type, "VND", BigDecimal.ZERO, BigDecimal.ZERO,
                        status));
    }

    private InboundTransfer saved() {
        ArgumentCaptor<InboundTransfer> captor = ArgumentCaptor.forClass(InboundTransfer.class);
        verify(repository).saveAndFlush(captor.capture());
        return captor.getValue();
    }
}
