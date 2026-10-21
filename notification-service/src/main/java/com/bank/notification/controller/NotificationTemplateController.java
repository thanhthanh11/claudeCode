package com.bank.notification.controller;

import com.bank.common.dto.ApiResponse;
import com.bank.notification.dto.NotificationTemplateResponse;
import com.bank.notification.dto.UpdateNotificationTemplateRequest;
import com.bank.notification.service.NotificationTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/notification-templates")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class NotificationTemplateController {

    private final NotificationTemplateService templateService;

    @GetMapping
    public ApiResponse<List<NotificationTemplateResponse>> listAll() {
        return ApiResponse.ok(templateService.listAll());
    }

    @PutMapping("/{id}")
    public ApiResponse<NotificationTemplateResponse> update(@PathVariable Long id,
                                                            @Valid @RequestBody UpdateNotificationTemplateRequest request) {
        return ApiResponse.ok("Cập nhật mẫu thông báo thành công", templateService.update(id, request));
    }
}
