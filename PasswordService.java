package al.albus.service;

import org.mindrot.jbcrypt.BCrypt;

public class PasswordService {

    /** Hash a plain-text password for storage. */
    public String hash(String plainPassword) {
        return BCrypt.hashpw(plainPassword, BCrypt.gensalt(10));
    }

    /** Verify a plain-text password against a stored hash. */
    public boolean verify(String plainPassword, String storedHash) {
        return BCrypt.checkpw(plainPassword, storedHash);
    }
}

