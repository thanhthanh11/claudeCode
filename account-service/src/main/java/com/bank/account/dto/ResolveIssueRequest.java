package com.bank.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResolveIssueRequest(@NotBlank @Size(max = 500) String note) {
}
