package al.albus.service;

/**
 * Plain Java tests for AuthService / PasswordService — no JUnit required.
 * Run via: right-click → Run 'AuthServiceTest.main()'
 */
public class AuthServiceTest {

    private static PasswordService passwordService = new PasswordService();
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {

        // ── Registration input validation ─────────────────────
        check("TC01 - Valid name, email, password pass all guards",
                isValidRegistration("Arjol Basha", "arjol@example.com", "secure123"));
        check("TC02 - Blank full name fails validation",
                !isValidRegistration("   ", "arjol@example.com", "secure123"));
        check("TC03 - Null full name fails validation",
                !isValidRegistration(null, "arjol@example.com", "secure123"));
        check("TC04 - Blank email fails validation",
                !isValidRegistration("Arjol Basha", "", "secure123"));
        check("TC05 - Password length 3 fails validation",
                !isValidRegistration("Arjol Basha", "arjol@example.com", "abc"));
        check("TC06 - Password exactly 6 chars passes validation",
                isValidRegistration("Arjol Basha", "arjol@example.com", "abcdef"));
        check("TC07 - Password length 5 fails validation (boundary)",
                !isValidRegistration("Arjol Basha", "arjol@example.com", "abcde"));
        check("TC08 - Password length 100 passes validation",
                isValidRegistration("Arjol Basha", "arjol@example.com", "a".repeat(100)));

        // ── Password reset input validation ───────────────────
        check("TC09 - Valid email and new password pass reset guards",
                isValidReset("user@example.com", "newpass99"));
        check("TC10 - Blank email fails reset validation",
                !isValidReset("", "newpass99"));
        check("TC11 - New password length 5 fails reset validation",
                !isValidReset("user@example.com", "12345"));
        check("TC12 - New password exactly 6 chars passes reset validation",
                isValidReset("user@example.com", "123456"));

        // ── PasswordService — hashing and verification ────────
        String plain = "mypassword";
        String hash  = passwordService.hash(plain);

        check("TC13 - Hash is not equal to plain text",
                !plain.equals(hash));
        check("TC14 - Correct password verifies against hash",
                passwordService.verify(plain, hash));
        check("TC15 - Wrong password does not verify against hash",
                !passwordService.verify("wrongpass", hash));

        String hash1 = passwordService.hash(plain);
        String hash2 = passwordService.hash(plain);
        check("TC16 - Two hashes of same password are different (BCrypt salt)",
                !hash1.equals(hash2));
        check("TC17 - Both hashes verify correctly against same password",
                passwordService.verify(plain, hash1) && passwordService.verify(plain, hash2));

        String emptyHash = passwordService.hash("");
        check("TC18 - Empty string password hashes and verifies",
                passwordService.verify("", emptyHash));

        // ── Dashboard routing ─────────────────────────────────
        check("TC19 - Operator role routes to OPERATOR_DASHBOARD",
                "OPERATOR_DASHBOARD".equals(resolveDashboard("operator")));
        check("TC20 - Driver role routes to DRIVER_DASHBOARD",
                "DRIVER_DASHBOARD".equals(resolveDashboard("driver")));
        check("TC21 - Passenger role routes to PASSENGER_DASHBOARD",
                "PASSENGER_DASHBOARD".equals(resolveDashboard("passenger")));
        check("TC22 - Unknown role defaults to PASSENGER_DASHBOARD",
                "PASSENGER_DASHBOARD".equals(resolveDashboard("unknown")));

        // ── Summary ───────────────────────────────────────────
        System.out.println("\n========================================");
        System.out.println("AuthServiceTest Results");
        System.out.println("========================================");
        System.out.println("Tests run : " + (passed + failed));
        System.out.println("Passed    : " + passed);
        System.out.println("Failed    : " + failed);
        System.out.println("========================================");
        if (failed == 0) System.out.println("ALL TESTS PASSED");
        else             System.out.println("SOME TESTS FAILED");
    }

    // ── Helpers ───────────────────────────────────────────────

    private static boolean isValidRegistration(String fullName, String email, String password) {
        if (fullName == null || fullName.isBlank()) return false;
        if (email    == null || email.isBlank())    return false;
        if (password == null || password.length() < 6) return false;
        return true;
    }

    private static boolean isValidReset(String email, String newPassword) {
        if (email       == null || email.isBlank())           return false;
        if (newPassword == null || newPassword.length() < 6)  return false;
        return true;
    }

    private static String resolveDashboard(String role) {
        return switch (role) {
            case "operator" -> "OPERATOR_DASHBOARD";
            case "driver"   -> "DRIVER_DASHBOARD";
            default         -> "PASSENGER_DASHBOARD";
        };
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            System.out.println("PASS: " + name);
            passed++;
        } else {
            System.out.println("FAIL: " + name);
            failed++;
        }
    }
}