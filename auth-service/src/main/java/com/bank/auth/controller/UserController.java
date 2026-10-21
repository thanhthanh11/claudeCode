package com.bank.auth.controller;

import com.bank.auth.dto.UpdateUserRolesRequest;
import com.bank.auth.dto.UpdateUserStatusRequest;
import com.bank.auth.dto.UserResponse;
import com.bank.auth.service.UserService;
import com.bank.common.dto.ApiResponse;
import com.bank.common.dto.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('USER_MANAGE')")
public class UserController {

    private final UserService userService;

    @GetMapping
    public ApiResponse<PageResponse<UserResponse>> listUsers(
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(userService.listUsers(pageable));
    }

    @GetMapping("/{id}")
    public ApiResponse<UserResponse> getUser(@PathVariable Long id) {
        return ApiResponse.ok(userService.getUser(id));
    }

    @PutMapping("/{id}/roles")
    public ApiResponse<UserResponse> updateRoles(@PathVariable Long id,
                                                 @Valid @RequestBody UpdateUserRolesRequest request) {
        return ApiResponse.ok("Cập nhật role thành công", userService.updateRoles(id, request));
    }

    @PutMapping("/{id}/status")
    public ApiResponse<UserResponse> updateStatus(@PathVariable Long id,
                                                  @Valid @RequestBody UpdateUserStatusRequest request) {
        return ApiResponse.ok("Cập nhật trạng thái thành công", userService.updateStatus(id, request));
    }
}
