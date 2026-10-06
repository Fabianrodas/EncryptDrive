package com.fabianrodas.services;

import com.fabianrodas.repositories.VaultRepository;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;

/**
 * Exclusive process-level lock on {@code .encryptdrive/lock}. Locks held by
 * this JVM are also tracked in memory: opening a second channel to the same
 * file and closing it would drop the OS lock on POSIX systems.
 */
public final class VaultLockService {

    private static final Set<Path> HELD = new HashSet<>();

    public VaultLock acquire(Path vaultRoot) throws VaultException {
        Path lockFile;

        try {
            lockFile = VaultRepository.metaDir(vaultRoot)
                    .toRealPath()
                    .resolve(VaultRepository.LOCK_FILE);
        } catch (IOException e) {
            throw new VaultException(VaultException.Reason.STORAGE, e);
        }

        synchronized (HELD) {
            if (!HELD.add(lockFile)) {
                throw new VaultException(VaultException.Reason.BUSY);
            }
        }

        FileChannel channel = null;
        boolean locked = false;

        try {
            channel = FileChannel.open(
                    lockFile,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE
            );

            if (channel.tryLock() == null) {
                throw new VaultException(VaultException.Reason.BUSY);
            }

            locked = true;
            return new VaultLock(lockFile, channel);

        } catch (OverlappingFileLockException e) {
            throw new VaultException(VaultException.Reason.BUSY, e);
        } catch (IOException e) {
            throw new VaultException(VaultException.Reason.STORAGE, e);
        } finally {
            if (!locked) {
                closeQuietly(channel);
                release(lockFile);
            }
        }
    }

    private static void release(Path lockFile) {
        synchronized (HELD) {
            HELD.remove(lockFile);
        }
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // Closing the channel is best effort; the lock dies with it.
            }
        }
    }

    public static final class VaultLock implements AutoCloseable {

        private final Path lockFile;
        private final FileChannel channel;

        private VaultLock(Path lockFile, FileChannel channel) {
            this.lockFile = lockFile;
            this.channel = channel;
        }

        /** Releases the OS lock by closing its channel. Idempotent. */
        @Override
        public void close() {
            closeQuietly(channel);
            release(lockFile);
        }
    }
}
