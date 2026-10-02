package com.fabianrodas.repositories;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * An AtomicFileWriter whose n-th write from now fails. The registry checkpoint
 * performs four writes in order: backup 1, backup 2, backup 3, users.enc.
 */
public final class FailingWriter extends AtomicFileWriter {

    private int countdown;
    private boolean leavePartialTemp;

    /** The n-th write from now throws before anything is written. */
    public void failBeforeWrite(int nth) {
        countdown = nth;
        leavePartialTemp = false;
    }

    /** The n-th write from now fails part-way, leaving a stray temp file like an interrupted write. */
    public void failDuringWrite(int nth) {
        countdown = nth;
        leavePartialTemp = true;
    }

    @Override
    public void write(Path destination, byte[] bytes) throws IOException {
        if (countdown > 0 && --countdown == 0) {
            if (leavePartialTemp) {
                Files.write(destination.resolveSibling(destination.getFileName() + ".123.tmp"), new byte[]{1});
            }

            throw new IOException("simulated write failure for " + destination.getFileName());
        }

        super.write(destination, bytes);
    }
}
