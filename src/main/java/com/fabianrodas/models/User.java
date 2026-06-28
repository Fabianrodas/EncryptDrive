package com.fabianrodas.models;

/**
 * Model Class
 * 
 * @author Fabian Rodas
 */

public class User {

    private int id;
    private String fullName;
    private String username;

    /*
     * It is used only temporarily when the user registers.
     * Gson will not write it to users.json.
     */
    private transient String password;

    /*
     * These are stored in users.json.
     */
    private String passwordHash;
    private String salt;

    public User() {
    }

    public User(int id, String fullName, String username, String password) {
        this.id = id;
        this.fullName = fullName;
        this.username = username;
        this.password = password;
    }

    public User(String fullName, String username, String password) {
        this(0, fullName, username, password);
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getSalt() {
        return salt;
    }

    public void setSalt(String salt) {
        this.salt = salt;
    }
}