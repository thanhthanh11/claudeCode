package com.bank.account.service;

import com.bank.account.dto.BalanceOperationResponse;
import com.bank.account.entity.Account;
import com.bank.account.entity.AccountLedgerEntry;
import com.bank.account.enums.AccountStatusEnum;
import com.bank.account.enums.AccountTypeEnum;
import com.bank.account.enums.EntryTypeEnum;
import com.bank.account.repository.AccountLedgerEntryRepository;
import com.bank.account.repository.AccountRepository;
import com.bank.common.event.AccountCommandPayload;
import com.bank.common.event.EventTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountBalanceServiceTest {

    private static final String ACCOUNT_NUMBER = "100000000001";
    private static final String TXN_REF = "TX0001";

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private AccountLedgerEntryRepository ledgerEntryRepository;
    @InjectMocks
    private AccountBalanceService balanceService;

    private Account account;

    @BeforeEach
    void setUp() {
        account = new Account();
        account.setId(1L);
        account.setAccountNumber(ACCOUNT_NUMBER);
        account.setAccountType(AccountTypeEnum.PAYMENT);
        account.setCurrency("VND");
        account.setBalance(new BigDecimal("1000000"));
        account.setAvailableBalance(new BigDecimal("1000000"));
        account.setStatus(AccountStatusEnum.ACTIVE);
    }

    @Test
    void shouldDebitWhenBalanceIsSufficient() {
        when(accountRepository.findByAccountNumberForUpdate(ACCOUNT_NUMBER)).thenReturn(Optional.of(account));
        when(ledgerEntryRepository.findByAccountIdAndTransactionRefAndEntryType(1L, TXN_REF, EntryTypeEnum.DEBIT))
                .thenReturn(Optional.empty());

        BalanceOperationResponse result = balanceService.debit(command("300000"));

        assertThat(result.eventType()).isEqualTo(EventTypes.ACCOUNT_DEBITED);
        assertThat(account.getBalance()).isEqualByComparingTo("700000");
        assertThat(account.getAvailableBalance()).isEqualByComparingTo("700000");
        assertThat(result.payload().balanceAfter()).isEqualByComparingTo("700000");
        verify(ledgerEntryRepository).save(any(AccountLedgerEntry.class));
    }

    @Test
    void shouldFailWhenBalanceIsInsufficient() {
        when(accountRepository.findByAccountNumberForUpdate(ACCOUNT_NUMBER)).thenReturn(Optional.of(account));
        when(ledgerEntryRepository.findByAccountIdAndTransactionRefAndEntryType(1L, TXN_REF, EntryTypeEnum.DEBIT))
                .thenReturn(Optional.empty());

        BalanceOperationResponse result = balanceService.debit(command("2000000"));

        assertThat(result.eventType()).isEqualTo(EventTypes.ACCOUNT_DEBIT_FAILED);
        assertThat(result.payload().reason()).isEqualTo("Số dư khả dụng không đủ");
        assertThat(account.getBalance()).isEqualByComparingTo("1000000");
        verify(ledgerEntryRepository, never()).save(any());
    }

    @Test
    void shouldNotDebitTwiceForSameTransaction() {
        AccountLedgerEntry existing = new AccountLedgerEntry();
        existing.setBalanceAfter(new BigDecimal("700000"));
        when(accountRepository.findByAccountNumberForUpdate(ACCOUNT_NUMBER)).thenReturn(Optional.of(account));
        when(ledgerEntryRepository.findByAccountIdAndTransactionRefAndEntryType(1L, TXN_REF, EntryTypeEnum.DEBIT))
                .thenReturn(Optional.of(existing));

        BalanceOperationResponse result = balanceService.debit(command("300000"));

        assertThat(result.eventType()).isEqualTo(EventTypes.ACCOUNT_DEBITED);
        assertThat(result.payload().balanceAfter()).isEqualByComparingTo("700000");
        assertThat(account.getBalance()).isEqualByComparingTo("1000000");
        verify(ledgerEntryRepository, never()).save(any());
    }

    @Test
    void shouldRejectRefundWithoutPriorDebit() {
        when(accountRepository.findByAccountNumberForUpdate(ACCOUNT_NUMBER)).thenReturn(Optional.of(account));
        when(ledgerEntryRepository.findByAccountIdAndTransactionRefAndEntryType(eq(1L), eq(TXN_REF), any()))
                .thenReturn(Optional.empty());

        BalanceOperationResponse result = balanceService.refund(command("300000"));

        assertThat(result.eventType()).isEqualTo(EventTypes.ACCOUNT_REFUND_FAILED);
        assertThat(account.getBalance()).isEqualByComparingTo("1000000");
        verify(ledgerEntryRepository, never()).save(any());
    }

    private static AccountCommandPayload command(String amount) {
        return new AccountCommandPayload(TXN_REF, ACCOUNT_NUMBER, new BigDecimal(amount), "VND", "Chuyển tiền");
    }
}
