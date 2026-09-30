package com.fabianrodas.services;

import com.fabianrodas.models.ManifestEntry;
import com.fabianrodas.models.ManifestEntryKind;
import com.fabianrodas.models.UserManifest;
import com.fabianrodas.repositories.ManifestRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Rules of a user's logical filesystem, applied to a decrypted manifest in
 * memory. Callers persist the manifest afterwards.
 */
public final class ManifestService {

    public static final int MAX_NAME_CODE_POINTS = 255;
    public static final String ROOT_NAME = "/";

    private final UserManifest manifest;

    public ManifestService(UserManifest manifest) {
        this.manifest = manifest;
    }

    /** A manifest holding only the root folder, which is never deleted or renamed. */
    public static UserManifest newManifest(UUID userId) {
        UUID rootFolderId = UUID.randomUUID();
        List<ManifestEntry> entries = new ArrayList<>();
        entries.add(new ManifestEntry(
                rootFolderId,
                ManifestEntryKind.FOLDER,
                null,
                ROOT_NAME,
                Instant.now().toString()
        ));

        return new UserManifest(ManifestRepository.FORMAT_VERSION, userId, rootFolderId, entries);
    }

    public UUID rootFolderId() {
        return manifest.getRootFolderId();
    }

    public ManifestEntry find(UUID entryId) {
        for (ManifestEntry entry : manifest.getEntries()) {
            if (entry.getEntryId().equals(entryId)) {
                return entry;
            }
        }

        return null;
    }

    public ManifestEntry createFolder(UUID parentId, String name) throws FileServiceException {
        ManifestEntry folder = new ManifestEntry(
                UUID.randomUUID(),
                ManifestEntryKind.FOLDER,
                parentId,
                requireAvailableName(parentId, name),
                Instant.now().toString()
        );

        manifest.getEntries().add(folder);
        return folder;
    }

    public List<ManifestEntry> listChildren(UUID parentId, boolean includeDeleted) {
        return manifest.getEntries().stream()
                .filter(entry -> parentId.equals(entry.getParentId()))
                .filter(entry -> includeDeleted || entry.getDeletedAt() == null)
                .toList();
    }

    /**
     * Returns the stripped name if it can be added under the given folder.
     * Names are metadata and may contain path separators; export sanitizes
     * them for the target filesystem.
     */
    public String requireAvailableName(UUID parentId, String name) throws FileServiceException {
        ManifestEntry parent = find(parentId);

        if (parent == null || parent.getDeletedAt() != null) {
            throw new FileServiceException(FileServiceException.Reason.NOT_FOUND);
        }

        if (parent.getKind() != ManifestEntryKind.FOLDER) {
            throw new FileServiceException(FileServiceException.Reason.NOT_A_FOLDER);
        }

        String candidate = name == null ? "" : name.strip();

        if (candidate.isEmpty()
                || candidate.equals(".")
                || candidate.equals("..")
                || candidate.indexOf('\0') >= 0
                || candidate.codePointCount(0, candidate.length()) > MAX_NAME_CODE_POINTS) {
            throw new FileServiceException(FileServiceException.Reason.INVALID_NAME);
        }

        for (ManifestEntry sibling : listChildren(parentId, false)) {
            if (sibling.getName().equalsIgnoreCase(candidate)) {
                throw new FileServiceException(FileServiceException.Reason.DUPLICATE_NAME);
            }
        }

        return candidate;
    }
}
