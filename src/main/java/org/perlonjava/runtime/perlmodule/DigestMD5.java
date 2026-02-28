package org.perlonjava.runtime.perlmodule;

import org.perlonjava.frontend.parser.StringParser;
import org.perlonjava.runtime.runtimetypes.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import static org.perlonjava.runtime.perlmodule.DigestSHA.bitStringToBytes;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.*;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.JAVAOBJECT;

/**
 * Digest::MD5 module implementation for PerlOnJava.
 * This class provides MD5 hashing using Java's MessageDigest.
 */
public class DigestMD5 extends PerlModuleBase {

    private static final String CACHE_KEY = "_MessageDigest";
    private static final String STATE_KEY = "_state";
    private static final String BLOCK_COUNT_KEY = "_block_count";

    /**
     * Constructor initializes the Digest::MD5 module.
     */
    public DigestMD5() {
        super("Digest::MD5", false);
    }

    /**
     * Initializes and registers all Digest::MD5 methods.
     */
    public static void initialize() {
        DigestMD5 md5 = new DigestMD5();
        try {
            // Register core MD5 methods
            md5.registerMethod("add", null);
            md5.registerMethod("addfile", null);
            md5.registerMethod("add_bits", null);
            md5.registerMethod("digest", null);
            md5.registerMethod("hexdigest", null);
            md5.registerMethod("b64digest", null);
            md5.registerMethod("clone", null);
            md5.registerMethod("context", null);
            md5.registerMethod("reset", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Digest::MD5 method: " + e.getMessage());
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
                if (data.type != RuntimeScalarType.UNDEF) {
                    String dataStr = data.toString();

                    // Check for wide characters using the utility method
                    StringParser.assertNoWideCharacters(dataStr, "add");

                    byte[] bytes = dataStr.getBytes(StandardCharsets.UTF_8);
                    md.update(bytes);
                    updateBlockCount(self, bytes.length);
                }
            }

            return self.createReference().getList();

        } catch (Exception e) {
            throw new RuntimeException("Digest::MD5 add failed: " + e.getMessage(), e);
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

            // Check if argument is a reference (filehandle)
            if (!RuntimeScalarType.isReference(fileArg)) {
                // Not a reference at all - this should croak
                throw new PerlCompilerException("Not a GLOB reference");
            }

            // Extract the filehandle from the reference
            fh = RuntimeIO.getRuntimeIO(fileArg);
            if (fh == null) {
                // It's a reference but not a valid filehandle
                throw new PerlCompilerException("Not a GLOB reference");
            }

            // The filehandle should already be in the appropriate mode
            // The caller is responsible for setting binmode if needed

            // Read the file content in chunks
            byte[] buffer = new byte[8192];
            try {
                while (true) {
                    RuntimeScalar result = fh.ioHandle.read(buffer.length);
                    if (result.type == RuntimeScalarType.UNDEF || result.toString().isEmpty()) {
                        break;  // EOF
                    }

                    byte[] bytes = result.toString().getBytes(StandardCharsets.ISO_8859_1);
                    md.update(bytes);
                    updateBlockCount(self, bytes.length);
                }
            } catch (Exception e) {
                // If reading fails, the state is unpredictable as documented
                throw new PerlCompilerException("Read error in addfile: " + e.getMessage());
            }

            return self.createReference().getList();

        } catch (PerlCompilerException e) {
            // Re-throw PerlCompilerException as-is
            throw e;
        } catch (Exception e) {
            throw new PerlCompilerException("Digest::MD5 addfile failed: " + e.getMessage());
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
                updateBlockCount(self, bytes.length);
            } else {
                // Two arguments - data and bit count
                RuntimeScalar nbitsScalar = args.get(2);
                String data = bitsOrData.toString();
                int nbits = nbitsScalar.getInt();

                // MD5 only supports multiples of 8 bits
                if (nbits % 8 != 0) {
                    throw new RuntimeException("Number of bits must be multiple of 8 for MD5");
                }

                int numBytes = nbits / 8;
                byte[] dataBytes = data.getBytes(StandardCharsets.UTF_8);
                byte[] truncatedBytes = new byte[Math.min(numBytes, dataBytes.length)];
                System.arraycopy(dataBytes, 0, truncatedBytes, 0, truncatedBytes.length);

                md.update(truncatedBytes);
                updateBlockCount(self, truncatedBytes.length);
            }

            return self.createReference().getList();

        } catch (Exception e) {
            throw new RuntimeException("Digest::MD5 add_bits failed: " + e.getMessage(), e);
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

            // Clone before getting digest to preserve state
            MessageDigest mdClone = (MessageDigest) md.clone();
            byte[] digestBytes = mdClone.digest();

            // Convert bytes to string (ISO-8859-1 to preserve byte values)
            String digestStr = new String(digestBytes, StandardCharsets.ISO_8859_1);

            // Reset for next use
            md.reset();
            self.put(BLOCK_COUNT_KEY, scalarZero);

            return new RuntimeScalar(digestStr).getList();

        } catch (Exception e) {
            throw new RuntimeException("Digest::MD5 digest failed: " + e.getMessage(), e);
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

            // Clone before getting digest to preserve state
            MessageDigest mdClone = (MessageDigest) md.clone();
            byte[] digestBytes = mdClone.digest();

            // Convert to hex string
            StringBuilder hexString = new StringBuilder();
            for (byte b : digestBytes) {
                hexString.append(String.format("%02x", b & 0xff));
            }

            // Reset for next use
            md.reset();
            self.put(BLOCK_COUNT_KEY, scalarZero);

            return new RuntimeScalar(hexString.toString()).getList();

        } catch (Exception e) {
            throw new RuntimeException("Digest::MD5 hexdigest failed: " + e.getMessage(), e);
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

            // Clone before getting digest to preserve state
            MessageDigest mdClone = (MessageDigest) md.clone();
            byte[] digestBytes = mdClone.digest();

            // Convert to base64 string (without padding to match Perl's behavior)
            String b64String = Base64.getEncoder().encodeToString(digestBytes);
            b64String = b64String.replaceAll("=+$", ""); // Remove padding

            // Reset for next use
            md.reset();
            self.put(BLOCK_COUNT_KEY, scalarZero);

            return new RuntimeScalar(b64String).getList();

        } catch (Exception e) {
            throw new RuntimeException("Digest::MD5 b64digest failed: " + e.getMessage(), e);
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
            clonedSelf.put("algorithm", new RuntimeScalar("MD5"));
            clonedSelf.put(CACHE_KEY, new RuntimeScalar(clonedMd));

            // Copy block count
            RuntimeScalar blockCount = self.get(BLOCK_COUNT_KEY);
            if (blockCount != null) {
                clonedSelf.put(BLOCK_COUNT_KEY, blockCount);
            } else {
                clonedSelf.put(BLOCK_COUNT_KEY, scalarZero);
            }

            return clonedSelf.createReference().getList();

        } catch (Exception e) {
            throw new RuntimeException("Digest::MD5 clone failed: " + e.getMessage(), e);
        }
    }

    /**
     * Get or set the context (internal state).
     */
    public static RuntimeList context(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarUndef.getList();
        }

        RuntimeHash self = args.get(0).hashDeref();

        try {
            if (args.size() == 1) {
                // Get context
                MessageDigest md = getMessageDigest(self);

                // Get block count
                RuntimeScalar blockCount = self.get(BLOCK_COUNT_KEY);
                if (blockCount == null) {
                    blockCount = scalarZero;
                }

                // For simplicity, we'll return block count and algorithm
                // In a real implementation, we'd need to serialize the MD5 state
                RuntimeArray result = new RuntimeArray();
                RuntimeArray.push(result, blockCount); // Number of blocks processed

                // Add a fake 16-byte state buffer (MD5 internal state)
                byte[] stateBuffer = new byte[16];
                RuntimeArray.push(result, new RuntimeScalar(new String(stateBuffer, StandardCharsets.ISO_8859_1)));

                // No unprocessed data in our implementation

                return result.getList();

            } else {
                // Set context
                RuntimeArray ctxArray = new RuntimeArray();
                for (int i = 1; i < args.size(); i++) {
                    RuntimeArray.push(ctxArray, args.get(i));
                }

                if (!ctxArray.isEmpty()) {
                    self.put(BLOCK_COUNT_KEY, ctxArray.get(0));
                }

                // Reset MD5 (we can't truly restore internal state with Java's MessageDigest)
                MessageDigest md = MessageDigest.getInstance("MD5");
                self.put(CACHE_KEY, new RuntimeScalar(md));

                return self.createReference().getList();
            }

        } catch (Exception e) {
            throw new RuntimeException("Digest::MD5 context failed: " + e.getMessage(), e);
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
            self.put(BLOCK_COUNT_KEY, scalarZero);

            return self.createReference().getList();

        } catch (Exception e) {
            throw new RuntimeException("Digest::MD5 reset failed: " + e.getMessage(), e);
        }
    }

    /**
     * Get or create MessageDigest from the object.
     */
    private static MessageDigest getMessageDigest(RuntimeHash self) throws NoSuchAlgorithmException {
        RuntimeScalar cached = self.get(CACHE_KEY);
        if (cached != null && cached.type == JAVAOBJECT && cached.value instanceof MessageDigest) {
            return (MessageDigest) cached.value;
        }

        // Create new MessageDigest
        MessageDigest md = MessageDigest.getInstance("MD5");
        self.put(CACHE_KEY, new RuntimeScalar(md));
        self.put(BLOCK_COUNT_KEY, scalarZero);

        return md;
    }

    /**
     * Update block count based on bytes processed.
     */
    private static void updateBlockCount(RuntimeHash self, int bytesAdded) {
        RuntimeScalar blockCount = self.get(BLOCK_COUNT_KEY);
        if (blockCount == null) {
            blockCount = scalarZero;
        }

        // MD5 processes data in 64-byte blocks
        long totalBytes = blockCount.getLong() * 64 + bytesAdded;
        long newBlockCount = totalBytes / 64;

        self.put(BLOCK_COUNT_KEY, new RuntimeScalar(newBlockCount));
    }
}