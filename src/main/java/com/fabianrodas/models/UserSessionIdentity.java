package com.fabianrodas.models;

import java.util.UUID;

/**
 * UI-safe identity of the signed-in user: no salts, wrapped keys, or
 * registry payloads.
 */
public record UserSessionIdentity(
        UUID userId,
        String fullName,
        String username,
        UUID manifestId
) {
}
