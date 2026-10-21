package com.bank.auth.service;

import com.bank.common.audit.AuditActions;
import com.bank.common.audit.AuditPublisher;
import com.bank.auth.dto.UpdateUserRolesRequest;
import com.bank.auth.dto.UpdateUserStatusRequest;
import com.bank.auth.dto.UserResponse;
import com.bank.auth.entity.Role;
import com.bank.auth.entity.User;
import com.bank.auth.enums.UserStatusEnum;
import com.bank.auth.exception.UserNotFoundException;
import com.bank.auth.repository.RefreshTokenRepository;
import com.bank.auth.repository.RoleRepository;
import com.bank.auth.repository.UserRepository;
import com.bank.common.dto.PageResponse;
import com.bank.common.exception.BadRequestException;
import com.bank.common.security.AuthUser;
import com.bank.common.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserMapperService userMapper;
    private final AccessTokenRevocationService tokenRevocationService;
    private final AuditPublisher auditPublisher;

    @Transactional(readOnly = true)
    public UserResponse getCurrentUser() {
        AuthUser currentUser = SecurityUtils.requireCurrentUser();
        return getUser(currentUser.userId());
    }

    @Transactional(readOnly = true)
    public UserResponse getUser(Long id) {
        return userMapper.toResponse(findUser(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> listUsers(Pageable pageable) {
        return PageResponse.from(userRepository.findAll(pageable).map(userMapper::toResponse));
    }

    /**
     * Đổi role, thu hồi refresh token và access token đang dùng để người dùng phải đăng nhập lại nhận quyền mới.
     */
    @Transactional
    public UserResponse updateRoles(Long id, UpdateUserRolesRequest request) {
        User user = findUser(id);
        List<Role> roles = roleRepository.findByCodeIn(request.roleCodes());
        if (roles.size() != request.roleCodes().size()) {
            throw new BadRequestException("INVALID_ROLE", "Có role không tồn tại trong danh sách");
        }
        user.setRoles(new HashSet<>(roles));
        refreshTokenRepository.revokeAllByUserId(id);
        tokenRevocationService.revokeAllAfterCommit(id);
        log.info("Cập nhật role người dùng #{}: {}", id, request.roleCodes());
        auditPublisher.publish(AuditActions.USER_ROLES_UPDATED, "USER", id, "roles=" + request.roleCodes());
        return userMapper.toResponse(user);
    }

    @Transactional
    public UserResponse updateStatus(Long id, UpdateUserStatusRequest request) {
        AuthUser currentUser = SecurityUtils.requireCurrentUser();
        if (Objects.equals(currentUser.userId(), id) && request.status() != UserStatusEnum.ACTIVE) {
            throw new BadRequestException("CANNOT_LOCK_SELF", "Không thể tự khóa tài khoản của mình");
        }
        User user = findUser(id);
        user.setStatus(request.status());
        if (request.status() != UserStatusEnum.ACTIVE) {
            refreshTokenRepository.revokeAllByUserId(id);
            tokenRevocationService.revokeAllAfterCommit(id);
        }
        log.info("Cập nhật trạng thái người dùng #{} → {}", id, request.status());
        auditPublisher.publish(AuditActions.USER_STATUS_UPDATED, "USER", id, "status=" + request.status());
        return userMapper.toResponse(user);
    }

    private User findUser(Long id) {
        return userRepository.findWithRolesById(id).orElseThrow(() -> new UserNotFoundException(id));
    }
}
