package com.bank.account.service;

import com.bank.account.dto.PhoneAliasResponse;
import com.bank.account.entity.Account;
import com.bank.account.entity.PhoneAlias;
import com.bank.account.enums.AccountStatusEnum;
import com.bank.account.enums.AccountTypeEnum;
import com.bank.account.repository.AccountRepository;
import com.bank.account.repository.PhoneAliasRepository;
import com.bank.common.crypto.BlindIndexer;
import com.bank.common.crypto.EncryptionKeyring;
import com.bank.common.dto.CustomerInfoResponse;
import com.bank.common.dto.CustomerLookupRequest;
import com.bank.common.dto.PhoneAliasLookupResponse;
import com.bank.common.exception.BadRequestException;
import com.bank.common.exception.BusinessException;
import com.bank.common.exception.ConflictException;
import com.bank.common.exception.ForbiddenException;
import com.bank.common.redis.RedisActions;
import com.bank.common.redis.RedisRequestReplyClient;
import com.bank.common.security.AuthUser;
import com.bank.common.security.SecurityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PhoneAliasServiceTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String ACCOUNT_NUMBER = "100000000001";
    private static final String PHONE = "0912345678";

    @Mock
    private PhoneAliasRepository aliasRepository;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private RedisRequestReplyClient redisClient;

    private PhoneAliasService service;
    private BlindIndexer indexer;
    private Account account;

    @BeforeEach
    void setUp() {
        EncryptionKeyring keyring = new EncryptionKeyring(KEY);
        indexer = keyring.blindIndexer();
        service = new PhoneAliasService(aliasRepository, accountRepository, redisClient, keyring);
        account = account(1L, 10L, AccountTypeEnum.PAYMENT, AccountStatusEnum.ACTIVE);
        lenient().when(accountRepository.findByAccountNumber(ACCOUNT_NUMBER)).thenReturn(Optional.of(account));
        lenient().when(accountRepository.findById(1L)).thenReturn(Optional.of(account));
        lenient().when(aliasRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        SecurityContextHolder.getContext().setAuthentication(SecurityUtils.toAuthentication(
                new AuthUser(100L, "customer", Set.of("CUSTOMER"), Set.of("TRANSFER_CREATE")), "token"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldRegisterAliasWithProfilePhone() {
        currentCustomer(customer(10L, PHONE, "ACTIVE", "VERIFIED"));
        when(aliasRepository.findByPhoneHashForUpdate(indexer.hashPhone(PHONE))).thenReturn(Optional.empty());
        when(aliasRepository.findByCustomerId(10L)).thenReturn(List.of());

        PhoneAliasResponse response = service.register(ACCOUNT_NUMBER);

        ArgumentCaptor<PhoneAlias> saved = ArgumentCaptor.forClass(PhoneAlias.class);
        verify(aliasRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getPhoneHash()).isEqualTo(indexer.hashPhone("+84912345678"));
        assertThat(saved.getValue().getAccountId()).isEqualTo(1L);
        assertThat(response.maskedPhone()).isEqualTo("*******678");
    }

    @Test
    void shouldRejectRegistrationWhenKycNotVerified() {
        currentCustomer(customer(10L, PHONE, "ACTIVE", "PENDING"));

        assertThatThrownBy(() -> service.register(ACCOUNT_NUMBER))
                .isInstanceOf(BadRequestException.class)
                .extracting("code").isEqualTo("KYC_NOT_VERIFIED");
    }

    @Test
    void shouldRejectRegistrationOnAccountOfAnotherCustomer() {
        currentCustomer(customer(11L, PHONE, "ACTIVE", "VERIFIED"));

        assertThatThrownBy(() -> service.register(ACCOUNT_NUMBER)).isInstanceOf(ForbiddenException.class);
        verify(aliasRepository, never()).saveAndFlush(any());
    }

    @Test
    void shouldRejectRegistrationOnSavingsAccount() {
        account.setAccountType(AccountTypeEnum.SAVINGS);
        currentCustomer(customer(10L, PHONE, "ACTIVE", "VERIFIED"));

        assertThatThrownBy(() -> service.register(ACCOUNT_NUMBER))
                .isInstanceOf(BadRequestException.class)
                .extracting("code").isEqualTo("PHONE_ALIAS_ACCOUNT_INVALID");
    }

    @Test
    void shouldRejectWhenPhoneStillBelongsToAnotherCustomer() {
        currentCustomer(customer(10L, PHONE, "ACTIVE", "VERIFIED"));
        when(aliasRepository.findByPhoneHashForUpdate(indexer.hashPhone(PHONE)))
                .thenReturn(Optional.of(alias(5L, PHONE, 2L, 20L)));
        customerById(customer(20L, PHONE, "ACTIVE", "VERIFIED"));

        assertThatThrownBy(() -> service.register(ACCOUNT_NUMBER))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("PHONE_ALIAS_TAKEN");
    }

    @Test
    void shouldTakeOverAliasWhenPreviousOwnerChangedPhone() {
        currentCustomer(customer(10L, PHONE, "ACTIVE", "VERIFIED"));
        PhoneAlias stale = alias(5L, PHONE, 2L, 20L);
        when(aliasRepository.findByPhoneHashForUpdate(indexer.hashPhone(PHONE))).thenReturn(Optional.of(stale));
        customerById(customer(20L, "0987654321", "ACTIVE", "VERIFIED"));
        when(aliasRepository.findByCustomerId(10L)).thenReturn(List.of());

        service.register(ACCOUNT_NUMBER);

        assertThat(stale.getCustomerId()).isEqualTo(10L);
        assertThat(stale.getAccountId()).isEqualTo(1L);
    }

    @Test
    void shouldResolveAliasToAccountWithMaskedName() {
        when(aliasRepository.findByPhoneHash(indexer.hashPhone(PHONE))).thenReturn(Optional.of(alias(5L, PHONE, 1L, 10L)));
        customerById(customer(10L, PHONE, "ACTIVE", "VERIFIED"));

        PhoneAliasLookupResponse resolved = service.resolve("+84 912 345 678");

        assertThat(resolved.accountNumber()).isEqualTo(ACCOUNT_NUMBER);
        assertThat(resolved.currency()).isEqualTo("VND");
        assertThat(resolved.maskedName()).isEqualTo("NGUYEN V** A*");
    }

    @Test
    void shouldNotResolveAliasAfterOwnerChangedPhone() {
        when(aliasRepository.findByPhoneHash(indexer.hashPhone(PHONE))).thenReturn(Optional.of(alias(5L, PHONE, 1L, 10L)));
        customerById(customer(10L, "0987654321", "ACTIVE", "VERIFIED"));

        assertThatThrownBy(() -> service.resolve(PHONE))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("PHONE_ALIAS_NOT_FOUND");
    }

    @Test
    void shouldNotResolveAliasOfFrozenAccount() {
        account.setStatus(AccountStatusEnum.FROZEN);
        when(aliasRepository.findByPhoneHash(indexer.hashPhone(PHONE))).thenReturn(Optional.of(alias(5L, PHONE, 1L, 10L)));

        assertThatThrownBy(() -> service.resolve(PHONE))
                .isInstanceOf(BadRequestException.class)
                .extracting("code").isEqualTo("PHONE_ALIAS_ACCOUNT_INVALID");
    }

    @Test
    void shouldRejectInvalidPhone() {
        assertThatThrownBy(() -> service.resolve("12ab"))
                .isInstanceOf(BadRequestException.class)
                .extracting("code").isEqualTo("INVALID_PHONE");
    }

    private void currentCustomer(CustomerInfoResponse customer) {
        when(redisClient.send(eq(RedisActions.CUSTOMER_SERVICE), eq(RedisActions.CUSTOMER_GET_BY_USER_ID),
                eq(CustomerLookupRequest.byUserId(100L)), eq(CustomerInfoResponse.class))).thenReturn(customer);
    }

    private void customerById(CustomerInfoResponse customer) {
        when(redisClient.sendAsService(eq(RedisActions.CUSTOMER_SERVICE), eq(RedisActions.CUSTOMER_GET_BY_ID),
                eq(CustomerLookupRequest.byId(customer.id())), eq(CustomerInfoResponse.class))).thenReturn(customer);
    }

    private PhoneAlias alias(Long id, String phone, Long accountId, Long customerId) {
        PhoneAlias alias = new PhoneAlias();
        alias.setId(id);
        alias.setPhoneHash(indexer.hashPhone(phone));
        alias.setAccountId(accountId);
        alias.setCustomerId(customerId);
        return alias;
    }

    private static CustomerInfoResponse customer(Long id, String phone, String status, String kycStatus) {
        return new CustomerInfoResponse(id, 100L, "KH" + id, "Nguyen Van An", phone, "a@x.vn", status, kycStatus);
    }

    private static Account account(Long id, Long customerId, AccountTypeEnum type, AccountStatusEnum status) {
        Account account = new Account();
        account.setId(id);
        account.setAccountNumber(ACCOUNT_NUMBER);
        account.setCustomerId(customerId);
        account.setAccountType(type);
        account.setCurrency("VND");
        account.setStatus(status);
        return account;
    }
}
