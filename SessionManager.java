package al.albus.util;

import al.albus.model.User;

public class SessionManager {

    private static User currentUser;

    public static void setCurrentUser(User user) { currentUser = user; }
    public static User getCurrentUser()          { return currentUser; }
    public static void logout()                  { currentUser = null; }

    public static boolean isLoggedIn()           { return currentUser != null; }
}