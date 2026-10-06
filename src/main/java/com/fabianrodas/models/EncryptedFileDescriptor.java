package com.fabianrodas.models;

/**
 * Result of streaming one file into an encrypted blob.
 */
public record EncryptedFileDescriptor(long plainSize, long encryptedSize) {
}
