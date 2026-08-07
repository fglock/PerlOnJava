package org.perlonjava.runtime.perlmodule;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.perlonjava.runtime.runtimetypes.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Crypt::Argon2's XS primitives, backed by the bundled Bouncy Castle provider. */
public class CryptArgon2 extends PerlModuleBase {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern ENCODED = Pattern.compile(
            "^\\$(argon2(?:id|i|d))\\$v=(\\d+)\\$m=(\\d+),t=(\\d+),p=(\\d+)\\$([^$]+)\\$(.*)$");

    public CryptArgon2() {
        super("Crypt::Argon2", false);
    }

    public static void initialize() {
        CryptArgon2 mod = new CryptArgon2();
        GlobalVariable.getGlobalVariable("Crypt::Argon2::VERSION").set(new RuntimeScalar("0.032"));
        try {
            for (String method : new String[]{
                    "argon2_pass", "argon2_raw", "argon2_verify",
                    "argon2id_pass", "argon2id_raw", "argon2id_verify",
                    "argon2i_pass", "argon2i_raw", "argon2i_verify",
                    "argon2d_pass", "argon2d_raw", "argon2d_verify",
                    "argon2_implementation", "_argon2_random_bytes"}) {
                mod.registerMethod(method, null);
            }
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Crypt::Argon2 Java binding is incomplete", e);
        }
    }

    public static RuntimeList argon2_pass(RuntimeArray args, int ctx) {
        requireArgs(args, 7, "argon2_pass");
        return pass(args.get(0).toString(), args, 1).getList();
    }

    public static RuntimeList argon2_raw(RuntimeArray args, int ctx) {
        requireArgs(args, 7, "argon2_raw");
        return raw(args.get(0).toString(), args, 1).getList();
    }

    public static RuntimeList argon2id_pass(RuntimeArray args, int ctx) { return pass("argon2id", args, 0).getList(); }
    public static RuntimeList argon2i_pass(RuntimeArray args, int ctx) { return pass("argon2i", args, 0).getList(); }
    public static RuntimeList argon2d_pass(RuntimeArray args, int ctx) { return pass("argon2d", args, 0).getList(); }
    public static RuntimeList argon2id_raw(RuntimeArray args, int ctx) { return raw("argon2id", args, 0).getList(); }
    public static RuntimeList argon2i_raw(RuntimeArray args, int ctx) { return raw("argon2i", args, 0).getList(); }
    public static RuntimeList argon2d_raw(RuntimeArray args, int ctx) { return raw("argon2d", args, 0).getList(); }

    public static RuntimeList argon2_verify(RuntimeArray args, int ctx) { return verify(null, args).getList(); }
    public static RuntimeList argon2id_verify(RuntimeArray args, int ctx) { return verify("argon2id", args).getList(); }
    public static RuntimeList argon2i_verify(RuntimeArray args, int ctx) { return verify("argon2i", args).getList(); }
    public static RuntimeList argon2d_verify(RuntimeArray args, int ctx) { return verify("argon2d", args).getList(); }

    public static RuntimeList argon2_implementation(RuntimeArray args, int ctx) {
        return new RuntimeScalar("bouncycastle").getList();
    }

    public static RuntimeList _argon2_random_bytes(RuntimeArray args, int ctx) {
        int length = args.isEmpty() ? 16 : args.get(0).getInt();
        byte[] output = new byte[Math.max(0, length)];
        RANDOM.nextBytes(output);
        return new RuntimeScalar(new String(output, StandardCharsets.ISO_8859_1)).getList();
    }

    private static RuntimeScalar pass(String type, RuntimeArray args, int offset) {
        requireArgs(args, offset + 6, type + "_pass");
        byte[] salt = bytes(args.get(offset + 1));
        int t = args.get(offset + 2).getInt();
        int memory = memoryKiB(args.get(offset + 3).toString(), type);
        int lanes = args.get(offset + 4).getInt();
        byte[] tag = derive(type, bytes(args.get(offset)), salt, t, memory, lanes,
                args.get(offset + 5).getInt());
        Base64.Encoder b64 = Base64.getEncoder().withoutPadding();
        String encoded = "$" + type + "$v=19$m=" + memory + ",t=" + t + ",p=" + lanes
                + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(tag);
        return new RuntimeScalar(encoded);
    }

    private static RuntimeScalar raw(String type, RuntimeArray args, int offset) {
        requireArgs(args, offset + 6, type + "_raw");
        byte[] tag = derive(type, bytes(args.get(offset)), bytes(args.get(offset + 1)),
                args.get(offset + 2).getInt(), memoryKiB(args.get(offset + 3).toString(), type),
                args.get(offset + 4).getInt(), args.get(offset + 5).getInt());
        return new RuntimeScalar(new String(tag, StandardCharsets.ISO_8859_1));
    }

    private static RuntimeScalar verify(String requiredType, RuntimeArray args) {
        requireArgs(args, 2, "argon2_verify");
        Matcher match = ENCODED.matcher(args.get(0).toString());
        if (!match.matches()) {
            throw new PerlCompilerException("Could not detect argon2 type: missing '$' separator");
        }
        String type = match.group(1);
        if (requiredType != null && !requiredType.equals(type)) return new RuntimeScalar(0);
        try {
            byte[] salt = Base64.getDecoder().decode(padBase64(match.group(6)));
            byte[] expected = Base64.getDecoder().decode(padBase64(match.group(7)));
            byte[] actual = derive(type, bytes(args.get(1)), salt,
                    Integer.parseInt(match.group(4)), Integer.parseInt(match.group(3)),
                    Integer.parseInt(match.group(5)), expected.length);
            return new RuntimeScalar(MessageDigest.isEqual(expected, actual) ? 1 : 0);
        } catch (IllegalArgumentException e) {
            throw new PerlCompilerException("Could not verify " + type + " tag: decoding failed");
        }
    }

    private static byte[] derive(String type, byte[] password, byte[] salt,
                                 int iterations, int memory, int lanes, int size) {
        int bcType = switch (type) {
            case "argon2i" -> Argon2Parameters.ARGON2_i;
            case "argon2d" -> Argon2Parameters.ARGON2_d;
            case "argon2id" -> Argon2Parameters.ARGON2_id;
            default -> throw new PerlCompilerException("No such argon2 type " + type);
        };
        try {
            Argon2Parameters params = new Argon2Parameters.Builder(bcType)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withSalt(salt).withIterations(iterations).withMemoryAsKB(memory)
                    .withParallelism(lanes).build();
            Argon2BytesGenerator generator = new Argon2BytesGenerator();
            generator.init(params);
            byte[] output = new byte[size];
            generator.generateBytes(password, output);
            return output;
        } catch (RuntimeException e) {
            throw new PerlCompilerException("Couldn't compute " + type + " tag: " + e.getMessage());
        }
    }

    private static int memoryKiB(String text, String type) {
        Matcher matcher = Pattern.compile("^(\\d+)([kMG]?)$").matcher(text);
        if (!matcher.matches()) {
            throw new PerlCompilerException("Couldn't compute " + type + " tag: memory cost doesn't contain anything numeric");
        }
        long value = Long.parseLong(matcher.group(1));
        value = switch (matcher.group(2)) {
            case "k" -> value;
            case "M" -> value * 1024L;
            case "G" -> value * 1024L * 1024L;
            default -> {
                if (value <= 1024) throw new PerlCompilerException(
                        "Couldn't compute " + type + " tag: Memory size must be at least a kilobyte");
                yield value / 1024L;
            }
        };
        if (value > Integer.MAX_VALUE) throw new PerlCompilerException("Couldn't compute " + type + " tag: memory cost too large");
        return (int) value;
    }

    private static byte[] bytes(RuntimeScalar scalar) {
        return scalar.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String padBase64(String input) {
        return input + "=".repeat((4 - input.length() % 4) % 4);
    }

    private static void requireArgs(RuntimeArray args, int count, String name) {
        if (args.size() < count) throw new PerlCompilerException("Usage: Crypt::Argon2::" + name);
    }
}
