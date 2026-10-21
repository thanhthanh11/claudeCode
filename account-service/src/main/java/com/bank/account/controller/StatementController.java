package com.bank.account.controller;

import com.bank.account.dto.StatementResponse;
import com.bank.account.service.StatementService;
import com.bank.common.dto.ApiResponse;
import com.bank.common.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * Sao kê tài khoản. {@code /statement} trả JSON bọc ApiResponse; {@code /statement/export} trả file
 * (CSV/PDF) — ngoại lệ duy nhất không bọc ApiResponse vì là tải file.
 */
@RestController
@RequestMapping("/api/accounts/{accountNumber}/statement")
@RequiredArgsConstructor
public class StatementController {

    private final StatementService statementService;

    @GetMapping
    @PreAuthorize("hasAuthority('ACCOUNT_READ')")
    public ApiResponse<StatementResponse> getStatement(
            @PathVariable String accountNumber,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(statementService.getStatement(accountNumber, from, to));
    }

    @GetMapping("/export")
    @PreAuthorize("hasAuthority('ACCOUNT_READ')")
    public ResponseEntity<byte[]> export(
            @PathVariable String accountNumber,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "pdf") String format) {
        StatementResponse statement = statementService.getStatement(accountNumber, from, to);
        String fileBase = "sao-ke-" + accountNumber + "-" + from + "-" + to;
        return switch (format.toLowerCase()) {
            case "csv" -> file(statementService.toCsv(statement), fileBase + ".csv",
                    new MediaType("text", "csv", StandardCharsets.UTF_8));
            case "pdf" -> file(statementService.toPdf(statement), fileBase + ".pdf", MediaType.APPLICATION_PDF);
            default -> throw new BadRequestException("INVALID_FORMAT", "Định dạng hỗ trợ: pdf, csv");
        };
    }

    private static ResponseEntity<byte[]> file(byte[] body, String fileName, MediaType mediaType) {
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(fileName).build().toString())
                .body(body);
    }
}
