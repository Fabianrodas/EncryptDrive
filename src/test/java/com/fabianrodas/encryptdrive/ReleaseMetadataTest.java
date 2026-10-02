package com.fabianrodas.encryptdrive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/*
 * The Maven project version is the only application version: the app reads
 * it from a filtered resource, and nothing else may hard-code it.
 */
class ReleaseMetadataTest {

    private static final Pattern PROJECT_VERSION = Pattern.compile(
            "<artifactId>EncryptDrive</artifactId>\\s*<version>([^<]+)</version>"
    );

    @Test
    void appReportsTheMavenProjectVersion() throws IOException {
        String version = pomVersion();

        assertTrue(version.matches("\\d+\\.\\d+\\.\\d+(-SNAPSHOT)?"), version);
        assertEquals(version, App.version());
    }

    /** The project version from pom.xml (tests run with the project root as working directory). */
    static String pomVersion() throws IOException {
        Matcher matcher = PROJECT_VERSION.matcher(
                Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8)
        );

        if (!matcher.find()) {
            throw new IllegalStateException("pom.xml has no project version");
        }

        return matcher.group(1).trim();
    }
}
