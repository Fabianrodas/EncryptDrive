package com.fabianrodas.services;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import java.io.File;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/*
 * Routine guard against whole-file buffering: 128 MiB through import and
 * export in a JVM whose heap is 32 MiB. No wall-clock threshold.
 */
class StreamingMemoryTest {

    @Test
    void importAndExportStreamWithinASmallHeap(@TempDir Path dir) throws Exception {
        String classpath = Stream.of(
                        Path.of("target", "classes").toAbsolutePath().toString(),
                        Path.of("target", "test-classes").toAbsolutePath().toString(),
                        location(Gson.class),
                        location(GCMBlockCipher.class))
                .collect(Collectors.joining(File.pathSeparator));

        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx32m",
                "-cp", classpath,
                StreamingMemoryProbe.class.getName(),
                dir.toString(),
                String.valueOf(128L << 20)
        ).redirectErrorStream(true).start();

        String output = new String(process.getInputStream().readAllBytes(), UTF_8);

        assertEquals(0, process.waitFor(), output);
        assertTrue(output.endsWith("MATCH"), output);
    }

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }
}
