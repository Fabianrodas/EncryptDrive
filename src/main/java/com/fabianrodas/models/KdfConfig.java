package com.fabianrodas.models;

/**
 * Argon2id parameters persisted next to each wrapped key. The salt is
 * Base64; none of these values are secret.
 */
public final class KdfConfig {

    private String algorithm;
    private int memoryKiB;
    private int iterations;
    private int parallelism;
    private String salt;

    public KdfConfig() {
    }

    public KdfConfig(
            String algorithm,
            int memoryKiB,
            int iterations,
            int parallelism,
            String salt
    ) {
        this.algorithm = algorithm;
        this.memoryKiB = memoryKiB;
        this.iterations = iterations;
        this.parallelism = parallelism;
        this.salt = salt;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public int getMemoryKiB() {
        return memoryKiB;
    }

    public int getIterations() {
        return iterations;
    }

    public int getParallelism() {
        return parallelism;
    }

    public String getSalt() {
        return salt;
    }
}
