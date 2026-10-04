package com.fabianrodas.services;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.gson.Gson;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/*
 * Routine guard against whole-file buffering: 128 MiB through import and
 * export in a JVM whose heap is 32 MiB. The timeout only stops a wedged child
 * from hanging the build; it is not a performance threshold.
 */
class StreamingMemoryTest {

    private static final int CHILD_TIMEOUT_MINUTES = 5;

    @Test
    void importAndExportStreamWithinASmallHeap(@TempDir Path dir) throws Exception {
        String classpath = Stream.of(
                        location(FileService.class),
                        location(StreamingMemoryProbe.class),
                        location(Gson.class),
                        location(GCMBlockCipher.class))
                .collect(Collectors.joining(File.pathSeparator));
        Path log = dir.resolve("probe.log");

        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx32m",
                "-cp", classpath,
                StreamingMemoryProbe.class.getName(),
                dir.toString(),
                String.valueOf(128L << 20)
        ).redirectErrorStream(true).redirectOutput(log.toFile()).start();

        try {
            if (!process.waitFor(CHILD_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly().waitFor();
                fail("probe still running after " + CHILD_TIMEOUT_MINUTES + " minutes: " + output(log));
            }

            String output = output(log);

            assertEquals(0, process.exitValue(), output);
            // The exact last line: "MISMATCH" also ends with "MATCH".
            assertEquals("MATCH", output.lines().reduce((first, second) -> second).orElse(""), output);
        } finally {
            process.destroyForcibly();
        }
    }

    private static String output(Path log) throws Exception {
        return new String(Files.readAllBytes(log), UTF_8);
    }

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }
}
