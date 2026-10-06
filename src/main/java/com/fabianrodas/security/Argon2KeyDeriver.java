package com.fabianrodas.security;

import com.fabianrodas.models.KdfConfig;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

public final class Argon2KeyDeriver {

    public static final String ALGORITHM = "Argon2id";

    private static final int MAX_MEMORY_KIB = 1_048_576;
    private static final int MAX_ITERATIONS = 10;
    private static final int MAX_PARALLELISM = 8;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Production parameters with a fresh random salt. */
    public KdfConfig newConfig() {
        byte[] salt = new byte[CryptoConstants.SALT_BYTES];
        RANDOM.nextBytes(salt);

        return new KdfConfig(
                ALGORITHM,
                CryptoConstants.ARGON_MEMORY_KIB,
                CryptoConstants.ARGON_ITERATIONS,
                CryptoConstants.ARGON_PARALLELISM,
                Base64.getEncoder().encodeToString(salt)
        );
    }

    /**
     * Derives from stored parameters. Stored configs may come from untrusted
     * plaintext, so anything outside the supported bounds is rejected before
     * memory is allocated.
     */
    public byte[] derive(char[] password, KdfConfig kdf) {
        if (kdf == null
                || !ALGORITHM.equals(kdf.getAlgorithm())
                || kdf.getParallelism() < 1
                || kdf.getParallelism() > MAX_PARALLELISM
                || kdf.getMemoryKiB() < 8 * kdf.getParallelism()
                || kdf.getMemoryKiB() > MAX_MEMORY_KIB
                || kdf.getIterations() < 1
                || kdf.getIterations() > MAX_ITERATIONS
                || kdf.getSalt() == null) {
            throw new IllegalArgumentException("Unsupported key derivation parameters.");
        }

        byte[] salt = Base64.getDecoder().decode(kdf.getSalt());

        if (salt.length != CryptoConstants.SALT_BYTES) {
            throw new IllegalArgumentException("Unsupported key derivation parameters.");
        }

        return derive(
                password,
                salt,
                kdf.getMemoryKiB(),
                kdf.getIterations(),
                kdf.getParallelism()
        );
    }

    public byte[] derive(
            char[] password,
            byte[] salt,
            int memoryKiB,
            int iterations,
            int parallelism
    ) {
        if (password == null || salt == null) {
            throw new IllegalArgumentException("Password and salt are required.");
        }

        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
        byte[] passwordBytes = new byte[encoded.remaining()];
        encoded.get(passwordBytes);

        try {
            Argon2BytesGenerator generator = new Argon2BytesGenerator();
            generator.init(new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withSalt(salt)
                    .withMemoryAsKB(memoryKiB)
                    .withIterations(iterations)
                    .withParallelism(parallelism)
                    .build());

            byte[] key = new byte[CryptoConstants.KEY_BYTES];
            generator.generateBytes(passwordBytes, key);
            return key;

        } finally {
            Arrays.fill(passwordBytes, (byte) 0);
            Arrays.fill(encoded.array(), (byte) 0);
        }
    }
}
