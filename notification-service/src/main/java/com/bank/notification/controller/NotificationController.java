package com.bank.notification.controller;

import com.bank.common.dto.ApiResponse;
import com.bank.common.dto.PageResponse;
import com.bank.notification.dto.NotificationResponse;
import com.bank.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping("/me")
    public ApiResponse<PageResponse<NotificationResponse>> getMyNotifications(
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(notificationService.getMyNotifications(pageable));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('CUSTOMER_MANAGE')")
    public ApiResponse<PageResponse<NotificationResponse>> getByCustomer(
            @RequestParam Long customerId,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(notificationService.getByCustomer(customerId, pageable));
    }
}
