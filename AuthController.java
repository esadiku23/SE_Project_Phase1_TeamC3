package al.albus.api;

import al.albus.model.User;
import al.albus.service.AuthService;
import al.albus.util.SessionManager;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService = new AuthService();

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body, HttpSession session) {
        String email    = body.get("email");
        String password = body.get("password");

        if (email == null || email.isBlank())    return bad("Email is required.");
        if (password == null || password.isBlank()) return bad("Password is required.");

        Optional<User> result = authService.login(email, password);
        if (result.isEmpty())
            return ResponseEntity.status(401)
                    .body(Map.of("success", false, "message", "Incorrect email or password."));

        User user = result.get();
        session.setAttribute("userId",     user.getId());
        session.setAttribute("userRole",   user.getRole());
        session.setAttribute("terminalId", user.getTerminalId());
        String terminalName = authService.terminalLabel(user.getTerminalId());

        return ok(Map.of(
                "id",         user.getId(),
                "fullName",   user.getFullName(),
                "email",      user.getEmail(),
                "role",       user.getRole(),
                "terminalId", user.getTerminalId() != null ? user.getTerminalId() : "",
                "terminalName", terminalName
        ));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpSession session) {
        session.invalidate();
        SessionManager.logout();
        return ok(Map.of("message", "Logged out."));
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Map<String, String> body) {
        String fullName = body.get("fullName");
        String email    = body.get("email");
        String password = body.get("password");

        if (fullName == null || fullName.isBlank()) return bad("Full name is required.");
        if (email    == null || email.isBlank())    return bad("Email is required.");
        if (password == null || password.length() < 6) return bad("Password must be at least 6 characters.");

        boolean created = authService.registerPassenger(fullName, email, password);
        if (!created) return bad("This email is already registered.");

        return ok(Map.of("message", "Account created successfully."));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(HttpSession session) {
        Object userId = session.getAttribute("userId");
        Object role   = session.getAttribute("userRole");
        Object termId = session.getAttribute("terminalId");
        Integer terminalId = null;
        if (termId instanceof Number) terminalId = ((Number) termId).intValue();
        else if (termId != null && !String.valueOf(termId).isBlank()) {
            try {
                terminalId = Integer.parseInt(String.valueOf(termId));
            } catch (NumberFormatException ignored) {}
        }
        String terminalName = authService.terminalLabel(terminalId);

        if (userId == null)
            return ResponseEntity.status(401)
                    .body(Map.of("success", false, "message", "Not authenticated."));

        return ok(Map.of(
                "id",         userId,
                "role",       role,
                "terminalId", termId != null ? termId : "",
                "terminalName", terminalName
        ));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        String newPwd = body.get("newPassword");

        if (email == null || email.isBlank()) return bad("Email is required.");
        if (newPwd == null || newPwd.length() < 6) return bad("Password must be at least 6 characters.");

        boolean updated = authService.resetPassword(email, newPwd);
        if (!updated) return bad("No account was found for that email.");

        return ok(Map.of("message", "Password reset successfully."));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        String newPwd = body.get("newPassword");

        if (email == null || email.isBlank()) return bad("Email is required.");
        if (newPwd == null || newPwd.length() < 6) return bad("Password must be at least 6 characters.");

        boolean updated = authService.resetPassword(email, newPwd);
        if (!updated) return bad("No account was found for that email.");

        return ok(Map.of("message", "Password reset successfully."));
    }

    private ResponseEntity<?> ok(Object data) {
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    private ResponseEntity<?> bad(String message) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "message", message));
    }
}
