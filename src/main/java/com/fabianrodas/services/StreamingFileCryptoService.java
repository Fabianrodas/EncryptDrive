package com.fabianrodas.services;

import com.fabianrodas.models.EncryptedFileDescriptor;
import com.fabianrodas.security.CryptoConstants;
import com.fabianrodas.security.CryptoException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.modes.GCMModeCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;

/**
 * Streams file content through AES-256-GCM in 64 KiB chunks, so memory use
 * does not grow with file size. Output is standard GCM (ciphertext followed
 * by the 128-bit tag). Bouncy Castle's GCM is used because the JDK
 * implementation buffers the whole ciphertext while decrypting.
 *
 * Both methods write only to the given part file, which the caller owns and
 * renames on success; on any failure the part file is deleted.
 */
public class StreamingFileCryptoService {

    private static final int BUFFER_BYTES = 64 * 1024;

    public EncryptedFileDescriptor encrypt(
            Path source,
            Path destinationPart,
            byte[] fileKey,
            byte[] nonce,
            byte[] aad
    ) throws IOException {

        GCMModeCipher cipher = cipher(true, fileKey, nonce, aad);

        try {
            long plainSize = stream(cipher, source, destinationPart);
            return new EncryptedFileDescriptor(plainSize, Files.size(destinationPart));

        } catch (InvalidCipherTextException e) {
            throw new IllegalStateException("GCM encryption cannot fail authentication.", e);
        }
    }

    /**
     * Writes plaintext to the part file and fails with {@link CryptoException}
     * (deleting that file) unless the authentication tag verifies at the end.
     */
    public void decrypt(
            Path encryptedBlob,
            Path destinationPart,
            byte[] fileKey,
            byte[] nonce,
            byte[] aad
    ) throws IOException, CryptoException {

        try {
            stream(cipher(false, fileKey, nonce, aad), encryptedBlob, destinationPart);
        } catch (InvalidCipherTextException e) {
            throw new CryptoException();
        }
    }

    private static long stream(GCMModeCipher cipher, Path input, Path outputPart)
            throws IOException, InvalidCipherTextException {

        byte[] in = new byte[BUFFER_BYTES];
        byte[] out = new byte[BUFFER_BYTES + 2 * CryptoConstants.GCM_TAG_BITS / 8];
        long consumed = 0;
        boolean complete = false;

        try (InputStream source = Files.newInputStream(input);
                FileChannel target = FileChannel.open(
                        outputPart,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING
                )) {

            int read;

            while ((read = source.read(in)) != -1) {
                consumed += read;
                write(target, out, cipher.processBytes(in, 0, read, out, 0));
            }

            write(target, out, cipher.doFinal(out, 0));
            target.force(true);
            complete = true;
            return consumed;

        } finally {
            Arrays.fill(in, (byte) 0);
            Arrays.fill(out, (byte) 0);

            if (!complete) {
                Files.deleteIfExists(outputPart);
            }
        }
    }

    private static void write(FileChannel target, byte[] bytes, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, length);

        while (buffer.hasRemaining()) {
            target.write(buffer);
        }
    }

    private static GCMModeCipher cipher(
            boolean encrypt,
            byte[] fileKey,
            byte[] nonce,
            byte[] aad
    ) {
        if (fileKey == null
                || fileKey.length != CryptoConstants.KEY_BYTES
                || nonce == null
                || nonce.length != CryptoConstants.GCM_NONCE_BYTES
                || aad == null) {
            throw new IllegalArgumentException("A 256-bit key, 96-bit nonce, and AAD are required.");
        }

        GCMModeCipher cipher = GCMBlockCipher.newInstance(AESEngine.newInstance());
        cipher.init(encrypt, new AEADParameters(
                new KeyParameter(fileKey),
                CryptoConstants.GCM_TAG_BITS,
                nonce,
                aad
        ));
        return cipher;
    }
}
