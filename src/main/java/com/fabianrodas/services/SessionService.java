package com.fabianrodas.services;

import com.fabianrodas.models.UserSessionIdentity;

/**
 * Service class
 *
 * @author Fabian Rodas
 */

public final class SessionService {

    private static UserSessionIdentity currentUser;

    private SessionService() {
    }

    public static void startSession(UserSessionIdentity user) {
        currentUser = user;
    }

    public static UserSessionIdentity getCurrentUser() {
        return currentUser;
    }

    public static boolean hasActiveSession() {
        return currentUser != null;
    }

    public static void closeSession() {
        currentUser = null;
    }
}
