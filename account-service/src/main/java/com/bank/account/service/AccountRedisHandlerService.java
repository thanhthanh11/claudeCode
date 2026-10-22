package com.bank.account.service;

import com.bank.account.entity.Account;
import com.bank.account.enums.AccountTypeEnum;
import com.bank.account.repository.AccountLedgerEntryRepository;
import com.bank.account.repository.AccountRepository;
import com.bank.common.dto.AccountLookupRequest;
import com.bank.common.dto.LedgerEntryInfo;
import com.bank.common.dto.LedgerLookupRequest;
import com.bank.common.dto.PhoneAliasLookupRequest;
import com.bank.common.exception.BadRequestException;
import com.bank.common.exception.ForbiddenException;
import com.bank.common.redis.RedisActions;
import com.bank.common.redis.RedisRequestHandler;
import com.bank.common.security.AuthUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Xử lý tra cứu tài khoản / sổ cái / alias số điện thoại từ service khác qua Redis (transaction-service,
 * notification-service, interbank-service).
 */
@Service
@RequiredArgsConstructor
public class AccountRedisHandlerService implements RedisRequestHandler {

    private final AccountService accountService;
    private final PhoneAliasService phoneAliasService;
    private final AccountRepository accountRepository;
    private final AccountLedgerEntryRepository ledgerEntryRepository;
    private final ObjectMapper objectMapper;

    @Override
    public Set<String> supportedActions() {
        return Set.of(RedisActions.ACCOUNT_GET_BY_NUMBER, RedisActions.ACCOUNT_LEDGER_BY_REF,
                RedisActions.ACCOUNT_RESOLVE_PHONE_ALIAS);
    }

    @Override
    public Object handle(String action, JsonNode payload, AuthUser caller) {
        return switch (action) {
            case RedisActions.ACCOUNT_GET_BY_NUMBER -> {
                AccountLookupRequest request = parse(payload, AccountLookupRequest.class);
                requireText(request.accountNumber(), "accountNumber");
                yield accountService.getAccountInfo(request.accountNumber(), caller);
            }
            case RedisActions.ACCOUNT_LEDGER_BY_REF -> {
                if (!caller.isService() && !caller.isStaff()) {
                    throw new ForbiddenException("Chỉ service nội bộ / nhân viên được tra sổ cái theo giao dịch");
                }
                LedgerLookupRequest request = parse(payload, LedgerLookupRequest.class);
                requireText(request.transactionRef(), "transactionRef");
                yield ledgerByRef(request.transactionRef());
            }
            case RedisActions.ACCOUNT_RESOLVE_PHONE_ALIAS -> {
                // Người chuyển tiền (đã đăng nhập) hoặc service; kết quả chỉ có tên đã che
                PhoneAliasLookupRequest request = parse(payload, PhoneAliasLookupRequest.class);
                requireText(request.phone(), "phone");
                yield phoneAliasService.resolve(request.phone());
            }
            default -> throw new BadRequestException("UNKNOWN_ACTION", "Action không được hỗ trợ: " + action);
        };
    }

    @Transactional(readOnly = true)
    public LedgerEntryInfo[] ledgerByRef(String transactionRef) {
        var entries = ledgerEntryRepository.findByTransactionRefOrderByIdAsc(transactionRef);
        Map<Long, Account> accounts = accountRepository.findAllById(
                        entries.stream().map(entry -> entry.getAccountId()).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));
        return entries.stream()
                .map(entry -> {
                    Account account = accounts.get(entry.getAccountId());
                    return new LedgerEntryInfo(account.getAccountNumber(), entry.getTransactionRef(),
                            entry.getEntryType().name(), entry.getAmount(),
                            account.getAccountType() == AccountTypeEnum.INTERNAL);
                })
                .toArray(LedgerEntryInfo[]::new);
    }

    private <T> T parse(JsonNode payload, Class<T> type) {
        try {
            return objectMapper.treeToValue(payload, type);
        } catch (JsonProcessingException e) {
            throw new BadRequestException("INVALID_PAYLOAD", "Payload không hợp lệ");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException("INVALID_PAYLOAD", "Thiếu " + field);
        }
    }
}
