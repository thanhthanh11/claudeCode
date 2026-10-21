package com.bank.auth.dto;

import java.io.Serializable;
import java.util.Set;

/**
 * Serializable vì được cache trong Hazelcast.
 */
public record RoleResponse(
        Long id,
        String code,
        String name,
        String description,
        Set<String> permissions) implements Serializable {
}
