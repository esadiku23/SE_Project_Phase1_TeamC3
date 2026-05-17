package al.albus.service;

import al.albus.model.*;
import al.albus.repository.UserRepository;
import al.albus.util.SessionManager;

import java.util.Optional;

public class AuthService {

    private final UserRepository  userRepo        = new UserRepository();
    private final PasswordService passwordService = new PasswordService();

    public Optional<User> login(String email, String password) {

        System.out.println("DEBUG: Trying email: " + email);

        Optional<User> maybeUser = userRepo.findByEmail(email);

        System.out.println("DEBUG: User found: " + maybeUser.isPresent());

        if (maybeUser.isEmpty()) {
            return Optional.empty();
        }

        User user = maybeUser.get();
        if (!user.isActive()) {
            System.out.println("DEBUG: User inactive: " + email);
            return Optional.empty();
        }

        System.out.println("DEBUG: Hash in DB: " + user.getPasswordHash());
        System.out.println("DEBUG: Password entered: " + password);

        boolean match = passwordService.verify(password, user.getPasswordHash());
        System.out.println("DEBUG: Password match: " + match);

        if (!match) {
            return Optional.empty();
        }

        SessionManager.setCurrentUser(user);
        return Optional.of(user);
    }

    public boolean registerPassenger(String fullName, String email, String plainPassword) {
        String hash = passwordService.hash(plainPassword);
        return userRepo.registerPassenger(fullName, email, hash);
    }

    public String terminalLabel(Integer terminalId) {
        return userRepo.findTerminalLabelById(terminalId);
    }

    public String resolveDashboard(User user) {
        return switch (user.getRole()) {
            case "operator"  -> "OPERATOR_DASHBOARD";
            case "driver"    -> "DRIVER_DASHBOARD";
            default          -> "PASSENGER_DASHBOARD";
        };
    }
// ADD this method to AuthService.java

    /**
     * Reset a user's password — called after code verification.
     */
    public boolean resetPassword(String email, String newPlainPassword) {
        if (!userRepo.existsByEmail(email)) return false;
        String hash = passwordService.hash(newPlainPassword);
        return userRepo.updatePassword(email, hash);
    }
}
