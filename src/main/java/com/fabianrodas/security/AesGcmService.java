package com.fabianrodas.security;

import com.fabianrodas.models.EncryptedPayload;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class AesGcmService {

    public static final String ALGORITHM = "AES/GCM/NoPadding";
    public static final int PAYLOAD_VERSION = 1;

    private static final SecureRandom RANDOM = new SecureRandom();

    public EncryptedPayload encrypt(byte[] plaintext, byte[] key, byte[] aad) {
        byte[] nonce = new byte[CryptoConstants.GCM_NONCE_BYTES];
        RANDOM.nextBytes(nonce);

        try {
            byte[] ciphertext = cipher(Cipher.ENCRYPT_MODE, key, nonce, aad)
                    .doFinal(plaintext);

            return new EncryptedPayload(
                    PAYLOAD_VERSION,
                    ALGORITHM,
                    Base64.getEncoder().encodeToString(nonce),
                    Base64.getEncoder().encodeToString(ciphertext)
            );

        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM is unavailable.", e);
        }
    }

    public byte[] decrypt(EncryptedPayload payload, byte[] key, byte[] aad)
            throws CryptoException {

        if (payload == null
                || payload.getVersion() != PAYLOAD_VERSION
                || !ALGORITHM.equals(payload.getAlgorithm())) {
            throw new CryptoException();
        }

        byte[] nonce = decode(payload.getNonce());
        byte[] ciphertext = decode(payload.getCiphertext());

        if (nonce.length != CryptoConstants.GCM_NONCE_BYTES
                || ciphertext.length < CryptoConstants.GCM_TAG_BITS / 8) {
            throw new CryptoException();
        }

        try {
            return cipher(Cipher.DECRYPT_MODE, key, nonce, aad).doFinal(ciphertext);

        } catch (GeneralSecurityException e) {
            throw new CryptoException();
        }
    }

    public EncryptedPayload wrapKey(
            byte[] rawKeyToWrap,
            byte[] wrappingKey,
            byte[] aad
    ) {
        requireKey(rawKeyToWrap);
        return encrypt(rawKeyToWrap, wrappingKey, aad);
    }

    public byte[] unwrapKey(
            EncryptedPayload wrappedKey,
            byte[] wrappingKey,
            byte[] aad
    ) throws CryptoException {

        byte[] key = decrypt(wrappedKey, wrappingKey, aad);

        if (key.length != CryptoConstants.KEY_BYTES) {
            Arrays.fill(key, (byte) 0);
            throw new CryptoException();
        }

        return key;
    }

    private static Cipher cipher(int mode, byte[] key, byte[] nonce, byte[] aad)
            throws GeneralSecurityException {

        requireKey(key);

        if (aad == null) {
            throw new IllegalArgumentException("Associated data is required.");
        }

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(
                mode,
                new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(CryptoConstants.GCM_TAG_BITS, nonce)
        );
        cipher.updateAAD(aad);
        return cipher;
    }

    private static void requireKey(byte[] key) {
        if (key == null || key.length != CryptoConstants.KEY_BYTES) {
            throw new IllegalArgumentException("A 256-bit key is required.");
        }
    }

    private static byte[] decode(String base64) throws CryptoException {
        if (base64 == null) {
            throw new CryptoException();
        }

        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new CryptoException();
        }
    }
}
