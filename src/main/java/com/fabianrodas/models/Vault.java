package com.fabianrodas.models;

public class Vault {

    private int formatVersion;
    private String id;
    private String name;
    private String createdAt;

    public Vault() {
    }

    public Vault(int formatVersion, String id, String name, String createdAt) {
        this.formatVersion = formatVersion;
        this.id = id;
        this.name = name;
        this.createdAt = createdAt;
    }

    public int getFormatVersion() {
        return formatVersion;
    }

    public void setFormatVersion(int formatVersion) {
        this.formatVersion = formatVersion;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}