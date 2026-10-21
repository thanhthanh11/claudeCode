package com.bank.auth.service;

import com.bank.auth.dto.UserResponse;
import com.bank.auth.entity.Permission;
import com.bank.auth.entity.Role;
import com.bank.auth.entity.User;
import com.bank.common.security.AuthUser;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Chuyển đổi entity User sang DTO / AuthUser. Phải gọi trong transaction vì roles là lazy.
 */
@Service
public class UserMapperService {

    public UserResponse toResponse(User user) {
        Set<String> roles = user.getRoles().stream().map(Role::getCode).collect(Collectors.toUnmodifiableSet());
        return new UserResponse(user.getId(), user.getUsername(), user.getEmail(), user.getStatus(), roles,
                user.getLastLoginAt(), user.getCreatedAt());
    }

    public AuthUser toAuthUser(User user) {
        Set<String> roles = user.getRoles().stream().map(Role::getCode).collect(Collectors.toSet());
        Set<String> permissions = user.getRoles().stream()
                .flatMap(role -> role.getPermissions().stream())
                .map(Permission::getCode)
                .collect(Collectors.toSet());
        return new AuthUser(user.getId(), user.getUsername(), roles, permissions);
    }
}
