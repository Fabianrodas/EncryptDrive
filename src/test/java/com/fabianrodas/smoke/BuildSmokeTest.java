package com.fabianrodas.smoke;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BuildSmokeTest {
    @Test
    void testRuntimeIsJava21OrNewer() {
        assertEquals(true, Runtime.version().feature() >= 21);
    }
}
