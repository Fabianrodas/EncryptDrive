package com.fabianrodas.services;

import com.fabianrodas.models.ManifestEntry;
import com.fabianrodas.models.ManifestEntryKind;
import com.fabianrodas.models.PendingDeletion;
import com.fabianrodas.models.UserManifest;
import com.fabianrodas.models.UserSessionIdentity;
import com.fabianrodas.models.VaultContext;
import com.fabianrodas.models.WorkspaceStats;
import com.fabianrodas.repositories.BlobRepository;
import com.fabianrodas.repositories.ManifestRepository;
import com.fabianrodas.repositories.VaultStorageException;
import com.fabianrodas.security.Aad;
import com.fabianrodas.security.AesGcmService;
import com.fabianrodas.security.CryptoConstants;
import com.fabianrodas.security.CryptoException;
import com.fabianrodas.security.SensitiveBytes;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Files and folders of the signed-in user. Every file has its own random key
 * (FDEK), wrapped under the user master key (UMK) and stored only in the
 * user's encrypted manifest. A manifest entry is committed only after its
 * blob is complete, and permanent deletion removes entries from the manifest
 * and every backup before it removes their blobs, so neither the manifest nor
 * a recovered backup references a blob that was deleted.
 *
 * The UMK is never held here: each operation takes a fresh copy from the
 * supplier and wipes it, so logging out ends access.
 */
public final class FileService {

    private static final Object MANIFEST_LOCK = new Object();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_FOLDER_DEPTH = 10_000;

    private static final Pattern UNSAFE_CHARACTERS
            = Pattern.compile("[<>:\"/\\\\|?*\\x00-\\x1F]");

    private static final Pattern RESERVED_NAMES
            = Pattern.compile("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?");

    private static final Comparator<ManifestEntry> FOLDERS_THEN_NAME = Comparator
            .comparing((ManifestEntry entry) -> entry.getKind() != ManifestEntryKind.FOLDER)
            .thenComparing(ManifestEntry::getName, String.CASE_INSENSITIVE_ORDER);

    private final VaultContext vault;
    private final UserSessionIdentity identity;
    private final Supplier<SensitiveBytes> userMasterKey;
    private final ManifestRepository manifestRepository;
    private final BlobRepository blobRepository;
    private final StreamingFileCryptoService crypto;
    private final AesGcmService aes = new AesGcmService();

    public FileService(
            VaultContext vault,
            UserSessionIdentity identity,
            Supplier<SensitiveBytes> userMasterKey
    ) {
        this(
                vault,
                identity,
                userMasterKey,
                new ManifestRepository(vault),
                new BlobRepository(),
                new StreamingFileCryptoService()
        );
    }

    FileService(
            VaultContext vault,
            UserSessionIdentity identity,
            Supplier<SensitiveBytes> userMasterKey,
            ManifestRepository manifestRepository,
            BlobRepository blobRepository,
            StreamingFileCryptoService crypto
    ) {
        this.vault = vault;
        this.identity = identity;
        this.userMasterKey = userMasterKey;
        this.manifestRepository = manifestRepository;
        this.blobRepository = blobRepository;
        this.crypto = crypto;
    }

    /** Files of the user signed in to the open vault; refuses to run for a later session. */
    public static FileService forCurrentSession() {
        UserSessionIdentity identity = SessionService.identity();

        return new FileService(
                VaultSessionService.current(),
                identity,
                () -> SessionService.copyUserMasterKey(identity)
        );
    }

    public UUID rootFolderId() throws FileServiceException {
        return folderView(null).folderId();
    }

    /** A folder's breadcrumb path (root first) and its active children, from one manifest read. */
    public record FolderView(UUID folderId, List<ManifestEntry> path, List<ManifestEntry> children) {
    }

    /**
     * The view of {@code folderId}, or of the root when it is null or no longer
     * an active folder (for example because it was moved to the trash).
     */
    public FolderView folderView(UUID folderId) throws FileServiceException {
        return withUserMasterKey(key -> {
            UserManifest manifest = load(key);
            ManifestService rules = new ManifestService(manifest);
            ManifestEntry folder = folderId == null ? null : rules.find(folderId);

            if (folder == null
                    || folder.getDeletedAt() != null
                    || folder.getKind() != ManifestEntryKind.FOLDER) {
                folder = rules.find(manifest.getRootFolderId());
            }

            return new FolderView(
                    folder.getEntryId(),
                    path(rules, folder),
                    rules.listChildren(folder.getEntryId(), false).stream().sorted(FOLDERS_THEN_NAME).toList()
            );
        });
    }

    /** Folders from the root down to {@code folder}. */
    private static List<ManifestEntry> path(ManifestService rules, ManifestEntry folder) {
        LinkedList<ManifestEntry> path = new LinkedList<>();

        for (ManifestEntry entry = folder;
                entry != null && path.size() <= MAX_FOLDER_DEPTH;
                entry = entry.getParentId() == null ? null : rules.find(entry.getParentId())) {
            path.addFirst(entry);
        }

        return path;
    }

    /** Active children, folders first, then by name. */
    public List<ManifestEntry> listChildren(UUID folderId) throws FileServiceException {
        return withUserMasterKey(key -> new ManifestService(load(key))
                .listChildren(folderId, false).stream()
                .sorted(FOLDERS_THEN_NAME)
                .toList());
    }

    /** A search hit and the folders leading to it (below the root, outermost first). */
    public record SearchResult(ManifestEntry entry, List<String> folders) {
    }

    /**
     * Active entries whose name contains {@code query}, ignoring case,
     * anywhere in the signed-in user's tree, folders first, then by name. Only
     * the decrypted manifest in memory is used; nothing is indexed or written.
     * Entries below a trashed folder never match, whatever their own state.
     */
    public List<SearchResult> search(String query) throws FileServiceException {
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);

        if (needle.isEmpty()) {
            return List.of();
        }

        return withUserMasterKey(key -> {
            UserManifest manifest = load(key);
            // One lookup table: a manifest holds up to 64 MiB of entries, too many to scan per hit.
            Map<UUID, ManifestEntry> byId = manifest.getEntries().stream()
                    .collect(Collectors.toMap(ManifestEntry::getEntryId, entry -> entry));
            List<SearchResult> results = new ArrayList<>();

            for (ManifestEntry entry : manifest.getEntries()) {
                if (entry.getDeletedAt() != null
                        || entry.getEntryId().equals(manifest.getRootFolderId())
                        || !entry.getName().toLowerCase(Locale.ROOT).contains(needle)) {
                    continue;
                }

                // The repository only returns trees, so this walk ends at the root.
                LinkedList<String> folders = new LinkedList<>();
                boolean trashed = false;

                for (ManifestEntry folder = byId.get(entry.getParentId());
                        !folder.getEntryId().equals(manifest.getRootFolderId());
                        folder = byId.get(folder.getParentId())) {
                    trashed |= folder.getDeletedAt() != null;
                    folders.addFirst(folder.getName());
                }

                if (!trashed) {
                    results.add(new SearchResult(entry, folders));
                }
            }

            results.sort(Comparator.comparing(SearchResult::entry, FOLDERS_THEN_NAME));
            return results;
        });
    }

    /** Entries the user moved to the trash, newest first. */
    public List<ManifestEntry> listTrash() throws FileServiceException {
        return withUserMasterKey(key -> load(key).getEntries().stream()
                .filter(FileService::isTrashRoot)
                .sorted(Comparator.comparing(ManifestEntry::getDeletedAt).reversed())
                .toList());
    }

    public ManifestEntry createFolder(String name, UUID parentFolderId)
            throws FileServiceException {

        return modify(manifest -> new ManifestService(manifest).createFolder(parentFolderId, name));
    }

    /**
     * Encrypts a copy of {@code source} into the vault. The source file is
     * never modified or deleted.
     */
    public ManifestEntry importFile(Path source, UUID parentFolderId)
            throws FileServiceException {
        return importFile(source, parentFolderId, bytes -> { });
    }

    /**
     * Like {@link #importFile(Path, UUID)}, reporting source bytes encrypted so far.
     * A source inside the vault folder (its blobs and metadata) is refused.
     */
    public ManifestEntry importFile(Path source, UUID parentFolderId, LongConsumer progress)
            throws FileServiceException {

        // An unreadable source is reported first: a link without a target has no real path to check.
        requireReadableFile(source);

        // Resolved once, so the check and the read look at the same place; the name stays the one chosen.
        return importChecked(
                requireOutsideVault(source, false), source.getFileName().toString(), parentFolderId, progress);
    }

    private static void requireReadableFile(Path source) throws FileServiceException {
        if (!Files.isRegularFile(source) || !Files.isReadable(source)) {
            throw new FileServiceException(FileServiceException.Reason.SOURCE_UNREADABLE);
        }
    }

    /** The import itself, for a source that is already known to be a readable file outside the vault. */
    private ManifestEntry importChecked(Path source, String name, UUID parentFolderId, LongConsumer progress)
            throws FileServiceException {

        return withUserMasterKey(key -> {
            // Fail before encrypting; the name is checked again at commit time.
            new ManifestService(load(key)).requireAvailableName(parentFolderId, name);

            UUID fileId = UUID.randomUUID();
            UUID blobId = UUID.randomUUID();
            byte[] fileKey = randomBytes(CryptoConstants.KEY_BYTES);
            byte[] contentNonce = randomBytes(CryptoConstants.GCM_NONCE_BYTES);

            try {
                long plainSize = writeBlob(source, blobId, fileId, fileKey, contentNonce, progress);

                try {
                    return modify(manifest -> {
                        ManifestEntry entry = new ManifestEntry(
                                fileId,
                                ManifestEntryKind.FILE,
                                parentFolderId,
                                new ManifestService(manifest).requireAvailableName(parentFolderId, name),
                                Instant.now().toString()
                        );
                        entry.setContent(
                                plainSize,
                                blobId,
                                aes.wrapKey(fileKey, key, Aad.fileKey(vaultId(), userId(), fileId.toString())),
                                Base64.getEncoder().encodeToString(contentNonce)
                        );
                        manifest.getEntries().add(entry);
                        return entry;
                    });

                } catch (FileServiceException | RuntimeException e) {
                    deleteBlobQuietly(blobId);
                    throw e;
                }

            } finally {
                Arrays.fill(fileKey, (byte) 0);
            }
        });
    }

    /** A file or folder of a folder import that was not imported, and why. */
    public record ImportFailure(String path, FileServiceException.Reason reason) {
    }

    /** What a folder import did; {@code stopped} means the vault could not be written and the rest was skipped. */
    public record FolderImport(
            int foldersCreated,
            int filesImported,
            int linksSkipped,
            List<ImportFailure> failures,
            boolean stopped
    ) {
    }

    /** Folder-import progress: files finished and source bytes encrypted so far. */
    @FunctionalInterface
    public interface ImportProgress {
        void update(int filesDone, int filesTotal, long bytesDone, long bytesTotal);
    }

    /**
     * Imports the directory tree at {@code source} as a new folder with the
     * same name under {@code parentFolderId}, including empty folders. The
     * tree is scanned first without following links ({@link SourceTree}). A
     * name already used in the destination aborts before anything is
     * imported; a name that clashes inside the tree skips only that item.
     * Folders and files are committed one manifest change at a time, so an
     * interruption leaves a valid, partly imported tree and never a
     * referenced partial blob. The source is never modified.
     */
    public FolderImport importFolder(Path source, UUID parentFolderId, ImportProgress progress)
            throws FileServiceException {

        // Resolved once, so the overlap check and the scan look at the same place.
        Path real = requireOutsideVault(source, true);
        SourceTree tree = SourceTree.scan(real);
        // Fails before anything is created; checked again when the folder is committed.
        withUserMasterKey(key -> new ManifestService(load(key))
                .requireAvailableName(parentFolderId, tree.root().name()));

        FolderImportRun run = new FolderImportRun(tree, progress);
        run.importDirectory(tree.root(), parentFolderId);
        return run.result();
    }

    /**
     * Imports must not read the vault itself, and exports must not write
     * plaintext into it (it may be a synced folder). With
     * {@code refuseAncestors}, a path that contains the vault is refused too.
     * A path whose real location cannot be determined is refused with
     * {@code STORAGE}. Returns the resolved path that was checked, which is
     * the one the caller should go on to use.
     */
    private Path requireOutsideVault(Path path, boolean refuseAncestors) throws FileServiceException {
        try {
            Path vaultRoot = canonical(vault.root());
            Path candidate = canonical(path);

            if (candidate.startsWith(vaultRoot)
                    || within(candidate, vaultRoot)
                    || (refuseAncestors && (vaultRoot.startsWith(candidate) || within(vaultRoot, candidate)))) {
                throw new FileServiceException(FileServiceException.Reason.INSIDE_VAULT);
            }

            return candidate;

        } catch (IOException e) {
            throw new FileServiceException(FileServiceException.Reason.STORAGE, e);
        }
    }

    /**
     * Whether {@code path} or one of its existing ancestors is the same folder
     * as {@code container} under another spelling. A share alias of a local
     * drive (\\localhost\C$) keeps its own form in real paths, so comparing
     * prefixes is not enough.
     */
    private static boolean within(Path path, Path container) throws IOException {
        if (!Files.exists(container)) {
            return false;
        }

        for (Path ancestor = path; ancestor != null; ancestor = ancestor.getParent()) {
            if (Files.exists(ancestor) && Files.isSameFile(ancestor, container)) {
                return true;
            }
        }

        return false;
    }

    /**
     * The real path of the nearest existing ancestor, with the rest appended.
     * A link whose target is missing exists but has no real path, and throws.
     */
    private static Path canonical(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path existing = absolute;

        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }

        return existing == null ? absolute : existing.toRealPath().resolve(existing.relativize(absolute));
    }

    /** One folder import; per-item failures are collected instead of thrown. */
    private final class FolderImportRun {

        private final SourceTree tree;
        private final ImportProgress progress;
        private final List<ImportFailure> failures = new ArrayList<>();
        private int folders;
        private int files;
        private int filesDone;
        private long bytesDone;
        private boolean stopped;

        FolderImportRun(SourceTree tree, ImportProgress progress) {
            this.tree = tree;
            this.progress = progress;

            for (String path : tree.unreadable()) {
                failures.add(new ImportFailure(path, FileServiceException.Reason.SOURCE_UNREADABLE));
            }
        }

        void importDirectory(SourceTree.Node directory, UUID parentId) {
            ManifestEntry folder;

            try {
                folder = createFolder(directory.name(), parentId);
                folders++;
            } catch (FileServiceException e) {
                fail(directory, e);
                skip(directory);
                return;
            }

            for (SourceTree.Node child : directory.children()) {
                if (stopped) {
                    return;
                }

                if (child.directory()) {
                    importDirectory(child, folder.getEntryId());
                } else {
                    importOne(child, folder.getEntryId());
                }
            }
        }

        private void importOne(SourceTree.Node file, UUID folderId) {
            long before = bytesDone;

            try {
                // The tree can change after the scan. A swap in the instant between
                // this check and the open inside importChecked would still be followed.
                // The folder's root was checked against the vault, so its files are not walked again.
                if (!SourceTree.isUnlinkedFile(file.path())) {
                    throw new FileServiceException(FileServiceException.Reason.SOURCE_UNREADABLE);
                }

                requireReadableFile(file.path());
                importChecked(file.path(), file.name(), folderId, bytes ->
                        progress.update(filesDone, tree.fileCount(), before + bytes, tree.totalBytes()));
                files++;
            } catch (FileServiceException e) {
                fail(file, e);
            }

            filesDone++;
            bytesDone = before + file.size();
            progress.update(filesDone, tree.fileCount(), bytesDone, tree.totalBytes());
        }

        /** Counts the files of a skipped subtree as done, so progress still reaches the total. */
        private void skip(SourceTree.Node directory) {
            for (SourceTree.Node child : directory.children()) {
                if (child.directory()) {
                    skip(child);
                } else {
                    filesDone++;
                    bytesDone += child.size();
                }
            }

            progress.update(filesDone, tree.fileCount(), bytesDone, tree.totalBytes());
        }

        private void fail(SourceTree.Node node, FileServiceException e) {
            failures.add(new ImportFailure(tree.display(node.path()), e.getReason()));

            // A problem with one source item skips that item; anything else
            // means the vault cannot be written, so the import stops.
            stopped |= switch (e.getReason()) {
                case SOURCE_UNREADABLE, DUPLICATE_NAME, INVALID_NAME -> false;
                default -> true;
            };
        }

        FolderImport result() {
            return new FolderImport(folders, files, tree.linksSkipped(), List.copyOf(failures), stopped);
        }
    }

    /**
     * Writes plaintext to {@code destination}: the file itself, or for a
     * folder a directory holding its active descendants. Existing files at the
     * destination are replaced, so callers confirm overwrites first. Each file
     * is decrypted to a partial file that is renamed only after the GCM tag
     * verifies. Destinations inside the vault folder are refused.
     */
    public void exportEntry(UUID entryId, Path destination) throws FileServiceException {
        // Resolved once, so the check and the writes look at the same place.
        Path resolved = requireOutsideVault(destination, false);

        withUserMasterKey(key -> {
            ManifestService rules = new ManifestService(load(key));
            ManifestEntry entry = rules.find(entryId);

            if (entry == null || entry.getDeletedAt() != null) {
                throw new FileServiceException(FileServiceException.Reason.NOT_FOUND);
            }

            export(rules, entry, resolved, key);
            return null;
        });
    }

    /**
     * Where {@link #exportEntry} should write each entry inside
     * {@code directory}: Windows-safe names, made unique among themselves.
     * A directory inside the vault folder is refused.
     */
    public List<Path> exportTargets(List<UUID> entryIds, Path directory)
            throws FileServiceException {

        Path resolved = requireOutsideVault(directory, false);

        return withUserMasterKey(key -> {
            ManifestService rules = new ManifestService(load(key));
            Set<String> usedNames = new HashSet<>();
            List<Path> targets = new ArrayList<>();

            for (UUID entryId : entryIds) {
                ManifestEntry entry = rules.find(entryId);

                if (entry == null || entry.getDeletedAt() != null) {
                    throw new FileServiceException(FileServiceException.Reason.NOT_FOUND);
                }

                targets.add(resolved.resolve(uniqueSafeName(entry, usedNames)));
            }

            return targets;
        });
    }

    /** Renames an active file or folder; metadata only, the blob is untouched. */
    public void rename(UUID entryId, String newName) throws FileServiceException {
        modify(manifest -> {
            new ManifestService(manifest).rename(entryId, newName);
            return null;
        });
    }

    /** Moves active entries into an active folder; metadata only, blobs are untouched. */
    public void move(List<UUID> entryIds, UUID destinationFolderId) throws FileServiceException {
        modify(manifest -> {
            new ManifestService(manifest).move(entryIds, destinationFolderId);
            return null;
        });
    }

    /** Every active folder, the root included, for choosing a move destination. */
    public List<ManifestEntry> activeFolders() throws FileServiceException {
        return withUserMasterKey(key -> load(key).getEntries().stream()
                .filter(entry -> entry.getKind() == ManifestEntryKind.FOLDER && entry.getDeletedAt() == null)
                .toList());
    }

    /** Soft delete: only the encrypted manifest changes. */
    public void moveToTrash(UUID entryId) throws FileServiceException {
        moveToTrash(List.of(entryId));
    }

    /**
     * Moves a whole selection to the trash as one manifest change: every entry
     * is checked first (the root, unknown and already trashed entries are
     * refused) and nothing changes if one fails. A selected entry that lies
     * inside another selected entry goes with it and is not a trash root of
     * its own, so restoring the outer entry brings it back.
     */
    public void moveToTrash(List<UUID> entryIds) throws FileServiceException {
        if (entryIds.isEmpty()) {
            return;
        }

        modify(manifest -> {
            ManifestService rules = new ManifestService(manifest);
            List<ManifestEntry> selected = new ArrayList<>();

            for (UUID entryId : entryIds) {
                selected.add(rules.requireMovable(entryId));
            }

            Map<UUID, List<ManifestEntry>> children = childrenByParent(manifest);
            Map<UUID, List<ManifestEntry>> subtrees = new HashMap<>();
            Set<UUID> inside = new HashSet<>();

            for (ManifestEntry entry : selected) {
                List<ManifestEntry> subtree = subtree(children, entry);
                subtrees.put(entry.getEntryId(), subtree);
                subtree.stream().skip(1).forEach(item -> inside.add(item.getEntryId()));
            }

            String deletedAt = Instant.now().toString();

            for (ManifestEntry entry : selected) {
                if (inside.contains(entry.getEntryId())) {
                    continue;
                }

                entry.setOriginalParentId(entry.getParentId());

                for (ManifestEntry item : subtrees.get(entry.getEntryId())) {
                    if (item.getDeletedAt() == null) {
                        item.setDeletedAt(deletedAt);
                    }
                }
            }

            return null;
        });
    }

    /**
     * Restores a trashed entry and everything trashed with it into its
     * original folder, or into the root when that folder is gone. The entry is
     * renamed if its name has been taken meanwhile.
     */
    public void restore(UUID entryId) throws FileServiceException {
        modify(manifest -> {
            ManifestService rules = new ManifestService(manifest);
            ManifestEntry entry = rules.find(entryId);

            if (entry == null || !isTrashRoot(entry)) {
                throw new FileServiceException(FileServiceException.Reason.NOT_FOUND);
            }

            ManifestEntry original = rules.find(entry.getOriginalParentId());
            UUID target = original != null
                    && original.getKind() == ManifestEntryKind.FOLDER
                    && original.getDeletedAt() == null
                    ? original.getEntryId()
                    : manifest.getRootFolderId();
            String deletedAt = entry.getDeletedAt();

            for (ManifestEntry item : subtree(childrenByParent(manifest), entry)) {
                if (deletedAt.equals(item.getDeletedAt())) {
                    item.setDeletedAt(null);
                }
            }

            entry.setOriginalParentId(null);
            entry.setParentId(target);
            entry.setName(availableName(rules, target, entry));
            return null;
        });
    }

    /**
     * Permanently deletes trashed entries and everything below them. The
     * entries leave the manifest and every backup generation before any blob
     * is touched; their blobs are queued in the encrypted manifest and removed
     * afterwards. A crash can orphan ciphertext but never leaves metadata that
     * references deleted ciphertext. Returns how many blobs are still queued
     * because they could not be removed yet.
     */
    public int permanentlyDelete(List<UUID> entryIds) throws FileServiceException {
        synchronized (MANIFEST_LOCK) {
            return withUserMasterKey(key -> deletePermanently(load(key), entryIds, key));
        }
    }

    /**
     * Removes blobs left queued by an interrupted or partly failed permanent
     * delete. Blobs that still cannot be removed stay queued; returns how many.
     */
    public int resumePendingDeletions() throws FileServiceException {
        synchronized (MANIFEST_LOCK) {
            return withUserMasterKey(key -> {
                UserManifest manifest = load(key);

                if (manifest.getPendingDeletions().isEmpty()) {
                    return 0;
                }

                // The delete may have stopped before every backup was reseeded.
                checkpoint(manifest, key);
                return purge(manifest, key);
            });
        }
    }

    /**
     * Permanently deletes everything in the trash through {@link #permanentlyDelete}'s
     * sequence, retrying any blobs still queued from earlier. Returns how many
     * blobs are still queued.
     */
    public int emptyTrash() throws FileServiceException {
        synchronized (MANIFEST_LOCK) {
            return withUserMasterKey(key -> {
                UserManifest manifest = load(key);
                // Trash roots are enough: the engine takes in everything below each of them.
                List<UUID> trash = manifest.getEntries().stream()
                        .filter(FileService::isTrashRoot)
                        .map(ManifestEntry::getEntryId)
                        .toList();

                return deletePermanently(manifest, trash, key);
            });
        }
    }

    /** Spec 9.2: steps 1-5 here, steps 6-9 in {@link #purge}. */
    private int deletePermanently(UserManifest manifest, Collection<UUID> entryIds, byte[] key)
            throws FileServiceException {

        // Both tables are built once: a trash can hold more entries than a scan per entry could cover.
        Map<UUID, ManifestEntry> byId = new HashMap<>();
        manifest.getEntries().forEach(entry -> byId.put(entry.getEntryId(), entry));
        Map<UUID, List<ManifestEntry>> children = childrenByParent(manifest);
        Set<ManifestEntry> doomed = new LinkedHashSet<>();

        for (UUID entryId : entryIds) {
            ManifestEntry entry = byId.get(entryId);

            if (entry == null) {
                throw new FileServiceException(FileServiceException.Reason.NOT_FOUND);
            }

            if (entry.getDeletedAt() == null) {
                throw new FileServiceException(FileServiceException.Reason.NOT_IN_TRASH);
            }

            doomed.addAll(subtree(children, entry));
        }

        queueBlobs(manifest, doomed);
        manifest.getEntries().removeAll(doomed);
        checkpoint(manifest, key);
        return purge(manifest, key);
    }

    /**
     * Deletes queued blobs one by one, then checkpoints the shorter queue.
     * Call only right after a successful {@link #checkpoint} of this manifest:
     * that is what guarantees no backup still lists the blobs. Storage
     * failures are reported through the return value, never thrown.
     */
    private int purge(UserManifest manifest, byte[] key) {
        List<PendingDeletion> pending = manifest.getPendingDeletions();
        Set<UUID> inUse = manifest.getEntries().stream()
                .map(ManifestEntry::getBlobId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<PendingDeletion> remaining = new ArrayList<>();

        for (PendingDeletion deletion : pending) {
            // A blob a live entry still uses is dropped from the queue, never deleted.
            if (!inUse.contains(deletion.blobId()) && !deleteBlob(deletion.blobId())) {
                remaining.add(deletion);
            }
        }

        if (remaining.size() == pending.size()) {
            return remaining.size();
        }

        int recorded = pending.size();
        pending.clear();
        pending.addAll(remaining);

        try {
            checkpoint(manifest, key);
            return remaining.size();
        } catch (FileServiceException e) {
            // Deleted blobs stay listed on disk; the next retry finds them missing.
            return recorded;
        }
    }

    private static void queueBlobs(UserManifest manifest, Collection<ManifestEntry> doomed) {
        Set<UUID> queued = new HashSet<>();

        for (PendingDeletion deletion : manifest.getPendingDeletions()) {
            queued.add(deletion.blobId());
        }

        String queuedAt = Instant.now().toString();

        for (ManifestEntry entry : doomed) {
            if (entry.getKind() == ManifestEntryKind.FILE && queued.add(entry.getBlobId())) {
                manifest.getPendingDeletions().add(new PendingDeletion(entry.getBlobId(), queuedAt));
            }
        }
    }

    /** Missing blobs count as deleted (BlobRepository.delete is deleteIfExists). */
    private boolean deleteBlob(UUID blobId) {
        try {
            blobRepository.delete(vault.root(), blobId);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Spec 9.2 steps 4-5 (and 8-9): the manifest, then every backup generation. */
    private void checkpoint(UserManifest manifest, byte[] key) throws FileServiceException {
        try {
            manifestRepository.saveCheckpoint(manifest, identity.manifestId(), key);
        } catch (VaultStorageException e) {
            throw storageFailure(e);
        }
    }

    public WorkspaceStats stats() throws FileServiceException {
        return withUserMasterKey(key -> {
            int activeFiles = 0;
            long activeBytes = 0;
            long encryptedBytes = 0;
            int trash = 0;

            UserManifest manifest = load(key);

            for (ManifestEntry entry : manifest.getEntries()) {
                if (isTrashRoot(entry)) {
                    trash++;
                }

                if (entry.getKind() == ManifestEntryKind.FILE) {
                    encryptedBytes += blobSize(entry.getBlobId());

                    if (entry.getDeletedAt() == null) {
                        activeFiles++;
                        activeBytes += entry.getPlainSize();
                    }
                }
            }

            return new WorkspaceStats(
                    activeFiles, activeBytes, encryptedBytes, trash, manifest.getPendingDeletions().size()
            );
        });
    }

    /**
     * A file or folder name Windows accepts: reserved characters and control
     * characters become {@code _}, trailing dots and spaces are dropped, and
     * device names such as {@code CON} get a {@code _} prefix.
     */
    public static String safeFileName(String name) {
        String safe = UNSAFE_CHARACTERS.matcher(name).replaceAll("_").replaceAll("[. ]+$", "");

        if (safe.isEmpty()) {
            return "_";
        }

        return RESERVED_NAMES.matcher(safe).matches() ? "_" + safe : safe;
    }

    // ---------------------------------------------------------------- import

    private long writeBlob(
            Path source,
            UUID blobId,
            UUID fileId,
            byte[] fileKey,
            byte[] contentNonce,
            LongConsumer progress
    ) throws FileServiceException {

        Path part = null;

        try {
            part = blobRepository.newPart(vault.root(), blobId);
            long plainSize = crypto.encrypt(
                    source,
                    part,
                    fileKey,
                    contentNonce,
                    Aad.fileContent(vaultId(), userId(), fileId.toString()),
                    progress
            ).plainSize();
            blobRepository.commit(vault.root(), blobId);
            return plainSize;

        } catch (StreamingFileCryptoService.SourceReadException e) {
            deleteQuietly(part);
            throw new FileServiceException(FileServiceException.Reason.SOURCE_UNREADABLE, e);
        } catch (IOException e) {
            deleteQuietly(part);
            throw new FileServiceException(
                    Files.isReadable(source)
                            ? FileServiceException.Reason.STORAGE
                            : FileServiceException.Reason.SOURCE_UNREADABLE,
                    e
            );
        }
    }

    // ---------------------------------------------------------------- export

    private void export(
            ManifestService rules,
            ManifestEntry entry,
            Path requested,
            byte[] key
    ) throws FileServiceException {

        if (entry.getKind() == ManifestEntryKind.FILE) {
            exportFile(entry, requested, key);
            return;
        }

        try {
            Files.createDirectories(requested);
        } catch (IOException e) {
            throw new FileServiceException(FileServiceException.Reason.STORAGE, e);
        }

        // A folder that already existed may be a junction or link into the vault.
        Path target = requireOutsideVault(requested, false);
        Set<String> usedNames = new HashSet<>();

        for (ManifestEntry child : rules.listChildren(entry.getEntryId(), false).stream()
                .sorted(FOLDERS_THEN_NAME)
                .toList()) {

            Path childTarget = target.resolve(uniqueSafeName(child, usedNames)).normalize();

            if (!childTarget.getParent().equals(target)) {
                throw new FileServiceException(FileServiceException.Reason.INVALID_NAME);
            }

            export(rules, child, childTarget, key);
        }
    }

    private void exportFile(ManifestEntry entry, Path destination, byte[] key)
            throws FileServiceException {

        Path blob = blobRepository.blobPath(vault.root(), entry.getBlobId());
        byte[] fileKey = null;
        Path part = null;

        try {
            if (!Files.isRegularFile(blob)) {
                throw new FileServiceException(FileServiceException.Reason.INTEGRITY);
            }

            fileKey = aes.unwrapKey(
                    entry.getWrappedFileKey(),
                    key,
                    Aad.fileKey(vaultId(), userId(), entry.getEntryId().toString())
            );
            byte[] contentNonce = Base64.getDecoder().decode(entry.getContentNonce());

            Files.createDirectories(destination.getParent());
            part = Files.createTempFile(
                    destination.getParent(),
                    destination.getFileName() + ".",
                    ".part"
            );
            crypto.decrypt(
                    blob,
                    part,
                    fileKey,
                    contentNonce,
                    Aad.fileContent(vaultId(), userId(), entry.getEntryId().toString())
            );
            moveIntoPlace(part, destination);

        } catch (CryptoException | IllegalArgumentException e) {
            throw new FileServiceException(FileServiceException.Reason.INTEGRITY, e);
        } catch (IOException e) {
            throw new FileServiceException(FileServiceException.Reason.STORAGE, e);
        } finally {
            if (fileKey != null) {
                Arrays.fill(fileKey, (byte) 0);
            }

            deleteQuietly(part);
        }
    }

    private static void moveIntoPlace(Path part, Path destination) throws IOException {
        try {
            Files.move(
                    part,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(part, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // --------------------------------------------------------------- helpers

    @FunctionalInterface
    private interface KeyedWork<T> {
        T run(byte[] userMasterKey) throws FileServiceException;
    }

    @FunctionalInterface
    private interface ManifestChange<T> {
        T apply(UserManifest manifest) throws FileServiceException;
    }

    private <T> T withUserMasterKey(KeyedWork<T> work) throws FileServiceException {
        try (SensitiveBytes holder = userMasterKey.get()) {
            byte[] key = holder.copy();

            try {
                return work.run(key);
            } finally {
                Arrays.fill(key, (byte) 0);
            }
        }
    }

    /** Load, change, and save the manifest as one step for this process. */
    private <T> T modify(ManifestChange<T> change) throws FileServiceException {
        synchronized (MANIFEST_LOCK) {
            return withUserMasterKey(key -> {
                UserManifest manifest = load(key);
                T result = change.apply(manifest);
                save(manifest, key);
                return result;
            });
        }
    }

    /**
     * The only caller of {@link ManifestRepository#load}: recovery inside it
     * may copy a backup over the live file, so it must never overlap a save.
     */
    private UserManifest load(byte[] key) throws FileServiceException {
        // Never read while this process is replacing the manifest.
        synchronized (MANIFEST_LOCK) {
            // A read that waited for the lock must not recover files of a vault closed meanwhile.
            if (vault.isClosed()) {
                throw new FileServiceException(FileServiceException.Reason.STORAGE);
            }

            try {
                return manifestRepository.load(identity.userId(), identity.manifestId(), key);
            } catch (VaultStorageException e) {
                throw new FileServiceException(
                        e.getReason() == VaultStorageException.Reason.IO
                                ? FileServiceException.Reason.STORAGE
                                : FileServiceException.Reason.CORRUPTED,
                        e
                );
            }
        }
    }

    private void save(UserManifest manifest, byte[] key) throws FileServiceException {
        try {
            manifestRepository.save(manifest, identity.manifestId(), key);
        } catch (VaultStorageException e) {
            throw storageFailure(e);
        }
    }

    private static FileServiceException storageFailure(VaultStorageException e) {
        return new FileServiceException(
                e.getReason() == VaultStorageException.Reason.TOO_LARGE
                        ? FileServiceException.Reason.LIMIT
                        : FileServiceException.Reason.STORAGE,
                e
        );
    }

    /** Every entry's children (trashed or not) by parent id, from one pass over the manifest. */
    private static Map<UUID, List<ManifestEntry>> childrenByParent(UserManifest manifest) {
        Map<UUID, List<ManifestEntry>> children = new HashMap<>();

        for (ManifestEntry entry : manifest.getEntries()) {
            if (entry.getParentId() != null) {
                children.computeIfAbsent(entry.getParentId(), id -> new ArrayList<>()).add(entry);
            }
        }

        return children;
    }

    /** The entry and all of its descendants, trashed or not; {@code children} is {@link #childrenByParent}. */
    private static List<ManifestEntry> subtree(Map<UUID, List<ManifestEntry>> children, ManifestEntry top) {
        List<ManifestEntry> result = new ArrayList<>();
        Set<UUID> visited = new HashSet<>();
        LinkedList<ManifestEntry> pending = new LinkedList<>(List.of(top));

        while (!pending.isEmpty()) {
            ManifestEntry entry = pending.removeFirst();

            if (visited.add(entry.getEntryId())) {
                result.add(entry);
                pending.addAll(children.getOrDefault(entry.getEntryId(), List.of()));
            }
        }

        return result;
    }

    private static boolean isTrashRoot(ManifestEntry entry) {
        return entry.getDeletedAt() != null && entry.getOriginalParentId() != null;
    }

    private static String availableName(ManifestService rules, UUID folderId, ManifestEntry entry) {
        Set<String> taken = rules.listChildren(folderId, false).stream()
                .filter(sibling -> sibling != entry)
                .map(sibling -> sibling.getName().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        boolean folder = entry.getKind() == ManifestEntryKind.FOLDER;
        String candidate = entry.getName();

        for (int copy = 2; taken.contains(candidate.toLowerCase(Locale.ROOT)); copy++) {
            candidate = withCopyNumber(entry.getName(), copy, folder);
        }

        return candidate;
    }

    /** A Windows-safe name for the entry that is not yet in usedNames (case-insensitive). */
    private static String uniqueSafeName(ManifestEntry entry, Set<String> usedNames) {
        String safe = safeFileName(entry.getName());
        boolean folder = entry.getKind() == ManifestEntryKind.FOLDER;
        String name = safe;

        for (int copy = 2; !usedNames.add(name.toLowerCase(Locale.ROOT)); copy++) {
            name = withCopyNumber(safe, copy, folder);
        }

        return name;
    }

    /** "report.pdf" becomes "report (2).pdf"; folders get the suffix at the end. */
    private static String withCopyNumber(String name, int copy, boolean folder) {
        int dot = name.lastIndexOf('.');

        return folder || dot <= 0
                ? name + " (" + copy + ")"
                : name.substring(0, dot) + " (" + copy + ")" + name.substring(dot);
    }

    private long blobSize(UUID blobId) throws FileServiceException {
        try {
            return blobRepository.size(vault.root(), blobId);
        } catch (IOException e) {
            throw new FileServiceException(FileServiceException.Reason.STORAGE, e);
        }
    }

    private void deleteBlobQuietly(UUID blobId) {
        try {
            blobRepository.delete(vault.root(), blobId);
        } catch (IOException ignored) {
            // An unreferenced blob is unreadable ciphertext; leaving it is harmless.
        }
    }

    private static void deleteQuietly(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // Best effort; stale partial files are cleaned up on vault open.
            }
        }
    }

    private String vaultId() {
        return vault.vaultId();
    }

    private String userId() {
        return identity.userId().toString();
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
