package com.fabianrodas.services;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fabianrodas.models.UserSessionIdentity;
import com.fabianrodas.security.SensitiveBytes;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SessionServiceTest {

    private static final UserSessionIdentity ALICE = new UserSessionIdentity(
            UUID.randomUUID(), "Alice Example", "alice", UUID.randomUUID()
    );

    @AfterEach
    void endSession() {
        SessionService.logout();
    }

    @Test
    void logoutDestroysUserMasterKey() {
        SensitiveBytes userMasterKey = SensitiveBytes.copyOf(new byte[]{1, 2, 3});
        SessionService.start(ALICE, userMasterKey);

        SessionService.logout();

        assertTrue(userMasterKey.isDestroyed());
        assertFalse(SessionService.isActive());
        assertThrows(IllegalStateException.class, SessionService::identity);
        assertThrows(IllegalStateException.class, SessionService::copyUserMasterKey);
    }

    @Test
    void closingTheVaultAlsoDestroysUserMasterKey() {
        SensitiveBytes userMasterKey = SensitiveBytes.copyOf(new byte[]{1, 2, 3});
        SessionService.start(ALICE, userMasterKey);

        VaultSessionService.closeVault();

        assertTrue(userMasterKey.isDestroyed());
        assertFalse(SessionService.isActive());
    }

    @Test
    void startingAnotherSessionDestroysThePreviousKey() {
        SensitiveBytes first = SensitiveBytes.copyOf(new byte[]{1, 2, 3});
        SessionService.start(ALICE, first);

        SessionService.start(ALICE, SensitiveBytes.copyOf(new byte[]{4, 5, 6}));

        assertTrue(first.isDestroyed());
    }

    @Test
    void copiesAreIndependentOfTheSessionKey() {
        SensitiveBytes userMasterKey = SensitiveBytes.copyOf(new byte[]{1, 2, 3});
        SessionService.start(ALICE, userMasterKey);

        SensitiveBytes copy = SessionService.copyUserMasterKey();
        assertArrayEquals(new byte[]{1, 2, 3}, copy.copy());
        copy.close();

        assertFalse(userMasterKey.isDestroyed());
        assertEquals(ALICE, SessionService.identity());
    }

    @Test
    void logoutWithoutSessionIsHarmless() {
        assertDoesNotThrow(SessionService::logout);
    }
}
