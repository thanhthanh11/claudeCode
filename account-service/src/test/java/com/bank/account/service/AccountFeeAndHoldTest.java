package com.bank.account.service;

import com.bank.account.config.FeeAccountConfig;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountFeeAndHoldTest {

    private static final String CUSTOMER_ACCOUNT = "100000000001";
    private static final String FEE_ACCOUNT = "990000000001";
    private static final String TXN_REF = "TX0001";

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private AccountLedgerEntryRepository ledgerEntryRepository;

    private AccountBalanceService balanceService;
    private Account customer;
    private Account feeAccount;

    @BeforeEach
    void setUp() {
        FeeAccountConfig feeAccountConfig = new FeeAccountConfig();
        feeAccountConfig.setAccounts(Map.of("VND", List.of(FEE_ACCOUNT)));
        balanceService = new AccountBalanceService(accountRepository, ledgerEntryRepository, feeAccountConfig);
        customer = account(1L, CUSTOMER_ACCOUNT, AccountTypeEnum.PAYMENT, "1000000");
        feeAccount = account(99L, FEE_ACCOUNT, AccountTypeEnum.INTERNAL, "0");
        lenient().when(accountRepository.findByAccountNumberForUpdate(CUSTOMER_ACCOUNT)).thenReturn(Optional.of(customer));
        lenient().when(accountRepository.findByAccountNumberForUpdate(FEE_ACCOUNT)).thenReturn(Optional.of(feeAccount));
        lenient().when(ledgerEntryRepository.findByAccountIdAndTransactionRefAndEntryType(anyLong(), anyString(), any()))
                .thenReturn(Optional.empty());
    }

    @Test
    void debitWithFeeShouldMoveFeeToInternalAccount() {
        BalanceOperationResponse result = balanceService.debit(command("100000", "2000"));

        assertThat(result.eventType()).isEqualTo(EventTypes.ACCOUNT_DEBITED);
        assertThat(customer.getBalance()).isEqualByComparingTo("898000");
        assertThat(feeAccount.getBalance()).isEqualByComparingTo("2000");
        List<AccountLedgerEntry> entries = savedEntries(2);
        assertThat(entries).extracting(AccountLedgerEntry::getEntryType, AccountLedgerEntry::getAmount)
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactly(org.assertj.core.groups.Tuple.tuple(EntryTypeEnum.DEBIT, new BigDecimal("102000")),
                        org.assertj.core.groups.Tuple.tuple(EntryTypeEnum.CREDIT, new BigDecimal("2000")));
    }

    @Test
    void debitShouldFailWhenBalanceDoesNotCoverFee() {
        BalanceOperationResponse result = balanceService.debit(command("999000", "2000"));

        assertThat(result.eventType()).isEqualTo(EventTypes.ACCOUNT_DEBIT_FAILED);
        assertThat(customer.getBalance()).isEqualByComparingTo("1000000");
        verify(ledgerEntryRepository, never()).save(any());
    }

    @Test
    void refundShouldReturnAmountAndFee() {
        feeAccount.setBalance(new BigDecimal("2000"));
        feeAccount.setAvailableBalance(new BigDecimal("2000"));
        AccountLedgerEntry debit = new AccountLedgerEntry();
        debit.setAmount(new BigDecimal("102000"));
        when(ledgerEntryRepository.findByAccountIdAndTransactionRefAndEntryType(1L, TXN_REF, EntryTypeEnum.DEBIT))
                .thenReturn(Optional.of(debit));
        AccountLedgerEntry feeCredit = new AccountLedgerEntry();
        feeCredit.setAccountId(99L);
        feeCredit.setAmount(new BigDecimal("2000"));
        when(ledgerEntryRepository.findByTransactionRefOnAccountType(TXN_REF, EntryTypeEnum.CREDIT,
                AccountTypeEnum.INTERNAL)).thenReturn(List.of(feeCredit));
        when(accountRepository.findByIdForUpdate(99L)).thenReturn(Optional.of(feeAccount));

        BalanceOperationResponse result = balanceService.refund(command("100000", "2000"));

        assertThat(result.eventType()).isEqualTo(EventTypes.ACCOUNT_REFUNDED);
        assertThat(customer.getBalance()).isEqualByComparingTo("1102000");
        assertThat(feeAccount.getBalance()).isEqualByComparingTo("0");
        savedEntries(2);
    }

    @Test
    void refundShouldFailWhenCollectedFeeEntryIsMissing() {
        AccountLedgerEntry debit = new AccountLedgerEntry();
        debit.setAmount(new BigDecimal("102000"));
        when(ledgerEntryRepository.findByAccountIdAndTransactionRefAndEntryType(1L, TXN_REF, EntryTypeEnum.DEBIT))
                .thenReturn(Optional.of(debit));
        when(ledgerEntryRepository.findByTransactionRefOnAccountType(TXN_REF, EntryTypeEnum.CREDIT,
                AccountTypeEnum.INTERNAL)).thenReturn(List.of());

        BalanceOperationResponse result = balanceService.refund(command("100000", "2000"));

        assertThat(result.eventType()).isEqualTo(EventTypes.ACCOUNT_REFUND_FAILED);
        assertThat(customer.getBalance()).isEqualByComparingTo("1000000");
        verify(ledgerEntryRepository, never()).save(any());
    }

    @Test
    void feeAccountShouldBeChosenByTransactionRefAcrossShards() {
        FeeAccountConfig config = new FeeAccountConfig();
        config.setAccounts(Map.of("VND", List.of("990000000001", "990000000004", "990000000005", "990000000006")));

        List<String> chosen = IntStream.range(0, 200)
                .mapToObj(i -> config.accountFor("VND", "TX" + i))
                .toList();

        assertThat(config.accountFor("VND", TXN_REF)).isEqualTo(config.accountFor("VND", TXN_REF));
        assertThat(chosen).containsOnly("990000000001", "990000000004", "990000000005", "990000000006");
        assertThat(Set.copyOf(chosen)).hasSize(4);
        assertThat(config.accountFor("USD", TXN_REF)).isNull();
    }

    @Test
    void holdShouldReduceOnlyAvailableBalance() {
        customer.hold(new BigDecimal("300000"));

        assertThat(customer.getBalance()).isEqualByComparingTo("1000000");
        assertThat(customer.getAvailableBalance()).isEqualByComparingTo("700000");
        BalanceOperationResponse result = balanceService.debit(command("800000", null));
        assertThat(result.eventType()).isEqualTo(EventTypes.ACCOUNT_DEBIT_FAILED);

        customer.releaseHold(new BigDecimal("300000"));
        assertThat(customer.getAvailableBalance()).isEqualByComparingTo("1000000");
    }

    private List<AccountLedgerEntry> savedEntries(int expected) {
        ArgumentCaptor<AccountLedgerEntry> captor = ArgumentCaptor.forClass(AccountLedgerEntry.class);
        verify(ledgerEntryRepository, times(expected)).save(captor.capture());
        return captor.getAllValues();
    }

    private static AccountCommandPayload command(String amount, String fee) {
        return new AccountCommandPayload(TXN_REF, CUSTOMER_ACCOUNT, new BigDecimal(amount), "VND", "Chuyển tiền",
                fee == null ? null : new BigDecimal(fee));
    }

    private static Account account(Long id, String number, AccountTypeEnum type, String balance) {
        Account account = new Account();
        account.setId(id);
        account.setAccountNumber(number);
        account.setAccountType(type);
        account.setCurrency("VND");
        account.setBalance(new BigDecimal(balance));
        account.setAvailableBalance(new BigDecimal(balance));
        account.setStatus(AccountStatusEnum.ACTIVE);
        return account;
    }
}
