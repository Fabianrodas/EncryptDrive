package com.fabianrodas.encryptdrive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

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

    @Test
    void scriptsAndWorkflowsHardCodeNoApplicationVersion() throws IOException {
        String numeric = pomVersion().replace("-SNAPSHOT", "");
        List<Path> files;

        try (Stream<Path> scripts = Files.list(Path.of("scripts"));
                Stream<Path> workflows = Files.list(Path.of(".github", "workflows"))) {
            files = Stream.concat(scripts, workflows)
                    .filter(Files::isRegularFile)
                    .toList();
        }

        for (Path file : files) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            assertFalse(text.contains(numeric), file + " hard-codes " + numeric);
            assertFalse(text.contains("1.0-SNAPSHOT"), file + " hard-codes 1.0-SNAPSHOT");
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void releaseNamesDeriveFromTheProjectVersionAndChannel() throws Exception {
        String version = pomVersion();
        String dir = "target/release/" + version + "/";

        Map<String, String> dev = dryRun();
        assertEquals(version, dev.get("version"));
        assertEquals(version.replace("-SNAPSHOT", ""), dev.get("windowsVersion"));
        assertEquals(dir + "EncryptDrive-" + version + "-Setup.exe", dev.get("installer"));
        assertEquals(dir + "EncryptDrive-" + version + "-Windows-Portable.zip", dev.get("portable"));
        assertEquals(dir + "SHA256SUMS.txt", dev.get("checksums"));

        Map<String, String> rc = dryRun("-Channel", "rc.2");
        assertEquals(dir + "EncryptDrive-" + version + "-rc.2-Setup.exe", rc.get("installer"));
        assertEquals(dir + "EncryptDrive-" + version + "-rc.2-Windows-Portable.zip", rc.get("portable"));

        assertNotEquals(0, buildRelease("-DryRun", "-Channel", "beta").exit());
        assertEquals(version.endsWith("-SNAPSHOT"), buildRelease("-DryRun", "-Release").exit() != 0);
    }

    private record Run(int exit, String output) {
    }

    private static Map<String, String> dryRun(String... arguments) throws Exception {
        List<String> all = new ArrayList<>(List.of("-DryRun"));
        all.addAll(List.of(arguments));
        Run run = buildRelease(all.toArray(String[]::new));
        assertEquals(0, run.exit(), run.output());

        Map<String, String> values = new HashMap<>();
        for (String line : run.output().split("\\R")) {
            int equals = line.indexOf('=');
            if (equals > 0) {
                values.put(line.substring(0, equals).trim(), line.substring(equals + 1).trim());
            }
        }
        return values;
    }

    private static Run buildRelease(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "scripts/build-release.ps1"
        ));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Run(process.waitFor(), output);
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
