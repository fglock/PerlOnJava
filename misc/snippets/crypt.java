import java.security.MessageDigest;

public class CryptUtils {
    public static String crypt(String key, String salt) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(salt.getBytes());
        byte[] hashed = md.digest(key.getBytes());
        return new String(hashed); // In Perl, crypt uses DES; Java needs explicit DES implementation.
    }
}

