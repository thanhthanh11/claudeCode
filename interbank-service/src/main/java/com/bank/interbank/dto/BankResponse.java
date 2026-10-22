package com.bank.interbank.dto;

import java.io.Serializable;

public record BankResponse(String bankCode, String shortName, String fullName) implements Serializable {
}
