package com.fabianrodas.security;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fabianrodas.models.EncryptedPayload;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class AesGcmServiceTest {

    private static final byte[] AAD
            = "EncryptDrive|users|v1|test-vault".getBytes(UTF_8);
    private static final byte[] PLAINTEXT
            = "secret registry".getBytes(UTF_8);

    private final AesGcmService aes = new AesGcmService();
    private final byte[] key = randomKey();

    @Test
    void encryptThenDecryptReturnsOriginalBytes() throws CryptoException {
        EncryptedPayload payload = aes.encrypt(PLAINTEXT, key, AAD);

        assertArrayEquals(PLAINTEXT, aes.decrypt(payload, key, AAD));
    }

    @Test
    void twoEncryptionsUseDifferentNonces() {
        EncryptedPayload first = aes.encrypt(PLAINTEXT, key, AAD);
        EncryptedPayload second = aes.encrypt(PLAINTEXT, key, AAD);

        assertNotEquals(first.getNonce(), second.getNonce());
        assertNotEquals(first.getCiphertext(), second.getCiphertext());
    }

    @Test
    void payloadUses96BitNonceAnd128BitTag() {
        EncryptedPayload payload = aes.encrypt(new byte[5], key, AAD);

        assertEquals(1, payload.getVersion());
        assertEquals("AES/GCM/NoPadding", payload.getAlgorithm());
        assertEquals(12, decode(payload.getNonce()).length);
        assertEquals(5 + 16, decode(payload.getCiphertext()).length);
    }

    /*
     * Vector produced independently with Python cryptography's AESGCM:
     * key 00..1f, nonce a0..ab, AAD "EncryptDrive|users|v1|kat".
     */
    @Test
    void decryptsIndependentKnownAnswerVector() throws CryptoException {
        EncryptedPayload vector = new EncryptedPayload(
                1,
                "AES/GCM/NoPadding",
                "oKGio6Slpqeoqaqr",
                "o3YfXzy7dvsQDPG2JxGusQfCdHH8xDUJ7i5W6h7CG3W3DjOdoCeyeaQxKhOXP+qB6VDQ"
        );
        byte[] vectorKey = HexFormat.of().parseHex(
                "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        );

        assertArrayEquals(
                "EncryptDrive known-answer plaintext".getBytes(UTF_8),
                aes.decrypt(vector, vectorKey, "EncryptDrive|users|v1|kat".getBytes(UTF_8))
        );
    }

    @Test
    void wrongKeyFailsAuthentication() {
        EncryptedPayload payload = aes.encrypt(PLAINTEXT, key, AAD);

        assertThrows(
                CryptoException.class,
                () -> aes.decrypt(payload, randomKey(), AAD)
        );
    }

    @Test
    void wrongAadFailsAuthentication() {
        EncryptedPayload payload = aes.encrypt(PLAINTEXT, key, AAD);
        byte[] otherVault = "EncryptDrive|users|v1|other-vault".getBytes(UTF_8);

        assertThrows(
                CryptoException.class,
                () -> aes.decrypt(payload, key, otherVault)
        );
    }

    @Test
    void modifiedCiphertextFailsAuthentication() {
        EncryptedPayload payload = aes.encrypt(PLAINTEXT, key, AAD);
        int length = decode(payload.getCiphertext()).length;

        for (int index : new int[]{0, length / 2, length - 1}) {
            EncryptedPayload tampered = new EncryptedPayload(
                    payload.getVersion(),
                    payload.getAlgorithm(),
                    payload.getNonce(),
                    flipByte(payload.getCiphertext(), index)
            );

            assertThrows(
                    CryptoException.class,
                    () -> aes.decrypt(tampered, key, AAD),
                    "byte " + index
            );
        }
    }

    @Test
    void modifiedNonceFailsAuthentication() {
        EncryptedPayload payload = aes.encrypt(PLAINTEXT, key, AAD);
        EncryptedPayload tampered = new EncryptedPayload(
                payload.getVersion(),
                payload.getAlgorithm(),
                flipByte(payload.getNonce(), 0),
                payload.getCiphertext()
        );

        assertThrows(CryptoException.class, () -> aes.decrypt(tampered, key, AAD));
    }

    @Test
    void malformedPayloadsFailClosed() {
        EncryptedPayload valid = aes.encrypt(PLAINTEXT, key, AAD);
        String algorithm = valid.getAlgorithm();
        String nonce = valid.getNonce();
        String ciphertext = valid.getCiphertext();

        List<EncryptedPayload> malformed = List.of(
                new EncryptedPayload(2, algorithm, nonce, ciphertext),
                new EncryptedPayload(1, "AES/CBC/PKCS5Padding", nonce, ciphertext),
                new EncryptedPayload(1, null, nonce, ciphertext),
                new EncryptedPayload(1, algorithm, null, ciphertext),
                new EncryptedPayload(1, algorithm, "not base64!", ciphertext),
                new EncryptedPayload(1, algorithm, encode(new byte[16]), ciphertext),
                new EncryptedPayload(1, algorithm, nonce, null),
                new EncryptedPayload(1, algorithm, nonce, encode(new byte[15]))
        );

        for (EncryptedPayload payload : malformed) {
            assertThrows(CryptoException.class, () -> aes.decrypt(payload, key, AAD));
        }
        assertThrows(CryptoException.class, () -> aes.decrypt(null, key, AAD));
    }

    @Test
    void wrapThenUnwrapKeyReturnsSameKey() throws CryptoException {
        byte[] fileKey = randomKey();

        EncryptedPayload wrapped = aes.wrapKey(fileKey, key, AAD);

        assertArrayEquals(fileKey, aes.unwrapKey(wrapped, key, AAD));
    }

    @Test
    void unwrapRejectsPayloadThatIsNotA256BitKey() {
        EncryptedPayload notAKey = aes.encrypt(new byte[16], key, AAD);

        assertThrows(CryptoException.class, () -> aes.unwrapKey(notAKey, key, AAD));
    }

    @Test
    void unwrapRejectsWrappedKeysOfTheWrongSize() {
        EncryptedPayload wrapped = aes.wrapKey(randomKey(), key, AAD);
        byte[] ciphertext = decode(wrapped.getCiphertext());

        for (int size : new int[]{0, 47, 49, 64}) {
            EncryptedPayload resized = new EncryptedPayload(
                    1, wrapped.getAlgorithm(), wrapped.getNonce(), encode(Arrays.copyOf(ciphertext, size))
            );
            assertThrows(CryptoException.class, () -> aes.unwrapKey(resized, key, AAD), "size " + size);
        }

        assertThrows(CryptoException.class, () -> aes.unwrapKey(null, key, AAD));
        assertThrows(CryptoException.class, () -> aes.unwrapKey(
                new EncryptedPayload(1, wrapped.getAlgorithm(), wrapped.getNonce(), "not base64!"), key, AAD
        ));
    }

    private static byte[] randomKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static String flipByte(String base64, int index) {
        byte[] bytes = decode(base64);
        bytes[index] ^= 0x01;
        return encode(bytes);
    }

    private static byte[] decode(String base64) {
        return Base64.getDecoder().decode(base64);
    }

    private static String encode(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}
