package com.fabianrodas.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SensitiveBytesTest {

    @Test
    void copyOfIsIndependentOfTheSourceArray() {
        byte[] source = {1, 2, 3};

        try (SensitiveBytes key = SensitiveBytes.copyOf(source)) {
            source[0] = 9;

            assertArrayEquals(new byte[]{1, 2, 3}, key.copy());
        }
    }

    @Test
    void copyReturnsAnArrayTheCallerCanWipe() {
        try (SensitiveBytes key = SensitiveBytes.copyOf(new byte[]{1, 2, 3})) {
            byte[] working = key.copy();
            working[0] = 0;

            assertArrayEquals(new byte[]{1, 2, 3}, key.copy());
        }
    }

    @Test
    void closeZeroesTheOwnedArray() {
        byte[] owned = {1, 2, 3};

        SensitiveBytes.wrap(owned).close();

        assertArrayEquals(new byte[3], owned);
    }

    @Test
    void closeMarksDestroyedAndIsIdempotent() {
        SensitiveBytes key = SensitiveBytes.copyOf(new byte[]{1, 2, 3});
        assertFalse(key.isDestroyed());

        key.close();

        assertTrue(key.isDestroyed());
        assertDoesNotThrow(key::close);
    }

    @Test
    void copyAfterCloseThrows() {
        SensitiveBytes key = SensitiveBytes.copyOf(new byte[]{1, 2, 3});
        key.close();

        assertThrows(IllegalStateException.class, key::copy);
    }
}
