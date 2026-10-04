package com.fabianrodas.services;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/*
 * Opt-in measurement (spec 14.2/14.3), never a CI threshold:
 *   mvn -Dtest=FolderImportBenchmarkTest -Dencryptdrive.perfCheck=true test
 */
@EnabledIfSystemProperty(named = "encryptdrive.perfCheck", matches = "true")
class FolderImportBenchmarkTest {

    @Test
    void thousandSmallFiles(@TempDir Path dir) throws Exception {
        Path source = Files.createDirectories(dir.resolve("in").resolve("Bench"));

        for (int i = 0; i < 1000; i++) {
            Files.write(source.resolve("file-" + i + ".bin"), new byte[4096]);
        }

        try (TestVault vault = new TestVault(dir)) {
            FileService files = vault.files(vault.register("alice"));
            long start = System.nanoTime();

            FileService.FolderImport result = files.importFolder(source, files.rootFolderId(), (a, b, c, d) -> { });

            System.out.println("Folder import benchmark: 1000 x 4 KiB in "
                    + (System.nanoTime() - start) / 1_000_000 + " ms");
            assertEquals(1000, result.filesImported());
        }
    }
}
