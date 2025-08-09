package org.perlonjava.perlmodule;

import org.perlonjava.parser.StringParser;
import org.perlonjava.runtime.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.perlonjava.runtime.RuntimeScalarCache.*;
import static org.perlonjava.runtime.RuntimeScalarType.JAVAOBJECT;

/**
 * Digest::SHA module implementation for PerlOnJava.
 * This class provides SHA hashing using Java's MessageDigest.
 */
public class DigestSHA extends PerlModuleBase {

    private static final String cacheKey = "_MessageDigest";

    // Map Perl algorithm names to Java algorithm names
    private static final Map<String, String> ALGORITHM_MAP = new HashMap<>();
    static {
        ALGORITHM_MAP.put("1", "SHA-1");
        ALGORITHM_MAP.put("224", "SHA-224");
        ALGORITHM_MAP.put("256", "SHA-256");
        ALGORITHM_MAP.put("384", "SHA-384");
        ALGORITHM_MAP.put("512", "SHA-512");
        ALGORITHM_MAP.put("512224", "SHA-512/224");
        ALGORITHM_MAP.put("512256", "SHA-512/256");
        ALGORITHM_MAP.put("sha1", "SHA-1");
        ALGORITHM_MAP.put("sha224", "SHA-224");
        ALGORITHM_MAP.put("sha256", "SHA-256");
        ALGORITHM_MAP.put("sha384", "SHA-384");
        ALGORITHM_MAP.put("sha512", "SHA-512");
        ALGORITHM_MAP.put("sha512224", "SHA-512/224");
        ALGORITHM_MAP.put("sha512256", "SHA-512/256");
        ALGORITHM_MAP.put("SHA1", "SHA-1");
        ALGORITHM_MAP.put("SHA224", "SHA-224");
        ALGORITHM_MAP.put("SHA256", "SHA-256");
        ALGORITHM_MAP.put("SHA384", "SHA-384");
        ALGORITHM_MAP.put("SHA512", "SHA-512");
        ALGORITHM_MAP.put("SHA512224", "SHA-512/224");
        ALGORITHM_MAP.put("SHA512256", "SHA-512/256");
    }

    /**
     * Constructor initializes the Digest::SHA module.
     */
    public DigestSHA() {
        super("Digest::SHA", false);
    }

    /**
     * Initializes and registers all Digest::SHA methods.
     */
    public static void initialize() {
        DigestSHA sha = new DigestSHA();
        try {
            // Register core SHA methods (high-level methods in Perl)
            sha.registerMethod("add", null);
            sha.registerMethod("addfile", null);
            sha.registerMethod("add_bits", null);
            sha.registerMethod("digest", null);
            sha.registerMethod("hexdigest", null);
            sha.registerMethod("b64digest", null);
            sha.registerMethod("clone", null);
            sha.registerMethod("getstate", null);
            sha.registerMethod("putstate", null);
            sha.registerMethod("reset", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Digest::SHA method: " + e.getMessage());
        }
    }

    /**
     * Add data to the digest.
     */
    public static RuntimeList add(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            return scalarFalse.getList();
        }

        RuntimeHash self = args.get(0).hashDeref();

        try {
            MessageDigest md = getMessageDigest(self);

            // Add all provided data arguments
            for (int i = 1; i < args.size(); i++) {
                RuntimeScalar data = args.get(i);

                String dataStr = data.toString();

                // Check for wide characters using the utility method
                StringParser.assertNoWideCharacters(dataStr, "add");

                if (data.type != RuntimeScalarType.UNDEF) {
                    md.update(dataStr.getBytes(StandardCharsets.UTF_8));
                }
            }

            return self.createReference().getList();

        } catch (Exception e) {
            throw new RuntimeException("Digest::SHA add failed: " + e.getMessage(), e);
        }
    }

    /**
     * Add file contents to the digest.
     */
    public static RuntimeList addfile(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            return scalarFalse.getList();
        }

        RuntimeHash self = args.get(0).hashDeref();
        RuntimeScalar fileArg = args.get(1);

        try {
            MessageDigest md = getMessageDigest(self);
            RuntimeIO fh = null;
            boolean needToClose = false;

            // Check if argument is a reference (filehandle) or string (filename)
            if (RuntimeScalarType.isReference(fileArg)) {
                // Extract the filehandle from the reference
                fh = RuntimeIO.getRuntimeIO(fileArg);
                if (fh == null) {
                    throw new RuntimeException("Not a valid filehandle reference");
                }
            } else {
                // It's a filename string - open it
                String filename = fileArg.toString();
                fh = RuntimeIO.open(filename, "<");  // Open in read mode
                if (fh == null) {
                    throw new RuntimeException("Cannot open file: " + filename);
                }
                needToClose = true;  // We opened it, so we should close it
            }

            // Now we have a filehandle in both cases
            // Set binary mode for accurate byte reading
            fh.binmode(":raw");

            // Read the file content in chunks
            byte[] buffer = new byte[8192];
            while (true) {
                RuntimeScalar result = fh.ioHandle.read(buffer.length);
                if (result.type == RuntimeScalarType.UNDEF || result.toString().isEmpty()) {
                    break;  // EOF
                }

                byte[] bytes = result.toString().getBytes(StandardCharsets.ISO_8859_1);
                md.update(bytes);
            }

            // Close the filehandle only if we opened it
            if (needToClose) {
                fh.close();
            }

            return self.createReference().getList();

        } catch (Exception e) {
            throw new RuntimeException("Digest::SHA addfile failed: " + e.getMessage(), e);
        }
    }

    /**
     * Add bits to the digest.
     */
    public static RuntimeList add_bits(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            return scalarFalse.getList();
        }

        RuntimeHash self = args.get(0).hashDeref();
        RuntimeScalar bitsOrData = args.get(1);

        try {
            MessageDigest md = getMessageDigest(self);

            if (args.size() == 2) {
                // Single argument - treat as bit string
                String bitString = bitsOrData.toString();
                byte[] bytes = bitStringToBytes(bitString);
                md.update(bytes);
            } else {
                // Two arguments - data and bit count
                RuntimeScalar nbitsScalar = args.get(2);
                String data = bitsOrData.toString();
                int nbits = nbitsScalar.getInt();

                // Convert data to bytes, but only use the specified number of bits
                byte[] dataBytes = data.getBytes(StandardCharsets.UTF_8);
                byte[] truncatedBytes = truncateToNBits(dataBytes, nbits);
                md.update(truncatedBytes);
            }

            return self.createReference().getList();

        } catch (Exception e) {
            throw new RuntimeException("Digest::SHA add_bits failed: " + e.getMessage(), e);
        }
    }

    /**
     * Compute digest and return raw bytes.
     */
    public static RuntimeList digest(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarUndef.getList();
        }

        RuntimeHash self = args.get(0).hashDeref();

        try {
            MessageDigest md = getMessageDigest(self);
            byte[] digestBytes = md.digest();

            // Convert bytes to string (this mimics Perl's behavior)
            String digestStr = new String(digestBytes, StandardCharsets.ISO_8859_1);

            // Reset the digest for potential reuse
            md.reset();

            return new RuntimeScalar(digestStr).getList();

        } catch (Exception e) {
            throw new RuntimeException("Digest::SHA digest failed: " + e.getMessage(), e);
        }
    }

    /**
     * Compute digest and return hex string.
     */
    public static RuntimeList hexdigest(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarUndef.getList();
        }

        RuntimeHash self = args.get(0).hashDeref();

        try {
            MessageDigest md = getMessageDigest(self);
            byte[] digestBytes = md.digest();

            // Convert to hex string
            StringBuilder hexString = new StringBuilder();
            for (byte b : digestBytes) {
                hexString.append(String.format("%02x", b & 0xff));
            }

            // Reset the digest for potential reuse
            md.reset();

            return new RuntimeScalar(hexString.toString()).getList();

        } catch (Exception e) {
            throw new RuntimeException("Digest::SHA hexdigest failed: " + e.getMessage(), e);
        }
    }

    /**
     * Compute digest and return base64 string.
     */
    public static RuntimeList b64digest(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarUndef.getList();
        }

        RuntimeHash self = args.get(0).hashDeref();

        try {
            MessageDigest md = getMessageDigest(self);
            byte[] digestBytes = md.digest();

            // Convert to base64 string (without padding to match Perl's behavior)
            String b64String = Base64.getEncoder().encodeToString(digestBytes);
            b64String = b64String.replaceAll("=+$", ""); // Remove padding

            // Reset the digest for potential reuse
            md.reset();

            return new RuntimeScalar(b64String).getList();

        } catch (Exception e) {
            throw new RuntimeException("Digest::SHA b64digest failed: " + e.getMessage(), e);
        }
    }

    /**
     * Clone the digest object.
     */
    public static RuntimeList clone(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarUndef.getList();
        }

        RuntimeHash self = args.get(0).hashDeref();

        try {
            MessageDigest md = getMessageDigest(self);
            MessageDigest clonedMd = (MessageDigest) md.clone();

            // Create new Perl object
            RuntimeHash clonedSelf = new RuntimeHash();
            clonedSelf.blessId = self.blessId;
            clonedSelf.put("algorithm", self.get("algorithm"));
            clonedSelf.put(cacheKey, new RuntimeScalar(clonedMd));

            return clonedSelf.createReference().getList();

        } catch (Exception e) {
            throw new RuntimeException("Digest::SHA clone failed: " + e.getMessage(), e);
        }
    }

    /**
     * Get the current state as a string.
     */
    public static RuntimeList getstate(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarUndef.getList();
        }

        RuntimeHash self = args.get(0).hashDeref();

        try {
            MessageDigest md = getMessageDigest(self);

            // Java's MessageDigest doesn't provide direct state serialization
            // We'll use a simplified approach by cloning and getting the algorithm
            String algorithm = self.get("algorithm").toString();

            // For simplicity, we'll just return the algorithm name
            // In a full implementation, you'd need to serialize the internal state
            return new RuntimeScalar(algorithm).getList();

        } catch (Exception e) {
            throw new RuntimeException("Digest::SHA getstate failed: " + e.getMessage(), e);
        }
    }

    /**
     * Restore state from a string.
     */
    public static RuntimeList putstate(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            return scalarFalse.getList();
        }

        RuntimeHash self = args.get(0).hashDeref();
        RuntimeScalar state = args.get(1);

        try {
            // For simplicity, recreate MessageDigest from algorithm name
            String algorithm = state.toString();
            String javaAlgorithm = ALGORITHM_MAP.get(algorithm.toLowerCase());
            if (javaAlgorithm == null) {
                javaAlgorithm = algorithm;
            }

            MessageDigest md = MessageDigest.getInstance(javaAlgorithm);
            self.put(cacheKey, new RuntimeScalar(md));
            self.put("algorithm", new RuntimeScalar(algorithm));

            return self.createReference().getList();

        } catch (Exception e) {
            throw new RuntimeException("Digest::SHA putstate failed: " + e.getMessage(), e);
        }
    }

    /**
     * Reset the digest.
     */
    public static RuntimeList reset(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarFalse.getList();
        }

        RuntimeHash self = args.get(0).hashDeref();

        try {
            MessageDigest md = getMessageDigest(self);
            md.reset();

            return self.createReference().getList();

        } catch (Exception e) {
            throw new RuntimeException("Digest::SHA reset failed: " + e.getMessage(), e);
        }
    }

    /**
     * Get or create MessageDigest from the object.
     */
    private static MessageDigest getMessageDigest(RuntimeHash self) throws NoSuchAlgorithmException {
        RuntimeScalar cached = self.get(cacheKey);
        if (cached != null && cached.type == JAVAOBJECT) {
            return (MessageDigest) cached.value;
        }

        // Create new MessageDigest
        String algorithm = self.get("algorithm").toString();
        String javaAlgorithm = ALGORITHM_MAP.get(algorithm.toLowerCase());
        if (javaAlgorithm == null) {
            javaAlgorithm = algorithm;
        }

        MessageDigest md = MessageDigest.getInstance(javaAlgorithm);
        self.put(cacheKey, new RuntimeScalar(md));

        return md;
    }

    /**
     * Convert bit string to bytes.
     */
    public static byte[] bitStringToBytes(String bitString) {
        // Remove any non-binary characters
        bitString = bitString.replaceAll("[^01]", "");

        // Pad to multiple of 8 bits
        while (bitString.length() % 8 != 0) {
            bitString += "0";
        }

        byte[] bytes = new byte[bitString.length() / 8];
        for (int i = 0; i < bytes.length; i++) {
            String byteStr = bitString.substring(i * 8, (i + 1) * 8);
            bytes[i] = (byte) Integer.parseInt(byteStr, 2);
        }

        return bytes;
    }

    /**
     * Truncate byte array to specified number of bits.
     */
    private static byte[] truncateToNBits(byte[] data, int nbits) {
        int fullBytes = nbits / 8;
        int remainingBits = nbits % 8;

        if (remainingBits == 0) {
            // Exact byte boundary
            byte[] result = new byte[fullBytes];
            System.arraycopy(data, 0, result, 0, Math.min(fullBytes, data.length));
            return result;
        } else {
            // Need to handle partial byte
            byte[] result = new byte[fullBytes + 1];
            System.arraycopy(data, 0, result, 0, Math.min(fullBytes, data.length));

            if (fullBytes < data.length) {
                // Mask the last byte to keep only the required bits
                int mask = (0xFF << (8 - remainingBits)) & 0xFF;
                result[fullBytes] = (byte) (data[fullBytes] & mask);
            }

            return result;
        }
    }
}