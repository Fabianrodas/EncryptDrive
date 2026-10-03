package com.fabianrodas.services;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * A directory tree as it was when a folder import started. An entry whose
 * real path is not where it was found (a symbolic link, junction or mount
 * point) is counted and never followed, so an import cannot leave the
 * selected tree. Reparse points that do not redirect, such as OneDrive
 * placeholders, are ordinary entries.
 */
final class SourceTree {

    /** A directory with its children, or a regular file with its size. */
    record Node(Path path, String name, boolean directory, long size, List<Node> children) {
    }

    private final Path root;
    private final List<Path> unreadable = new ArrayList<>();
    private Node rootNode;
    private int fileCount;
    private long totalBytes;
    private int linksSkipped;

    private SourceTree(Path root) {
        this.root = root;
    }

    /** Scans the directory the user selected (resolved to its real location). */
    static SourceTree scan(Path source) throws FileServiceException {
        Path real;

        try {
            real = source.toRealPath();
        } catch (IOException e) {
            throw new FileServiceException(FileServiceException.Reason.SOURCE_UNREADABLE, e);
        }

        if (!Files.isDirectory(real)) {
            throw new FileServiceException(FileServiceException.Reason.SOURCE_UNREADABLE);
        }

        SourceTree tree = new SourceTree(real);
        tree.rootNode = tree.directory(real, nameOf(real));

        if (tree.rootNode == null) {
            throw new FileServiceException(FileServiceException.Reason.SOURCE_UNREADABLE);
        }

        return tree;
    }

    Node root() {
        return rootNode;
    }

    int fileCount() {
        return fileCount;
    }

    long totalBytes() {
        return totalBytes;
    }

    int linksSkipped() {
        return linksSkipped;
    }

    /** Entries that could not be read during the scan, as display paths. */
    List<String> unreadable() {
        return unreadable.stream().map(this::display).toList();
    }

    /** "Photos\2024\a.jpg" for an entry of the tree rooted at "Photos". */
    String display(Path path) {
        Path parent = root.getParent();
        return parent == null ? path.toString() : parent.relativize(path).toString();
    }

    // ponytail: recursion depth equals folder depth; fine below thousands of levels.
    private Node directory(Path directory, String name) {
        List<Path> entries;

        try (Stream<Path> listing = Files.list(directory)) {
            entries = listing.sorted().toList();
        } catch (IOException | UncheckedIOException e) {
            unreadable.add(directory);
            return null;
        }

        List<Node> children = new ArrayList<>();

        for (Path entry : entries) {
            Node child = node(entry);

            if (child != null) {
                children.add(child);
            }
        }

        return new Node(directory, name, true, 0, children);
    }

    private Node node(Path entry) {
        BasicFileAttributes attributes;

        try {
            attributes = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);

            if (attributes.isSymbolicLink() || !entry.toRealPath().equals(entry)) {
                linksSkipped++;
                return null;
            }
        } catch (IOException e) {
            unreadable.add(entry);
            return null;
        }

        String name = entry.getFileName().toString();

        if (attributes.isDirectory()) {
            return directory(entry, name);
        }

        fileCount++;
        totalBytes += attributes.size();
        return new Node(entry, name, false, attributes.size(), List.of());
    }

    private static String nameOf(Path real) {
        Path name = real.getFileName();
        // A drive root such as D:\ has no file name; use its letter.
        return name != null ? name.toString() : real.toString().replaceAll("[:\\\\/]", "");
    }
}
