package com.fabianrodas.security;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Security util class
 * 
 * @author Fabian Rodas
 */

public final class PasswordHasher {

    private static final int SALT_LENGTH = 16;
    private static final int ITERATIONS = 600_000;
    private static final int KEY_LENGTH_BITS = 256;

    private PasswordHasher() {
    }

    public static String generateSalt() {
        byte[] salt = new byte[SALT_LENGTH];

        SecureRandom random = new SecureRandom();
        random.nextBytes(salt);

        return Base64.getEncoder().encodeToString(salt);
    }

    public static String hash(String password, String base64Salt) {
        if (password == null || base64Salt == null) {
            throw new IllegalArgumentException(
                    "Password and salt are required."
            );
        }

        byte[] salt = Base64.getDecoder().decode(base64Salt);
        char[] passwordChars = password.toCharArray();

        PBEKeySpec spec = new PBEKeySpec(
                passwordChars,
                salt,
                ITERATIONS,
                KEY_LENGTH_BITS
        );

        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(
                    "PBKDF2WithHmacSHA256"
            );

            byte[] hash = factory.generateSecret(spec).getEncoded();

            return Base64.getEncoder().encodeToString(hash);

        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException(
                    "Could not process the password securely.",
                    e
            );

        } finally {
            spec.clearPassword();
            Arrays.fill(passwordChars, '\0');
        }
    }

    public static boolean matches(
            String password,
            String base64Salt,
            String storedHash
    ) {
        if (password == null || base64Salt == null || storedHash == null) {
            return false;
        }

        try {
            String generatedHash = hash(password, base64Salt);

            byte[] expectedHash = Base64.getDecoder().decode(storedHash);
            byte[] actualHash = Base64.getDecoder().decode(generatedHash);

            return MessageDigest.isEqual(expectedHash, actualHash);

        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}