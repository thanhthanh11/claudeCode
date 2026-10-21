package com.bank.auth.service;

import com.bank.auth.dto.RoleResponse;
import com.bank.auth.entity.Permission;
import com.bank.auth.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RoleService {

    public static final String ROLES_CACHE = "roles";

    private final RoleRepository roleRepository;

    /** Danh sách role/permission ít thay đổi → cache trong Hazelcast. */
    @Cacheable(cacheNames = ROLES_CACHE, key = "'all'")
    @Transactional(readOnly = true)
    public List<RoleResponse> listRoles() {
        return roleRepository.findAllByOrderByIdAsc().stream()
                .map(role -> new RoleResponse(role.getId(), role.getCode(), role.getName(), role.getDescription(),
                        role.getPermissions().stream().map(Permission::getCode).collect(Collectors.toUnmodifiableSet())))
                .toList();
    }
}
