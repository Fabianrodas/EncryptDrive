package com.fabianrodas.controllers;

import com.fabianrodas.models.User;
import java.util.ArrayList;

/**
 *
 * @author Fabian
 */
public class UserController {
    public int create(User usr) {
        int result = 0;
        return result;
    }
    
    public ArrayList<User> getAll() {
        ArrayList<User> users = new ArrayList();
        return users;
    }
    
    public User getById(int id) {
        User result = new User();
        return result;
    }
    
    public User getByUsername(String username) {
        User result = new User();
        return result;
    }
    
    public int delete(int id) {
        int result = 0;
        return result;
    }
    
    public int changePassword(int id, String newPassword) {
        int result = 0;
        return result;
    }
}
