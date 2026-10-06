package com.fabianrodas.models;

import com.fabianrodas.security.SensitiveBytes;

/**
 * Successful login: the caller owns {@code userMasterKey} and must close it.
 */
public record UserLoginResult(
        UserSessionIdentity identity,
        SensitiveBytes userMasterKey
) {
}
