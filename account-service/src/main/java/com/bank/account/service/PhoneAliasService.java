package com.bank.account.service;

import com.bank.account.dto.PhoneAliasResponse;
import com.bank.account.entity.Account;
import com.bank.account.entity.PhoneAlias;
import com.bank.account.enums.AccountStatusEnum;
import com.bank.account.enums.AccountTypeEnum;
import com.bank.account.exception.AccountNotFoundException;
import com.bank.account.repository.AccountRepository;
import com.bank.account.repository.PhoneAliasRepository;
import com.bank.common.crypto.BlindIndexer;
import com.bank.common.crypto.EncryptionKeyring;
import com.bank.common.dto.CustomerInfoResponse;
import com.bank.common.dto.CustomerLookupRequest;
import com.bank.common.dto.PhoneAliasLookupResponse;
import com.bank.common.exception.BadRequestException;
import com.bank.common.exception.ConflictException;
import com.bank.common.exception.ForbiddenException;
import com.bank.common.exception.ResourceNotFoundException;
import com.bank.common.redis.RedisActions;
import com.bank.common.redis.RedisRequestReplyClient;
import com.bank.common.security.AuthUser;
import com.bank.common.security.SecurityUtils;
import com.bank.common.util.MaskingUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/**
 * Alias số điện thoại → tài khoản nhận (chuyển tiền qua số điện thoại).
 * <ul>
 *   <li>Đăng ký: khách hàng ACTIVE, KYC VERIFIED liên kết số điện thoại <b>trong hồ sơ</b> (không nhập tay) với một
 *       tài khoản PAYMENT ACTIVE của chính mình. Đăng ký lại chuyển alias sang tài khoản mới.</li>
 *   <li>Phân giải: tìm theo blind index, rồi so lại với số điện thoại hiện tại của chủ tài khoản — nhân viên đổi số
 *       trong hồ sơ thì alias cũ tự mất hiệu lực, không cần đồng bộ ngược từ customer-service.</li>
 * </ul>
 */
@Slf4j
@Service
public class PhoneAliasService {

    /** Số điện thoại Việt Nam sau chuẩn hóa: 10 chữ số bắt đầu bằng 0 (chấp nhận 9–11 để không chặn số đặc thù). */
    private static final int MIN_PHONE_DIGITS = 9;
    private static final int MAX_PHONE_DIGITS = 11;

    private final PhoneAliasRepository aliasRepository;
    private final AccountRepository accountRepository;
    private final RedisRequestReplyClient redisClient;
    private final BlindIndexer blindIndexer;

    public PhoneAliasService(PhoneAliasRepository aliasRepository, AccountRepository accountRepository,
                             RedisRequestReplyClient redisClient, EncryptionKeyring keyring) {
        this.aliasRepository = aliasRepository;
        this.accountRepository = accountRepository;
        this.redisClient = redisClient;
        this.blindIndexer = keyring.blindIndexer();
    }

    @Transactional
    public PhoneAliasResponse register(String accountNumber) {
        CustomerInfoResponse customer = currentCustomer();
        if (!customer.isActive()) {
            throw new BadRequestException("CUSTOMER_NOT_ACTIVE", "Khách hàng không ở trạng thái hoạt động");
        }
        if (!customer.isKycVerified()) {
            throw new BadRequestException("KYC_NOT_VERIFIED", "Cần hoàn tất KYC trước khi đăng ký chuyển tiền qua số điện thoại");
        }
        String phoneHash = blindIndexer.hashPhone(customer.phone());
        if (phoneHash == null) {
            throw new BadRequestException("PHONE_MISSING", "Hồ sơ khách hàng chưa có số điện thoại");
        }
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException(accountNumber));
        if (!Objects.equals(account.getCustomerId(), customer.id())) {
            throw new ForbiddenException("Chỉ chủ tài khoản được đăng ký alias số điện thoại");
        }
        if (account.getAccountType() != AccountTypeEnum.PAYMENT || account.getStatus() != AccountStatusEnum.ACTIVE) {
            throw new BadRequestException("PHONE_ALIAS_ACCOUNT_INVALID",
                    "Chỉ tài khoản thanh toán đang hoạt động được nhận tiền qua số điện thoại");
        }

        Optional<PhoneAlias> byPhone = aliasRepository.findByPhoneHashForUpdate(phoneHash);
        if (byPhone.isPresent() && !Objects.equals(byPhone.get().getCustomerId(), customer.id())) {
            // Số điện thoại từng thuộc khách hàng khác (đã đổi số / số bị thu hồi): chỉ chiếm được khi chủ cũ
            // không còn dùng số này trong hồ sơ
            if (stillOwnsPhone(byPhone.get())) {
                throw new ConflictException("PHONE_ALIAS_TAKEN", "Số điện thoại đã được khách hàng khác đăng ký");
            }
            log.info("Chuyển alias số điện thoại {} từ khách hàng #{} sang #{}",
                    MaskingUtils.maskPhone(customer.phone()), byPhone.get().getCustomerId(), customer.id());
        }
        // Mỗi khách hàng chỉ giữ một alias (số điện thoại hiện tại): xóa alias cũ của khách và alias khác trên
        // tài khoản này trước khi ghi
        aliasRepository.findByCustomerId(customer.id()).stream()
                .filter(alias -> byPhone.map(current -> !current.getId().equals(alias.getId())).orElse(true))
                .forEach(aliasRepository::delete);
        aliasRepository.flush();

        PhoneAlias alias = byPhone.orElseGet(PhoneAlias::new);
        alias.setPhoneHash(phoneHash);
        alias.setAccountId(account.getId());
        alias.setCustomerId(customer.id());
        try {
            aliasRepository.saveAndFlush(alias);
        } catch (DataIntegrityViolationException e) {
            // Hai request đăng ký cùng số điện thoại / cùng tài khoản chạy song song
            throw new ConflictException("PHONE_ALIAS_CONFLICT", "Alias đang được cập nhật, vui lòng thử lại");
        }
        log.info("Khách hàng #{} đăng ký alias số điện thoại {} cho tài khoản {}", customer.id(),
                MaskingUtils.maskPhone(customer.phone()), MaskingUtils.maskAccountNumber(accountNumber));
        return new PhoneAliasResponse(MaskingUtils.maskPhone(customer.phone()), account.getAccountNumber(),
                alias.getUpdatedAt());
    }

    @Transactional
    public void unregister(String accountNumber) {
        CustomerInfoResponse customer = currentCustomer();
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException(accountNumber));
        if (!Objects.equals(account.getCustomerId(), customer.id())) {
            throw new ForbiddenException("Chỉ chủ tài khoản được hủy alias số điện thoại");
        }
        PhoneAlias alias = aliasRepository.findByAccountId(account.getId())
                .orElseThrow(() -> new ResourceNotFoundException("PHONE_ALIAS_NOT_FOUND",
                        "Tài khoản chưa đăng ký nhận tiền qua số điện thoại"));
        aliasRepository.delete(alias);
        log.info("Khách hàng #{} hủy alias số điện thoại của tài khoản {}", customer.id(),
                MaskingUtils.maskAccountNumber(accountNumber));
    }

    @Transactional(readOnly = true)
    public Optional<PhoneAliasResponse> getMine() {
        CustomerInfoResponse customer = currentCustomer();
        String currentHash = blindIndexer.hashPhone(customer.phone());
        return aliasRepository.findByCustomerId(customer.id()).stream()
                .filter(alias -> alias.getPhoneHash().equals(currentHash))
                .findFirst()
                .flatMap(alias -> accountRepository.findById(alias.getAccountId())
                        .map(account -> new PhoneAliasResponse(MaskingUtils.maskPhone(customer.phone()),
                                account.getAccountNumber(), alias.getUpdatedAt())));
    }

    /**
     * Phân giải số điện thoại → tài khoản nhận. Mọi trường hợp không dùng được (chưa đăng ký, số đã đổi, khách hàng
     * bị khóa) đều trả cùng một lỗi để không lộ trạng thái của người khác.
     */
    @Transactional(readOnly = true)
    public PhoneAliasLookupResponse resolve(String phone) {
        String normalized = BlindIndexer.normalizePhone(phone);
        if (normalized == null || normalized.length() < MIN_PHONE_DIGITS || normalized.length() > MAX_PHONE_DIGITS) {
            throw new BadRequestException("INVALID_PHONE", "Số điện thoại không hợp lệ");
        }
        PhoneAlias alias = aliasRepository.findByPhoneHash(blindIndexer.hash(normalized))
                .orElseThrow(PhoneAliasService::aliasNotFound);
        Account account = accountRepository.findById(alias.getAccountId())
                .orElseThrow(PhoneAliasService::aliasNotFound);
        if (account.getStatus() != AccountStatusEnum.ACTIVE) {
            throw new BadRequestException("PHONE_ALIAS_ACCOUNT_INVALID",
                    "Tài khoản nhận của số điện thoại này không ở trạng thái hoạt động");
        }
        CustomerInfoResponse owner = customerById(alias.getCustomerId());
        if (!owner.isActive() || !alias.getPhoneHash().equals(blindIndexer.hashPhone(owner.phone()))) {
            throw aliasNotFound();
        }
        return new PhoneAliasLookupResponse(account.getAccountNumber(), account.getCurrency(),
                MaskingUtils.maskName(owner.fullName()));
    }

    private boolean stillOwnsPhone(PhoneAlias alias) {
        CustomerInfoResponse previousOwner = customerById(alias.getCustomerId());
        return alias.getPhoneHash().equals(blindIndexer.hashPhone(previousOwner.phone()));
    }

    private CustomerInfoResponse currentCustomer() {
        AuthUser user = SecurityUtils.requireCurrentUser();
        return redisClient.send(RedisActions.CUSTOMER_SERVICE, RedisActions.CUSTOMER_GET_BY_USER_ID,
                CustomerLookupRequest.byUserId(user.userId()), CustomerInfoResponse.class);
    }

    /** Tra hồ sơ khách hàng khác (chủ alias) — người gọi không có quyền xem nên dùng service token. */
    private CustomerInfoResponse customerById(Long customerId) {
        return redisClient.sendAsService(RedisActions.CUSTOMER_SERVICE, RedisActions.CUSTOMER_GET_BY_ID,
                CustomerLookupRequest.byId(customerId), CustomerInfoResponse.class);
    }

    private static ResourceNotFoundException aliasNotFound() {
        return new ResourceNotFoundException("PHONE_ALIAS_NOT_FOUND",
                "Số điện thoại chưa đăng ký nhận tiền");
    }
}
