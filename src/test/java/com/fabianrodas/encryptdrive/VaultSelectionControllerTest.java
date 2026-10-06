package com.fabianrodas.encryptdrive;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class VaultSelectionControllerTest {

    @Test
    void acceptsOrdinaryFolderNames() {
        for (String name : new String[]{"EncryptDrive Vault", "Bóveda 2026", "vault.backup", "a"}) {
            assertNull(VaultSelectionController.vaultNameError(name), name);
        }
    }

    @Test
    void rejectsNamesWindowsCannotUseAsFolders() {
        for (String name : new String[]{
            "", ".", "..", "a/b", "a\\b", "a:b", "a*b", "a?b", "a\"b", "a<b", "a>b", "a|b",
            "trailing.", "CON", "nul", "com1", "LPT9", "con.txt", "tab\tname"
        }) {
            assertNotNull(VaultSelectionController.vaultNameError(name), name);
        }
    }
}
