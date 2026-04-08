package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.*;

import java.util.HashMap;
import java.util.Map;

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
    }

    // Report as OpenSSL 3.0.0 — modern enough for IO::Socket::SSL features
    private static final long OPENSSL_VERSION = 0x30000000L;

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
            mod.registerMethod("SSLeay_add_ssl_algorithms", null);
            mod.registerMethod("OpenSSL_add_all_digests", null);
            mod.registerMethod("randomize", null);

            // Version info
            mod.registerMethod("SSLeay", null);
            mod.registerMethod("SSLeay_version", null);
            mod.registerMethod("OPENSSL_VERSION_NUMBER", "");

            // Error functions
            mod.registerMethod("ERR_clear_error", null);
            mod.registerMethod("ERR_get_error", null);
            mod.registerMethod("ERR_error_string", null);
            mod.registerMethod("print_errs", null);

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
                    "ERR_clear_error", "ERR_get_error", "ERR_error_string", "print_errs");

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

    public static RuntimeList SSLeay_add_ssl_algorithms(RuntimeArray args, int ctx) {
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList OpenSSL_add_all_digests(RuntimeArray args, int ctx) {
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList randomize(RuntimeArray args, int ctx) {
        return new RuntimeScalar(1).getList();
    }

    // ---- Version info ----

    public static RuntimeList SSLeay(RuntimeArray args, int ctx) {
        return new RuntimeScalar(OPENSSL_VERSION).getList();
    }

    public static RuntimeList SSLeay_version(RuntimeArray args, int ctx) {
        return new RuntimeScalar("PerlOnJava TLS (Java " +
                System.getProperty("java.version") + ")").getList();
    }

    public static RuntimeList OPENSSL_VERSION_NUMBER(RuntimeArray args, int ctx) {
        return new RuntimeScalar(OPENSSL_VERSION).getList();
    }

    // ---- Error functions ----

    public static RuntimeList ERR_clear_error(RuntimeArray args, int ctx) {
        return new RuntimeScalar(0).getList();
    }

    public static RuntimeList ERR_get_error(RuntimeArray args, int ctx) {
        return new RuntimeScalar(0).getList();
    }

    public static RuntimeList ERR_error_string(RuntimeArray args, int ctx) {
        long errorCode = args.size() > 0 ? args.get(0).getLong() : 0;
        if (errorCode == 0) {
            return new RuntimeScalar("").getList();
        }
        return new RuntimeScalar("error:" + errorCode + ":PerlOnJava TLS stub").getList();
    }

    public static RuntimeList print_errs(RuntimeArray args, int ctx) {
        return new RuntimeScalar("").getList();
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
}
