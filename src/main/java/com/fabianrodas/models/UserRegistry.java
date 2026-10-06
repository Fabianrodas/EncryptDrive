package com.fabianrodas.models;

import java.util.List;

/**
 * Decrypted content of {@code users.enc}.
 */
public final class UserRegistry {

    private int formatVersion;
    private List<UserRecord> users;

    public UserRegistry() {
    }

    public UserRegistry(int formatVersion, List<UserRecord> users) {
        this.formatVersion = formatVersion;
        this.users = users;
    }

    public int getFormatVersion() {
        return formatVersion;
    }

    public List<UserRecord> getUsers() {
        return users;
    }
}
