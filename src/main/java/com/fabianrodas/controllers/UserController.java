package com.fabianrodas.controllers;

import com.fabianrodas.models.User;
import com.fabianrodas.security.PasswordHasher;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;

/**
 * Model Controller class
 * 
 * @author Fabian Rodas
 */

public class UserController {

    public static final int SUCCESS = 1;
    public static final int USERNAME_ALREADY_EXISTS = 0;
    public static final int STORAGE_ERROR = -1;
    public static final int INVALID_DATA = -2;
    public static final int USER_NOT_FOUND = -3;
    public static final int INCORRECT_CURRENT_PASSWORD = -4;

    private static final Path USERS_FILE = Paths.get(
            System.getProperty("user.dir"),
            "data",
            "users.json"
    );

    private static final Type USER_LIST_TYPE
            = new TypeToken<ArrayList<User>>() {
            }.getType();

    private final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    private String lastError = "";

    public int create(User usr) {
        lastError = "";

        if (usr == null
                || isBlank(usr.getFullName())
                || isBlank(usr.getUsername())
                || isBlank(usr.getPassword())) {

            return INVALID_DATA;
        }

        try {
            ArrayList<User> users = readUsers();

            String username = usr.getUsername().trim();

            for (User user : users) {
                if (user.getUsername().equalsIgnoreCase(username)) {
                    return USERNAME_ALREADY_EXISTS;
                }
            }

            usr.setId(getNextId(users));
            usr.setFullName(usr.getFullName().trim());
            usr.setUsername(username);

            String salt = PasswordHasher.generateSalt();
            String passwordHash = PasswordHasher.hash(
                    usr.getPassword(),
                    salt
            );

            usr.setSalt(salt);
            usr.setPasswordHash(passwordHash);
            usr.setPassword(null);

            users.add(usr);
            writeUsers(users);

            return SUCCESS;

        } catch (IOException | IllegalStateException e) {
            lastError = "Could not save the local user database.";
            return STORAGE_ERROR;
        }
    }

    public User authenticate(String username, String password) {
        lastError = "";

        if (isBlank(username) || isBlank(password)) {
            return null;
        }

        try {
            ArrayList<User> users = readUsers();

            for (User user : users) {
                if (user.getUsername().equalsIgnoreCase(username.trim())) {

                    boolean passwordMatches = PasswordHasher.matches(
                            password,
                            user.getSalt(),
                            user.getPasswordHash()
                    );

                    return passwordMatches ? user : null;
                }
            }

        } catch (IOException | IllegalStateException e) {
            lastError = "Could not read the local user database.";
        }

        return null;
    }

    public ArrayList<User> getAll() {
        lastError = "";

        try {
            return readUsers();
        } catch (IOException e) {
            lastError = "Could not read the local user database.";
            return new ArrayList<>();
        }
    }

    public User getById(int id) {
        lastError = "";

        try {
            for (User user : readUsers()) {
                if (user.getId() == id) {
                    return user;
                }
            }
        } catch (IOException e) {
            lastError = "Could not read the local user database.";
        }

        return null;
    }

    public User getByUsername(String username) {
        lastError = "";

        if (isBlank(username)) {
            return null;
        }

        try {
            for (User user : readUsers()) {
                if (user.getUsername().equalsIgnoreCase(username.trim())) {
                    return user;
                }
            }
        } catch (IOException e) {
            lastError = "Could not read the local user database.";
        }

        return null;
    }

    public int delete(int id) {
        lastError = "";

        try {
            ArrayList<User> users = readUsers();

            boolean removed = users.removeIf(user -> user.getId() == id);

            if (!removed) {
                return USER_NOT_FOUND;
            }

            writeUsers(users);
            return SUCCESS;

        } catch (IOException e) {
            lastError = "Could not update the local user database.";
            return STORAGE_ERROR;
        }
    }

    public int changePassword(int id, String currentPassword, String newPassword) {
        lastError = "";

        if (isBlank(currentPassword)
                || isBlank(newPassword)
                || newPassword.length() < 8) {

            return INVALID_DATA;
        }

        try {
            ArrayList<User> users = readUsers();

            for (User user : users) {
                if (user.getId() == id) {

                    boolean currentPasswordMatches = PasswordHasher.matches(
                            currentPassword,
                            user.getSalt(),
                            user.getPasswordHash()
                    );

                    if (!currentPasswordMatches) {
                        return INCORRECT_CURRENT_PASSWORD;
                    }

                    String salt = PasswordHasher.generateSalt();

                    user.setSalt(salt);
                    user.setPasswordHash(
                            PasswordHasher.hash(newPassword, salt)
                    );

                    writeUsers(users);

                    return SUCCESS;
                }
            }

            return USER_NOT_FOUND;

        } catch (IOException | IllegalStateException e) {
            lastError = "Could not update the password.";
            return STORAGE_ERROR;
        }
    }

    public String getLastError() {
        return lastError;
    }

    private ArrayList<User> readUsers() throws IOException {
        createUsersFileIfNeeded();

        try (Reader reader = Files.newBufferedReader(
                USERS_FILE,
                StandardCharsets.UTF_8
        )) {
            ArrayList<User> users = gson.fromJson(reader, USER_LIST_TYPE);

            return users == null ? new ArrayList<>() : users;

        } catch (JsonParseException e) {
            throw new IOException("Invalid users.json format.", e);
        }
    }

    private void writeUsers(ArrayList<User> users) throws IOException {
        Path temporaryFile = Files.createTempFile(
                USERS_FILE.getParent(),
                "users-",
                ".tmp"
        );

        try {
            try (Writer writer = Files.newBufferedWriter(
                    temporaryFile,
                    StandardCharsets.UTF_8
            )) {
                gson.toJson(users, writer);
            }

            try {
                Files.move(
                        temporaryFile,
                        USERS_FILE,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );

            } catch (AtomicMoveNotSupportedException e) {
                Files.move(
                        temporaryFile,
                        USERS_FILE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }

        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    private void createUsersFileIfNeeded() throws IOException {
        Files.createDirectories(USERS_FILE.getParent());

        if (Files.notExists(USERS_FILE)) {
            Files.writeString(
                    USERS_FILE,
                    "[]",
                    StandardCharsets.UTF_8
            );
        }
    }

    private int getNextId(ArrayList<User> users) {
        int highestId = 0;

        for (User user : users) {
            if (user.getId() > highestId) {
                highestId = user.getId();
            }
        }

        return highestId + 1;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}