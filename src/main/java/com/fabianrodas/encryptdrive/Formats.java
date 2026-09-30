package com.fabianrodas.encryptdrive;

import com.fabianrodas.models.ManifestEntry;
import com.fabianrodas.models.ManifestEntryKind;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Display formatting shared by the workspace views.
 */
final class Formats {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    private static final String[] UNITS = {"KB", "MB", "GB", "TB"};

    /** Shown once after damaged metadata was restored from an automatic backup. */
    static final String RECOVERY_NOTICE = "Damaged vault data was restored from an automatic "
            + "backup. Your most recent change may be missing.";

    private Formats() {
    }

    static String bytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }

        double value = bytes / 1024.0;
        int unit = 0;

        while (value >= 1024 && unit < UNITS.length - 1) {
            value /= 1024;
            unit++;
        }

        return String.format(Locale.ROOT, "%.1f %s", value, UNITS[unit]);
    }

    static String dateTime(String isoInstant) {
        try {
            return DATE_TIME.format(Instant.parse(isoInstant));
        } catch (RuntimeException e) {
            return "—";
        }
    }

    static String type(ManifestEntry entry) {
        if (entry.getKind() == ManifestEntryKind.FOLDER) {
            return "Folder";
        }

        String name = entry.getName();
        int dot = name.lastIndexOf('.');

        return dot > 0 && dot < name.length() - 1
                ? name.substring(dot + 1).toUpperCase(Locale.ROOT) + " file"
                : "File";
    }

    static String initials(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "U";
        }

        String[] parts = fullName.trim().split("\\s+");
        String first = parts[0].substring(0, 1);
        String last = parts.length > 1 ? parts[parts.length - 1].substring(0, 1) : "";

        return (first + last).toUpperCase(Locale.ROOT);
    }
}
