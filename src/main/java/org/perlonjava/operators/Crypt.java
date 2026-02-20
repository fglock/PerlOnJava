package org.perlonjava.operators;

import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import static org.perlonjava.parser.StringParser.assertNoWideCharacters;

/**
 * Provides functionality to perform cryptographic hashing on strings
 * using a salt, similar to Perl's crypt function.
 */
public class Crypt {

    private static final String SALT_CHARS = "./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int DIGEST_LENGTH = 13;

    /**
     * Hashes a plaintext string using a salt and returns the hashed result.
     *
     * @param args A RuntimeList containing the plaintext string and the salt.
     * @return A RuntimeScalar representing the hashed string.
     * @throws RuntimeException if there are not enough arguments or if hashing fails.
     */
    public static RuntimeScalar crypt(RuntimeList args) {
        if (args.elements.size() < 2) {
            throw new RuntimeException("crypt: not enough arguments");
        }

        RuntimeScalar plaintextScalar = (RuntimeScalar) args.elements.get(0);
        RuntimeScalar saltScalar = (RuntimeScalar) args.elements.get(1);

        String plaintext = plaintextScalar.toString();
        String salt = saltScalar.toString();

        assertNoWideCharacters(plaintext, "crypt");

        // Pad or truncate salt to exactly 2 characters
        if (salt.isEmpty()) {
            salt = generateSalt();
        } else if (salt.length() == 1) {
            // Pad single character salt with '.' (Perl-compatible behavior)
            salt = salt + ".";
        } else {
            // Use first 2 characters
            salt = salt.substring(0, 2);
        }

        String hashed = hashWithSalt(plaintext, salt);
        return new RuntimeScalar(hashed);
    }

    /**
     * Generates a random salt of 2 characters from the set of allowed salt characters.
     *
     * @return A string representing the generated salt.
     */
    private static String generateSalt() {
        StringBuilder salt = new StringBuilder();
        for (int i = 0; i < 2; i++) {
            int index = (int) (Math.random() * SALT_CHARS.length());
            salt.append(SALT_CHARS.charAt(index));
        }
        return salt.toString();
    }

    /**
     * Hashes the plaintext string using the specified salt and returns the hashed result.
     *
     * @param plaintext The plaintext string to hash.
     * @param salt      The salt to use for hashing.
     * @return A string representing the hashed result.
     * @throws RuntimeException if the hashing algorithm is not available.
     */
    private static String hashWithSalt(String plaintext, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt.getBytes());
            byte[] bytes = md.digest(plaintext.getBytes());
            String encoded = Base64.getEncoder().encodeToString(bytes);

            // Ensure the result is 13 characters long
            return (salt + encoded).substring(0, DIGEST_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to create hash", e);
        }
    }
}
