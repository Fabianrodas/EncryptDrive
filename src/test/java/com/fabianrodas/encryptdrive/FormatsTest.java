package com.fabianrodas.encryptdrive;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fabianrodas.models.ManifestEntry;
import com.fabianrodas.models.ManifestEntryKind;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FormatsTest {

    @Test
    void sizesUseBinaryUnitsWithOneDecimal() {
        assertEquals("0 B", Formats.bytes(0));
        assertEquals("1023 B", Formats.bytes(1023));
        assertEquals("1.0 KB", Formats.bytes(1024));
        assertEquals("1.5 KB", Formats.bytes(1536));
        assertEquals("1.0 MB", Formats.bytes(1024 * 1024));
        assertEquals("2.5 GB", Formats.bytes(5L * 1024 * 1024 * 1024 / 2));
    }

    @Test
    void typeNamesFoldersAndExtensions() {
        assertEquals("Folder", Formats.type(entry(ManifestEntryKind.FOLDER, "archive.2025")));
        assertEquals("PDF file", Formats.type(entry(ManifestEntryKind.FILE, "report.pdf")));
        assertEquals("File", Formats.type(entry(ManifestEntryKind.FILE, "README")));
        assertEquals("File", Formats.type(entry(ManifestEntryKind.FILE, ".bashrc")));
    }

    @Test
    void initialsUseFirstAndLastName() {
        assertEquals("AE", Formats.initials("Alice Marie Example"));
        assertEquals("A", Formats.initials("alice"));
        assertEquals("U", Formats.initials("   "));
    }

    private static ManifestEntry entry(ManifestEntryKind kind, String name) {
        return new ManifestEntry(UUID.randomUUID(), kind, UUID.randomUUID(), name, "2026-09-30T00:00:00Z");
    }
}
