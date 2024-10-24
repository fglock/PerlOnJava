package org.perlonjava.operators;

import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

public class Crypt {

    private static final String SALT_CHARS = "./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int DIGEST_LENGTH = 13;

    public static RuntimeScalar crypt(RuntimeList args) {
        if (args.elements.size() < 2) {
            throw new RuntimeException("crypt: not enough arguments");
        }

        RuntimeScalar plaintextScalar = (RuntimeScalar) args.elements.get(0);
        RuntimeScalar saltScalar = (RuntimeScalar) args.elements.get(1);

        String plaintext = plaintextScalar.toString();
        String salt = saltScalar.toString();

        // Ensure salt is at least 2 characters long
        if (salt.length() < 2) {
            salt = generateSalt();
        } else {
            salt = salt.substring(0, 2);
        }

        String hashed = hashWithSalt(plaintext, salt);
        return new RuntimeScalar(hashed);
    }

    private static String generateSalt() {
        StringBuilder salt = new StringBuilder();
        for (int i = 0; i < 2; i++) {
            int index = (int) (Math.random() * SALT_CHARS.length());
            salt.append(SALT_CHARS.charAt(index));
        }
        return salt.toString();
    }

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

