package com.fabianrodas.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VaultLockServiceTest {

    @TempDir
    Path vaultRoot;

    private final VaultLockService lockService = new VaultLockService();

    @BeforeEach
    void createMetadataDirectory() throws IOException {
        Files.createDirectories(vaultRoot.resolve(".encryptdrive"));
    }

    @Test
    void secondLockInSameJvmFailsWhileFirstIsHeld() throws Exception {
        try (VaultLockService.VaultLock held = lockService.acquire(vaultRoot)) {
            VaultException error = assertThrows(
                    VaultException.class,
                    () -> lockService.acquire(vaultRoot)
            );

            assertEquals(VaultException.Reason.BUSY, error.getReason());
        }
    }

    @Test
    void lockCanBeReacquiredAfterClose() throws Exception {
        lockService.acquire(vaultRoot).close();

        assertDoesNotThrow(() -> lockService.acquire(vaultRoot).close());
    }

    @Test
    void anotherProcessCannotLockWhileHeld(@TempDir Path probeDir) throws Exception {
        try (VaultLockService.VaultLock held = lockService.acquire(vaultRoot)) {
            assertEquals("BUSY", probe(probeDir));
        }

        assertEquals("FREE", probe(probeDir));
    }

    /*
     * Runs a separate JVM that tries to lock the vault lock file the same way
     * a second EncryptDrive process would.
     */
    private String probe(Path probeDir) throws Exception {
        Path source = probeDir.resolve("LockProbe.java");
        Files.writeString(source, """
                import java.nio.channels.FileChannel;
                import java.nio.file.Path;
                import java.nio.file.StandardOpenOption;

                public class LockProbe {
                    public static void main(String[] args) throws Exception {
                        try (FileChannel channel = FileChannel.open(
                                Path.of(args[0]),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE)) {
                            System.out.print(channel.tryLock() == null ? "BUSY" : "FREE");
                        }
                    }
                }
                """, StandardCharsets.UTF_8);

        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                source.toString(),
                vaultRoot.resolve(".encryptdrive").resolve("lock").toString()
        ).redirectErrorStream(true).start();

        String output = new String(
                process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8
        ).trim();
        process.waitFor();
        return output;
    }
}
