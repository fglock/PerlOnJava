package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.*;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal Net::SSLeay stub for PerlOnJava.
 * <p>
 * Provides only the constants, version info, and no-op initialization functions
 * that IO::Socket::SSL probes for. Actual SSL operations are handled by
 * IOSocketSSL.java using javax.net.ssl.
 */
public class NetSSLeay extends PerlModuleBase {

    // Map of constant names to their values
    private static final Map<String, Long> CONSTANTS = new HashMap<>();

    static {
        // SSL error constants
        CONSTANTS.put("ERROR_NONE", 0L);
        CONSTANTS.put("ERROR_SSL", 1L);
        CONSTANTS.put("ERROR_WANT_READ", 2L);
        CONSTANTS.put("ERROR_WANT_WRITE", 3L);
        CONSTANTS.put("ERROR_WANT_X509_LOOKUP", 4L);
        CONSTANTS.put("ERROR_SYSCALL", 5L);
        CONSTANTS.put("ERROR_ZERO_RETURN", 6L);
        CONSTANTS.put("ERROR_WANT_CONNECT", 7L);
        CONSTANTS.put("ERROR_WANT_ACCEPT", 8L);

        // Verify mode constants
        CONSTANTS.put("VERIFY_NONE", 0L);
        CONSTANTS.put("VERIFY_PEER", 1L);
        CONSTANTS.put("VERIFY_FAIL_IF_NO_PEER_CERT", 2L);
        CONSTANTS.put("VERIFY_CLIENT_ONCE", 4L);

        // File type constants
        CONSTANTS.put("FILETYPE_PEM", 1L);
        CONSTANTS.put("FILETYPE_ASN1", 2L);

        // SSL OP constants (matching OpenSSL 3.x values)
        CONSTANTS.put("OP_ALL", 0x80000BFFL);
        CONSTANTS.put("OP_SINGLE_DH_USE", 0x00100000L);
        CONSTANTS.put("OP_SINGLE_ECDH_USE", 0x00080000L);
        CONSTANTS.put("OP_NO_SSLv2", 0x01000000L);
        CONSTANTS.put("OP_NO_SSLv3", 0x02000000L);
        CONSTANTS.put("OP_NO_TLSv1", 0x04000000L);
        CONSTANTS.put("OP_NO_TLSv1_1", 0x10000000L);
        CONSTANTS.put("OP_NO_TLSv1_2", 0x08000000L);
        CONSTANTS.put("OP_NO_TLSv1_3", 0x20000000L);
        CONSTANTS.put("OP_CIPHER_SERVER_PREFERENCE", 0x00400000L);
        CONSTANTS.put("OP_NO_COMPRESSION", 0x00020000L);

        // SSL mode constants
        CONSTANTS.put("MODE_ENABLE_PARTIAL_WRITE", 1L);
        CONSTANTS.put("MODE_ACCEPT_MOVING_WRITE_BUFFER", 2L);
        CONSTANTS.put("MODE_AUTO_RETRY", 4L);

        // X509 verify flags
        CONSTANTS.put("X509_V_FLAG_TRUSTED_FIRST", 0x8000L);
        CONSTANTS.put("X509_V_FLAG_PARTIAL_CHAIN", 0x80000L);
        CONSTANTS.put("X509_V_FLAG_CRL_CHECK", 0x4L);

        // OCSP constants
        CONSTANTS.put("TLSEXT_STATUSTYPE_ocsp", 1L);
        CONSTANTS.put("OCSP_RESPONSE_STATUS_SUCCESSFUL", 0L);
        CONSTANTS.put("V_OCSP_CERTSTATUS_GOOD", 0L);

        // TLS version constants
        CONSTANTS.put("TLS1_VERSION", 0x0301L);
        CONSTANTS.put("TLS1_1_VERSION", 0x0302L);
        CONSTANTS.put("TLS1_2_VERSION", 0x0303L);
        CONSTANTS.put("TLS1_3_VERSION", 0x0304L);

        // Session cache modes
        CONSTANTS.put("SESS_CACHE_CLIENT", 1L);
        CONSTANTS.put("SESS_CACHE_SERVER", 2L);
        CONSTANTS.put("SESS_CACHE_BOTH", 3L);
        CONSTANTS.put("SESS_CACHE_OFF", 0L);

        // NID constants (for X509_NAME_get_text_by_NID)
        CONSTANTS.put("NID_commonName", 13L);
        CONSTANTS.put("NID_subject_alt_name", 85L);

        // Shutdown constants
        CONSTANTS.put("SSL_SENT_SHUTDOWN", 1L);
        CONSTANTS.put("SSL_RECEIVED_SHUTDOWN", 2L);

        // LIBRESSL_VERSION_NUMBER (we are not LibreSSL)
        CONSTANTS.put("LIBRESSL_VERSION_NUMBER", 0L);

        // OPENSSL_VERSION_NUMBER (report as 3.0.0)
        CONSTANTS.put("OPENSSL_VERSION_NUMBER", 0x30000000L);

        // SSLeay_version() type constants
        CONSTANTS.put("SSLEAY_VERSION", 0L);
        CONSTANTS.put("SSLEAY_CFLAGS", 2L);
        CONSTANTS.put("SSLEAY_BUILT_ON", 3L);
        CONSTANTS.put("SSLEAY_PLATFORM", 4L);
        CONSTANTS.put("SSLEAY_DIR", 5L);
        CONSTANTS.put("OPENSSL_VERSION", 0L);
        CONSTANTS.put("OPENSSL_CFLAGS", 2L);
        CONSTANTS.put("OPENSSL_BUILT_ON", 3L);
        CONSTANTS.put("OPENSSL_PLATFORM", 4L);
        CONSTANTS.put("OPENSSL_DIR", 5L);

        // OpenSSL 3.x version component constants
        CONSTANTS.put("OPENSSL_VERSION_MAJOR", 3L);
        CONSTANTS.put("OPENSSL_VERSION_MINOR", 0L);
        CONSTANTS.put("OPENSSL_VERSION_PATCH", 0L);

        // OPENSSL_info() type constants
        CONSTANTS.put("OPENSSL_INFO_CONFIG_DIR", 1001L);
        CONSTANTS.put("OPENSSL_INFO_ENGINES_DIR", 1002L);
        CONSTANTS.put("OPENSSL_INFO_MODULES_DIR", 1003L);
        CONSTANTS.put("OPENSSL_INFO_DSO_EXTENSION", 1004L);
        CONSTANTS.put("OPENSSL_INFO_DIR_FILENAME_SEPARATOR", 1005L);
        CONSTANTS.put("OPENSSL_INFO_LIST_SEPARATOR", 1006L);
        CONSTANTS.put("OPENSSL_INFO_SEED_SOURCE", 1007L);
        CONSTANTS.put("OPENSSL_INFO_CPU_SETTINGS", 1008L);

        // Additional OpenSSL_version() type constants (OpenSSL 1.1.1+)
        CONSTANTS.put("OPENSSL_ENGINES_DIR", 6L);
        // OpenSSL 3.0+ version info type constants
        CONSTANTS.put("OPENSSL_MODULES_DIR", 7L);
        CONSTANTS.put("OPENSSL_CPU_INFO", 8L);
        CONSTANTS.put("OPENSSL_FULL_VERSION_STRING", 9L);
        CONSTANTS.put("OPENSSL_VERSION_STRING", 10L);
    }

    // Report as OpenSSL 3.0.0 — modern enough for IO::Socket::SSL features
    private static final long OPENSSL_VERSION_HEX = 0x30000000L;

    // Shared SecureRandom instance for RAND_* functions
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // Thread-local error queue for ERR_put_error / ERR_get_error
    private static final ThreadLocal<Deque<Long>> ERROR_QUEUE =
            ThreadLocal.withInitial(ArrayDeque::new);

    // Counter for generating unique opaque handle IDs
    private static final AtomicLong HANDLE_COUNTER = new AtomicLong(1);

    // Maps for opaque handles: handle_id → Java object
    private static final Map<Long, MemoryBIO> BIO_HANDLES = new HashMap<>();
    private static final Map<Long, EvpMdCtx> EVP_MD_CTX_HANDLES = new HashMap<>();
    private static final Map<Long, KeyPair> RSA_HANDLES = new HashMap<>();

    // OpenSSL NID constants
    private static final Map<String, Integer> NAME_TO_NID = new HashMap<>();
    private static final Map<Integer, String> NID_TO_NAME = new HashMap<>();
    private static final Map<String, String> NAME_TO_JAVA_ALG = new HashMap<>();

    static {
        // NID mappings (OpenSSL name → NID, NID → OpenSSL name, OpenSSL name → Java alg)
        addDigest("md2", 3, "MD2");
        addDigest("md4", 257, null);  // MD4 not in standard JCE
        addDigest("md5", 4, "MD5");
        addDigest("sha1", 64, "SHA-1");
        addDigest("sha224", 675, "SHA-224");
        addDigest("sha256", 672, "SHA-256");
        addDigest("sha384", 673, "SHA-384");
        addDigest("sha512", 674, "SHA-512");
        addDigest("sha512-224", 1094, "SHA-512/224");
        addDigest("sha512-256", 1095, "SHA-512/256");
        addDigest("sha3-224", 1096, "SHA3-224");
        addDigest("sha3-256", 1097, "SHA3-256");
        addDigest("sha3-384", 1098, "SHA3-384");
        addDigest("sha3-512", 1099, "SHA3-512");
        addDigest("ripemd160", 117, "RIPEMD160");
        // Add uppercase aliases
        for (Map.Entry<String, Integer> entry : new ArrayList<>(NAME_TO_NID.entrySet())) {
            String upper = entry.getKey().toUpperCase();
            if (!NAME_TO_NID.containsKey(upper)) {
                NAME_TO_NID.put(upper, entry.getValue());
                String javaAlg = NAME_TO_JAVA_ALG.get(entry.getKey());
                if (javaAlg != null) NAME_TO_JAVA_ALG.put(upper, javaAlg);
            }
        }
        // Additional NID constants
        CONSTANTS.put("NID_md5", 4L);
        CONSTANTS.put("NID_sha1", 64L);
        CONSTANTS.put("NID_sha224", 675L);
        CONSTANTS.put("NID_sha256", 672L);
        CONSTANTS.put("NID_sha384", 673L);
        CONSTANTS.put("NID_sha512", 674L);
        CONSTANTS.put("NID_sha3_256", 1097L);
        CONSTANTS.put("NID_sha3_512", 1099L);
        CONSTANTS.put("NID_ripemd160", 117L);
    }

    private static void addDigest(String opensslName, int nid, String javaAlg) {
        NAME_TO_NID.put(opensslName, nid);
        NID_TO_NAME.put(nid, opensslName);
        if (javaAlg != null) {
            NAME_TO_JAVA_ALG.put(opensslName, javaAlg);
        }
    }

    // Inner class: Memory BIO buffer
    private static class MemoryBIO {
        private byte[] data = new byte[0];
        private int readPos = 0;

        void write(byte[] bytes) {
            byte[] newData = new byte[data.length + bytes.length];
            System.arraycopy(data, 0, newData, 0, data.length);
            System.arraycopy(bytes, 0, newData, data.length, bytes.length);
            data = newData;
        }

        int pending() {
            return data.length - readPos;
        }

        byte[] read(int maxLen) {
            int available = Math.min(maxLen, pending());
            if (available <= 0) return new byte[0];
            byte[] result = new byte[available];
            System.arraycopy(data, readPos, result, 0, available);
            readPos += available;
            // Compact if all data has been read
            if (readPos == data.length) {
                data = new byte[0];
                readPos = 0;
            }
            return result;
        }
    }

    // Inner class: EVP_MD context wrapper
    private static class EvpMdCtx {
        MessageDigest digest;
        String algorithmName; // OpenSSL name (e.g. "sha256")
        int nid;

        EvpMdCtx() {
            this.digest = null;
            this.algorithmName = null;
            this.nid = 0;
        }

        EvpMdCtx(EvpMdCtx other) {
            try {
                this.digest = other.digest != null ? (MessageDigest) other.digest.clone() : null;
            } catch (CloneNotSupportedException e) {
                this.digest = null;
            }
            this.algorithmName = other.algorithmName;
            this.nid = other.nid;
        }
    }

    // Sentinel value for BIO_s_mem() method type
    private static final long BIO_S_MEM_SENTINEL = -1L;

    public NetSSLeay() {
        super("Net::SSLeay", false);
    }

    public static void initialize() {
        NetSSLeay mod = new NetSSLeay();
        mod.initializeExporter();

        // Set $Net::SSLeay::VERSION
        GlobalVariable.getGlobalVariable("Net::SSLeay::VERSION").set(new RuntimeScalar("1.96"));
        // Set $Net::SSLeay::trace (used by IO::Socket::SSL for debug logging)
        GlobalVariable.getGlobalVariable("Net::SSLeay::trace").set(new RuntimeScalar(0));

        try {
            // Constant lookup function (called by AUTOLOAD in upstream Net::SSLeay)
            mod.registerMethod("constant", null);

            // Library initialization (no-ops — JVM handles SSL natively)
            mod.registerMethod("library_init", null);
            mod.registerMethod("load_error_strings", null);
            mod.registerMethod("ERR_load_crypto_strings", null);
            mod.registerMethod("SSLeay_add_ssl_algorithms", null);
            mod.registerMethod("OpenSSL_add_all_digests", null);
            mod.registerMethod("randomize", null);
            mod.registerMethod("hello", null);

            // Version info
            mod.registerMethod("SSLeay", null);
            mod.registerMethod("SSLeay_version", null);
            mod.registerMethod("OpenSSL_version", null);
            mod.registerMethod("OpenSSL_version_num", null);
            mod.registerMethod("OPENSSL_VERSION_NUMBER", "");
            mod.registerMethod("OPENSSL_version_major", null);
            mod.registerMethod("OPENSSL_version_minor", null);
            mod.registerMethod("OPENSSL_version_patch", null);
            mod.registerMethod("OPENSSL_version_pre_release", null);
            mod.registerMethod("OPENSSL_version_build_metadata", null);
            mod.registerMethod("OPENSSL_info", null);

            // Error functions
            mod.registerMethod("ERR_clear_error", null);
            mod.registerMethod("ERR_get_error", null);
            mod.registerMethod("ERR_peek_error", null);
            mod.registerMethod("ERR_error_string", null);
            mod.registerMethod("ERR_put_error", null);
            // print_errs is implemented in Perl (Net/SSLeay.pm) to use Perl's warn()

            // RAND functions
            mod.registerMethod("RAND_status", null);
            mod.registerMethod("RAND_poll", null);
            mod.registerMethod("RAND_bytes", null);
            mod.registerMethod("RAND_pseudo_bytes", null);
            mod.registerMethod("RAND_priv_bytes", null);
            mod.registerMethod("RAND_file_name", null);
            mod.registerMethod("RAND_load_file", null);
            mod.registerMethod("RAND_write_file", null);
            mod.registerMethod("RAND_seed", null);
            mod.registerMethod("RAND_cleanup", null);
            mod.registerMethod("RAND_add", null);

            // BIO memory functions
            mod.registerMethod("BIO_s_mem", null);
            mod.registerMethod("BIO_new", null);
            mod.registerMethod("BIO_new_file", null);
            mod.registerMethod("BIO_free", null);
            mod.registerMethod("BIO_read", null);
            mod.registerMethod("BIO_write", null);
            mod.registerMethod("BIO_pending", null);
            mod.registerMethod("BIO_eof", null);

            // RSA functions
            mod.registerMethod("RSA_generate_key", null);
            mod.registerMethod("RSA_free", null);

            // EVP digest functions
            mod.registerMethod("EVP_get_digestbyname", null);
            mod.registerMethod("EVP_MD_CTX_create", null);
            mod.registerMethod("EVP_MD_CTX_new", null);
            mod.registerMethod("EVP_MD_CTX_destroy", null);
            mod.registerMethod("EVP_MD_CTX_free", null);
            mod.registerMethod("EVP_DigestInit", null);
            mod.registerMethod("EVP_DigestInit_ex", null);
            mod.registerMethod("EVP_DigestUpdate", null);
            mod.registerMethod("EVP_DigestFinal", null);
            mod.registerMethod("EVP_DigestFinal_ex", null);
            mod.registerMethod("EVP_Digest", null);
            mod.registerMethod("EVP_MD_type", null);
            mod.registerMethod("EVP_MD_size", null);
            mod.registerMethod("EVP_MD_CTX_md", null);
            mod.registerMethod("EVP_MD_CTX_size", null);
            mod.registerMethod("EVP_sha1", null);
            mod.registerMethod("EVP_sha224", null);
            mod.registerMethod("EVP_sha256", null);
            mod.registerMethod("EVP_sha384", null);
            mod.registerMethod("EVP_sha512", null);
            mod.registerMethod("EVP_md5", null);
            mod.registerMethod("EVP_MD_get0_name", null);
            mod.registerMethod("EVP_MD_get0_description", null);
            mod.registerMethod("EVP_MD_get_type", null);
            mod.registerMethod("P_EVP_MD_list_all", null);

            // Convenience digest functions
            mod.registerMethod("MD5", null);
            mod.registerMethod("SHA1", null);
            mod.registerMethod("SHA256", null);
            mod.registerMethod("SHA512", null);
            mod.registerMethod("RIPEMD160", null);

            // Register commonly-accessed constants as subs with empty prototype
            for (String name : CONSTANTS.keySet()) {
                mod.registerMethod(name, name, "");
            }

            // Define exports
            String[] exportOk = CONSTANTS.keySet().toArray(new String[0]);
            mod.defineExport("EXPORT_OK", exportOk);
            mod.defineExport("EXPORT_OK",
                    "constant", "library_init", "load_error_strings",
                    "SSLeay_add_ssl_algorithms", "OpenSSL_add_all_digests",
                    "randomize", "SSLeay", "SSLeay_version",
                    "OPENSSL_VERSION_NUMBER",
                    "ERR_clear_error", "ERR_get_error", "ERR_peek_error",
                    "ERR_error_string", "ERR_put_error", "print_errs",
                    "RAND_status", "RAND_poll", "RAND_bytes", "RAND_pseudo_bytes",
                    "RAND_priv_bytes", "RAND_file_name", "RAND_load_file",
                    "RAND_write_file", "RAND_seed", "RAND_cleanup", "RAND_add",
                    "BIO_s_mem", "BIO_new", "BIO_new_file", "BIO_free",
                    "BIO_read", "BIO_write", "BIO_pending", "BIO_eof",
                    "RSA_generate_key", "RSA_free",
                    "EVP_get_digestbyname", "EVP_MD_CTX_create", "EVP_MD_CTX_new",
                    "EVP_MD_CTX_destroy", "EVP_MD_CTX_free",
                    "EVP_DigestInit", "EVP_DigestInit_ex",
                    "EVP_DigestUpdate", "EVP_DigestFinal", "EVP_DigestFinal_ex",
                    "EVP_Digest", "EVP_MD_type", "EVP_MD_size", "EVP_MD_CTX_md",
                    "EVP_sha1", "EVP_sha224", "EVP_sha256", "EVP_sha384", "EVP_sha512",
                    "EVP_md5", "EVP_MD_get0_name", "EVP_MD_get0_description",
                    "EVP_MD_get_type", "P_EVP_MD_list_all",
                    "MD5", "SHA1", "SHA256", "SHA512", "RIPEMD160");

        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing NetSSLeay method: " + e.getMessage());
        }
    }

    // ---- Constant lookup (prevents AUTOLOAD infinite recursion) ----

    public static RuntimeList constant(RuntimeArray args, int ctx) {
        String name = args.size() > 0 ? args.get(0).toString() : "";
        Long val = CONSTANTS.get(name);
        if (val != null) {
            // Clear errno to signal success
            GlobalVariable.getGlobalVariable("main::!").set(new RuntimeScalar(0));
            return new RuntimeScalar(val).getList();
        }
        // Distinguish OpenSSL constant names from function names:
        // - ALL_CAPS names (like AD_CLOSE_NOTIFY) are OpenSSL macros → ENOENT
        //   This makes AUTOLOAD croak "Your vendor has not defined SSLeay macro ..."
        // - Other names (like doesnt_exist) are not constants → EINVAL
        //   This makes AUTOLOAD fall through to AutoLoader for .al file lookup
        if (name.length() > 0 && (name.charAt(0) == '_' || Character.isUpperCase(name.charAt(0)))) {
            // Looks like an OpenSSL constant name — set ENOENT ("not supported")
            GlobalVariable.getGlobalVariable("main::!").set(new RuntimeScalar(2)); // ENOENT
        } else {
            // Not a constant name — set EINVAL ("invalid") to trigger AutoLoader
            GlobalVariable.getGlobalVariable("main::!").set(new RuntimeScalar(22)); // EINVAL
        }
        return new RuntimeScalar(0).getList();
    }

    // ---- No-op initialization functions ----

    public static RuntimeList library_init(RuntimeArray args, int ctx) {
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList load_error_strings(RuntimeArray args, int ctx) {
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList ERR_load_crypto_strings(RuntimeArray args, int ctx) {
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList SSLeay_add_ssl_algorithms(RuntimeArray args, int ctx) {
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList OpenSSL_add_all_digests(RuntimeArray args, int ctx) {
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList randomize(RuntimeArray args, int ctx) {
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList hello(RuntimeArray args, int ctx) {
        return new RuntimeScalar(1).getList();
    }

    // ---- Version info ----

    public static RuntimeList SSLeay(RuntimeArray args, int ctx) {
        return new RuntimeScalar(OPENSSL_VERSION_HEX).getList();
    }

    public static RuntimeList SSLeay_version(RuntimeArray args, int ctx) {
        int type = args.size() > 0 ? (int) args.get(0).getLong() : 0;
        switch (type) {
            case 0: // SSLEAY_VERSION / OPENSSL_VERSION
                return new RuntimeScalar("PerlOnJava TLS (Java " +
                        System.getProperty("java.version") + ")").getList();
            case 2: // SSLEAY_CFLAGS / OPENSSL_CFLAGS
                return new RuntimeScalar("compiler: javac").getList();
            case 3: // SSLEAY_BUILT_ON / OPENSSL_BUILT_ON
                return new RuntimeScalar("built on: JVM").getList();
            case 4: // SSLEAY_PLATFORM / OPENSSL_PLATFORM
                return new RuntimeScalar("platform: " + System.getProperty("os.name")).getList();
            case 5: // SSLEAY_DIR / OPENSSL_DIR
                return new RuntimeScalar("OPENSSLDIR: \"\"").getList();
            case 6: // OPENSSL_ENGINES_DIR
                return new RuntimeScalar("ENGINESDIR: \"\"").getList();
            case 7: // OPENSSL_MODULES_DIR
                return new RuntimeScalar("MODULESDIR: \"\"").getList();
            case 8: // OPENSSL_CPU_INFO
                return new RuntimeScalar("CPUINFO: " + System.getProperty("os.arch")).getList();
            case 9: // OPENSSL_FULL_VERSION_STRING
                return new RuntimeScalar("PerlOnJava TLS 3.0.0").getList();
            case 10: // OPENSSL_VERSION_STRING
                return new RuntimeScalar("3.0.0").getList();
            default:
                return new RuntimeScalar("PerlOnJava TLS (Java " +
                        System.getProperty("java.version") + ")").getList();
        }
    }

    // OpenSSL_version is an alias for SSLeay_version
    public static RuntimeList OpenSSL_version(RuntimeArray args, int ctx) {
        return SSLeay_version(args, ctx);
    }

    // OpenSSL_version_num is an alias for SSLeay (returns numeric version)
    public static RuntimeList OpenSSL_version_num(RuntimeArray args, int ctx) {
        return new RuntimeScalar(OPENSSL_VERSION_HEX).getList();
    }

    // OpenSSL 3.x version component functions
    public static RuntimeList OPENSSL_version_major(RuntimeArray args, int ctx) {
        return new RuntimeScalar(3).getList();
    }

    public static RuntimeList OPENSSL_version_minor(RuntimeArray args, int ctx) {
        return new RuntimeScalar(0).getList();
    }

    public static RuntimeList OPENSSL_version_patch(RuntimeArray args, int ctx) {
        return new RuntimeScalar(0).getList();
    }

    public static RuntimeList OPENSSL_version_pre_release(RuntimeArray args, int ctx) {
        return new RuntimeScalar("").getList();
    }

    public static RuntimeList OPENSSL_version_build_metadata(RuntimeArray args, int ctx) {
        return new RuntimeScalar("").getList();
    }

    public static RuntimeList OPENSSL_info(RuntimeArray args, int ctx) {
        int type = args.size() > 0 ? (int) args.get(0).getLong() : -1;
        switch (type) {
            case 1001: // OPENSSL_INFO_CONFIG_DIR
                return new RuntimeScalar("").getList();
            case 1002: // OPENSSL_INFO_ENGINES_DIR
                return new RuntimeScalar("").getList();
            case 1003: // OPENSSL_INFO_MODULES_DIR
                return new RuntimeScalar("").getList();
            case 1004: // OPENSSL_INFO_DSO_EXTENSION
                return new RuntimeScalar(".so").getList();
            case 1005: // OPENSSL_INFO_DIR_FILENAME_SEPARATOR
                return new RuntimeScalar("/").getList();
            case 1006: // OPENSSL_INFO_LIST_SEPARATOR
                return new RuntimeScalar(":").getList();
            case 1007: // OPENSSL_INFO_SEED_SOURCE
                return new RuntimeScalar("os-specific").getList();
            case 1008: // OPENSSL_INFO_CPU_SETTINGS
                return new RuntimeScalar("").getList();
            default:
                return new RuntimeScalar().getList(); // undef for unknown types
        }
    }

    public static RuntimeList OPENSSL_VERSION_NUMBER(RuntimeArray args, int ctx) {
        return new RuntimeScalar(OPENSSL_VERSION_HEX).getList();
    }

    // ---- Error functions (thread-local error queue) ----

    public static RuntimeList ERR_clear_error(RuntimeArray args, int ctx) {
        ERROR_QUEUE.get().clear();
        return new RuntimeScalar(0).getList();
    }

    public static RuntimeList ERR_get_error(RuntimeArray args, int ctx) {
        Deque<Long> queue = ERROR_QUEUE.get();
        if (queue.isEmpty()) {
            return new RuntimeScalar(0).getList();
        }
        return new RuntimeScalar(queue.pollFirst()).getList();
    }

    public static RuntimeList ERR_peek_error(RuntimeArray args, int ctx) {
        Deque<Long> queue = ERROR_QUEUE.get();
        if (queue.isEmpty()) {
            return new RuntimeScalar(0).getList();
        }
        return new RuntimeScalar(queue.peekFirst()).getList();
    }

    public static RuntimeList ERR_error_string(RuntimeArray args, int ctx) {
        long errorCode = args.size() > 0 ? args.get(0).getLong() : 0;
        if (errorCode == 0) {
            return new RuntimeScalar("").getList();
        }
        // OpenSSL 3.0.0 format: lib(9 bits) << 23 | reason(23 bits)
        int lib = (int) ((errorCode >> 23) & 0x1FF);
        int reason = (int) (errorCode & 0x7FFFFF);
        String libName = getLibName(lib);
        String reasonStr = getReasonString(lib, reason);
        return new RuntimeScalar(String.format("error:%08X:%s::%s",
                errorCode, libName, reasonStr)).getList();
    }

    public static RuntimeList ERR_put_error(RuntimeArray args, int ctx) {
        // ERR_put_error(lib, func, reason, file, line)
        int lib = args.size() > 0 ? (int) args.get(0).getLong() : 0;
        // func is ignored in OpenSSL 3.0.0 error packing
        int reason = args.size() > 2 ? (int) args.get(2).getLong() : 0;
        // OpenSSL 3.0.0 packing: lib << 23 | reason
        long errorCode = ((long) lib << 23) | (reason & 0x7FFFFF);
        ERROR_QUEUE.get().addLast(errorCode);
        return new RuntimeScalar(0).getList();
    }

    // Library name lookup for error strings
    private static String getLibName(int lib) {
        switch (lib) {
            case 2: return "RSA routines";
            case 6: return "EVP routines";
            case 9: return "PEM routines";
            case 13: return "ASN1 routines";
            case 20: return "X509 routines";
            case 32: return "BIO routines";
            case 33: return "PKCS7 routines";
            case 35: return "X509V3 routines";
            case 36: return "PKCS12 routines";
            case 37: return "RAND routines";
            case 38: return "DSO routines";
            case 41: return "OCSP routines";
            case 47: return "engine routines";
            default: return "lib(" + lib + ")";
        }
    }

    // Reason string lookup for error strings
    private static String getReasonString(int lib, int reason) {
        // BIO reasons
        if (lib == 32) {
            switch (reason) {
                case 128: return "no such file";
                case 2: return "accept error";
                case 109: return "in use";
                default: return "reason(" + reason + ")";
            }
        }
        return "reason(" + reason + ")";
    }

    public static RuntimeList print_errs(RuntimeArray args, int ctx) {
        String prefix = args.size() > 0 ? args.get(0).toString() : "";
        Deque<Long> queue = ERROR_QUEUE.get();
        if (queue.isEmpty()) {
            return new RuntimeScalar("").getList();
        }
        long pid = ProcessHandle.current().pid();
        StringBuilder sb = new StringBuilder();
        int count = 0;
        while (!queue.isEmpty()) {
            long err = queue.pollFirst();
            count++;
            // Format matching Perl Net::SSLeay::print_errs:
            // "$prefix $pid: $count - " . ERR_error_string($err)
            int lib = (int) ((err >> 23) & 0x1FF);
            int reason = (int) (err & 0x7FFFFF);
            String libName = getLibName(lib);
            String reasonStr = getReasonString(lib, reason);
            sb.append(prefix).append(" ").append(pid).append(": ")
                    .append(count).append(" - ")
                    .append(String.format("error:%08X:%s::%s", err, libName, reasonStr))
                    .append("\n");
        }
        // Warn if trace is enabled (checked by caller in Perl usually,
        // but we handle it here for completeness)
        RuntimeScalar trace = GlobalVariable.getGlobalVariable("Net::SSLeay::trace");
        if (trace.getBoolean()) {
            System.err.print(sb);
        }
        return new RuntimeScalar(sb.toString()).getList();
    }

    // ---- RAND functions (backed by java.security.SecureRandom) ----

    public static RuntimeList RAND_status(RuntimeArray args, int ctx) {
        // SecureRandom is always seeded
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList RAND_poll(RuntimeArray args, int ctx) {
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList RAND_seed(RuntimeArray args, int ctx) {
        if (args.size() > 0) {
            byte[] seed = args.get(0).toString().getBytes(StandardCharsets.ISO_8859_1);
            SECURE_RANDOM.setSeed(seed);
        }
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList RAND_add(RuntimeArray args, int ctx) {
        // RAND_add(buf, num, entropy) - add data to PRNG
        if (args.size() > 0) {
            byte[] seed = args.get(0).toString().getBytes(StandardCharsets.ISO_8859_1);
            SECURE_RANDOM.setSeed(seed);
        }
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList RAND_cleanup(RuntimeArray args, int ctx) {
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList RAND_bytes(RuntimeArray args, int ctx) {
        // RAND_bytes($buf, $num) - fills $buf with $num random bytes, returns 1 on success
        int num = args.size() > 1 ? (int) args.get(1).getLong() : 0;
        if (num == 0) {
            args.get(0).set(new RuntimeScalar(""));
            return new RuntimeScalar(1).getList();
        }
        if (num < 0) return new RuntimeScalar(0).getList();
        byte[] bytes = new byte[num];
        SECURE_RANDOM.nextBytes(bytes);
        RuntimeScalar result = new RuntimeScalar(new String(bytes, StandardCharsets.ISO_8859_1));
        result.type = RuntimeScalarType.BYTE_STRING;
        args.get(0).set(result);
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList RAND_pseudo_bytes(RuntimeArray args, int ctx) {
        // Same as RAND_bytes on modern systems
        int num = args.size() > 1 ? (int) args.get(1).getLong() : 0;
        if (num == 0) {
            args.get(0).set(new RuntimeScalar(""));
            return new RuntimeScalar(1).getList();
        }
        if (num < 0) {
            return new RuntimeScalar(0).getList();
        }
        byte[] bytes = new byte[num];
        SECURE_RANDOM.nextBytes(bytes);
        RuntimeScalar buf = new RuntimeScalar(new String(bytes, StandardCharsets.ISO_8859_1));
        buf.type = RuntimeScalarType.BYTE_STRING;
        args.get(0).set(buf);
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList RAND_priv_bytes(RuntimeArray args, int ctx) {
        return RAND_bytes(args, ctx);
    }

    public static RuntimeList RAND_file_name(RuntimeArray args, int ctx) {
        // RAND_file_name(buf_size) — returns path to random seed file
        // Respects RANDFILE env var; falls back to $HOME/.rnd
        // Returns undef if buffer too short (OpenSSL 1.1.0a+ behavior)
        int bufSize = args.size() > 0 ? (int) args.get(0).getLong() : 256;

        // Read from Perl's %ENV (not Java's System.getenv)
        RuntimeHash env = GlobalVariable.getGlobalHash("main::ENV");
        String randfile = null;
        String home = null;
        RuntimeScalar randfileVal = env.get("RANDFILE");
        if (randfileVal != null && randfileVal.type != RuntimeScalarType.UNDEF) {
            randfile = randfileVal.toString();
        }
        RuntimeScalar homeVal = env.get("HOME");
        if (homeVal != null && homeVal.type != RuntimeScalarType.UNDEF) {
            home = homeVal.toString();
        }

        String result;
        if (randfile != null && !randfile.isEmpty()) {
            result = randfile;
        } else {
            if (home == null || home.isEmpty()) {
                home = System.getProperty("user.home", "");
            }
            result = home + "/.rnd";
        }

        // OpenSSL 1.1.0a+: return undef if buffer too short
        if (result.length() >= bufSize) {
            return new RuntimeScalar().getList(); // undef
        }
        return new RuntimeScalar(result).getList();
    }

    public static RuntimeList RAND_load_file(RuntimeArray args, int ctx) {
        // RAND_load_file(filename, max_bytes)
        // When max_bytes=-1, returns the actual file size
        String filename = args.size() > 0 ? args.get(0).toString() : "";
        long maxBytes = args.size() > 1 ? args.get(1).getLong() : 0;
        if (maxBytes == -1) {
            // Return actual file size
            try {
                java.io.File f = new java.io.File(filename);
                if (f.exists()) {
                    return new RuntimeScalar(f.length()).getList();
                }
            } catch (Exception e) {
                // Fall through
            }
            return new RuntimeScalar(1024).getList();
        }
        return new RuntimeScalar(maxBytes).getList();
    }

    public static RuntimeList RAND_write_file(RuntimeArray args, int ctx) {
        return new RuntimeScalar(1024).getList();
    }

    // ---- BIO memory functions ----

    public static RuntimeList BIO_s_mem(RuntimeArray args, int ctx) {
        // Returns a sentinel value representing the "memory BIO method"
        return new RuntimeScalar(BIO_S_MEM_SENTINEL).getList();
    }

    public static RuntimeList BIO_new(RuntimeArray args, int ctx) {
        // BIO_new(method) - creates a new BIO
        long handleId = HANDLE_COUNTER.getAndIncrement();
        BIO_HANDLES.put(handleId, new MemoryBIO());
        return new RuntimeScalar(handleId).getList();
    }

    public static RuntimeList BIO_new_file(RuntimeArray args, int ctx) {
        // BIO_new_file(filename, mode) - not fully supported, return handle for in-memory
        long handleId = HANDLE_COUNTER.getAndIncrement();
        BIO_HANDLES.put(handleId, new MemoryBIO());
        return new RuntimeScalar(handleId).getList();
    }

    public static RuntimeList BIO_free(RuntimeArray args, int ctx) {
        long handleId = args.size() > 0 ? args.get(0).getLong() : 0;
        BIO_HANDLES.remove(handleId);
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList BIO_write(RuntimeArray args, int ctx) {
        // BIO_write(bio, data) - returns number of bytes written
        if (args.size() < 2) return new RuntimeScalar(-1).getList();
        long handleId = args.get(0).getLong();
        MemoryBIO bio = BIO_HANDLES.get(handleId);
        if (bio == null) return new RuntimeScalar(-1).getList();
        byte[] data = args.get(1).toString().getBytes(StandardCharsets.ISO_8859_1);
        bio.write(data);
        return new RuntimeScalar(data.length).getList();
    }

    public static RuntimeList BIO_read(RuntimeArray args, int ctx) {
        // BIO_read(bio, [max_len]) - returns data read
        if (args.size() < 1) return new RuntimeScalar("").getList();
        long handleId = args.get(0).getLong();
        MemoryBIO bio = BIO_HANDLES.get(handleId);
        if (bio == null) return new RuntimeScalar("").getList();
        int maxLen = args.size() > 1 ? (int) args.get(1).getLong() : bio.pending();
        if (maxLen <= 0) maxLen = bio.pending();
        byte[] data = bio.read(maxLen);
        RuntimeScalar result = new RuntimeScalar(new String(data, StandardCharsets.ISO_8859_1));
        result.type = RuntimeScalarType.BYTE_STRING;
        return result.getList();
    }

    public static RuntimeList BIO_pending(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar(0).getList();
        long handleId = args.get(0).getLong();
        MemoryBIO bio = BIO_HANDLES.get(handleId);
        if (bio == null) return new RuntimeScalar(0).getList();
        return new RuntimeScalar(bio.pending()).getList();
    }

    public static RuntimeList BIO_eof(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar(1).getList();
        long handleId = args.get(0).getLong();
        MemoryBIO bio = BIO_HANDLES.get(handleId);
        if (bio == null) return new RuntimeScalar(1).getList();
        return new RuntimeScalar(bio.pending() == 0 ? 1 : 0).getList();
    }

    // ---- RSA key generation ----

    public static RuntimeList RSA_generate_key(RuntimeArray args, int ctx) {
        // RSA_generate_key(bits, e, [cb], [cb_arg])
        int bits = args.size() > 0 ? (int) args.get(0).getLong() : 2048;
        // e (public exponent) is typically 65537 — Java handles this internally

        // Handle callback argument
        RuntimeScalar callback = args.size() > 2 ? args.get(2) : null;
        RuntimeScalar cbArg = args.size() > 3 ? args.get(3) : new RuntimeScalar();

        // Validate callback if provided — must be a code ref or undef
        if (callback != null && callback.type != RuntimeScalarType.UNDEF
                && callback.type != RuntimeScalarType.CODE) {
            // Treat as a subroutine name (like the real XS does)
            // Resolving "1" as a sub name will trigger "Undefined subroutine &main::1 called"
            String subName = callback.toString();
            RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef("main::" + subName);
            RuntimeArray cbArgs = new RuntimeArray();
            cbArgs.push(new RuntimeScalar(0));
            cbArgs.push(new RuntimeScalar(0));
            cbArgs.push(cbArg);
            RuntimeCode.apply(codeRef, cbArgs, RuntimeContextType.VOID);
        }

        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(bits, SECURE_RANDOM);

            // Call callback during key generation if provided
            if (callback != null && callback.type == RuntimeScalarType.CODE) {
                // Call with phase 0 (generate primes) several times
                for (int i = 0; i < 3; i++) {
                    RuntimeArray cbArgs = new RuntimeArray();
                    cbArgs.push(new RuntimeScalar(0)); // phase: generating primes
                    cbArgs.push(new RuntimeScalar(i)); // iteration
                    cbArgs.push(cbArg);
                    RuntimeCode.apply(callback, cbArgs, RuntimeContextType.VOID);
                }
            }

            KeyPair kp = kpg.generateKeyPair();
            long handleId = HANDLE_COUNTER.getAndIncrement();
            RSA_HANDLES.put(handleId, kp);

            // Call callback with phase 3 (done) if provided
            if (callback != null && callback.type == RuntimeScalarType.CODE) {
                RuntimeArray cbArgs = new RuntimeArray();
                cbArgs.push(new RuntimeScalar(3)); // phase: done
                cbArgs.push(new RuntimeScalar(0));
                cbArgs.push(cbArg);
                RuntimeCode.apply(callback, cbArgs, RuntimeContextType.VOID);
            }

            return new RuntimeScalar(handleId).getList();
        } catch (NoSuchAlgorithmException e) {
            return new RuntimeScalar().getList(); // undef
        }
    }

    public static RuntimeList RSA_free(RuntimeArray args, int ctx) {
        long handleId = args.size() > 0 ? args.get(0).getLong() : 0;
        RSA_HANDLES.remove(handleId);
        return new RuntimeScalar(1).getList();
    }

    // ---- EVP digest functions (backed by java.security.MessageDigest) ----

    // Helper: resolve an OpenSSL digest name to its NID, or return 0
    private static int resolveNid(String name) {
        if (name == null) return 0;
        Integer nid = NAME_TO_NID.get(name);
        if (nid != null) return nid;
        // Try lowercase
        nid = NAME_TO_NID.get(name.toLowerCase());
        return nid != null ? nid : 0;
    }

    // Helper: get Java algorithm name from OpenSSL name
    private static String resolveJavaAlg(String opensslName) {
        if (opensslName == null) return null;
        String alg = NAME_TO_JAVA_ALG.get(opensslName);
        if (alg != null) return alg;
        return NAME_TO_JAVA_ALG.get(opensslName.toLowerCase());
    }

    // Helper: create a MessageDigest from an OpenSSL name, or null
    private static MessageDigest createDigest(String opensslName) {
        String javaAlg = resolveJavaAlg(opensslName);
        if (javaAlg == null) return null;
        try {
            return MessageDigest.getInstance(javaAlg);
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    // Helper: convert byte[] to Perl binary string
    private static RuntimeScalar bytesToPerlString(byte[] bytes) {
        RuntimeScalar s = new RuntimeScalar(new String(bytes, StandardCharsets.ISO_8859_1));
        s.type = RuntimeScalarType.BYTE_STRING;
        return s;
    }

    public static RuntimeList EVP_get_digestbyname(RuntimeArray args, int ctx) {
        // Returns an opaque "md" handle (we use the NID as the handle)
        String name = args.size() > 0 ? args.get(0).toString() : "";
        int nid = resolveNid(name);
        if (nid == 0) return new RuntimeScalar().getList(); // undef
        return new RuntimeScalar(nid).getList();
    }

    public static RuntimeList EVP_MD_CTX_create(RuntimeArray args, int ctx) {
        // Alias: EVP_MD_CTX_new — creates an empty digest context
        long handleId = HANDLE_COUNTER.getAndIncrement();
        EVP_MD_CTX_HANDLES.put(handleId, new EvpMdCtx());
        return new RuntimeScalar(handleId).getList();
    }

    public static RuntimeList EVP_MD_CTX_new(RuntimeArray args, int ctx) {
        return EVP_MD_CTX_create(args, ctx);
    }

    public static RuntimeList EVP_MD_CTX_destroy(RuntimeArray args, int ctx) {
        long handleId = args.size() > 0 ? args.get(0).getLong() : 0;
        EVP_MD_CTX_HANDLES.remove(handleId);
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList EVP_MD_CTX_free(RuntimeArray args, int ctx) {
        return EVP_MD_CTX_destroy(args, ctx);
    }

    public static RuntimeList EVP_DigestInit(RuntimeArray args, int ctx) {
        // EVP_DigestInit(ctx_handle, md_nid)
        if (args.size() < 2) return new RuntimeScalar(0).getList();
        long ctxHandle = args.get(0).getLong();
        int mdNid = (int) args.get(1).getLong();
        EvpMdCtx evpCtx = EVP_MD_CTX_HANDLES.get(ctxHandle);
        if (evpCtx == null) return new RuntimeScalar(0).getList();
        String opensslName = NID_TO_NAME.get(mdNid);
        if (opensslName == null) return new RuntimeScalar(0).getList();
        MessageDigest md = createDigest(opensslName);
        if (md == null) return new RuntimeScalar(0).getList();
        evpCtx.digest = md;
        evpCtx.algorithmName = opensslName;
        evpCtx.nid = mdNid;
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList EVP_DigestInit_ex(RuntimeArray args, int ctx) {
        // EVP_DigestInit_ex(ctx, md, engine) — engine is ignored
        return EVP_DigestInit(args, ctx);
    }

    public static RuntimeList EVP_DigestUpdate(RuntimeArray args, int ctx) {
        // EVP_DigestUpdate(ctx_handle, data)
        if (args.size() < 2) return new RuntimeScalar(0).getList();
        long ctxHandle = args.get(0).getLong();
        EvpMdCtx evpCtx = EVP_MD_CTX_HANDLES.get(ctxHandle);
        if (evpCtx == null || evpCtx.digest == null) return new RuntimeScalar(0).getList();
        byte[] data = args.get(1).toString().getBytes(StandardCharsets.ISO_8859_1);
        evpCtx.digest.update(data);
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList EVP_DigestFinal(RuntimeArray args, int ctx) {
        // EVP_DigestFinal(ctx_handle) - returns binary digest string
        if (args.size() < 1) return new RuntimeScalar().getList();
        long ctxHandle = args.get(0).getLong();
        EvpMdCtx evpCtx = EVP_MD_CTX_HANDLES.get(ctxHandle);
        if (evpCtx == null || evpCtx.digest == null) return new RuntimeScalar().getList();
        byte[] digest = evpCtx.digest.digest();
        return bytesToPerlString(digest).getList();
    }

    public static RuntimeList EVP_DigestFinal_ex(RuntimeArray args, int ctx) {
        return EVP_DigestFinal(args, ctx);
    }

    public static RuntimeList EVP_Digest(RuntimeArray args, int ctx) {
        // EVP_Digest(data, md_nid) - one-shot digest, returns binary string
        if (args.size() < 2) return new RuntimeScalar().getList();
        String data = args.get(0).toString();
        int mdNid = (int) args.get(1).getLong();
        String opensslName = NID_TO_NAME.get(mdNid);
        if (opensslName == null) return new RuntimeScalar().getList();
        MessageDigest md = createDigest(opensslName);
        if (md == null) return new RuntimeScalar().getList();
        byte[] digest = md.digest(data.getBytes(StandardCharsets.ISO_8859_1));
        return bytesToPerlString(digest).getList();
    }

    public static RuntimeList EVP_MD_type(RuntimeArray args, int ctx) {
        // EVP_MD_type(md_nid) - returns the NID
        if (args.size() < 1) return new RuntimeScalar(0).getList();
        return new RuntimeScalar(args.get(0).getLong()).getList();
    }

    public static RuntimeList EVP_MD_size(RuntimeArray args, int ctx) {
        // EVP_MD_size(md_nid) - returns digest size in bytes
        if (args.size() < 1) return new RuntimeScalar(0).getList();
        int nid = (int) args.get(0).getLong();
        String opensslName = NID_TO_NAME.get(nid);
        if (opensslName == null) return new RuntimeScalar(0).getList();
        MessageDigest md = createDigest(opensslName);
        if (md == null) return new RuntimeScalar(0).getList();
        return new RuntimeScalar(md.getDigestLength()).getList();
    }

    public static RuntimeList EVP_MD_CTX_md(RuntimeArray args, int ctx) {
        // EVP_MD_CTX_md(ctx_handle) - returns the md (NID) from a context
        if (args.size() < 1) return new RuntimeScalar().getList();
        long ctxHandle = args.get(0).getLong();
        EvpMdCtx evpCtx = EVP_MD_CTX_HANDLES.get(ctxHandle);
        if (evpCtx == null || evpCtx.nid == 0) return new RuntimeScalar().getList();
        return new RuntimeScalar(evpCtx.nid).getList();
    }

    public static RuntimeList EVP_MD_CTX_size(RuntimeArray args, int ctx) {
        // EVP_MD_CTX_size(ctx_handle) - returns digest size from context
        if (args.size() < 1) return new RuntimeScalar(0).getList();
        long ctxHandle = args.get(0).getLong();
        EvpMdCtx evpCtx = EVP_MD_CTX_HANDLES.get(ctxHandle);
        if (evpCtx == null || evpCtx.digest == null) return new RuntimeScalar(0).getList();
        return new RuntimeScalar(evpCtx.digest.getDigestLength()).getList();
    }

    // Direct MD accessors — return NID for the algorithm
    public static RuntimeList EVP_sha1(RuntimeArray args, int ctx) {
        return new RuntimeScalar(64).getList();
    }

    public static RuntimeList EVP_sha224(RuntimeArray args, int ctx) {
        return new RuntimeScalar(675).getList();
    }

    public static RuntimeList EVP_sha256(RuntimeArray args, int ctx) {
        return new RuntimeScalar(672).getList();
    }

    public static RuntimeList EVP_sha384(RuntimeArray args, int ctx) {
        return new RuntimeScalar(673).getList();
    }

    public static RuntimeList EVP_sha512(RuntimeArray args, int ctx) {
        return new RuntimeScalar(674).getList();
    }

    public static RuntimeList EVP_md5(RuntimeArray args, int ctx) {
        return new RuntimeScalar(4).getList();
    }

    // OpenSSL 3.0+ MD query functions
    public static RuntimeList EVP_MD_get0_name(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        int nid = (int) args.get(0).getLong();
        String name = NID_TO_NAME.get(nid);
        if (name == null) return new RuntimeScalar().getList();
        return new RuntimeScalar(name.toUpperCase()).getList();
    }

    public static RuntimeList EVP_MD_get0_description(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        int nid = (int) args.get(0).getLong();
        String name = NID_TO_NAME.get(nid);
        if (name == null) return new RuntimeScalar().getList();
        return new RuntimeScalar(name.toUpperCase() + " via Java MessageDigest").getList();
    }

    public static RuntimeList EVP_MD_get_type(RuntimeArray args, int ctx) {
        // Same as EVP_MD_type
        return EVP_MD_type(args, ctx);
    }

    public static RuntimeList P_EVP_MD_list_all(RuntimeArray args, int ctx) {
        // Returns an array reference of all available digest names
        RuntimeArray result = new RuntimeArray();
        for (Map.Entry<String, String> entry : NAME_TO_JAVA_ALG.entrySet()) {
            String opensslName = entry.getKey();
            // Only include lowercase names (avoid duplicates from uppercase aliases)
            if (opensslName.equals(opensslName.toLowerCase())) {
                try {
                    MessageDigest.getInstance(entry.getValue());
                    result.push(new RuntimeScalar(opensslName));
                } catch (NoSuchAlgorithmException e) {
                    // Algorithm not available in this JVM
                }
            }
        }
        return result.createReference().getList();
    }

    // ---- Convenience digest functions ----
    // These take data and return the binary digest

    private static RuntimeList convenienceDigest(String opensslName, RuntimeArray args) {
        String data = args.size() > 0 ? args.get(0).toString() : "";
        MessageDigest md = createDigest(opensslName);
        if (md == null) return new RuntimeScalar().getList();
        byte[] digest = md.digest(data.getBytes(StandardCharsets.ISO_8859_1));
        return bytesToPerlString(digest).getList();
    }

    public static RuntimeList MD5(RuntimeArray args, int ctx) {
        return convenienceDigest("md5", args);
    }

    public static RuntimeList SHA1(RuntimeArray args, int ctx) {
        return convenienceDigest("sha1", args);
    }

    public static RuntimeList SHA256(RuntimeArray args, int ctx) {
        return convenienceDigest("sha256", args);
    }

    public static RuntimeList SHA512(RuntimeArray args, int ctx) {
        return convenienceDigest("sha512", args);
    }

    public static RuntimeList RIPEMD160(RuntimeArray args, int ctx) {
        return convenienceDigest("ripemd160", args);
    }

    // ---- NID constant methods ----

    public static RuntimeList NID_md5(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("NID_md5")).getList();
    }

    public static RuntimeList NID_sha1(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("NID_sha1")).getList();
    }

    public static RuntimeList NID_sha224(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("NID_sha224")).getList();
    }

    public static RuntimeList NID_sha256(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("NID_sha256")).getList();
    }

    public static RuntimeList NID_sha384(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("NID_sha384")).getList();
    }

    public static RuntimeList NID_sha512(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("NID_sha512")).getList();
    }

    public static RuntimeList NID_sha3_256(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("NID_sha3_256")).getList();
    }

    public static RuntimeList NID_sha3_512(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("NID_sha3_512")).getList();
    }

    public static RuntimeList NID_ripemd160(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("NID_ripemd160")).getList();
    }

    // ---- Generic constant accessor (used by registerMethod for each constant name) ----
    // Each constant in the CONSTANTS map gets registered via registerMethod(name, name, "").
    // They all need a static method with the standard signature.
    // We generate these dynamically by having a single method per constant name.
    // Since Java doesn't allow dynamic method creation, we use the constant() function
    // for AUTOLOAD-based lookup, and register the most important ones directly.

    // The individual constant methods are needed because registerMethod looks up
    // static methods by name. We use a helper to generate them.
    // Actually, since we registered them all pointing at the name, and the Java reflection
    // will look for a method of that exact name, we need a different approach.
    // Let's NOT register individual constant methods but instead rely on the Perl
    // AUTOLOAD/constant mechanism. Remove the per-constant registerMethod calls
    // and instead just export them from the Perl side.

    // The constants IO::Socket::SSL accesses directly (not through AUTOLOAD):
    public static RuntimeList ERROR_NONE(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("ERROR_NONE")).getList();
    }

    public static RuntimeList ERROR_SSL(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("ERROR_SSL")).getList();
    }

    public static RuntimeList ERROR_WANT_READ(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("ERROR_WANT_READ")).getList();
    }

    public static RuntimeList ERROR_WANT_WRITE(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("ERROR_WANT_WRITE")).getList();
    }

    public static RuntimeList ERROR_WANT_X509_LOOKUP(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("ERROR_WANT_X509_LOOKUP")).getList();
    }

    public static RuntimeList ERROR_SYSCALL(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("ERROR_SYSCALL")).getList();
    }

    public static RuntimeList ERROR_ZERO_RETURN(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("ERROR_ZERO_RETURN")).getList();
    }

    public static RuntimeList ERROR_WANT_CONNECT(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("ERROR_WANT_CONNECT")).getList();
    }

    public static RuntimeList ERROR_WANT_ACCEPT(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("ERROR_WANT_ACCEPT")).getList();
    }

    public static RuntimeList VERIFY_NONE(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("VERIFY_NONE")).getList();
    }

    public static RuntimeList VERIFY_PEER(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("VERIFY_PEER")).getList();
    }

    public static RuntimeList VERIFY_FAIL_IF_NO_PEER_CERT(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("VERIFY_FAIL_IF_NO_PEER_CERT")).getList();
    }

    public static RuntimeList VERIFY_CLIENT_ONCE(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("VERIFY_CLIENT_ONCE")).getList();
    }

    public static RuntimeList FILETYPE_PEM(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("FILETYPE_PEM")).getList();
    }

    public static RuntimeList FILETYPE_ASN1(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("FILETYPE_ASN1")).getList();
    }

    public static RuntimeList OP_ALL(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("OP_ALL")).getList();
    }

    public static RuntimeList OP_SINGLE_DH_USE(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("OP_SINGLE_DH_USE")).getList();
    }

    public static RuntimeList OP_SINGLE_ECDH_USE(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("OP_SINGLE_ECDH_USE")).getList();
    }

    public static RuntimeList OP_NO_SSLv2(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("OP_NO_SSLv2")).getList();
    }

    public static RuntimeList OP_NO_SSLv3(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("OP_NO_SSLv3")).getList();
    }

    public static RuntimeList OP_NO_TLSv1(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("OP_NO_TLSv1")).getList();
    }

    public static RuntimeList OP_NO_TLSv1_1(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("OP_NO_TLSv1_1")).getList();
    }

    public static RuntimeList OP_NO_TLSv1_2(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("OP_NO_TLSv1_2")).getList();
    }

    public static RuntimeList OP_NO_TLSv1_3(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("OP_NO_TLSv1_3")).getList();
    }

    public static RuntimeList OP_CIPHER_SERVER_PREFERENCE(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("OP_CIPHER_SERVER_PREFERENCE")).getList();
    }

    public static RuntimeList OP_NO_COMPRESSION(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("OP_NO_COMPRESSION")).getList();
    }

    public static RuntimeList MODE_ENABLE_PARTIAL_WRITE(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("MODE_ENABLE_PARTIAL_WRITE")).getList();
    }

    public static RuntimeList MODE_ACCEPT_MOVING_WRITE_BUFFER(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("MODE_ACCEPT_MOVING_WRITE_BUFFER")).getList();
    }

    public static RuntimeList MODE_AUTO_RETRY(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("MODE_AUTO_RETRY")).getList();
    }

    public static RuntimeList X509_V_FLAG_TRUSTED_FIRST(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("X509_V_FLAG_TRUSTED_FIRST")).getList();
    }

    public static RuntimeList X509_V_FLAG_PARTIAL_CHAIN(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("X509_V_FLAG_PARTIAL_CHAIN")).getList();
    }

    public static RuntimeList X509_V_FLAG_CRL_CHECK(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("X509_V_FLAG_CRL_CHECK")).getList();
    }

    public static RuntimeList TLSEXT_STATUSTYPE_ocsp(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("TLSEXT_STATUSTYPE_ocsp")).getList();
    }

    public static RuntimeList OCSP_RESPONSE_STATUS_SUCCESSFUL(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("OCSP_RESPONSE_STATUS_SUCCESSFUL")).getList();
    }

    public static RuntimeList V_OCSP_CERTSTATUS_GOOD(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("V_OCSP_CERTSTATUS_GOOD")).getList();
    }

    public static RuntimeList TLS1_VERSION(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("TLS1_VERSION")).getList();
    }

    public static RuntimeList TLS1_1_VERSION(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("TLS1_1_VERSION")).getList();
    }

    public static RuntimeList TLS1_2_VERSION(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("TLS1_2_VERSION")).getList();
    }

    public static RuntimeList TLS1_3_VERSION(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("TLS1_3_VERSION")).getList();
    }

    public static RuntimeList SESS_CACHE_CLIENT(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("SESS_CACHE_CLIENT")).getList();
    }

    public static RuntimeList SESS_CACHE_SERVER(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("SESS_CACHE_SERVER")).getList();
    }

    public static RuntimeList SESS_CACHE_BOTH(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("SESS_CACHE_BOTH")).getList();
    }

    public static RuntimeList SESS_CACHE_OFF(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("SESS_CACHE_OFF")).getList();
    }

    public static RuntimeList NID_commonName(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("NID_commonName")).getList();
    }

    public static RuntimeList NID_subject_alt_name(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("NID_subject_alt_name")).getList();
    }

    public static RuntimeList SSL_SENT_SHUTDOWN(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("SSL_SENT_SHUTDOWN")).getList();
    }

    public static RuntimeList SSL_RECEIVED_SHUTDOWN(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("SSL_RECEIVED_SHUTDOWN")).getList();
    }

    public static RuntimeList LIBRESSL_VERSION_NUMBER(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("LIBRESSL_VERSION_NUMBER")).getList();
    }

    // SSLeay_version() type constants
    public static RuntimeList SSLEAY_VERSION(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("SSLEAY_VERSION")).getList();
    }

    public static RuntimeList SSLEAY_CFLAGS(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("SSLEAY_CFLAGS")).getList();
    }

    public static RuntimeList SSLEAY_BUILT_ON(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("SSLEAY_BUILT_ON")).getList();
    }

    public static RuntimeList SSLEAY_PLATFORM(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("SSLEAY_PLATFORM")).getList();
    }

    public static RuntimeList SSLEAY_DIR(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("SSLEAY_DIR")).getList();
    }

    // Note: OPENSSL_VERSION as a constant (=0) is separate from the OPENSSL_VERSION field (=0x30000000L)
    public static RuntimeList OPENSSL_VERSION(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("OPENSSL_VERSION")).getList();
    }

    public static RuntimeList OPENSSL_CFLAGS(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("OPENSSL_CFLAGS")).getList();
    }

    public static RuntimeList OPENSSL_BUILT_ON(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("OPENSSL_BUILT_ON")).getList();
    }

    public static RuntimeList OPENSSL_PLATFORM(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("OPENSSL_PLATFORM")).getList();
    }

    public static RuntimeList OPENSSL_DIR(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("OPENSSL_DIR")).getList();
    }

    public static RuntimeList OPENSSL_VERSION_MAJOR(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("OPENSSL_VERSION_MAJOR")).getList();
    }

    public static RuntimeList OPENSSL_VERSION_MINOR(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("OPENSSL_VERSION_MINOR")).getList();
    }

    public static RuntimeList OPENSSL_VERSION_PATCH(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("OPENSSL_VERSION_PATCH")).getList();
    }

    public static RuntimeList OPENSSL_INFO_CONFIG_DIR(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("OPENSSL_INFO_CONFIG_DIR")).getList();
    }

    public static RuntimeList OPENSSL_INFO_ENGINES_DIR(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("OPENSSL_INFO_ENGINES_DIR")).getList();
    }

    public static RuntimeList OPENSSL_INFO_MODULES_DIR(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("OPENSSL_INFO_MODULES_DIR")).getList();
    }

    public static RuntimeList OPENSSL_INFO_DSO_EXTENSION(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("OPENSSL_INFO_DSO_EXTENSION")).getList();
    }

    public static RuntimeList OPENSSL_INFO_DIR_FILENAME_SEPARATOR(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("OPENSSL_INFO_DIR_FILENAME_SEPARATOR")).getList();
    }

    public static RuntimeList OPENSSL_INFO_LIST_SEPARATOR(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("OPENSSL_INFO_LIST_SEPARATOR")).getList();
    }

    public static RuntimeList OPENSSL_INFO_SEED_SOURCE(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("OPENSSL_INFO_SEED_SOURCE")).getList();
    }

    public static RuntimeList OPENSSL_INFO_CPU_SETTINGS(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("OPENSSL_INFO_CPU_SETTINGS")).getList();
    }

    public static RuntimeList OPENSSL_ENGINES_DIR(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("OPENSSL_ENGINES_DIR")).getList();
    }

    public static RuntimeList OPENSSL_MODULES_DIR(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("OPENSSL_MODULES_DIR")).getList();
    }

    public static RuntimeList OPENSSL_CPU_INFO(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("OPENSSL_CPU_INFO")).getList();
    }

    public static RuntimeList OPENSSL_FULL_VERSION_STRING(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("OPENSSL_FULL_VERSION_STRING")).getList();
    }

    public static RuntimeList OPENSSL_VERSION_STRING(RuntimeArray args, int ctx) {
        return new RuntimeScalar(CONSTANTS.get("OPENSSL_VERSION_STRING")).getList();
    }
}
