package com.bank.transaction.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ConfirmTransactionRequest(
        @NotBlank @Pattern(regexp = "^[0-9]{6}$", message = "OTP gồm 6 chữ số") String otp) {

    /** Không để lộ OTP khi record bị in ra log. */
    @Override
    public String toString() {
        return "ConfirmTransactionRequest[otp=******]";
    }
}
