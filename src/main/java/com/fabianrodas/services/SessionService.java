package com.fabianrodas.services;

import com.fabianrodas.models.UserSessionIdentity;
import com.fabianrodas.security.SensitiveBytes;

/**
 * The signed-in user of the open vault: a UI-safe identity plus the user
 * master key (UMK), which is destroyed on logout.
 */
public final class SessionService {

    private static UserSessionIdentity identity;
    private static SensitiveBytes userMasterKey;

    private SessionService() {
    }

    /** Takes ownership of {@code userMasterKey}; any previous session ends first. */
    public static synchronized void start(
            UserSessionIdentity identity,
            SensitiveBytes userMasterKey
    ) {
        logout();
        SessionService.identity = identity;
        SessionService.userMasterKey = userMasterKey;
    }

    public static synchronized boolean isActive() {
        return identity != null;
    }

    public static synchronized UserSessionIdentity identity() {
        requireActive();
        return identity;
    }

    /** Returns a copy of the UMK that the caller must close. */
    public static synchronized SensitiveBytes copyUserMasterKey() {
        requireActive();
        return SensitiveBytes.wrap(userMasterKey.copy());
    }

    /** Destroys the UMK and forgets the identity. Idempotent. */
    public static synchronized void logout() {
        if (userMasterKey != null) {
            userMasterKey.close();
        }

        userMasterKey = null;
        identity = null;
    }

    private static void requireActive() {
        if (identity == null) {
            throw new IllegalStateException("No user is signed in.");
        }
    }
}
