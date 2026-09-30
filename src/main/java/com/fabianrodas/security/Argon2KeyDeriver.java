package com.fabianrodas.security;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

public final class Argon2KeyDeriver {

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
