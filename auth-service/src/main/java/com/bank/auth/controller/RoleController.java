package com.bank.auth.controller;

import com.bank.auth.dto.RoleResponse;
import com.bank.auth.service.RoleService;
import com.bank.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    @GetMapping
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public ApiResponse<List<RoleResponse>> listRoles() {
        return ApiResponse.ok(roleService.listRoles());
    }
}
