package com.bank.customer.service;

import com.bank.common.audit.AuditActions;
import com.bank.common.audit.AuditPublisher;
import com.bank.common.crypto.EncryptionKeyring;
import com.bank.common.exception.ForbiddenException;
import com.bank.common.security.AuthUser;
import com.bank.common.security.PermissionCodes;
import com.bank.common.security.RoleCodes;
import com.bank.common.security.SecurityUtils;
import com.bank.customer.dto.UpdateCustomerRequest;
import com.bank.customer.entity.Customer;
import com.bank.customer.repository.CustomerRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Base64;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    private static final long OWNER_USER_ID = 7L;

    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private CustomerInfoService customerInfoService;
    @Mock
    private AuditPublisher auditPublisher;

    private CustomerService customerService;
    private Customer customer;

    @BeforeEach
    void setUp() {
        customerService = new CustomerService(customerRepository, customerInfoService, auditPublisher,
                new EncryptionKeyring(Base64.getEncoder().encodeToString(new byte[32])));
        customer = new Customer();
        customer.setId(1L);
        customer.setUserId(OWNER_USER_ID);
        customer.setPhone("0912345678");
        customer.setEmail("owner@bank.local");
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void customerShouldNotChangeOwnPhoneBecauseItIsTheOtpChannel() {
        loginAs(new AuthUser(OWNER_USER_ID, "owner", Set.of(RoleCodes.CUSTOMER), Set.of(PermissionCodes.CUSTOMER_READ)));

        assertThatThrownBy(() -> customerService.update(1L, new UpdateCustomerRequest(null, "0999999999", null, null)))
                .isInstanceOf(ForbiddenException.class);
        assertThat(customer.getPhone()).isEqualTo("0912345678");
    }

    @Test
    void customerShouldUpdateOwnAddressWithoutAudit() {
        loginAs(new AuthUser(OWNER_USER_ID, "owner", Set.of(RoleCodes.CUSTOMER), Set.of(PermissionCodes.CUSTOMER_READ)));

        customerService.update(1L, new UpdateCustomerRequest(null, null, null, "12 Lý Thường Kiệt"));

        assertThat(customer.getAddress()).isEqualTo("12 Lý Thường Kiệt");
        verify(auditPublisher, never()).publish(anyString(), anyString(), any(), anyString());
    }

    @Test
    void staffChangingPhoneShouldBeAuditedWithoutValues() {
        loginAs(new AuthUser(99L, "teller", Set.of(RoleCodes.TELLER), Set.of(PermissionCodes.CUSTOMER_MANAGE)));

        customerService.update(1L, new UpdateCustomerRequest(null, "0999999999", null, null));

        assertThat(customer.getPhone()).isEqualTo("0999999999");
        verify(auditPublisher).publish(eq(AuditActions.CUSTOMER_PROFILE_UPDATED), eq("CUSTOMER"), eq(1L),
                eq("fields=[phone]"));
    }

    private static void loginAs(AuthUser user) {
        SecurityContextHolder.getContext().setAuthentication(SecurityUtils.toAuthentication(user, "token"));
    }
}
