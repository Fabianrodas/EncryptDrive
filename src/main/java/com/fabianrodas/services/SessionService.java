package com.fabianrodas.services;

import com.fabianrodas.models.User;

/**
 * Service class
 * 
 * @author Fabian Rodas
 */

public final class SessionService {

    private static User currentUser;

    private SessionService() {
    }

    public static void startSession(User user) {
        currentUser = user;
    }

    public static User getCurrentUser() {
        return currentUser;
    }

    public static boolean hasActiveSession() {
        return currentUser != null;
    }

    public static void closeSession() {
        currentUser = null;
    }
}