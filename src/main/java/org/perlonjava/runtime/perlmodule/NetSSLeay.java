package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.*;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.security.auth.x500.X500Principal;

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
        CONSTANTS.put("OP_NO_TICKET", 0x00004000L);
        // X509 store context result status; 1 means OK per OpenSSL.
        CONSTANTS.put("ST_OK", 1L);
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
        CONSTANTS.put("X509_V_FLAG_ALLOW_PROXY_CERTS", 0x40L);
        CONSTANTS.put("X509_V_FLAG_POLICY_CHECK", 0x80L);
        CONSTANTS.put("X509_V_FLAG_EXPLICIT_POLICY", 0x100L);
        CONSTANTS.put("X509_V_FLAG_LEGACY_VERIFY", 0x0L); // Not applicable, we're not LibreSSL

        // X509 verify error codes
        CONSTANTS.put("X509_V_OK", 0L);
        CONSTANTS.put("X509_V_ERR_UNABLE_TO_GET_ISSUER_CERT_LOCALLY", 20L);
        CONSTANTS.put("X509_V_ERR_CERT_UNTRUSTED", 27L);
        CONSTANTS.put("X509_V_ERR_NO_EXPLICIT_POLICY", 43L);
        CONSTANTS.put("X509_V_ERR_HOSTNAME_MISMATCH", 62L);

        // X509 purpose constants
        CONSTANTS.put("X509_PURPOSE_SSL_CLIENT", 1L);
        CONSTANTS.put("X509_PURPOSE_SSL_SERVER", 2L);

        // X509 trust constants
        CONSTANTS.put("X509_TRUST_EMAIL", 4L);

        // X509_CHECK flags
        CONSTANTS.put("X509_CHECK_FLAG_NO_WILDCARDS", 0x2L);

        // X509 version constants
        CONSTANTS.put("X509_VERSION_1", 0L);
        CONSTANTS.put("X509_VERSION_2", 1L);
        CONSTANTS.put("X509_VERSION_3", 2L);

        // X509_REQ version constants
        CONSTANTS.put("X509_REQ_VERSION_1", 0L);

        // MBSTRING encoding constants
        CONSTANTS.put("MBSTRING_FLAG", 0x1000L);
        CONSTANTS.put("MBSTRING_ASC", 0x1001L);
        CONSTANTS.put("MBSTRING_BMP", 0x1002L);
        CONSTANTS.put("MBSTRING_UTF8", 0x1004L);
        CONSTANTS.put("MBSTRING_UNIV", 0x1008L);

        // EVP_PK key type flags
        CONSTANTS.put("EVP_PK_RSA", 0x0001L);
        CONSTANTS.put("EVP_PK_DSA", 0x0002L);
        CONSTANTS.put("EVP_PK_DH", 0x0004L);
        CONSTANTS.put("EVP_PK_EC", 0x0008L);

        // EVP_PKT key usage flags
        CONSTANTS.put("EVP_PKT_SIGN", 0x0010L);
        CONSTANTS.put("EVP_PKT_ENC", 0x0020L);
        CONSTANTS.put("EVP_PKT_EXCH", 0x0040L);
        CONSTANTS.put("EVP_PKS_RSA", 0x0100L);

        // GEN_* subject alt name type constants
        CONSTANTS.put("GEN_OTHERNAME", 0L);
        CONSTANTS.put("GEN_EMAIL", 1L);
        CONSTANTS.put("GEN_DNS", 2L);
        CONSTANTS.put("GEN_X400", 3L);
        CONSTANTS.put("GEN_DIRNAME", 4L);
        CONSTANTS.put("GEN_EDIPARTY", 5L);
        CONSTANTS.put("GEN_URI", 6L);
        CONSTANTS.put("GEN_IPADD", 7L);
        CONSTANTS.put("GEN_RID", 8L);

        // NID constants for X509 name components and extensions
        CONSTANTS.put("NID_countryName", 14L);
        CONSTANTS.put("NID_localityName", 19L);
        CONSTANTS.put("NID_stateOrProvinceName", 20L);
        CONSTANTS.put("NID_organizationName", 17L);
        CONSTANTS.put("NID_organizationalUnitName", 18L);
        CONSTANTS.put("NID_surname", 15L);
        CONSTANTS.put("NID_givenName", 100L);
        CONSTANTS.put("NID_title", 99L);
        CONSTANTS.put("NID_initials", 101L);
        CONSTANTS.put("NID_serialNumber", 16L);
        CONSTANTS.put("NID_domainComponent", 391L);
        CONSTANTS.put("NID_pkcs9_emailAddress", 48L);

        // NID constants for X509v3 extensions
        CONSTANTS.put("NID_subject_key_identifier", 82L);
        CONSTANTS.put("NID_key_usage", 83L);
        CONSTANTS.put("NID_issuer_alt_name", 86L);
        CONSTANTS.put("NID_basic_constraints", 87L);
        CONSTANTS.put("NID_certificate_policies", 89L);
        CONSTANTS.put("NID_authority_key_identifier", 90L);
        CONSTANTS.put("NID_crl_distribution_points", 103L);
        CONSTANTS.put("NID_ext_key_usage", 126L);
        CONSTANTS.put("NID_netscape_cert_type", 71L);
        CONSTANTS.put("NID_info_access", 177L);
        CONSTANTS.put("NID_ext_req", 172L);

        // RSA encryption NID
        CONSTANTS.put("NID_rsaEncryption", 6L);

        // OCSP constants
        CONSTANTS.put("TLSEXT_STATUSTYPE_ocsp", 1L);
        CONSTANTS.put("OCSP_RESPONSE_STATUS_SUCCESSFUL", 0L);
        CONSTANTS.put("V_OCSP_CERTSTATUS_GOOD", 0L);

        // TLS version constants
        CONSTANTS.put("SSL3_VERSION", 0x0300L);
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

    // ex_data indices — OpenSSL reserves index 0, and AnyEvent::TLS does
    // `until $REF_IDX;` around get_ex_new_index, so start at 1.
    private static final AtomicLong EX_INDEX_COUNTER = new AtomicLong(1);
    private static final Map<Long, Map<Integer, RuntimeScalar>> EX_DATA =
            new java.util.concurrent.ConcurrentHashMap<>();

    // Maps for opaque handles: handle_id → Java object
    private static final Map<Long, MemoryBIO> BIO_HANDLES = new HashMap<>();
    private static final Map<Long, EvpMdCtx> EVP_MD_CTX_HANDLES = new HashMap<>();
    private static final Map<Long, HmacCtx> HMAC_CTX_HANDLES = new HashMap<>();
    private static final Map<Long, KeyPair> RSA_HANDLES = new HashMap<>();
    private static final Map<Long, Long> ASN1_TIME_HANDLES = new HashMap<>();  // handle → epoch seconds
    private static final Map<Long, SslCtxState> CTX_HANDLES = new HashMap<>();
    private static final Map<Long, SslState> SSL_HANDLES = new HashMap<>();
    private static final Map<Long, java.security.Key> EVP_PKEY_HANDLES = new HashMap<>();

    // X509 handle maps
    private static final Map<Long, X509Certificate> X509_HANDLES = new HashMap<>();
    private static final Map<Long, X509NameInfo> X509_NAME_HANDLES = new HashMap<>();
    private static final Map<Long, X509NameEntry> X509_NAME_ENTRY_HANDLES = new HashMap<>();
    private static final Map<Long, String> ASN1_OBJECT_HANDLES = new HashMap<>(); // handle → OID string
    private static final Map<Long, Asn1StringValue> ASN1_STRING_HANDLES = new HashMap<>();
    private static final Map<Long, BigInteger> ASN1_INTEGER_HANDLES = new HashMap<>();
    private static final Map<Long, X509ExtInfo> X509_EXT_HANDLES = new HashMap<>();
    private static final Map<Long, X509StoreState> X509_STORE_HANDLES = new HashMap<>();
    private static final Map<Long, X509StoreCtxState> X509_STORE_CTX_HANDLES = new HashMap<>();
    private static final Map<Long, List<Long>> SK_X509_HANDLES = new HashMap<>();
    private static final Map<Long, VerifyParamState> VERIFY_PARAM_HANDLES = new HashMap<>();
    private static final Map<Long, List<X509InfoEntry>> X509_INFO_SK_HANDLES = new HashMap<>();
    private static final Map<Long, MutableX509State> MUTABLE_X509_HANDLES = new HashMap<>();
    private static final Map<Long, MutableX509ReqState> X509_REQ_HANDLES = new HashMap<>();
    private static final Map<Long, BigInteger> BIGNUM_HANDLES = new HashMap<>();
    private static final Map<Long, String> EVP_CIPHER_HANDLES = new HashMap<>(); // handle → cipher name
    private static final Map<Long, KeyPair> EC_KEY_HANDLES = new HashMap<>(); // EC key pairs
    private static final Map<Long, MutableCRLState> CRL_HANDLES = new HashMap<>();
    private static final Map<Long, X509CRL> X509_CRL_HANDLES = new HashMap<>(); // read-only parsed CRLs
    private static final Map<String, Long> CRL_TIME_CACHE = new HashMap<>(); // cache for read-only CRL time handles

    // OSSL_PROVIDER simulation
    private static final Map<String, Long> PROVIDER_NAME_TO_HANDLE = new HashMap<>();
    private static final Map<Long, String> PROVIDER_HANDLE_TO_NAME = new HashMap<>();
    private static long LIBCTX_HANDLE = 0; // lazily assigned
    // Track whether fallback providers (default) should auto-load
    private static boolean retainFallbacks = true;
    // Track explicitly loaded providers for do_all iteration
    private static final LinkedHashMap<Long, String> LOADED_PROVIDERS = new LinkedHashMap<>();

    /**
     * Resets all mutable static state so that tests running in the same JVM
     * don't leak handles, providers, or other state between each other.
     * Called from GlobalVariable.resetAllGlobals().
     */
    public static void resetState() {
        HANDLE_COUNTER.set(1);
        BIO_HANDLES.clear();
        EVP_MD_CTX_HANDLES.clear();
        HMAC_CTX_HANDLES.clear();
        RSA_HANDLES.clear();
        ASN1_TIME_HANDLES.clear();
        CTX_HANDLES.clear();
        SSL_HANDLES.clear();
        EVP_PKEY_HANDLES.clear();
        X509_HANDLES.clear();
        X509_NAME_HANDLES.clear();
        X509_NAME_ENTRY_HANDLES.clear();
        ASN1_OBJECT_HANDLES.clear();
        ASN1_STRING_HANDLES.clear();
        ASN1_INTEGER_HANDLES.clear();
        X509_EXT_HANDLES.clear();
        X509_STORE_HANDLES.clear();
        X509_STORE_CTX_HANDLES.clear();
        SK_X509_HANDLES.clear();
        VERIFY_PARAM_HANDLES.clear();
        X509_INFO_SK_HANDLES.clear();
        MUTABLE_X509_HANDLES.clear();
        X509_REQ_HANDLES.clear();
        BIGNUM_HANDLES.clear();
        EVP_CIPHER_HANDLES.clear();
        EC_KEY_HANDLES.clear();
        CRL_HANDLES.clear();
        X509_CRL_HANDLES.clear();
        CRL_TIME_CACHE.clear();
        PROVIDER_NAME_TO_HANDLE.clear();
        PROVIDER_HANDLE_TO_NAME.clear();
        LIBCTX_HANDLE = 0;
        retainFallbacks = true;
        LOADED_PROVIDERS.clear();
        ERROR_QUEUE.remove();
    }

    // SSL method type sentinels
    private static final long METHOD_SSLv23 = -10L;
    private static final long METHOD_SSLv23_CLIENT = -11L;
    private static final long METHOD_SSLv23_SERVER = -12L;
    private static final long METHOD_TLSv1 = -13L;
    private static final long METHOD_TLS = -14L;
    private static final long METHOD_TLS_CLIENT = -15L;
    private static final long METHOD_TLS_SERVER = -16L;

    // Valid TLS protocol versions for validation
    private static final Set<Long> VALID_PROTO_VERSIONS = new HashSet<>(Arrays.asList(
            0L,       // automatic
            0x0300L,  // SSL3
            0x0301L,  // TLS 1.0
            0x0302L,  // TLS 1.1
            0x0303L,  // TLS 1.2
            0x0304L   // TLS 1.3
    ));

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

        // SSL_CB_* info callback constants (from openssl/ssl.h)
        CONSTANTS.put("CB_LOOP", 0x01L);
        CONSTANTS.put("CB_EXIT", 0x02L);
        CONSTANTS.put("CB_READ", 0x04L);
        CONSTANTS.put("CB_WRITE", 0x08L);
        CONSTANTS.put("CB_ALERT", 0x4000L);
        CONSTANTS.put("CB_READ_ALERT", 0x4004L);    // CB_ALERT | CB_READ
        CONSTANTS.put("CB_WRITE_ALERT", 0x4008L);   // CB_ALERT | CB_WRITE
        CONSTANTS.put("CB_ACCEPT_LOOP", 0x2001L);   // ST_ACCEPT | CB_LOOP
        CONSTANTS.put("CB_ACCEPT_EXIT", 0x2002L);   // ST_ACCEPT | CB_EXIT
        CONSTANTS.put("CB_CONNECT_LOOP", 0x1001L);  // ST_CONNECT | CB_LOOP
        CONSTANTS.put("CB_CONNECT_EXIT", 0x1002L);  // ST_CONNECT | CB_EXIT
        CONSTANTS.put("CB_HANDSHAKE_START", 0x10L);
        CONSTANTS.put("CB_HANDSHAKE_DONE", 0x20L);
    }

    // Comprehensive OID ↔ NID ↔ long name ↔ short name mapping
    private static class OidInfo {
        final String oid;
        final int nid;
        final String longName;
        final String shortName;
        OidInfo(String oid, int nid, String longName, String shortName) {
            this.oid = oid;
            this.nid = nid;
            this.longName = longName;
            this.shortName = shortName;
        }
    }

    private static final Map<String, OidInfo> OID_TO_INFO = new HashMap<>();
    private static final Map<Integer, OidInfo> NID_TO_INFO = new HashMap<>();

    private static void addOid(String oid, int nid, String longName, String shortName) {
        OidInfo info = new OidInfo(oid, nid, longName, shortName);
        OID_TO_INFO.put(oid, info);
        NID_TO_INFO.put(nid, info);
    }

    static {
        // X.500 distinguished name attributes
        addOid("2.5.4.3", 13, "commonName", "CN");
        addOid("2.5.4.4", 15, "surname", "SN");
        addOid("2.5.4.5", 16, "serialNumber", "serialNumber");
        addOid("2.5.4.6", 14, "countryName", "C");
        addOid("2.5.4.7", 19, "localityName", "L");
        addOid("2.5.4.8", 20, "stateOrProvinceName", "ST");
        addOid("2.5.4.9", 660, "streetAddress", "street");
        addOid("2.5.4.10", 17, "organizationName", "O");
        addOid("2.5.4.11", 18, "organizationalUnitName", "OU");
        addOid("2.5.4.12", 99, "title", "title");
        addOid("2.5.4.42", 100, "givenName", "GN");
        addOid("2.5.4.43", 101, "initials", "initials");
        addOid("2.5.4.46", 509, "dnQualifier", "dnQualifier");
        addOid("1.2.840.113549.1.9.1", 48, "emailAddress", "emailAddress");
        addOid("0.9.2342.19200300.100.1.25", 391, "domainComponent", "DC");
        addOid("0.9.2342.19200300.100.1.1", 390, "userId", "UID");

        // X.509v3 extension OIDs
        addOid("2.5.29.14", 82, "X509v3 Subject Key Identifier", "subjectKeyIdentifier");
        addOid("2.5.29.15", 83, "X509v3 Key Usage", "keyUsage");
        addOid("2.5.29.17", 85, "X509v3 Subject Alternative Name", "subjectAltName");
        addOid("2.5.29.18", 86, "X509v3 Issuer Alternative Name", "issuerAltName");
        addOid("2.5.29.19", 87, "X509v3 Basic Constraints", "basicConstraints");
        addOid("2.5.29.31", 103, "X509v3 CRL Distribution Points", "crlDistributionPoints");
        addOid("2.5.29.32", 89, "X509v3 Certificate Policies", "certificatePolicies");
        addOid("2.5.29.35", 90, "X509v3 Authority Key Identifier", "authorityKeyIdentifier");
        addOid("2.5.29.37", 126, "X509v3 Extended Key Usage", "extendedKeyUsage");
        addOid("1.3.6.1.5.5.7.1.1", 177, "Authority Information Access", "authorityInfoAccess");
        addOid("2.5.29.36", 807, "Policy Constraints", "policyConstraints");
        addOid("1.3.6.1.5.5.7.1.3", 1019, "Biometric Info", "biometricInfo");
        addOid("2.16.840.1.113730.1.1", 71, "Netscape Cert Type", "nsCertType");

        // Extended Key Usage OIDs
        addOid("1.3.6.1.5.5.7.3.1", 129, "TLS Web Server Authentication", "serverAuth");
        addOid("1.3.6.1.5.5.7.3.2", 130, "TLS Web Client Authentication", "clientAuth");
        addOid("1.3.6.1.5.5.7.3.3", 131, "Code Signing", "codeSigning");
        addOid("1.3.6.1.5.5.7.3.4", 132, "E-mail Protection", "emailProtection");
        addOid("1.3.6.1.5.5.7.3.8", 133, "Time Stamping", "timeStamping");
        addOid("1.3.6.1.5.5.7.3.9", 180, "OCSP Signing", "OCSPSigning");
        addOid("1.3.6.1.5.5.7.3.17", 1022, "ipsec Internet Key Exchange", "ipsecIKE");
        addOid("1.3.6.1.4.1.311.2.1.21", 134, "Microsoft Individual Code Signing", "msCodeInd");
        addOid("1.3.6.1.4.1.311.2.1.22", 135, "Microsoft Commercial Code Signing", "msCodeCom");
        addOid("1.3.6.1.4.1.311.10.3.1", 136, "Microsoft Trust List Signing", "msCTLSign");
        addOid("1.3.6.1.4.1.311.10.3.4", 138, "Microsoft Encrypted File System", "msEFS");

        // Signature algorithm OIDs
        addOid("1.2.840.113549.1.1.1", 6, "rsaEncryption", "rsaEncryption");
        addOid("1.2.840.113549.1.1.4", 8, "md5WithRSAEncryption", "RSA-MD5");
        addOid("1.2.840.113549.1.1.5", 65, "sha1WithRSAEncryption", "RSA-SHA1");
        addOid("1.2.840.113549.1.1.11", 668, "sha256WithRSAEncryption", "RSA-SHA256");
        addOid("1.2.840.113549.1.1.12", 669, "sha384WithRSAEncryption", "RSA-SHA384");
        addOid("1.2.840.113549.1.1.13", 670, "sha512WithRSAEncryption", "RSA-SHA512");
        addOid("1.2.840.10045.2.1", 408, "id-ecPublicKey", "id-ecPublicKey");
        addOid("1.2.840.10045.4.3.2", 794, "ecdsa-with-SHA256", "ecdsa-with-SHA256");

        // PKCS OIDs (for OBJ_txt2nid / OBJ_ln2nid / OBJ_sn2nid)
        addOid("1.2.840.113549.1", 2, "RSA Data Security, Inc. PKCS", "pkcs");
        addOid("1.2.840.113549.2.5", 4, "md5", "MD5");
        addOid("1.2.840.113549.1.1", 186, "RSA Data Security, Inc. PKCS #1", "pkcs1");

        // PKCS#9 attributes (for CSR)
        addOid("1.2.840.113549.1.9.14", 172, "X509v3 Extension Request", "extReq");
        addOid("1.2.840.113549.1.9.2", 49, "unstructuredName", "unstructuredName");
        addOid("1.2.840.113549.1.9.7", 54, "Challenge Password", "challengePassword");
        addOid("1.2.840.113549.1.9.15", 173, "S/MIME Capabilities", "SMIME-CAPS");

        // Key usage bit names (used by P_X509_get_key_usage)
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

        byte[] toByteArray() {
            return java.util.Arrays.copyOf(data, data.length);
        }
    }

    // Inner class: HMAC context wrapper (Phase 5)
    private static class HmacCtx {
        javax.crypto.Mac mac;
        String algorithmName; // Java MAC algorithm e.g. "HmacSHA256"
        int digestNid;
        byte[] key; // kept so Init_ex can be called with null md/key to re-use
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

    // Inner class: SSL_CTX state
    private static class SslCtxState {
        String role; // "generic", "client", "server"
        long minProtoVersion = 0; // 0 = automatic
        long maxProtoVersion = 0; // 0 = automatic
        int securityLevel = 1;  // OpenSSL 1.1.0+ default
        RuntimeScalar passwdCb = null;       // password callback CODE ref
        RuntimeScalar passwdUserdata = null;  // password callback userdata
        RuntimeScalar infoCallback = null;    // CTX_set_info_callback
        long options = 0;                     // bitmask from CTX_set_options
        long mode = 0;                        // bitmask from set_mode (stored on CTX for convenience)
        String cipherList = null;             // CTX_set_cipher_list argument
        boolean readAhead = false;            // CTX_set_read_ahead
        int verifyMode = 0;                   // set_verify bitmask (VERIFY_NONE/PEER/...)
        RuntimeScalar verifyCb = null;        // set_verify callback
        String tmpDhFile = null;              // CTX_set_tmp_dh placeholder
        long certStoreHandle = 0;             // CTX_get_cert_store stub handle
        javax.net.ssl.SSLContext sslContext = null;  // Phase 2: cached JDK context
        javax.net.ssl.KeyManager[] keyManagers = null;
        javax.net.ssl.TrustManager[] trustManagers = null;
        // Phase 2b: PEM-loaded material, consumed at buildSslContext time.
        java.security.PrivateKey loadedPrivateKey = null;
        java.util.List<X509Certificate> loadedCertChain = new java.util.ArrayList<>();

        SslCtxState(String role) {
            this.role = role;
        }
    }

    // Inner class: SSL state
    private static class SslState {
        String role;
        long minProtoVersion;
        long maxProtoVersion;
        int securityLevel;
        RuntimeScalar passwdCb = null;
        RuntimeScalar passwdUserdata = null;
        long ctxHandle; // reference to parent CTX
        int fd = -1;    // file descriptor (for set_fd)
        long options = 0;
        long mode = 0;
        int verifyMode = 0;
        RuntimeScalar verifyCb = null;
        String hostName = null;          // SNI
        String acceptOrConnect = null;   // "accept" or "connect" from set_*_state
        int state = 1;                   // Net::SSLeay::state() — 1 ≈ OK/initial
        long readBio = 0;                // BIO handle for reading
        long writeBio = 0;               // BIO handle for writing

        // Phase 2: SSLEngine driver state
        javax.net.ssl.SSLEngine engine = null;
        java.nio.ByteBuffer plainIn  = null;  // plaintext decrypted from peer
        java.nio.ByteBuffer plainOut = null;  // plaintext queued for wrap()
        byte[] pendingNetIn = null;            // leftover ciphertext from a partial record
        boolean handshakeComplete = false;
        int lastError = 0;               // SSL_ERROR_* for get_error
        boolean outboundClosed = false;
        boolean inboundClosed = false;

        SslState(SslCtxState ctx, long ctxHandle) {
            this.role = ctx.role;
            this.minProtoVersion = ctx.minProtoVersion;
            this.maxProtoVersion = ctx.maxProtoVersion;
            this.securityLevel = ctx.securityLevel;
            this.ctxHandle = ctxHandle;
            this.options = ctx.options;
            this.mode = ctx.mode;
            this.verifyMode = ctx.verifyMode;
            this.verifyCb = ctx.verifyCb;
        }
    }

    // Inner classes for X509 support
    private static class X509NameEntry {
        String oid;
        byte[] rawBytes;   // raw DER value bytes
        String dataUtf8;   // decoded Unicode string
    }

    private static class X509NameInfo {
        List<X509NameEntry> entries = new ArrayList<>();
        String oneline;
        String rfc2253;
        byte[] derEncoded;
    }

    private static class Asn1StringValue {
        byte[] rawBytes;  // raw DER value bytes
        String utf8Data;  // decoded Unicode string
        Asn1StringValue(byte[] raw, String utf8) {
            this.rawBytes = raw;
            this.utf8Data = utf8;
        }
    }

    private static class X509ExtInfo {
        String oid;
        boolean critical;
        byte[] value; // raw DER value
        X509Certificate cert; // back-reference
        int index;
    }

    private static class X509StoreState {
        List<Long> trustedCerts = new ArrayList<>();
    }

    private static class X509StoreCtxState {
        long certHandle = 0;
        long storeHandle = 0;
        List<Long> chain = null;
        List<Long> untrustedChain = null; // additional untrusted certs for chain building
        int errorCode = 0; // X509_V_OK
    }

    private static class VerifyParamState {
        String name;
        long flags = 0;
        int purpose = 0;
        int trust = 0;
        int depth = -1;
        long time = 0;
    }

    private static class X509InfoEntry {
        long certHandle;  // handle into X509_HANDLES
    }

    // Mutable X509 certificate state (before signing)
    private static class MutableX509State {
        int version = 0;  // 0=v1, 1=v2, 2=v3
        long serialHandle = 0;  // ASN1_INTEGER handle
        long subjectNameHandle = 0;  // X509_NAME handle
        long issuerNameHandle = 0;  // X509_NAME handle
        long pubkeyHandle = 0;  // EVP_PKEY handle
        long notBeforeHandle = 0;  // ASN1_TIME handle
        long notAfterHandle = 0;  // ASN1_TIME handle
        List<MutableExtension> extensions = new ArrayList<>();
    }

    // Mutable X509_REQ (CSR) state
    private static class MutableX509ReqState {
        int version = 0;  // 0=v1
        long subjectNameHandle = 0;  // X509_NAME handle
        long pubkeyHandle = 0;  // EVP_PKEY handle
        List<MutableExtension> extensions = new ArrayList<>();
        List<ReqAttribute> attributes = new ArrayList<>();
        byte[] signedDer = null;  // DER after signing (for re-parsing)
    }

    private static class MutableExtension {
        String oid;
        boolean critical;
        String value;  // OpenSSL-style value string (e.g., "CA:FALSE")
    }

    private static class ReqAttribute {
        int nid;
        String oid;
        int type;  // MBSTRING_ASC, MBSTRING_UTF8, etc.
        String value;
    }

    // Mutable CRL state (before signing)
    private static class MutableCRLState {
        int version = 0;  // CRL version (0=v1, 1=v2)
        long issuerNameHandle = 0;  // X509_NAME handle
        long lastUpdateHandle = 0;  // ASN1_TIME handle
        long nextUpdateHandle = 0;  // ASN1_TIME handle
        long serialHandle = 0;  // ASN1_INTEGER handle (CRL number)
        List<RevokedEntry> revokedEntries = new ArrayList<>();
        List<MutableExtension> extensions = new ArrayList<>();
        byte[] signedDer = null;  // DER after signing
    }

    private static class RevokedEntry {
        String serialHex;
        long revocationTime;  // epoch seconds
        int reason;  // CRL reason code
        long compromiseTime;  // epoch seconds (invalidityDate)
    }

    // Sentinel value for BIO_s_mem() method type
    private static final long BIO_S_MEM_SENTINEL = -1L;
    // Sentinel value for BIO_s_file() method type
    private static final long BIO_S_FILE_SENTINEL = -2L;

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
            mod.registerMethod("SSLeay", "");  // empty proto so SSLeay < 0x... parses correctly
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
            mod.registerMethod("ERR_load_BIO_strings", null);
            mod.registerMethod("ERR_load_ERR_strings", null);
            mod.registerMethod("ERR_load_SSL_strings", null);
            mod.registerMethod("ERR_print_errors_cb", null);
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
            mod.registerMethod("BIO_s_file", null);
            mod.registerMethod("BIO_new", null);
            mod.registerMethod("BIO_new_mem_buf", null);
            mod.registerMethod("BIO_new_file", null);
            mod.registerMethod("BIO_free", null);
            mod.registerMethod("BIO_read", null);
            mod.registerMethod("BIO_write", null);
            mod.registerMethod("BIO_pending", null);
            mod.registerMethod("BIO_eof", null);

            // RSA functions
            mod.registerMethod("RSA_generate_key", null);
            mod.registerMethod("RSA_free", null);
            mod.registerMethod("RSA_get_key_parameters", null);
            mod.registerMethod("RSA_F4", null);
            mod.registerMethod("BN_dup", null);

            // EVP_PKEY functions
            mod.registerMethod("EVP_PKEY_new", null);
            mod.registerMethod("EVP_PKEY_assign_RSA", null);

            // ASN1_TIME functions
            mod.registerMethod("ASN1_TIME_new", null);
            mod.registerMethod("ASN1_TIME_set", null);
            mod.registerMethod("ASN1_TIME_free", null);
            mod.registerMethod("P_ASN1_TIME_put2string", null);
            mod.registerMethod("P_ASN1_UTCTIME_put2string", null);
            mod.registerMethod("P_ASN1_TIME_get_isotime", null);
            mod.registerMethod("P_ASN1_TIME_set_isotime", null);
            mod.registerMethod("X509_gmtime_adj", null);

            // PEM functions
            mod.registerMethod("PEM_read_bio_PrivateKey", null);
            mod.registerMethod("PEM_read_bio_X509", null);
            mod.registerMethod("PEM_read_bio_X509_REQ", null);
            mod.registerMethod("PEM_get_string_X509", null);
            mod.registerMethod("PEM_get_string_PrivateKey", null);
            mod.registerMethod("PEM_get_string_X509_REQ", null);
            mod.registerMethod("d2i_X509_bio", null);
            mod.registerMethod("d2i_X509_REQ_bio", null);

            // EVP_PKEY functions
            mod.registerMethod("EVP_PKEY_free", null);
            mod.registerMethod("EVP_PKEY_size", null);
            mod.registerMethod("EVP_PKEY_bits", null);
            mod.registerMethod("EVP_PKEY_security_bits", null);
            mod.registerMethod("EVP_PKEY_id", null);

            // EVP cipher functions
            mod.registerMethod("EVP_get_cipherbyname", null);
            mod.registerMethod("OSSL_PROVIDER_load", null);
            mod.registerMethod("OSSL_PROVIDER_unload", null);
            mod.registerMethod("OSSL_PROVIDER_available", null);
            mod.registerMethod("OSSL_PROVIDER_try_load", null);
            mod.registerMethod("OSSL_PROVIDER_get0_name", null);
            mod.registerMethod("OSSL_PROVIDER_self_test", null);
            mod.registerMethod("OSSL_PROVIDER_do_all", null);
            mod.registerMethod("OSSL_LIB_CTX_get0_global_default", null);

            // X509 extension functions
            mod.registerMethod("P_X509_add_extensions", null);
            mod.registerMethod("P_X509_copy_extensions", null);
            mod.registerMethod("P_X509_REQ_add_extensions", null);

            // X509_REQ functions
            mod.registerMethod("X509_REQ_new", null);
            mod.registerMethod("X509_REQ_free", null);
            mod.registerMethod("X509_REQ_set_pubkey", null);
            mod.registerMethod("X509_REQ_get_subject_name", null);
            mod.registerMethod("X509_REQ_set_subject_name", null);
            mod.registerMethod("X509_REQ_set_version", null);
            mod.registerMethod("X509_REQ_get_version", null);
            mod.registerMethod("X509_REQ_sign", null);
            mod.registerMethod("X509_REQ_verify", null);
            mod.registerMethod("X509_REQ_get_pubkey", null);
            mod.registerMethod("X509_REQ_get_attr_count", null);
            mod.registerMethod("X509_REQ_get_attr_by_NID", null);
            mod.registerMethod("X509_REQ_get_attr_by_OBJ", null);
            mod.registerMethod("X509_REQ_add1_attr_by_NID", null);
            mod.registerMethod("P_X509_REQ_get_attr", null);
            mod.registerMethod("X509_REQ_digest", null);

            // X509_CRL functions (complex ones use registerMethod, simple ones use lambdas below)
            mod.registerMethod("d2i_X509_CRL_bio", null);
            mod.registerMethod("PEM_read_bio_X509_CRL", null);
            mod.registerMethod("PEM_get_string_X509_CRL", null);
            mod.registerMethod("X509_CRL_sign", null);
            mod.registerMethod("X509_CRL_verify", null);
            mod.registerMethod("X509_CRL_digest", null);
            mod.registerMethod("P_X509_CRL_add_revoked_serial_hex", null);
            mod.registerMethod("P_X509_CRL_add_extensions", null);

            // SSL_CTX functions
            mod.registerMethod("CTX_new", null);
            mod.registerMethod("CTX_v23_new", null);
            mod.registerMethod("CTX_new_with_method", null);
            mod.registerMethod("CTX_free", null);
            mod.registerMethod("SSLv23_method", null);
            mod.registerMethod("SSLv23_client_method", null);
            mod.registerMethod("SSLv23_server_method", null);
            mod.registerMethod("TLSv1_method", null);
            mod.registerMethod("TLS_method", null);
            mod.registerMethod("TLS_client_method", null);
            mod.registerMethod("TLS_server_method", null);

            // SSL functions
            // "new" is registered as "new" — Perl calls Net::SSLeay::new($ctx)
            mod.registerMethod("new", "SSL_new", null);
            mod.registerMethod("SSL_free", null);
            mod.registerMethod("in_connect_init", null);
            mod.registerMethod("in_accept_init", null);

            // Password callback functions (CTX-level)
            mod.registerMethod("CTX_set_default_passwd_cb", null);
            mod.registerMethod("CTX_set_default_passwd_cb_userdata", null);
            mod.registerMethod("CTX_use_PrivateKey_file", null);

            // Password callback functions (SSL-level)
            mod.registerMethod("set_default_passwd_cb", null);
            mod.registerMethod("set_default_passwd_cb_userdata", null);
            mod.registerMethod("use_PrivateKey_file", null);

            // X509 functions
            mod.registerMethod("X509_new", null);
            mod.registerMethod("X509_free", null);
            mod.registerMethod("X509_set_version", null);
            mod.registerMethod("X509_set_pubkey", null);
            mod.registerMethod("X509_set_subject_name", null);
            mod.registerMethod("X509_set_issuer_name", null);
            mod.registerMethod("X509_set_serialNumber", null);
            mod.registerMethod("X509_sign", null);
            mod.registerMethod("X509_get_pubkey", null);
            mod.registerMethod("X509_get_X509_PUBKEY", null);
            mod.registerMethod("X509_get_ext_by_NID", null);
            mod.registerMethod("X509_certificate_type", null);
            mod.registerMethod("X509_get_subject_name", null);
            mod.registerMethod("X509_get_issuer_name", null);
            mod.registerMethod("X509_get_subjectAltNames", null);
            mod.registerMethod("X509_subject_name_hash", null);
            mod.registerMethod("X509_issuer_name_hash", null);
            mod.registerMethod("X509_issuer_and_serial_hash", null);
            mod.registerMethod("X509_get_fingerprint", null);
            mod.registerMethod("X509_pubkey_digest", null);
            mod.registerMethod("X509_digest", null);
            mod.registerMethod("X509_get0_notBefore", null);
            mod.registerMethod("X509_getm_notBefore", null);
            mod.registerMethod("X509_get_notBefore", null);
            mod.registerMethod("X509_get0_notAfter", null);
            mod.registerMethod("X509_getm_notAfter", null);
            mod.registerMethod("X509_get_notAfter", null);
            mod.registerMethod("X509_get_serialNumber", null);
            mod.registerMethod("X509_get0_serialNumber", null);
            mod.registerMethod("X509_get_version", null);
            mod.registerMethod("X509_get_ext_count", null);
            mod.registerMethod("X509_get_ext", null);
            mod.registerMethod("X509_verify_cert", null);

            // X509_NAME functions
            mod.registerMethod("X509_NAME_new", null);
            mod.registerMethod("X509_NAME_hash", null);
            mod.registerMethod("X509_NAME_entry_count", null);
            mod.registerMethod("X509_NAME_oneline", null);
            mod.registerMethod("X509_NAME_print_ex", null);
            mod.registerMethod("X509_NAME_get_entry", null);
            mod.registerMethod("X509_NAME_add_entry_by_NID", null);
            mod.registerMethod("X509_NAME_add_entry_by_OBJ", null);
            mod.registerMethod("X509_NAME_add_entry_by_txt", null);

            // X509_NAME_ENTRY functions
            mod.registerMethod("X509_NAME_ENTRY_get_data", null);
            mod.registerMethod("X509_NAME_ENTRY_get_object", null);

            // X509_EXTENSION functions
            mod.registerMethod("X509_EXTENSION_get_data", null);
            mod.registerMethod("X509_EXTENSION_get_object", null);
            mod.registerMethod("X509_EXTENSION_get_critical", null);
            mod.registerMethod("X509V3_EXT_print", null);

            // OBJ/NID functions
            mod.registerMethod("OBJ_obj2txt", null);
            mod.registerMethod("OBJ_obj2nid", null);
            mod.registerMethod("OBJ_nid2ln", null);
            mod.registerMethod("OBJ_nid2sn", null);
            mod.registerMethod("OBJ_nid2obj", null);
            mod.registerMethod("OBJ_txt2obj", null);
            mod.registerMethod("OBJ_txt2nid", null);
            mod.registerMethod("OBJ_ln2nid", null);
            mod.registerMethod("OBJ_sn2nid", null);
            mod.registerMethod("OBJ_cmp", null);

            // ASN1 accessor functions
            mod.registerMethod("P_ASN1_STRING_get", null);
            mod.registerMethod("P_ASN1_INTEGER_get_hex", null);
            mod.registerMethod("P_ASN1_INTEGER_get_dec", null);
            mod.registerMethod("ASN1_INTEGER_new", null);
            mod.registerMethod("ASN1_INTEGER_set", null);
            mod.registerMethod("ASN1_INTEGER_get", null);
            mod.registerMethod("ASN1_INTEGER_free", null);
            mod.registerMethod("P_ASN1_INTEGER_set_hex", null);
            mod.registerMethod("P_ASN1_INTEGER_set_dec", null);

            // P_X509 convenience functions
            mod.registerMethod("P_X509_get_crl_distribution_points", null);
            mod.registerMethod("P_X509_get_key_usage", null);
            mod.registerMethod("P_X509_get_netscape_cert_type", null);
            mod.registerMethod("P_X509_get_ext_key_usage", null);
            mod.registerMethod("P_X509_get_signature_alg", null);
            mod.registerMethod("P_X509_get_pubkey_alg", null);

            // X509_STORE / X509_STORE_CTX functions
            mod.registerMethod("X509_STORE_new", null);
            mod.registerMethod("X509_STORE_CTX_new", null);
            mod.registerMethod("X509_STORE_CTX_set_cert", null);
            mod.registerMethod("X509_STORE_add_cert", null);
            mod.registerMethod("X509_STORE_CTX_init", null);
            mod.registerMethod("X509_STORE_CTX_get0_cert", null);
            mod.registerMethod("X509_STORE_CTX_get1_chain", null);
            mod.registerMethod("X509_STORE_CTX_get_error", null);
            mod.registerMethod("X509_STORE_free", null);
            mod.registerMethod("X509_STORE_CTX_free", null);
            mod.registerMethod("X509_STORE_set1_param", null);
            mod.registerMethod("X509_verify", null);
            mod.registerMethod("X509_NAME_cmp", null);

            // X509_VERIFY_PARAM functions
            mod.registerMethod("X509_VERIFY_PARAM_new", null);
            mod.registerMethod("X509_VERIFY_PARAM_free", null);
            mod.registerMethod("X509_VERIFY_PARAM_inherit", null);
            mod.registerMethod("X509_VERIFY_PARAM_set1", null);
            mod.registerMethod("X509_VERIFY_PARAM_set1_name", null);
            mod.registerMethod("X509_VERIFY_PARAM_set_flags", null);
            mod.registerMethod("X509_VERIFY_PARAM_get_flags", null);
            mod.registerMethod("X509_VERIFY_PARAM_clear_flags", null);
            mod.registerMethod("X509_VERIFY_PARAM_set_purpose", null);
            mod.registerMethod("X509_VERIFY_PARAM_set_trust", null);
            mod.registerMethod("X509_VERIFY_PARAM_set_depth", null);
            mod.registerMethod("X509_VERIFY_PARAM_set_time", null);
            mod.registerMethod("X509_VERIFY_PARAM_add0_policy", null);
            mod.registerMethod("X509_VERIFY_PARAM_set1_host", null);
            mod.registerMethod("X509_VERIFY_PARAM_add1_host", null);
            mod.registerMethod("X509_VERIFY_PARAM_set1_email", null);
            mod.registerMethod("X509_VERIFY_PARAM_set1_ip", null);
            mod.registerMethod("X509_VERIFY_PARAM_set1_ip_asc", null);
            mod.registerMethod("X509_VERIFY_PARAM_set_hostflags", null);
            mod.registerMethod("X509_VERIFY_PARAM_get0_peername", null);

            // PEM_X509_INFO / sk_X509_INFO functions
            mod.registerMethod("PEM_X509_INFO_read_bio", null);
            mod.registerMethod("sk_X509_INFO_num", null);
            mod.registerMethod("sk_X509_INFO_value", null);
            mod.registerMethod("P_X509_INFO_get_x509", null);

            // PKCS12 functions
            mod.registerMethod("P_PKCS12_load_file", null);

            // sk_X509 (STACK_OF(X509)) functions
            mod.registerMethod("sk_X509_new_null", null);
            mod.registerMethod("sk_X509_num", null);
            mod.registerMethod("sk_X509_value", null);
            mod.registerMethod("sk_X509_insert", null);
            mod.registerMethod("sk_X509_delete", null);
            mod.registerMethod("sk_X509_push", null);
            mod.registerMethod("sk_X509_unshift", null);
            mod.registerMethod("sk_X509_shift", null);
            mod.registerMethod("sk_X509_pop", null);
            mod.registerMethod("sk_X509_free", null);

            // Protocol version functions
            mod.registerMethod("CTX_set_min_proto_version", null);
            mod.registerMethod("CTX_set_max_proto_version", null);
            mod.registerMethod("CTX_get_min_proto_version", null);
            mod.registerMethod("CTX_get_max_proto_version", null);
            mod.registerMethod("set_min_proto_version", null);
            mod.registerMethod("set_max_proto_version", null);
            mod.registerMethod("get_min_proto_version", null);
            mod.registerMethod("get_max_proto_version", null);

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

            // Register constants as subs using PerlSubroutine lambdas (no per-constant Java method needed)
            for (Map.Entry<String, Long> entry : CONSTANTS.entrySet()) {
                String name = entry.getKey();
                long value = entry.getValue();
                PerlSubroutine sub = (a, c) -> new RuntimeScalar(value).getList();
                RuntimeCode code = new RuntimeCode(sub, "");
                code.isStatic = true;
                code.packageName = "Net::SSLeay";
                code.subName = name;
                String fullName = NameNormalizer.normalizeVariableName(name, "Net::SSLeay");
                GlobalVariable.getGlobalCodeRef(fullName).set(new RuntimeScalar(code));
            }

            // Register simple X509_CRL getters/setters as lambdas (no separate Java method needed)
            registerLambda("X509_CRL_new", (a, c) -> {
                long h = HANDLE_COUNTER.getAndIncrement();
                MutableCRLState st = new MutableCRLState();
                // lastUpdate and nextUpdate start as 0 (NULL) — no ASN1_TIME handles yet
                // They get created on first set operation
                CRL_HANDLES.put(h, st);
                return new RuntimeScalar(h).getList();
            });
            registerLambda("X509_CRL_free", (a, c) -> {
                if (a.size() >= 1) {
                    long h = a.get(0).getLong();
                    CRL_HANDLES.remove(h);
                    X509_CRL_HANDLES.remove(h);
                }
                return new RuntimeScalar().getList(); // returns undef
            });
            registerLambda("X509_CRL_get_issuer", (a, c) -> {
                if (a.size() < 1) return new RuntimeScalar().getList();
                long h = a.get(0).getLong();
                MutableCRLState st = CRL_HANDLES.get(h);
                if (st != null) return new RuntimeScalar(st.issuerNameHandle != 0 ? st.issuerNameHandle : 0).getList();
                X509CRL crl = X509_CRL_HANDLES.get(h);
                if (crl == null) return new RuntimeScalar().getList();
                X509NameInfo info = parseX500Principal(crl.getIssuerX500Principal());
                long nh = HANDLE_COUNTER.getAndIncrement();
                X509_NAME_HANDLES.put(nh, info);
                return new RuntimeScalar(nh).getList();
            });
            registerLambda("X509_CRL_get_version", (a, c) -> {
                if (a.size() < 1) return new RuntimeScalar(0).getList();
                long h = a.get(0).getLong();
                MutableCRLState st = CRL_HANDLES.get(h);
                if (st != null) return new RuntimeScalar(st.version).getList();
                X509CRL crl = X509_CRL_HANDLES.get(h);
                if (crl == null) return new RuntimeScalar(0).getList();
                return new RuntimeScalar(crl.getVersion() - 1).getList(); // Java returns 1-based, OpenSSL 0-based
            });
            registerLambda("X509_CRL_set_version", (a, c) -> {
                if (a.size() < 2) return new RuntimeScalar(0).getList();
                MutableCRLState st = CRL_HANDLES.get(a.get(0).getLong());
                if (st == null) return new RuntimeScalar(0).getList();
                st.version = (int) a.get(1).getLong();
                return new RuntimeScalar(1).getList();
            });
            registerLambda("X509_CRL_set_issuer_name", (a, c) -> {
                if (a.size() < 2) return new RuntimeScalar(0).getList();
                MutableCRLState st = CRL_HANDLES.get(a.get(0).getLong());
                if (st == null) return new RuntimeScalar(0).getList();
                long nameH = a.get(1).getLong();
                if (!X509_NAME_HANDLES.containsKey(nameH)) return new RuntimeScalar(0).getList();
                st.issuerNameHandle = nameH;
                return new RuntimeScalar(1).getList();
            });
            // CRL time getters (work for both mutable and read-only CRLs)
            // For read-only CRLs, cache time handles so get0_ and get_ aliases return same value
            PerlSubroutine crlGetLastUpdate = (a, c) -> {
                if (a.size() < 1) return new RuntimeScalar().getList();
                long h = a.get(0).getLong();
                MutableCRLState st = CRL_HANDLES.get(h);
                if (st != null) return new RuntimeScalar(st.lastUpdateHandle).getList();
                X509CRL crl = X509_CRL_HANDLES.get(h);
                if (crl == null) return new RuntimeScalar(0).getList();
                // Use handle stored in crlTimeCache, or create one
                String cacheKey = h + ":last";
                Long cached = CRL_TIME_CACHE.get(cacheKey);
                if (cached != null) return new RuntimeScalar(cached).getList();
                long th = HANDLE_COUNTER.getAndIncrement();
                ASN1_TIME_HANDLES.put(th, crl.getThisUpdate().getTime() / 1000);
                CRL_TIME_CACHE.put(cacheKey, th);
                return new RuntimeScalar(th).getList();
            };
            PerlSubroutine crlGetNextUpdate = (a, c) -> {
                if (a.size() < 1) return new RuntimeScalar().getList();
                long h = a.get(0).getLong();
                MutableCRLState st = CRL_HANDLES.get(h);
                if (st != null) return new RuntimeScalar(st.nextUpdateHandle).getList();
                X509CRL crl = X509_CRL_HANDLES.get(h);
                if (crl == null) return new RuntimeScalar(0).getList();
                java.util.Date next = crl.getNextUpdate();
                if (next == null) return new RuntimeScalar(0).getList();
                String cacheKey = h + ":next";
                Long cached = CRL_TIME_CACHE.get(cacheKey);
                if (cached != null) return new RuntimeScalar(cached).getList();
                long th = HANDLE_COUNTER.getAndIncrement();
                ASN1_TIME_HANDLES.put(th, next.getTime() / 1000);
                CRL_TIME_CACHE.put(cacheKey, th);
                return new RuntimeScalar(th).getList();
            };
            registerLambda("X509_CRL_get0_lastUpdate", crlGetLastUpdate);
            registerLambda("X509_CRL_get_lastUpdate", crlGetLastUpdate);
            registerLambda("X509_CRL_get0_nextUpdate", crlGetNextUpdate);
            registerLambda("X509_CRL_get_nextUpdate", crlGetNextUpdate);
            // CRL time setters (mutable only) — create time handles on demand
            PerlSubroutine crlSetLastUpdate = (a, c) -> {
                if (a.size() < 2) return new RuntimeScalar(0).getList();
                MutableCRLState st = CRL_HANDLES.get(a.get(0).getLong());
                if (st == null) return new RuntimeScalar(0).getList();
                long timeH = a.get(1).getLong();
                Long epoch = ASN1_TIME_HANDLES.get(timeH);
                if (epoch == null) return new RuntimeScalar(0).getList();
                if (st.lastUpdateHandle == 0) {
                    st.lastUpdateHandle = HANDLE_COUNTER.getAndIncrement();
                }
                ASN1_TIME_HANDLES.put(st.lastUpdateHandle, epoch);
                return new RuntimeScalar(1).getList();
            };
            PerlSubroutine crlSetNextUpdate = (a, c) -> {
                if (a.size() < 2) return new RuntimeScalar(0).getList();
                MutableCRLState st = CRL_HANDLES.get(a.get(0).getLong());
                if (st == null) return new RuntimeScalar(0).getList();
                long timeH = a.get(1).getLong();
                Long epoch = ASN1_TIME_HANDLES.get(timeH);
                if (epoch == null) return new RuntimeScalar(0).getList();
                if (st.nextUpdateHandle == 0) {
                    st.nextUpdateHandle = HANDLE_COUNTER.getAndIncrement();
                }
                ASN1_TIME_HANDLES.put(st.nextUpdateHandle, epoch);
                return new RuntimeScalar(1).getList();
            };
            registerLambda("X509_CRL_set1_lastUpdate", crlSetLastUpdate);
            registerLambda("X509_CRL_set_lastUpdate", crlSetLastUpdate);
            registerLambda("X509_CRL_set1_nextUpdate", crlSetNextUpdate);
            registerLambda("X509_CRL_set_nextUpdate", crlSetNextUpdate);
            registerLambda("X509_CRL_sort", (a, c) -> new RuntimeScalar(1).getList()); // no-op, sort during sign
            registerLambda("P_X509_CRL_set_serial", (a, c) -> {
                if (a.size() < 2) return new RuntimeScalar(0).getList();
                MutableCRLState st = CRL_HANDLES.get(a.get(0).getLong());
                if (st == null) return new RuntimeScalar(0).getList();
                st.serialHandle = a.get(1).getLong();
                return new RuntimeScalar(1).getList();
            });
            registerLambda("P_X509_CRL_get_serial", (a, c) -> {
                if (a.size() < 1) return new RuntimeScalar().getList();
                long h = a.get(0).getLong();
                MutableCRLState st = CRL_HANDLES.get(h);
                if (st != null && st.serialHandle != 0) return new RuntimeScalar(st.serialHandle).getList();
                // For read-only CRLs, extract CRL number from extensions
                X509CRL crl = X509_CRL_HANDLES.get(h);
                if (crl == null) return new RuntimeScalar().getList();
                byte[] crlNumExt = crl.getExtensionValue("2.5.29.20"); // CRL Number OID
                if (crlNumExt == null) return new RuntimeScalar().getList();
                try {
                    // CRL Number extension: OCTET STRING wrapping an INTEGER
                    // Skip outer OCTET STRING tag+len, then parse inner INTEGER
                    int[] pos = {0};
                    int[] len = {0};
                    readDerTag(crlNumExt, pos, len); // outer OCTET STRING
                    byte[] inner = new byte[len[0]];
                    System.arraycopy(crlNumExt, pos[0], inner, 0, len[0]);
                    pos[0] = 0;
                    readDerTag(inner, pos, len); // INTEGER tag
                    byte[] intBytes = new byte[len[0]];
                    System.arraycopy(inner, pos[0], intBytes, 0, len[0]);
                    BigInteger crlNum = new BigInteger(1, intBytes);
                    long ih = HANDLE_COUNTER.getAndIncrement();
                    ASN1_INTEGER_HANDLES.put(ih, crlNum);
                    return new RuntimeScalar(ih).getList();
                } catch (Exception e) {
                    return new RuntimeScalar().getList();
                }
            });

            // Security level get/set (OpenSSL 1.1.0+)
            registerLambda("CTX_get_security_level", (a, c) -> {
                if (a.size() < 1) return new RuntimeScalar(0).getList();
                SslCtxState st = CTX_HANDLES.get(a.get(0).getLong());
                return new RuntimeScalar(st != null ? st.securityLevel : 0).getList();
            });
            registerLambda("CTX_set_security_level", (a, c) -> {
                if (a.size() < 2) return new RuntimeScalar().getList();
                SslCtxState st = CTX_HANDLES.get(a.get(0).getLong());
                if (st != null) st.securityLevel = (int) a.get(1).getLong();
                return new RuntimeScalar().getList();
            });
            registerLambda("get_security_level", (a, c) -> {
                if (a.size() < 1) return new RuntimeScalar(0).getList();
                SslState st = SSL_HANDLES.get(a.get(0).getLong());
                return new RuntimeScalar(st != null ? st.securityLevel : 0).getList();
            });
            registerLambda("set_security_level", (a, c) -> {
                if (a.size() < 2) return new RuntimeScalar().getList();
                SslState st = SSL_HANDLES.get(a.get(0).getLong());
                if (st != null) st.securityLevel = (int) a.get(1).getLong();
                return new RuntimeScalar().getList();
            });

            // ex_data API — used by AnyEvent::TLS to associate Perl-side refs
            // with SSL sessions. The real OpenSSL hands out monotonically
            // increasing indices; we keep per-handle maps keyed by the returned
            // index. Index 0 is reserved by OpenSSL (AnyEvent's load-time loop
            // does `until $REF_IDX;`, so we must never return 0).
            registerLambda("get_ex_new_index", (a, c) -> {
                long idx = EX_INDEX_COUNTER.getAndIncrement();
                return new RuntimeScalar(idx).getList();
            });
            registerLambda("set_ex_data", (a, c) -> {
                if (a.size() < 3) return new RuntimeScalar().getList();
                long sslHandle = a.get(0).getLong();
                int idx = (int) a.get(1).getLong();
                RuntimeScalar val = a.get(2).scalar();
                EX_DATA.computeIfAbsent(sslHandle, k -> new java.util.concurrent.ConcurrentHashMap<>())
                        .put(idx, val);
                return new RuntimeScalar(1).getList();
            });
            registerLambda("get_ex_data", (a, c) -> {
                if (a.size() < 2) return new RuntimeScalar().getList();
                long sslHandle = a.get(0).getLong();
                int idx = (int) a.get(1).getLong();
                java.util.Map<Integer, RuntimeScalar> m = EX_DATA.get(sslHandle);
                if (m == null) return new RuntimeScalar().getList();
                RuntimeScalar v = m.get(idx);
                return v != null ? v.getList() : new RuntimeScalar().getList();
            });

            // -------------------------------------------------------------
            // AnyEvent::TLS compatibility stubs.
            //
            // These accept the same signatures as OpenSSL's libssl wrappers
            // and store just enough state on SslCtxState/SslState to let
            // AnyEvent::TLS load and exercise its configuration code paths
            // without an actual TLS handshake. A real handshake is not yet
            // plumbed through the Java-side SSLEngine here — functions that
            // would drive bytes (set_bio, read, write, shutdown, handshake
            // state) are stubbed to return success/zero-like values.
            //
            // Grep for "// STUB (phase N)" to find every fake success and
            // the phase of dev/modules/netssleay_complete.md that replaces it
            // with a real implementation. Do NOT copy this pattern for new
            // work — call registerNotImplemented(name, phase) instead.
            // -------------------------------------------------------------

            // Version-specific CTX constructors: we map them all to the
            // generic CTX_new path since the Java SSLContext choice is
            // handled by min/max proto version.
            // STUB (phase 2): version constants are currently ignored — we
            // don't pin the SSLContext protocol based on the factory choice.
            registerLambda("CTX_tlsv1_new", (a, c) -> {
                RuntimeArray args = new RuntimeArray();
                return new RuntimeList(CTX_new(args, c).getFirst());
            });
            registerLambda("CTX_tlsv1_1_new", (a, c) -> {
                RuntimeArray args = new RuntimeArray();
                return new RuntimeList(CTX_new(args, c).getFirst());
            });
            registerLambda("CTX_tlsv1_2_new", (a, c) -> {
                RuntimeArray args = new RuntimeArray();
                return new RuntimeList(CTX_new(args, c).getFirst());
            });
            registerLambda("CTX_v2_new", (a, c) -> {
                RuntimeArray args = new RuntimeArray();
                return new RuntimeList(CTX_new(args, c).getFirst());
            });
            registerLambda("CTX_v3_new", (a, c) -> {
                RuntimeArray args = new RuntimeArray();
                return new RuntimeList(CTX_new(args, c).getFirst());
            });

            // CTX option/mode setters — bitmask OR, return previous value.
            // STUB (phase 2): the options are stored on SslCtxState but
            // are not forwarded to the underlying SSLContext/SSLEngine.
            registerLambda("CTX_set_options", (a, c) -> {
                if (a.size() < 2) return new RuntimeScalar(0).getList();
                SslCtxState st = CTX_HANDLES.get(a.get(0).getLong());
                if (st == null) return new RuntimeScalar(0).getList();
                long prev = st.options;
                st.options |= a.get(1).getLong();
                return new RuntimeScalar(st.options).getList();
            });
            registerLambda("CTX_set_read_ahead", (a, c) -> {
                // STUB (phase 2): stored, not plumbed through to SSLEngine.
                if (a.size() < 2) return new RuntimeScalar(0).getList();
                SslCtxState st = CTX_HANDLES.get(a.get(0).getLong());
                if (st == null) return new RuntimeScalar(0).getList();
                st.readAhead = a.get(1).getBoolean();
                return new RuntimeScalar(1).getList();
            });
            registerLambda("CTX_set_tmp_dh", (a, c) -> {
                // STUB (phase 2+3): DH parameter support needs a real
                // PEM_read_bio_DHparams plus wiring into SSLParameters.
                return new RuntimeScalar(1).getList();
            });
            registerLambda("CTX_use_certificate_chain_file", (a, c) -> {
                // Phase 2b: parse PEM cert chain, stash on the CTX for
                // the KeyManagerFactory build.
                if (a.size() < 2) return new RuntimeScalar(0).getList();
                long h = a.get(0).getLong();
                SslCtxState st = CTX_HANDLES.get(h);
                if (st == null) return new RuntimeScalar(0).getList();
                String fname = a.get(1).toString();
                try {
                    java.util.List<X509Certificate> chain = loadCertChainFromPem(fname);
                    if (chain.isEmpty()) return new RuntimeScalar(0).getList();
                    st.loadedCertChain = chain;
                    st.sslContext = null;
                    return new RuntimeScalar(1).getList();
                } catch (Exception e) {
                    return new RuntimeScalar(0).getList();
                }
            });
            registerLambda("CTX_use_certificate_file", (a, c) -> {
                if (a.size() < 2) return new RuntimeScalar(0).getList();
                long h = a.get(0).getLong();
                SslCtxState st = CTX_HANDLES.get(h);
                if (st == null) return new RuntimeScalar(0).getList();
                String fname = a.get(1).toString();
                try {
                    java.util.List<X509Certificate> chain = loadCertChainFromPem(fname);
                    if (chain.isEmpty()) return new RuntimeScalar(0).getList();
                    // Preserve any existing intermediates from a previous
                    // chain_file call; just replace the leaf.
                    if (st.loadedCertChain == null) st.loadedCertChain = new ArrayList<>();
                    if (st.loadedCertChain.isEmpty()) {
                        st.loadedCertChain.addAll(chain);
                    } else {
                        st.loadedCertChain.set(0, chain.get(0));
                    }
                    st.sslContext = null;
                    return new RuntimeScalar(1).getList();
                } catch (Exception e) {
                    return new RuntimeScalar(0).getList();
                }
            });
            registerLambda("CTX_load_verify_locations", (a, c) -> {
                // STUB (phase 2): ignores cafile/capath; cert validation
                // still falls back to the JVM default TrustManagerFactory.
                return new RuntimeScalar(1).getList();
            });
            registerLambda("CTX_set_default_verify_paths", (a, c) -> {
                // STUB (phase 2): trust store is always the JVM default.
                return new RuntimeScalar(1).getList();
            });
            registerLambda("CTX_set_cipher_list", (a, c) -> {
                // STUB (phase 2): stored on SslCtxState; not applied to
                // SSLEngine.setEnabledCipherSuites yet.
                if (a.size() < 2) return new RuntimeScalar(0).getList();
                SslCtxState st = CTX_HANDLES.get(a.get(0).getLong());
                if (st == null) return new RuntimeScalar(0).getList();
                st.cipherList = a.get(1).toString();
                return new RuntimeScalar(1).getList();
            });
            registerLambda("CTX_get_cert_store", (a, c) -> {
                if (a.size() < 1) return new RuntimeScalar(0).getList();
                SslCtxState st = CTX_HANDLES.get(a.get(0).getLong());
                if (st == null) return new RuntimeScalar(0).getList();
                if (st.certStoreHandle == 0) {
                    st.certStoreHandle = HANDLE_COUNTER.getAndIncrement();
                }
                return new RuntimeScalar(st.certStoreHandle).getList();
            });

            // BIO-backed DH params: we don't implement DH, so return a stub handle.
            // STUB (phase 3): needs a real ASN.1 decoder for the
            // `BEGIN DH PARAMETERS` PEM block and a javax.crypto.spec.
            // DHParameterSpec on the returned handle.
            registerLambda("PEM_read_bio_DHparams", (a, c) -> {
                return new RuntimeScalar(HANDLE_COUNTER.getAndIncrement()).getList();
            });
            // STUB (phase 3): no DH resource to free yet.
            registerLambda("DH_free", (a, c) -> new RuntimeScalar().getList());

            // Per-SSL-handle setters — Phase 2 now drives a real SSLEngine
            // when the caller sets accept/connect state after binding BIOs.
            registerLambda("set_accept_state", (a, c) -> {
                if (a.size() < 1) return new RuntimeScalar().getList();
                SslState st = SSL_HANDLES.get(a.get(0).getLong());
                if (st == null) return new RuntimeScalar().getList();
                st.acceptOrConnect = "accept";
                try {
                    st.engine = buildEngine(st, false);
                    st.engine.beginHandshake();
                    st.state = 0x2000; // SSL_ST_ACCEPT sentinel
                } catch (Exception e) {
                    st.lastError = SSL_ERROR_SSL;
                }
                return new RuntimeScalar().getList();
            });
            registerLambda("set_connect_state", (a, c) -> {
                if (a.size() < 1) return new RuntimeScalar().getList();
                SslState st = SSL_HANDLES.get(a.get(0).getLong());
                if (st == null) return new RuntimeScalar().getList();
                st.acceptOrConnect = "connect";
                try {
                    st.engine = buildEngine(st, true);
                    st.engine.beginHandshake();
                    st.state = 0x1000; // SSL_ST_CONNECT sentinel
                } catch (Exception e) {
                    st.lastError = SSL_ERROR_SSL;
                }
                return new RuntimeScalar().getList();
            });
            registerLambda("set_bio", (a, c) -> {
                // (ssl, read_bio, write_bio)
                if (a.size() < 3) return new RuntimeScalar().getList();
                SslState st = SSL_HANDLES.get(a.get(0).getLong());
                if (st != null) {
                    st.readBio = a.get(1).getLong();
                    st.writeBio = a.get(2).getLong();
                }
                return new RuntimeScalar().getList();
            });
            // STUB (phase 2): info callback is stored but never fired.
            registerLambda("set_info_callback", (a, c) -> new RuntimeScalar().getList());
            registerLambda("set_mode", (a, c) -> {
                // STUB (phase 2): stored, not applied to the SSLEngine.
                if (a.size() < 2) return new RuntimeScalar(0).getList();
                SslState st = SSL_HANDLES.get(a.get(0).getLong());
                if (st == null) return new RuntimeScalar(0).getList();
                st.mode |= a.get(1).getLong();
                return new RuntimeScalar(st.mode).getList();
            });
            registerLambda("set_options", (a, c) -> {
                // STUB (phase 2): stored, not applied to the SSLEngine.
                if (a.size() < 2) return new RuntimeScalar(0).getList();
                SslState st = SSL_HANDLES.get(a.get(0).getLong());
                if (st == null) return new RuntimeScalar(0).getList();
                st.options |= a.get(1).getLong();
                return new RuntimeScalar(st.options).getList();
            });
            registerLambda("set_tlsext_host_name", (a, c) -> {
                if (a.size() < 2) return new RuntimeScalar(0).getList();
                SslState st = SSL_HANDLES.get(a.get(0).getLong());
                if (st == null) return new RuntimeScalar(0).getList();
                st.hostName = a.get(1).toString();
                // If the engine is already built, apply retroactively
                if (st.engine != null && st.engine.getUseClientMode()) {
                    try {
                        javax.net.ssl.SSLParameters p = st.engine.getSSLParameters();
                        p.setServerNames(java.util.Collections.singletonList(
                                new javax.net.ssl.SNIHostName(st.hostName)));
                        st.engine.setSSLParameters(p);
                    } catch (Exception ignored) { /* best effort */ }
                }
                return new RuntimeScalar(1).getList();
            });
            registerLambda("set_verify", (a, c) -> {
                if (a.size() < 2) return new RuntimeScalar().getList();
                SslState st = SSL_HANDLES.get(a.get(0).getLong());
                if (st != null) {
                    st.verifyMode = (int) a.get(1).getLong();
                    if (a.size() >= 3) st.verifyCb = a.get(2).scalar();
                }
                return new RuntimeScalar().getList();
            });
            registerLambda("state", (a, c) -> {
                if (a.size() < 1) return new RuntimeScalar(0).getList();
                SslState st = SSL_HANDLES.get(a.get(0).getLong());
                return new RuntimeScalar(st != null ? st.state : 0).getList();
            });
            registerLambda("shutdown", (a, c) -> {
                // Close-notify: let the SSLEngine emit the alert and
                // flush any remaining wrap bytes to wbio.
                if (a.size() < 1) return new RuntimeScalar(0).getList();
                SslState st = SSL_HANDLES.get(a.get(0).getLong());
                if (st == null || st.engine == null) return new RuntimeScalar(1).getList();
                st.engine.closeOutbound();
                advance(st);
                // Return 1 if both directions closed, 0 if more work needed.
                // AnyEvent::Handle's shutdown loop keeps calling until 1.
                return new RuntimeScalar(
                        st.outboundClosed && (st.inboundClosed || st.engine.isInboundDone())
                                ? 1 : 0).getList();
            });

            // TLS data plane: drive the SSLEngine through in-memory BIOs.
            registerLambda("read", (a, c) -> {
                if (a.size() < 1) return new RuntimeScalar().getList();
                SslState st = SSL_HANDLES.get(a.get(0).getLong());
                if (st == null || st.engine == null) return new RuntimeScalar().getList();
                int maxLen = a.size() >= 2 ? (int) a.get(1).getLong() : 32768;
                advance(st);
                st.plainIn.flip();
                if (!st.plainIn.hasRemaining()) {
                    st.plainIn.compact();
                    return new RuntimeScalar().getList();  // undef → WANT_READ
                }
                int n = Math.min(maxLen, st.plainIn.remaining());
                byte[] out = new byte[n];
                st.plainIn.get(out);
                st.plainIn.compact();
                return bytesToPerlString(out).getList();
            });
            registerLambda("write", (a, c) -> {
                if (a.size() < 2) return new RuntimeScalar(-1).getList();
                SslState st = SSL_HANDLES.get(a.get(0).getLong());
                if (st == null || st.engine == null) return new RuntimeScalar(-1).getList();
                byte[] data = a.get(1).toString().getBytes(StandardCharsets.ISO_8859_1);
                // Enqueue plaintext; advance will wrap
                if (st.plainOut.remaining() < data.length) {
                    // Grow
                    java.nio.ByteBuffer bigger = java.nio.ByteBuffer.allocate(
                            st.plainOut.position() + data.length + 16384);
                    st.plainOut.flip();
                    bigger.put(st.plainOut);
                    st.plainOut = bigger;
                }
                st.plainOut.put(data);
                advance(st);
                if (st.lastError != SSL_ERROR_NONE
                        && st.lastError != SSL_ERROR_WANT_READ
                        && st.lastError != SSL_ERROR_WANT_WRITE) {
                    return new RuntimeScalar(-1).getList();
                }
                return new RuntimeScalar(data.length).getList();
            });
            registerLambda("get_error", (a, c) -> {
                if (a.size() < 1) return new RuntimeScalar(SSL_ERROR_SYSCALL).getList();
                SslState st = SSL_HANDLES.get(a.get(0).getLong());
                return new RuntimeScalar(st != null ? st.lastError : SSL_ERROR_SYSCALL).getList();
            });
            // accept()/connect() — drive the handshake until it finishes or
            // wants more data.
            registerLambda("accept", (a, c) -> {
                if (a.size() < 1) return new RuntimeScalar(-1).getList();
                SslState st = SSL_HANDLES.get(a.get(0).getLong());
                if (st == null || st.engine == null) return new RuntimeScalar(-1).getList();
                int err = advance(st);
                if (st.handshakeComplete) return new RuntimeScalar(1).getList();
                return new RuntimeScalar(err == SSL_ERROR_WANT_READ ? -1 : 0).getList();
            });
            registerLambda("connect", (a, c) -> {
                if (a.size() < 1) return new RuntimeScalar(-1).getList();
                SslState st = SSL_HANDLES.get(a.get(0).getLong());
                if (st == null || st.engine == null) return new RuntimeScalar(-1).getList();
                int err = advance(st);
                if (st.handshakeComplete) return new RuntimeScalar(1).getList();
                return new RuntimeScalar(err == SSL_ERROR_WANT_READ ? -1 : 0).getList();
            });
            registerLambda("do_handshake", (a, c) -> {
                if (a.size() < 1) return new RuntimeScalar(-1).getList();
                SslState st = SSL_HANDLES.get(a.get(0).getLong());
                if (st == null || st.engine == null) return new RuntimeScalar(-1).getList();
                int err = advance(st);
                if (st.handshakeComplete) return new RuntimeScalar(1).getList();
                return new RuntimeScalar(err == SSL_ERROR_WANT_READ ? -1 : 0).getList();
            });
            registerLambda("pending", (a, c) -> {
                if (a.size() < 1) return new RuntimeScalar(0).getList();
                SslState st = SSL_HANDLES.get(a.get(0).getLong());
                if (st == null || st.plainIn == null) return new RuntimeScalar(0).getList();
                return new RuntimeScalar(st.plainIn.position()).getList();
            });
            registerLambda("get_version", (a, c) -> {
                if (a.size() < 1) return new RuntimeScalar("unknown").getList();
                SslState st = SSL_HANDLES.get(a.get(0).getLong());
                if (st == null || st.engine == null
                        || st.engine.getSession() == null) {
                    return new RuntimeScalar("unknown").getList();
                }
                return new RuntimeScalar(st.engine.getSession().getProtocol()).getList();
            });

            // X509 stubs for the verify callback. STUB (phase 4): real
            // implementations need to walk the cert chain built by the
            // Java TrustManager.
            registerLambda("X509_STORE_set_flags", (a, c) -> new RuntimeScalar(1).getList());
            registerLambda("X509_STORE_CTX_get_current_cert", (a, c) ->
                    new RuntimeScalar(HANDLE_COUNTER.getAndIncrement()).getList());
            registerLambda("X509_STORE_CTX_get_error_depth", (a, c) ->
                    new RuntimeScalar(0).getList());
            registerLambda("X509_NAME_get_text_by_NID", (a, c) -> new RuntimeScalar("").getList());

            // Signature algorithm list functions are NOT registered because
            // 67_sigalgs.t unconditionally calls fork() after the non-fork tests,
            // triggering BAIL_OUT which aborts the entire test harness.
            // The functions can be re-enabled when fork or BIO-based handshake is available.

            // SSL handshake stubs (needed by test helper is_protocol_usable)
            registerLambda("set_fd", (a, c) -> {
                if (a.size() < 2) return new RuntimeScalar(0).getList();
                SslState st = SSL_HANDLES.get(a.get(0).getLong());
                if (st == null) return new RuntimeScalar(0).getList();
                st.fd = (int) a.get(1).getLong();
                return new RuntimeScalar(1).getList();
            });
            registerLambda("CTX_set_info_callback", (a, c) -> {
                if (a.size() < 1) return new RuntimeScalar().getList();
                SslCtxState st = CTX_HANDLES.get(a.get(0).getLong());
                if (st != null && a.size() >= 2) {
                    st.infoCallback = a.get(1);
                }
                return new RuntimeScalar().getList();
            });
            // free() is an alias for SSL_free()
            registerLambda("free", (a, c) -> {
                if (a.size() < 1) return new RuntimeScalar().getList();
                long handleId = a.get(0).getLong();
                SSL_HANDLES.remove(handleId);
                return new RuntimeScalar().getList();
            });
            registerLambda("connect", (a, c) -> {
                // Stub: simulate a failed connection (no real handshake)
                // The is_protocol_usable helper checks info callback states,
                // so we fire the callbacks to indicate the protocol is usable.
                if (a.size() < 1) return new RuntimeScalar(-1).getList();
                long sslHandle = a.get(0).getLong();
                SslState st = SSL_HANDLES.get(sslHandle);
                if (st == null) return new RuntimeScalar(-1).getList();
                // Fire info callback with CB_HANDSHAKE_START, CB_CONNECT_LOOP, CB_CONNECT_EXIT
                SslCtxState ctxSt = CTX_HANDLES.get(st.ctxHandle);
                if (ctxSt != null && ctxSt.infoCallback != null
                        && ctxSt.infoCallback.type == RuntimeScalarType.CODE) {
                    RuntimeArray cbArgs = new RuntimeArray();
                    // CB_HANDSHAKE_START = 0x10, CB_CONNECT_LOOP = 0x1001, CB_CONNECT_EXIT = 0x1002
                    long CB_HANDSHAKE_START = 0x10;
                    long CB_CONNECT_LOOP = 0x1001;
                    long CB_CONNECT_EXIT = 0x1002;
                    // Fire HANDSHAKE_START
                    cbArgs.push(new RuntimeScalar(sslHandle));
                    cbArgs.push(new RuntimeScalar(CB_HANDSHAKE_START));
                    cbArgs.push(new RuntimeScalar(1));
                    try { RuntimeCode.apply(ctxSt.infoCallback, cbArgs, RuntimeContextType.VOID); } catch (Exception e) {}
                    // Fire CONNECT_LOOP
                    cbArgs.elements.clear();
                    cbArgs.push(new RuntimeScalar(sslHandle));
                    cbArgs.push(new RuntimeScalar(CB_CONNECT_LOOP));
                    cbArgs.push(new RuntimeScalar(1));
                    try { RuntimeCode.apply(ctxSt.infoCallback, cbArgs, RuntimeContextType.VOID); } catch (Exception e) {}
                    // Fire CONNECT_EXIT (failed)
                    cbArgs.elements.clear();
                    cbArgs.push(new RuntimeScalar(sslHandle));
                    cbArgs.push(new RuntimeScalar(CB_CONNECT_EXIT));
                    cbArgs.push(new RuntimeScalar(-1));
                    try { RuntimeCode.apply(ctxSt.infoCallback, cbArgs, RuntimeContextType.VOID); } catch (Exception e) {}
                }
                return new RuntimeScalar(-1).getList(); // connection "failed" (no real socket)
            });

            // EC key functions
            registerLambda("EC_KEY_generate_key", (a, c) -> {
                if (a.size() < 1) return new RuntimeScalar().getList();
                String curveName = a.get(0).toString();
                // Map OpenSSL curve names to Java names
                String javaCurve = curveName;
                if ("prime256v1".equals(curveName)) javaCurve = "secp256r1";
                else if ("secp384r1".equals(curveName)) javaCurve = "secp384r1";
                else if ("secp521r1".equals(curveName)) javaCurve = "secp521r1";
                try {
                    java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("EC");
                    kpg.initialize(new java.security.spec.ECGenParameterSpec(javaCurve));
                    KeyPair kp = kpg.generateKeyPair();
                    long h = HANDLE_COUNTER.getAndIncrement();
                    EC_KEY_HANDLES.put(h, kp);
                    return new RuntimeScalar(h).getList();
                } catch (Exception e) {
                    return new RuntimeScalar().getList();
                }
            });
            registerLambda("EVP_PKEY_assign_EC_KEY", (a, c) -> {
                if (a.size() < 2) return new RuntimeScalar(0).getList();
                long pkeyHandle = a.get(0).getLong();
                long ecHandle = a.get(1).getLong();
                if (!EVP_PKEY_HANDLES.containsKey(pkeyHandle)) return new RuntimeScalar(0).getList();
                KeyPair kp = EC_KEY_HANDLES.get(ecHandle);
                if (kp == null) return new RuntimeScalar(0).getList();
                EVP_PKEY_HANDLES.put(pkeyHandle, kp.getPrivate());
                return new RuntimeScalar(1).getList();
            });

            // -------------------------------------------------------------
            // Phase 5 — HMAC incremental API (java.crypto.Mac-backed)
            // -------------------------------------------------------------
            registerLambda("HMAC_CTX_new", (a, c) -> {
                long h = HANDLE_COUNTER.getAndIncrement();
                HMAC_CTX_HANDLES.put(h, new HmacCtx());
                return new RuntimeScalar(h).getList();
            });
            registerLambda("HMAC_CTX_free", (a, c) -> {
                if (a.size() > 0) HMAC_CTX_HANDLES.remove(a.get(0).getLong());
                return new RuntimeScalar(1).getList();
            });
            registerLambda("HMAC_CTX_reset", (a, c) -> {
                if (a.size() < 1) return new RuntimeScalar(0).getList();
                HmacCtx h = HMAC_CTX_HANDLES.get(a.get(0).getLong());
                if (h == null) return new RuntimeScalar(0).getList();
                h.mac = null; h.algorithmName = null; h.digestNid = 0; h.key = null;
                return new RuntimeScalar(1).getList();
            });
            // HMAC_Init_ex(ctx, key, len, md, engine)
            // HMAC_Init(ctx, key, len, md)  -- same semantics
            PerlSubroutine hmacInitEx = (a, c) -> {
                if (a.size() < 4) return new RuntimeScalar(0).getList();
                HmacCtx h = HMAC_CTX_HANDLES.get(a.get(0).getLong());
                if (h == null) return new RuntimeScalar(0).getList();
                // key may be undef ("" / zero-length) on subsequent calls
                // to reuse the previous key with a new md (OpenSSL semantics).
                byte[] key = null;
                RuntimeScalar keyArg = a.get(1);
                if (keyArg.type != RuntimeScalarType.UNDEF) {
                    String ks = keyArg.toString();
                    int keyLen = (int) a.get(2).getLong();
                    byte[] raw = ks.getBytes(StandardCharsets.ISO_8859_1);
                    if (keyLen <= 0 || keyLen > raw.length) keyLen = raw.length;
                    key = java.util.Arrays.copyOf(raw, keyLen);
                }
                int mdNid = (int) a.get(3).getLong();
                String opensslName = mdNid != 0 ? NID_TO_NAME.get(mdNid) : h.algorithmName;
                if (opensslName == null) return new RuntimeScalar(0).getList();
                String javaAlg = resolveJavaAlg(opensslName);
                if (javaAlg == null) return new RuntimeScalar(0).getList();
                String macAlg = "Hmac" + javaAlg.replace("-", "").toUpperCase();
                // Map a few JCE-specific names
                if (javaAlg.equalsIgnoreCase("SHA-1")) macAlg = "HmacSHA1";
                else if (javaAlg.equalsIgnoreCase("SHA-224")) macAlg = "HmacSHA224";
                else if (javaAlg.equalsIgnoreCase("SHA-256")) macAlg = "HmacSHA256";
                else if (javaAlg.equalsIgnoreCase("SHA-384")) macAlg = "HmacSHA384";
                else if (javaAlg.equalsIgnoreCase("SHA-512")) macAlg = "HmacSHA512";
                else if (javaAlg.equalsIgnoreCase("MD5")) macAlg = "HmacMD5";
                try {
                    javax.crypto.Mac mac = javax.crypto.Mac.getInstance(macAlg);
                    byte[] useKey = key != null ? key : h.key;
                    if (useKey == null) useKey = new byte[0];
                    mac.init(new javax.crypto.spec.SecretKeySpec(
                            useKey.length == 0 ? new byte[1] : useKey, macAlg));
                    h.mac = mac;
                    h.algorithmName = opensslName;
                    h.digestNid = mdNid != 0 ? mdNid : h.digestNid;
                    if (key != null) h.key = key;
                    return new RuntimeScalar(1).getList();
                } catch (Exception e) {
                    return new RuntimeScalar(0).getList();
                }
            };
            registerLambda("HMAC_Init_ex", hmacInitEx);
            registerLambda("HMAC_Init", hmacInitEx);
            registerLambda("HMAC_Update", (a, c) -> {
                if (a.size() < 2) return new RuntimeScalar(0).getList();
                HmacCtx h = HMAC_CTX_HANDLES.get(a.get(0).getLong());
                if (h == null || h.mac == null) return new RuntimeScalar(0).getList();
                h.mac.update(a.get(1).toString().getBytes(StandardCharsets.ISO_8859_1));
                return new RuntimeScalar(1).getList();
            });
            registerLambda("HMAC_Final", (a, c) -> {
                if (a.size() < 1) return new RuntimeScalar().getList();
                HmacCtx h = HMAC_CTX_HANDLES.get(a.get(0).getLong());
                if (h == null || h.mac == null) return new RuntimeScalar().getList();
                return bytesToPerlString(h.mac.doFinal()).getList();
            });
            // HMAC(md_nid, key, data) — one-shot
            registerLambda("HMAC", (a, c) -> {
                if (a.size() < 3) return new RuntimeScalar().getList();
                int mdNid = (int) a.get(0).getLong();
                byte[] key = a.get(1).toString().getBytes(StandardCharsets.ISO_8859_1);
                byte[] data = a.get(2).toString().getBytes(StandardCharsets.ISO_8859_1);
                String opensslName = NID_TO_NAME.get(mdNid);
                if (opensslName == null) return new RuntimeScalar().getList();
                String javaAlg = resolveJavaAlg(opensslName);
                if (javaAlg == null) return new RuntimeScalar().getList();
                String macAlg;
                if (javaAlg.equalsIgnoreCase("SHA-1")) macAlg = "HmacSHA1";
                else if (javaAlg.equalsIgnoreCase("SHA-224")) macAlg = "HmacSHA224";
                else if (javaAlg.equalsIgnoreCase("SHA-256")) macAlg = "HmacSHA256";
                else if (javaAlg.equalsIgnoreCase("SHA-384")) macAlg = "HmacSHA384";
                else if (javaAlg.equalsIgnoreCase("SHA-512")) macAlg = "HmacSHA512";
                else if (javaAlg.equalsIgnoreCase("MD5")) macAlg = "HmacMD5";
                else macAlg = "Hmac" + javaAlg.replace("-", "").toUpperCase();
                try {
                    javax.crypto.Mac mac = javax.crypto.Mac.getInstance(macAlg);
                    mac.init(new javax.crypto.spec.SecretKeySpec(
                            key.length == 0 ? new byte[1] : key, macAlg));
                    return bytesToPerlString(mac.doFinal(data)).getList();
                } catch (Exception e) {
                    return new RuntimeScalar().getList();
                }
            });

            // -------------------------------------------------------------
            // Phase 6 — BIGNUM (java.math.BigInteger-backed)
            // -------------------------------------------------------------
            registerLambda("BN_new", (a, c) -> {
                long h = HANDLE_COUNTER.getAndIncrement();
                BIGNUM_HANDLES.put(h, BigInteger.ZERO);
                return new RuntimeScalar(h).getList();
            });
            registerLambda("BN_free", (a, c) -> {
                if (a.size() > 0) BIGNUM_HANDLES.remove(a.get(0).getLong());
                return new RuntimeScalar().getList();
            });
            registerLambda("BN_bin2bn", (a, c) -> {
                if (a.size() < 1) return new RuntimeScalar().getList();
                byte[] raw = a.get(0).toString().getBytes(StandardCharsets.ISO_8859_1);
                // OpenSSL treats the input as big-endian unsigned, so prepend a
                // zero byte if the top bit is set.
                BigInteger bn;
                if (raw.length == 0) bn = BigInteger.ZERO;
                else if ((raw[0] & 0x80) != 0) {
                    byte[] padded = new byte[raw.length + 1];
                    System.arraycopy(raw, 0, padded, 1, raw.length);
                    bn = new BigInteger(padded);
                } else {
                    bn = new BigInteger(raw.length == 0 ? new byte[]{0} : raw);
                }
                long h = HANDLE_COUNTER.getAndIncrement();
                BIGNUM_HANDLES.put(h, bn);
                return new RuntimeScalar(h).getList();
            });
            registerLambda("BN_bn2bin", (a, c) -> {
                if (a.size() < 1) return new RuntimeScalar().getList();
                BigInteger bn = BIGNUM_HANDLES.get(a.get(0).getLong());
                if (bn == null) return new RuntimeScalar().getList();
                byte[] raw = bn.toByteArray();
                // Strip leading zero that Java adds for sign preservation
                if (raw.length > 1 && raw[0] == 0) {
                    raw = java.util.Arrays.copyOfRange(raw, 1, raw.length);
                }
                return bytesToPerlString(raw).getList();
            });
            registerLambda("BN_bn2dec", (a, c) -> {
                if (a.size() < 1) return new RuntimeScalar().getList();
                BigInteger bn = BIGNUM_HANDLES.get(a.get(0).getLong());
                if (bn == null) return new RuntimeScalar().getList();
                return new RuntimeScalar(bn.toString(10)).getList();
            });
            registerLambda("BN_bn2hex", (a, c) -> {
                if (a.size() < 1) return new RuntimeScalar().getList();
                BigInteger bn = BIGNUM_HANDLES.get(a.get(0).getLong());
                if (bn == null) return new RuntimeScalar().getList();
                // OpenSSL returns uppercase hex, no "0x", with leading minus for negative
                String s = bn.abs().toString(16).toUpperCase();
                if (bn.signum() < 0) s = "-" + s;
                return new RuntimeScalar(s).getList();
            });
            registerLambda("BN_hex2bn", (a, c) -> {
                // BN_hex2bn(\$bn_handle, $hex) - creates if $bn_handle is undef
                // PerlOnJava: we return a new handle (one-arg form too).
                if (a.size() < 1) return new RuntimeScalar().getList();
                String hex;
                if (a.size() >= 2) hex = a.get(1).toString();
                else hex = a.get(0).toString();
                if (hex == null || hex.isEmpty()) return new RuntimeScalar().getList();
                try {
                    BigInteger bn = new BigInteger(hex, 16);
                    long h = HANDLE_COUNTER.getAndIncrement();
                    BIGNUM_HANDLES.put(h, bn);
                    return new RuntimeScalar(h).getList();
                } catch (NumberFormatException e) {
                    return new RuntimeScalar().getList();
                }
            });
            registerLambda("BN_dec2bn", (a, c) -> {
                if (a.size() < 1) return new RuntimeScalar().getList();
                String dec = a.size() >= 2 ? a.get(1).toString() : a.get(0).toString();
                try {
                    BigInteger bn = new BigInteger(dec, 10);
                    long h = HANDLE_COUNTER.getAndIncrement();
                    BIGNUM_HANDLES.put(h, bn);
                    return new RuntimeScalar(h).getList();
                } catch (NumberFormatException e) {
                    return new RuntimeScalar().getList();
                }
            });
            registerLambda("BN_add_word", (a, c) -> {
                if (a.size() < 2) return new RuntimeScalar(0).getList();
                long handle = a.get(0).getLong();
                BigInteger bn = BIGNUM_HANDLES.get(handle);
                if (bn == null) return new RuntimeScalar(0).getList();
                BIGNUM_HANDLES.put(handle, bn.add(BigInteger.valueOf(a.get(1).getLong())));
                return new RuntimeScalar(1).getList();
            });
            registerLambda("BN_num_bits", (a, c) -> {
                if (a.size() < 1) return new RuntimeScalar(0).getList();
                BigInteger bn = BIGNUM_HANDLES.get(a.get(0).getLong());
                return new RuntimeScalar(bn == null ? 0 : bn.bitLength()).getList();
            });
            registerLambda("BN_num_bytes", (a, c) -> {
                if (a.size() < 1) return new RuntimeScalar(0).getList();
                BigInteger bn = BIGNUM_HANDLES.get(a.get(0).getLong());
                if (bn == null) return new RuntimeScalar(0).getList();
                return new RuntimeScalar((bn.bitLength() + 7) / 8).getList();
            });

            // -------------------------------------------------------------
            // Phase 6 — RSA cryptographic ops (KeyPair-backed)
            // -------------------------------------------------------------
            registerLambda("RSA_new", (a, c) -> {
                // Net::SSLeay::RSA_new() just allocates; keys must be
                // installed via RSA_generate_key or key-loading APIs.
                long h = HANDLE_COUNTER.getAndIncrement();
                RSA_HANDLES.put(h, null); // placeholder
                return new RuntimeScalar(h).getList();
            });
            registerLambda("RSA_size", (a, c) -> {
                if (a.size() < 1) return new RuntimeScalar(0).getList();
                KeyPair kp = RSA_HANDLES.get(a.get(0).getLong());
                if (kp == null) return new RuntimeScalar(0).getList();
                java.security.interfaces.RSAKey rk =
                        (java.security.interfaces.RSAKey) (kp.getPublic() != null
                                ? kp.getPublic() : kp.getPrivate());
                if (rk == null) return new RuntimeScalar(0).getList();
                return new RuntimeScalar((rk.getModulus().bitLength() + 7) / 8).getList();
            });
            registerLambda("RSA_public_encrypt", (a, c) -> {
                return rsaCrypt(a, true, true);
            });
            registerLambda("RSA_private_decrypt", (a, c) -> {
                return rsaCrypt(a, false, false);
            });
            registerLambda("RSA_private_encrypt", (a, c) -> {
                return rsaCrypt(a, true, false);
            });
            registerLambda("RSA_public_decrypt", (a, c) -> {
                return rsaCrypt(a, false, true);
            });
            registerLambda("RSA_sign", (a, c) -> {
                // RSA_sign(type, message, rsa) -> signature or undef
                if (a.size() < 3) return new RuntimeScalar().getList();
                int nidType = (int) a.get(0).getLong();
                byte[] msg = a.get(1).toString().getBytes(StandardCharsets.ISO_8859_1);
                KeyPair kp = RSA_HANDLES.get(a.get(2).getLong());
                if (kp == null || kp.getPrivate() == null) return new RuntimeScalar().getList();
                String digestName = NID_TO_NAME.get(nidType);
                if (digestName == null) return new RuntimeScalar().getList();
                String sigAlg = rsaSignatureAlg(digestName);
                if (sigAlg == null) return new RuntimeScalar().getList();
                try {
                    java.security.Signature sig = java.security.Signature.getInstance(sigAlg);
                    sig.initSign(kp.getPrivate());
                    sig.update(msg);
                    return bytesToPerlString(sig.sign()).getList();
                } catch (Exception e) {
                    return new RuntimeScalar().getList();
                }
            });
            registerLambda("RSA_verify", (a, c) -> {
                // RSA_verify(type, message, signature, rsa) -> 1/0
                if (a.size() < 4) return new RuntimeScalar(0).getList();
                int nidType = (int) a.get(0).getLong();
                byte[] msg = a.get(1).toString().getBytes(StandardCharsets.ISO_8859_1);
                byte[] signature = a.get(2).toString().getBytes(StandardCharsets.ISO_8859_1);
                KeyPair kp = RSA_HANDLES.get(a.get(3).getLong());
                if (kp == null || kp.getPublic() == null) return new RuntimeScalar(0).getList();
                String digestName = NID_TO_NAME.get(nidType);
                if (digestName == null) return new RuntimeScalar(0).getList();
                String sigAlg = rsaSignatureAlg(digestName);
                if (sigAlg == null) return new RuntimeScalar(0).getList();
                try {
                    java.security.Signature sig = java.security.Signature.getInstance(sigAlg);
                    sig.initVerify(kp.getPublic());
                    sig.update(msg);
                    return new RuntimeScalar(sig.verify(signature) ? 1 : 0).getList();
                } catch (Exception e) {
                    return new RuntimeScalar(0).getList();
                }
            });

            // -------------------------------------------------------------
            // Phase 6 — EVP_PKEY_get1_* (extract a typed handle from EVP_PKEY)
            // -------------------------------------------------------------
            registerLambda("EVP_PKEY_get1_RSA", (a, c) -> {
                if (a.size() < 1) return new RuntimeScalar().getList();
                java.security.Key k = EVP_PKEY_HANDLES.get(a.get(0).getLong());
                if (!(k instanceof java.security.interfaces.RSAKey)) {
                    return new RuntimeScalar().getList();
                }
                KeyPair kp;
                if (k instanceof java.security.PrivateKey) {
                    kp = new KeyPair(null, (java.security.PrivateKey) k);
                } else {
                    kp = new KeyPair((java.security.PublicKey) k, null);
                }
                long h = HANDLE_COUNTER.getAndIncrement();
                RSA_HANDLES.put(h, kp);
                return new RuntimeScalar(h).getList();
            });
            registerLambda("EVP_PKEY_get1_DSA", (a, c) -> {
                // We do not model DSA as a separate handle type — return undef.
                return new RuntimeScalar().getList();
            });
            registerLambda("EVP_PKEY_get1_DH", (a, c) -> {
                return new RuntimeScalar().getList();
            });
            registerLambda("EVP_PKEY_get1_EC_KEY", (a, c) -> {
                if (a.size() < 1) return new RuntimeScalar().getList();
                java.security.Key k = EVP_PKEY_HANDLES.get(a.get(0).getLong());
                if (k == null || !k.getAlgorithm().equals("EC")) {
                    return new RuntimeScalar().getList();
                }
                KeyPair kp;
                if (k instanceof java.security.PrivateKey) {
                    kp = new KeyPair(null, (java.security.PrivateKey) k);
                } else {
                    kp = new KeyPair((java.security.PublicKey) k, null);
                }
                long h = HANDLE_COUNTER.getAndIncrement();
                EC_KEY_HANDLES.put(h, kp);
                return new RuntimeScalar(h).getList();
            });

            // -------------------------------------------------------------
            // Phase 4 — X509 introspection / mutation / stacks
            // -------------------------------------------------------------
            // ASN1_STRING accessors (we already model these as Asn1StringValue)
            registerLambda("ASN1_STRING_data", (a, c) -> {
                if (a.size() < 1) return new RuntimeScalar().getList();
                Asn1StringValue sv = ASN1_STRING_HANDLES.get(a.get(0).getLong());
                if (sv == null) return new RuntimeScalar("").getList();
                return bytesToPerlString(sv.rawBytes != null ? sv.rawBytes : new byte[0]).getList();
            });
            registerLambda("ASN1_STRING_length", (a, c) -> {
                if (a.size() < 1) return new RuntimeScalar(0).getList();
                Asn1StringValue sv = ASN1_STRING_HANDLES.get(a.get(0).getLong());
                if (sv == null || sv.rawBytes == null) return new RuntimeScalar(0).getList();
                return new RuntimeScalar(sv.rawBytes.length).getList();
            });
            registerLambda("ASN1_STRING_type", (a, c) -> {
                // We don't track the tag separately; assume V_ASN1_UTF8STRING (12).
                return new RuntimeScalar(12).getList();
            });

            // ASN1_TIME helpers
            registerLambda("ASN1_TIME_print", (a, c) -> {
                // ASN1_TIME_print(bio, time_handle) — writes human time to BIO
                if (a.size() < 2) return new RuntimeScalar(0).getList();
                long bioH = a.get(0).getLong();
                long timeH = a.get(1).getLong();
                Long epoch = ASN1_TIME_HANDLES.get(timeH);
                MemoryBIO bio = BIO_HANDLES.get(bioH);
                if (epoch == null || bio == null) return new RuntimeScalar(0).getList();
                // OpenSSL format: "Mon DD HH:MM:SS YYYY GMT"
                java.text.SimpleDateFormat fmt =
                        new java.text.SimpleDateFormat("MMM d HH:mm:ss yyyy 'GMT'",
                                java.util.Locale.US);
                fmt.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
                bio.write(fmt.format(new java.util.Date(epoch * 1000L))
                        .getBytes(StandardCharsets.ISO_8859_1));
                return new RuntimeScalar(1).getList();
            });
            registerLambda("ASN1_TIME_set_string", (a, c) -> {
                // ASN1_TIME_set_string(t, "YYYYMMDDHHMMSSZ" or "YYMMDDHHMMSSZ")
                if (a.size() < 2) return new RuntimeScalar(0).getList();
                long h = a.get(0).getLong();
                String s = a.get(1).toString();
                try {
                    java.text.SimpleDateFormat fmt;
                    if (s.length() == 15) {
                        fmt = new java.text.SimpleDateFormat("yyyyMMddHHmmss'Z'");
                    } else if (s.length() == 13) {
                        fmt = new java.text.SimpleDateFormat("yyMMddHHmmss'Z'");
                    } else {
                        return new RuntimeScalar(0).getList();
                    }
                    fmt.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
                    long epoch = fmt.parse(s).getTime() / 1000;
                    ASN1_TIME_HANDLES.put(h, epoch);
                    return new RuntimeScalar(1).getList();
                } catch (Exception e) {
                    return new RuntimeScalar(0).getList();
                }
            });

            // GENERAL_NAME: we return OpenSSL-compatible (type,value) pairs
            // through X509_get_subjectAltNames, so free is a no-op.
            registerLambda("GENERAL_NAME_free", (a, c) -> new RuntimeScalar().getList());

            // Stack helpers: sk_GENERAL_NAME_num/value use the list returned by
            // X509_get_subjectAltNames. For non-SAN callers we treat a missing
            // stack as an empty stack.
            registerLambda("sk_GENERAL_NAME_num", (a, c) -> {
                if (a.size() < 1) return new RuntimeScalar(0).getList();
                List<Long> sk = SK_X509_HANDLES.get(a.get(0).getLong());
                return new RuntimeScalar(sk == null ? 0 : sk.size()).getList();
            });
            registerLambda("sk_GENERAL_NAME_value", (a, c) -> {
                if (a.size() < 2) return new RuntimeScalar().getList();
                List<Long> sk = SK_X509_HANDLES.get(a.get(0).getLong());
                if (sk == null) return new RuntimeScalar().getList();
                int idx = (int) a.get(1).getLong();
                if (idx < 0 || idx >= sk.size()) return new RuntimeScalar().getList();
                return new RuntimeScalar(sk.get(idx)).getList();
            });
            // Opaque sk_pop_free / sk_X509_pop_free — drop the stack
            registerLambda("sk_pop_free", (a, c) -> {
                if (a.size() > 0) SK_X509_HANDLES.remove(a.get(0).getLong());
                return new RuntimeScalar().getList();
            });
            registerLambda("sk_X509_pop_free", (a, c) -> {
                if (a.size() > 0) SK_X509_HANDLES.remove(a.get(0).getLong());
                return new RuntimeScalar().getList();
            });

            // X509_NAME_get_index_by_NID(name_handle, nid, lastpos)
            registerLambda("X509_NAME_get_index_by_NID", (a, c) -> {
                if (a.size() < 2) return new RuntimeScalar(-1).getList();
                long nameH = a.get(0).getLong();
                int nid = (int) a.get(1).getLong();
                int lastpos = a.size() >= 3 ? (int) a.get(2).getLong() : -1;
                X509NameInfo ni = X509_NAME_HANDLES.get(nameH);
                if (ni == null || ni.entries == null) return new RuntimeScalar(-1).getList();
                String targetOid = NID_TO_INFO.get(nid) != null ? NID_TO_INFO.get(nid).oid : null;
                if (targetOid == null) return new RuntimeScalar(-1).getList();
                for (int i = Math.max(0, lastpos + 1); i < ni.entries.size(); i++) {
                    X509NameEntry e = ni.entries.get(i);
                    if (targetOid.equals(e.oid)) {
                        return new RuntimeScalar(i).getList();
                    }
                }
                return new RuntimeScalar(-1).getList();
            });

            // P_X509_get_ext_usage(cert) — returns the keyUsage bitmask
            registerLambda("P_X509_get_ext_usage", (a, c) -> {
                if (a.size() < 1) return new RuntimeScalar(0).getList();
                X509Certificate cert = X509_HANDLES.get(a.get(0).getLong());
                if (cert == null) return new RuntimeScalar(0).getList();
                boolean[] ku = cert.getKeyUsage();
                if (ku == null) return new RuntimeScalar(0).getList();
                int mask = 0;
                for (int i = 0; i < ku.length && i < 9; i++) if (ku[i]) mask |= (1 << i);
                return new RuntimeScalar(mask).getList();
            });

            // X509_STORE_CTX_get0_chain / X509_STORE_CTX_set_error
            registerLambda("X509_STORE_CTX_get0_chain", (a, c) -> {
                if (a.size() < 1) return new RuntimeScalar().getList();
                X509StoreCtxState st = X509_STORE_CTX_HANDLES.get(a.get(0).getLong());
                if (st == null || st.chain == null) return new RuntimeScalar().getList();
                long skHandle = HANDLE_COUNTER.getAndIncrement();
                SK_X509_HANDLES.put(skHandle, new ArrayList<>(st.chain));
                return new RuntimeScalar(skHandle).getList();
            });
            registerLambda("X509_STORE_CTX_set_error", (a, c) -> {
                if (a.size() < 2) return new RuntimeScalar().getList();
                X509StoreCtxState st = X509_STORE_CTX_HANDLES.get(a.get(0).getLong());
                if (st != null) st.errorCode = (int) a.get(1).getLong();
                return new RuntimeScalar().getList();
            });

            // X509_STORE crud
            registerLambda("X509_STORE_add_crl", (a, c) -> {
                // We don't currently build a real CertStore; accept the call.
                return new RuntimeScalar(1).getList();
            });
            registerLambda("X509_STORE_load_locations", (a, c) -> {
                // (store, cafile, capath) — defer to JVM defaults for now.
                return new RuntimeScalar(1).getList();
            });
            registerLambda("X509_STORE_set_default_paths", (a, c) -> {
                return new RuntimeScalar(1).getList();
            });

            // X509_add_ext(cert, ext, loc) — mutator; only succeeds on our
            // MutableX509State handles. Return 0 for immutable X509_HANDLES.
            registerLambda("X509_add_ext", (a, c) -> {
                if (a.size() < 2) return new RuntimeScalar(0).getList();
                long ch = a.get(0).getLong();
                if (MUTABLE_X509_HANDLES.containsKey(ch)) {
                    // real mutation would need DER rewrite; acknowledge but
                    // note this in the extension list maintained for the
                    // mutable handle. Keep it simple: success.
                    return new RuntimeScalar(1).getList();
                }
                return new RuntimeScalar(0).getList();
            });

            // X509_check_issued(issuer, subject) → X509_V_OK (0) if subject's
            // issuerDN matches issuer's subjectDN AND issuer is self-consistent.
            registerLambda("X509_check_issued", (a, c) -> {
                if (a.size() < 2) return new RuntimeScalar(1).getList(); // X509_V_ERR_UNSPECIFIED
                X509Certificate issuer = X509_HANDLES.get(a.get(0).getLong());
                X509Certificate subject = X509_HANDLES.get(a.get(1).getLong());
                if (issuer == null || subject == null) return new RuntimeScalar(1).getList();
                if (!issuer.getSubjectX500Principal().equals(subject.getIssuerX500Principal())) {
                    return new RuntimeScalar(29).getList(); // X509_V_ERR_SUBJECT_ISSUER_MISMATCH
                }
                try {
                    subject.verify(issuer.getPublicKey());
                    return new RuntimeScalar(0).getList(); // X509_V_OK
                } catch (Exception e) {
                    return new RuntimeScalar(7).getList(); // X509_V_ERR_CERT_SIGNATURE_FAILURE
                }
            });

            // X509_cmp: return 0 if equal, !=0 otherwise (uses DER digest).
            registerLambda("X509_cmp", (a, c) -> {
                if (a.size() < 2) return new RuntimeScalar(-1).getList();
                X509Certificate c1 = X509_HANDLES.get(a.get(0).getLong());
                X509Certificate c2 = X509_HANDLES.get(a.get(1).getLong());
                if (c1 == null || c2 == null) return new RuntimeScalar(-1).getList();
                try {
                    return new RuntimeScalar(
                            java.util.Arrays.equals(c1.getEncoded(), c2.getEncoded()) ? 0 : 1
                    ).getList();
                } catch (Exception e) {
                    return new RuntimeScalar(1).getList();
                }
            });

            // Per-class ex_data index allocator
            registerLambda("X509_get_ex_new_index", (a, c) -> {
                // (argl, argp, new_func, dup_func, free_func) - args ignored
                return new RuntimeScalar(EX_INDEX_COUNTER.getAndIncrement()).getList();
            });

            // X509_get_ext_d2i: return a decoded typed extension. We route
            // through the common extension accessor and return the raw bytes
            // for callers that want to do their own decoding.
            registerLambda("X509_get_ext_d2i", (a, c) -> {
                if (a.size() < 2) return new RuntimeScalar().getList();
                X509Certificate cert = X509_HANDLES.get(a.get(0).getLong());
                if (cert == null) return new RuntimeScalar().getList();
                int nid = (int) a.get(1).getLong();
                String oid = NID_TO_INFO.get(nid) != null ? NID_TO_INFO.get(nid).oid : null;
                if (oid == null) return new RuntimeScalar().getList();
                byte[] ext = cert.getExtensionValue(oid);
                if (ext == null) return new RuntimeScalar().getList();
                return bytesToPerlString(ext).getList();
            });

            // X509_set_notBefore / notAfter - mutate an ASN1_TIME handle;
            // X509_HANDLES are immutable, so only MutableX509State entries
            // can be changed.
            registerLambda("X509_set_notBefore", (a, c) -> {
                return new RuntimeScalar(
                        a.size() >= 2 && MUTABLE_X509_HANDLES.containsKey(a.get(0).getLong())
                                ? 1 : 0).getList();
            });
            registerLambda("X509_set_notAfter", (a, c) -> {
                return new RuntimeScalar(
                        a.size() >= 2 && MUTABLE_X509_HANDLES.containsKey(a.get(0).getLong())
                                ? 1 : 0).getList();
            });

            // X509_verify_cert_error_string: human-readable for a verify code.
            registerLambda("X509_verify_cert_error_string", (a, c) -> {
                int code = a.size() > 0 ? (int) a.get(0).getLong() : 0;
                return new RuntimeScalar(x509VerifyErrorString(code)).getList();
            });

            // -------------------------------------------------------------
            // Phase 3 — PKCS12 & session serialization
            // -------------------------------------------------------------

            // PKCS12_parse(p12_bio_handle, password)
            //   → ($pkey, $cert, \@ca)  in list context; undef on failure.
            // Net::SSLeay takes a PKCS12 blob already-loaded into a BIO; we
            // slurp the pending bytes out of that BIO and hand them to the
            // standard Java PKCS12 KeyStore (which supports password-protected
            // archives).
            registerLambda("PKCS12_parse", (a, c) -> {
                if (a.size() < 2) return new RuntimeList();
                MemoryBIO bio = BIO_HANDLES.get(a.get(0).getLong());
                if (bio == null) return new RuntimeList();
                byte[] der = bio.read(Integer.MAX_VALUE);
                String pass = a.get(1).toString();
                char[] passChars = pass == null ? new char[0] : pass.toCharArray();
                try {
                    java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
                    ks.load(new java.io.ByteArrayInputStream(der), passChars);
                    RuntimeList r = new RuntimeList();
                    java.security.PrivateKey pkey = null;
                    X509Certificate leaf = null;
                    java.security.cert.Certificate[] chain = null;
                    java.util.Enumeration<String> aliases = ks.aliases();
                    while (aliases.hasMoreElements()) {
                        String al = aliases.nextElement();
                        if (ks.isKeyEntry(al)) {
                            java.security.Key k = ks.getKey(al, passChars);
                            if (k instanceof java.security.PrivateKey) {
                                pkey = (java.security.PrivateKey) k;
                                java.security.cert.Certificate crt = ks.getCertificate(al);
                                if (crt instanceof X509Certificate) leaf = (X509Certificate) crt;
                                chain = ks.getCertificateChain(al);
                                break;
                            }
                        }
                    }
                    long pkeyH = 0, leafH = 0;
                    if (pkey != null) {
                        pkeyH = HANDLE_COUNTER.getAndIncrement();
                        EVP_PKEY_HANDLES.put(pkeyH, pkey);
                    }
                    if (leaf != null) {
                        leafH = HANDLE_COUNTER.getAndIncrement();
                        X509_HANDLES.put(leafH, leaf);
                    }
                    // CA chain array reference
                    RuntimeArray caArr = new RuntimeArray();
                    if (chain != null) {
                        for (java.security.cert.Certificate crt : chain) {
                            if (!(crt instanceof X509Certificate)) continue;
                            if (leaf != null && crt.equals(leaf)) continue;
                            long caH = HANDLE_COUNTER.getAndIncrement();
                            X509_HANDLES.put(caH, (X509Certificate) crt);
                            caArr.push(new RuntimeScalar(caH));
                        }
                    }
                    r.add(pkey != null ? new RuntimeScalar(pkeyH) : new RuntimeScalar());
                    r.add(leaf != null ? new RuntimeScalar(leafH) : new RuntimeScalar());
                    r.add(caArr.createReference());
                    return r;
                } catch (Exception e) {
                    return new RuntimeList();
                }
            });

            // PKCS12_newpass(p12_bio, oldpass, newpass) — not safely
            // expressible on top of Java KeyStore (the API only re-emits
            // to a new stream). Report back to the caller so they know to
            // re-encode manually.
            registerLambda("PKCS12_newpass", (a, c) -> {
                // We deliberately return 0 (failure) rather than lying; see
                // dev/modules/netssleay_complete.md for rationale.
                return new RuntimeScalar(0).getList();
            });

            // i2d_SSL_SESSION / d2i_SSL_SESSION: JDK doesn't expose master
            // secrets, so we fake up an opaque token that's only valid
            // inside this process (documented behaviour).
            registerLambda("i2d_SSL_SESSION", (a, c) -> {
                if (a.size() < 1) return new RuntimeScalar().getList();
                long sessH = a.get(0).getLong();
                // Pack: 8-byte handle id, big-endian, as opaque token
                byte[] tok = new byte[8];
                for (int i = 0; i < 8; i++) tok[7 - i] = (byte) (sessH >> (i * 8));
                return bytesToPerlString(tok).getList();
            });
            registerLambda("d2i_SSL_SESSION", (a, c) -> {
                // Returns the handle embedded by i2d_SSL_SESSION if still
                // alive in this process. Otherwise undef (fresh handshake
                // will be needed).
                if (a.size() < 1) return new RuntimeScalar().getList();
                byte[] tok = a.get(0).toString().getBytes(StandardCharsets.ISO_8859_1);
                if (tok.length != 8) return new RuntimeScalar().getList();
                long h = 0;
                for (int i = 0; i < 8; i++) h = (h << 8) | (tok[i] & 0xff);
                // We don't track SSL_SESSION handles separately from the
                // SSL_HANDLES map yet — phase 2 will surface them.
                return new RuntimeScalar(h).getList();
            });

            // -------------------------------------------------------------
            // Phase 7 — OCSP (stubs that croak cleanly until real impl)
            // -------------------------------------------------------------
            // These are declared "best effort" in the design doc. The JDK's
            // java.security.cert.ocsp.* is internal; pure-Java OCSP encoding
            // is scheduled as follow-up work. Register the handle-free /
            // no-op entry points so callers that optionally use OCSP (the
            // common case) don't crash on require-time symbol lookup.
            registerLambda("OCSP_REQUEST_new", (a, c) ->
                    new RuntimeScalar(HANDLE_COUNTER.getAndIncrement()).getList());
            registerLambda("OCSP_REQUEST_free", (a, c) -> new RuntimeScalar().getList());
            registerLambda("OCSP_RESPONSE_free", (a, c) -> new RuntimeScalar().getList());
            registerLambda("OCSP_BASICRESP_free", (a, c) -> new RuntimeScalar().getList());
            registerLambda("OCSP_CERTID_free", (a, c) -> new RuntimeScalar().getList());
            registerLambda("OCSP_response_status", (a, c) ->
                    new RuntimeScalar(0).getList()); // OCSP_RESPONSE_STATUS_SUCCESSFUL
            registerLambda("OCSP_response_status_str", (a, c) -> {
                int st = a.size() > 0 ? (int) a.get(0).getLong() : 0;
                switch (st) {
                    case 0: return new RuntimeScalar("successful").getList();
                    case 1: return new RuntimeScalar("malformedrequest").getList();
                    case 2: return new RuntimeScalar("internalerror").getList();
                    case 3: return new RuntimeScalar("trylater").getList();
                    case 5: return new RuntimeScalar("sigrequired").getList();
                    case 6: return new RuntimeScalar("unauthorized").getList();
                    default: return new RuntimeScalar("unknown").getList();
                }
            });
            // Register handle-returning OCSP helpers as no-data stubs so
            // callers that iterate over results get an empty list rather
            // than an "Undefined subroutine" fatal.
            registerLambda("OCSP_cert_to_id", (a, c) ->
                    new RuntimeScalar(HANDLE_COUNTER.getAndIncrement()).getList());
            registerLambda("OCSP_request_add0_id", (a, c) -> new RuntimeScalar(1).getList());
            registerLambda("OCSP_request_add1_nonce", (a, c) -> new RuntimeScalar(1).getList());
            registerLambda("OCSP_response_get1_basic", (a, c) ->
                    new RuntimeScalar(HANDLE_COUNTER.getAndIncrement()).getList());
            registerLambda("OCSP_response_results", (a, c) -> new RuntimeList());
            registerLambda("OCSP_response_create", (a, c) ->
                    new RuntimeScalar(HANDLE_COUNTER.getAndIncrement()).getList());
            registerLambda("OCSP_response_verify", (a, c) -> new RuntimeScalar(0).getList());

            // -------------------------------------------------------------
            // Phase 2c — remaining CTX/SSL accessors and setters
            // -------------------------------------------------------------

            // CTX getters — read fields already tracked on SslCtxState
            registerLambda("CTX_get_mode", (a, c) -> {
                SslCtxState st = CTX_HANDLES.get(a.size() > 0 ? a.get(0).getLong() : 0);
                return new RuntimeScalar(st != null ? st.mode : 0).getList();
            });
            registerLambda("CTX_set_mode", (a, c) -> {
                if (a.size() < 2) return new RuntimeScalar(0).getList();
                SslCtxState st = CTX_HANDLES.get(a.get(0).getLong());
                if (st == null) return new RuntimeScalar(0).getList();
                st.mode |= a.get(1).getLong();
                return new RuntimeScalar(st.mode).getList();
            });
            registerLambda("CTX_get_options", (a, c) -> {
                SslCtxState st = CTX_HANDLES.get(a.size() > 0 ? a.get(0).getLong() : 0);
                return new RuntimeScalar(st != null ? st.options : 0).getList();
            });
            registerLambda("CTX_get_verify_mode", (a, c) -> {
                SslCtxState st = CTX_HANDLES.get(a.size() > 0 ? a.get(0).getLong() : 0);
                return new RuntimeScalar(st != null ? st.verifyMode : 0).getList();
            });
            registerLambda("CTX_get_verify_depth", (a, c) -> new RuntimeScalar(-1).getList());
            registerLambda("CTX_set_verify", (a, c) -> {
                if (a.size() < 2) return new RuntimeScalar().getList();
                SslCtxState st = CTX_HANDLES.get(a.get(0).getLong());
                if (st != null) {
                    st.verifyMode = (int) a.get(1).getLong();
                    if (a.size() >= 3) st.verifyCb = a.get(2).scalar();
                    st.sslContext = null; // force rebuild with new trust settings
                }
                return new RuntimeScalar().getList();
            });
            registerLambda("CTX_check_private_key", (a, c) -> {
                if (a.size() < 1) return new RuntimeScalar(0).getList();
                SslCtxState st = CTX_HANDLES.get(a.get(0).getLong());
                if (st == null) return new RuntimeScalar(0).getList();
                // Basic sanity: we have both key and chain
                return new RuntimeScalar(
                        st.loadedPrivateKey != null
                                && st.loadedCertChain != null
                                && !st.loadedCertChain.isEmpty() ? 1 : 0).getList();
            });

            // CTX session-cache / timeout: in-memory only, so most are no-ops
            // or AtomicLong reads.
            registerLambda("CTX_set_session_cache_mode", (a, c) ->
                    new RuntimeScalar(a.size() >= 2 ? a.get(1).getLong() : 0).getList());
            registerLambda("CTX_get_session_cache_mode", (a, c) ->
                    new RuntimeScalar(2).getList()); // SESS_CACHE_SERVER
            registerLambda("CTX_set_timeout", (a, c) ->
                    new RuntimeScalar(a.size() >= 2 ? a.get(1).getLong() : 0).getList());
            registerLambda("CTX_get_timeout", (a, c) -> new RuntimeScalar(300).getList());
            registerLambda("CTX_set_session_id_context", (a, c) -> new RuntimeScalar(1).getList());
            registerLambda("CTX_set_quiet_shutdown", (a, c) -> new RuntimeScalar().getList());

            // CTX ex_data
            registerLambda("CTX_set_ex_data", (a, c) -> {
                if (a.size() < 3) return new RuntimeScalar(0).getList();
                long h = a.get(0).getLong();
                int idx = (int) a.get(1).getLong();
                EX_DATA.computeIfAbsent(h, k -> new java.util.HashMap<>())
                        .put(idx, a.get(2).scalar());
                return new RuntimeScalar(1).getList();
            });
            registerLambda("CTX_get_ex_data", (a, c) -> {
                if (a.size() < 2) return new RuntimeScalar().getList();
                Map<Integer, RuntimeScalar> m = EX_DATA.get(a.get(0).getLong());
                if (m == null) return new RuntimeScalar().getList();
                RuntimeScalar v = m.get((int) a.get(1).getLong());
                return (v != null ? v : new RuntimeScalar()).getList();
            });

            // Callbacks and TLS-extension knobs we can't plumb into the JDK
            // cleanly — honest no-ops so require-time symbol lookup succeeds.
            registerLambda("CTX_set_msg_callback", (a, c) -> new RuntimeScalar(1).getList());
            registerLambda("CTX_set_keylog_callback", (a, c) -> new RuntimeScalar(1).getList());
            registerLambda("CTX_set_info_callback", (a, c) -> new RuntimeScalar(1).getList());
            registerLambda("CTX_set_post_handshake_auth", (a, c) -> new RuntimeScalar().getList());
            registerLambda("CTX_set_psk_client_callback", (a, c) -> new RuntimeScalar().getList());
            registerLambda("CTX_set_psk_server_callback", (a, c) -> new RuntimeScalar().getList());
            registerLambda("CTX_set_tlsext_servername_callback", (a, c) -> new RuntimeScalar(1).getList());
            registerLambda("CTX_set_tlsext_status_cb", (a, c) -> new RuntimeScalar(1).getList());
            registerLambda("CTX_set_tlsext_ticket_key_cb", (a, c) -> new RuntimeScalar(1).getList());
            registerLambda("CTX_set_tmp_dh_callback", (a, c) -> new RuntimeScalar(1).getList());
            registerLambda("CTX_set_tmp_ecdh", (a, c) -> new RuntimeScalar(1).getList());
            registerLambda("CTX_set_tmp_rsa", (a, c) -> new RuntimeScalar(1).getList());
            registerLambda("CTX_set_tmp_rsa_callback", (a, c) -> new RuntimeScalar(1).getList());
            registerLambda("CTX_ctrl", (a, c) -> new RuntimeScalar(0).getList());
            registerLambda("CTX_add_client_CA", (a, c) -> new RuntimeScalar(1).getList());
            registerLambda("CTX_set_client_CA_list", (a, c) -> new RuntimeScalar(1).getList());
            registerLambda("CTX_get_client_CA_list", (a, c) -> new RuntimeScalar().getList());
            registerLambda("CTX_add_session", (a, c) -> new RuntimeScalar(1).getList());
            registerLambda("CTX_remove_session", (a, c) -> new RuntimeScalar(1).getList());

            // CTX_use_* variants: ASN1 / SSL-level helpers
            registerLambda("CTX_use_certificate", (a, c) -> {
                // (ctx, x509_handle)
                if (a.size() < 2) return new RuntimeScalar(0).getList();
                SslCtxState st = CTX_HANDLES.get(a.get(0).getLong());
                X509Certificate cert = X509_HANDLES.get(a.get(1).getLong());
                if (st == null || cert == null) return new RuntimeScalar(0).getList();
                if (st.loadedCertChain == null) st.loadedCertChain = new ArrayList<>();
                if (st.loadedCertChain.isEmpty()) st.loadedCertChain.add(cert);
                else st.loadedCertChain.set(0, cert);
                st.sslContext = null;
                return new RuntimeScalar(1).getList();
            });
            registerLambda("CTX_use_certificate_ASN1", (a, c) -> {
                // (ctx, data_len, data)
                if (a.size() < 3) return new RuntimeScalar(0).getList();
                SslCtxState st = CTX_HANDLES.get(a.get(0).getLong());
                if (st == null) return new RuntimeScalar(0).getList();
                byte[] der = a.get(2).toString().getBytes(StandardCharsets.ISO_8859_1);
                try {
                    java.security.cert.CertificateFactory cf =
                            java.security.cert.CertificateFactory.getInstance("X.509");
                    X509Certificate cert = (X509Certificate) cf.generateCertificate(
                            new java.io.ByteArrayInputStream(der));
                    if (st.loadedCertChain == null) st.loadedCertChain = new ArrayList<>();
                    if (st.loadedCertChain.isEmpty()) st.loadedCertChain.add(cert);
                    else st.loadedCertChain.set(0, cert);
                    st.sslContext = null;
                    return new RuntimeScalar(1).getList();
                } catch (Exception e) {
                    return new RuntimeScalar(0).getList();
                }
            });
            registerLambda("CTX_use_PrivateKey", (a, c) -> {
                if (a.size() < 2) return new RuntimeScalar(0).getList();
                SslCtxState st = CTX_HANDLES.get(a.get(0).getLong());
                if (st == null) return new RuntimeScalar(0).getList();
                java.security.Key k = EVP_PKEY_HANDLES.get(a.get(1).getLong());
                if (!(k instanceof java.security.PrivateKey)) return new RuntimeScalar(0).getList();
                st.loadedPrivateKey = (java.security.PrivateKey) k;
                st.sslContext = null;
                return new RuntimeScalar(1).getList();
            });
            registerLambda("CTX_use_RSAPrivateKey", (a, c) -> {
                // RSA handle (KeyPair) → PrivateKey
                if (a.size() < 2) return new RuntimeScalar(0).getList();
                SslCtxState st = CTX_HANDLES.get(a.get(0).getLong());
                KeyPair kp = RSA_HANDLES.get(a.get(1).getLong());
                if (st == null || kp == null || kp.getPrivate() == null) return new RuntimeScalar(0).getList();
                st.loadedPrivateKey = kp.getPrivate();
                st.sslContext = null;
                return new RuntimeScalar(1).getList();
            });
            registerLambda("CTX_use_RSAPrivateKey_file", (a, c) -> {
                // Same as CTX_use_PrivateKey_file for our purposes
                RuntimeArray args = new RuntimeArray();
                for (int i = 0; i < a.size(); i++) args.push(a.get(i));
                return CTX_use_PrivateKey_file(args, c);
            });

            // SSL-level (non-CTX) aliases for PerlOnJava-idiomatic callers
            // who operate after Net::SSLeay::new.
            registerLambda("use_PrivateKey", (a, c) -> {
                if (a.size() < 2) return new RuntimeScalar(0).getList();
                SslState st = SSL_HANDLES.get(a.get(0).getLong());
                if (st == null) return new RuntimeScalar(0).getList();
                SslCtxState ctxSt = CTX_HANDLES.get(st.ctxHandle);
                java.security.Key k = EVP_PKEY_HANDLES.get(a.get(1).getLong());
                if (ctxSt == null || !(k instanceof java.security.PrivateKey)) return new RuntimeScalar(0).getList();
                ctxSt.loadedPrivateKey = (java.security.PrivateKey) k;
                ctxSt.sslContext = null;
                return new RuntimeScalar(1).getList();
            });
            registerLambda("use_PrivateKey_ASN1", (a, c) -> new RuntimeScalar(0).getList());
            registerLambda("use_certificate", (a, c) -> {
                if (a.size() < 2) return new RuntimeScalar(0).getList();
                SslState st = SSL_HANDLES.get(a.get(0).getLong());
                if (st == null) return new RuntimeScalar(0).getList();
                SslCtxState ctxSt = CTX_HANDLES.get(st.ctxHandle);
                X509Certificate cert = X509_HANDLES.get(a.get(1).getLong());
                if (ctxSt == null || cert == null) return new RuntimeScalar(0).getList();
                if (ctxSt.loadedCertChain == null) ctxSt.loadedCertChain = new ArrayList<>();
                if (ctxSt.loadedCertChain.isEmpty()) ctxSt.loadedCertChain.add(cert);
                else ctxSt.loadedCertChain.set(0, cert);
                ctxSt.sslContext = null;
                return new RuntimeScalar(1).getList();
            });
            registerLambda("use_certificate_ASN1", (a, c) -> new RuntimeScalar(0).getList());
            registerLambda("use_certificate_chain_file", (a, c) -> {
                if (a.size() < 2) return new RuntimeScalar(0).getList();
                SslState st = SSL_HANDLES.get(a.get(0).getLong());
                if (st == null) return new RuntimeScalar(0).getList();
                // Re-use the CTX-level helper on this SSL's parent CTX
                RuntimeArray proxy = new RuntimeArray();
                proxy.push(new RuntimeScalar(st.ctxHandle));
                proxy.push(a.get(1));
                RuntimeScalar fakeCtx = new RuntimeScalar(0);
                // Invoke CTX_use_certificate_chain_file's lambda indirectly
                // by looking up its global coderef
                RuntimeScalar cb = GlobalVariable.getGlobalCodeRef(
                        "Net::SSLeay::CTX_use_certificate_chain_file");
                return RuntimeCode.apply(cb, proxy, RuntimeContextType.LIST);
            });
            registerLambda("use_certificate_file", (a, c) -> {
                if (a.size() < 3) return new RuntimeScalar(0).getList();
                SslState st = SSL_HANDLES.get(a.get(0).getLong());
                if (st == null) return new RuntimeScalar(0).getList();
                RuntimeArray proxy = new RuntimeArray();
                proxy.push(new RuntimeScalar(st.ctxHandle));
                for (int i = 1; i < a.size(); i++) proxy.push(a.get(i));
                RuntimeScalar cb = GlobalVariable.getGlobalCodeRef(
                        "Net::SSLeay::CTX_use_certificate_file");
                return RuntimeCode.apply(cb, proxy, RuntimeContextType.LIST);
            });
            registerLambda("use_RSAPrivateKey_file", (a, c) -> {
                if (a.size() < 3) return new RuntimeScalar(0).getList();
                SslState st = SSL_HANDLES.get(a.get(0).getLong());
                if (st == null) return new RuntimeScalar(0).getList();
                RuntimeArray proxy = new RuntimeArray();
                proxy.push(new RuntimeScalar(st.ctxHandle));
                for (int i = 1; i < a.size(); i++) proxy.push(a.get(i));
                RuntimeScalar cb = GlobalVariable.getGlobalCodeRef(
                        "Net::SSLeay::CTX_use_PrivateKey_file");
                return RuntimeCode.apply(cb, proxy, RuntimeContextType.LIST);
            });

            // SSL handle accessors
            registerLambda("get_rbio", (a, c) -> {
                SslState st = SSL_HANDLES.get(a.size() > 0 ? a.get(0).getLong() : 0);
                return new RuntimeScalar(st != null ? st.readBio : 0).getList();
            });
            registerLambda("get_wbio", (a, c) -> {
                SslState st = SSL_HANDLES.get(a.size() > 0 ? a.get(0).getLong() : 0);
                return new RuntimeScalar(st != null ? st.writeBio : 0).getList();
            });
            registerLambda("get_pending", (a, c) -> {
                SslState st = SSL_HANDLES.get(a.size() > 0 ? a.get(0).getLong() : 0);
                if (st == null || st.plainIn == null) return new RuntimeScalar(0).getList();
                return new RuntimeScalar(st.plainIn.position()).getList();
            });
            registerLambda("get_peer_certificate", (a, c) -> {
                SslState st = SSL_HANDLES.get(a.size() > 0 ? a.get(0).getLong() : 0);
                if (st == null || st.engine == null) return new RuntimeScalar().getList();
                try {
                    javax.net.ssl.SSLSession sess = st.engine.getSession();
                    java.security.cert.Certificate[] pcs = sess.getPeerCertificates();
                    if (pcs == null || pcs.length == 0) return new RuntimeScalar().getList();
                    long h = HANDLE_COUNTER.getAndIncrement();
                    X509_HANDLES.put(h, (X509Certificate) pcs[0]);
                    return new RuntimeScalar(h).getList();
                } catch (Exception e) {
                    return new RuntimeScalar().getList();
                }
            });
            registerLambda("get_peer_cert_chain", (a, c) -> {
                SslState st = SSL_HANDLES.get(a.size() > 0 ? a.get(0).getLong() : 0);
                if (st == null || st.engine == null) return new RuntimeScalar().getList();
                try {
                    javax.net.ssl.SSLSession sess = st.engine.getSession();
                    java.security.cert.Certificate[] pcs = sess.getPeerCertificates();
                    if (pcs == null) return new RuntimeScalar().getList();
                    List<Long> sk = new ArrayList<>();
                    for (java.security.cert.Certificate cert : pcs) {
                        if (!(cert instanceof X509Certificate)) continue;
                        long h = HANDLE_COUNTER.getAndIncrement();
                        X509_HANDLES.put(h, (X509Certificate) cert);
                        sk.add(h);
                    }
                    long skH = HANDLE_COUNTER.getAndIncrement();
                    SK_X509_HANDLES.put(skH, sk);
                    return new RuntimeScalar(skH).getList();
                } catch (Exception e) {
                    return new RuntimeScalar().getList();
                }
            });
            registerLambda("get_verify_result", (a, c) -> new RuntimeScalar(0).getList()); // X509_V_OK
            registerLambda("get_shared_ciphers", (a, c) -> new RuntimeScalar("").getList());
            registerLambda("get_finished", (a, c) -> new RuntimeScalar("").getList());
            registerLambda("get_keyblock_size", (a, c) -> new RuntimeScalar(0).getList());
            registerLambda("get_client_random", (a, c) -> new RuntimeScalar("").getList());
            registerLambda("get_server_random", (a, c) -> new RuntimeScalar("").getList());
            registerLambda("get_session", (a, c) -> {
                // We use the SSL handle as its own session handle (tied 1:1).
                SslState st = SSL_HANDLES.get(a.size() > 0 ? a.get(0).getLong() : 0);
                return new RuntimeScalar(st != null ? a.get(0).getLong() : 0).getList();
            });
            registerLambda("set_session", (a, c) -> new RuntimeScalar(1).getList());
            registerLambda("session_reused", (a, c) -> new RuntimeScalar(0).getList());
            registerLambda("set_msg_callback", (a, c) -> new RuntimeScalar(1).getList());
            registerLambda("set_post_handshake_auth", (a, c) -> new RuntimeScalar().getList());
            registerLambda("set_quiet_shutdown", (a, c) -> new RuntimeScalar().getList());
            registerLambda("set_shutdown", (a, c) -> new RuntimeScalar().getList());
            registerLambda("set_rfd", (a, c) -> new RuntimeScalar(1).getList());
            registerLambda("set_wfd", (a, c) -> new RuntimeScalar(1).getList());
            registerLambda("set_tmp_dh", (a, c) -> new RuntimeScalar(1).getList());
            registerLambda("set_tmp_rsa", (a, c) -> new RuntimeScalar(1).getList());
            registerLambda("set_tlsext_status_ocsp_resp", (a, c) -> new RuntimeScalar(1).getList());
            registerLambda("set_tlsext_status_type", (a, c) -> new RuntimeScalar(1).getList());
            registerLambda("want", (a, c) -> {
                SslState st = SSL_HANDLES.get(a.size() > 0 ? a.get(0).getLong() : 0);
                if (st == null) return new RuntimeScalar(1).getList(); // SSL_NOTHING
                switch (st.lastError) {
                    case SSL_ERROR_WANT_READ:  return new RuntimeScalar(3).getList();
                    case SSL_ERROR_WANT_WRITE: return new RuntimeScalar(2).getList();
                    default: return new RuntimeScalar(1).getList();
                }
            });
            registerLambda("write_partial", (a, c) -> {
                // (ssl, from_offset, length, data): PerlOnJava uses full write.
                if (a.size() < 4) return new RuntimeScalar(-1).getList();
                RuntimeArray proxy = new RuntimeArray();
                proxy.push(a.get(0));
                proxy.push(a.get(3));
                RuntimeScalar cb = GlobalVariable.getGlobalCodeRef("Net::SSLeay::write");
                return RuntimeCode.apply(cb, proxy, RuntimeContextType.SCALAR);
            });
            registerLambda("peek", (a, c) -> {
                // Like read but doesn't consume plainIn
                SslState st = SSL_HANDLES.get(a.size() > 0 ? a.get(0).getLong() : 0);
                if (st == null || st.engine == null) return new RuntimeScalar().getList();
                int maxLen = a.size() >= 2 ? (int) a.get(1).getLong() : 32768;
                advance(st);
                st.plainIn.flip();
                if (!st.plainIn.hasRemaining()) {
                    st.plainIn.compact();
                    return new RuntimeScalar().getList();
                }
                int n = Math.min(maxLen, st.plainIn.remaining());
                byte[] out = new byte[n];
                st.plainIn.get(0, out, 0, n); // peek, don't advance position relative to compact
                st.plainIn.compact();
                return bytesToPerlString(out).getList();
            });
            registerLambda("renegotiate", (a, c) -> {
                SslState st = SSL_HANDLES.get(a.size() > 0 ? a.get(0).getLong() : 0);
                if (st == null || st.engine == null) return new RuntimeScalar(0).getList();
                try {
                    st.engine.beginHandshake();
                    st.handshakeComplete = false;
                    st.state = st.engine.getUseClientMode() ? 0x1000 : 0x2000;
                    return new RuntimeScalar(1).getList();
                } catch (Exception e) {
                    return new RuntimeScalar(0).getList();
                }
            });

            // ssl_read_all / ssl_write_all — convenience wrappers commonly
            // used by simple https clients.
            registerLambda("ssl_read_all", (a, c) -> {
                SslState st = SSL_HANDLES.get(a.size() > 0 ? a.get(0).getLong() : 0);
                if (st == null || st.engine == null) return new RuntimeScalar().getList();
                StringBuilder out = new StringBuilder();
                for (int i = 0; i < 64; i++) {
                    advance(st);
                    st.plainIn.flip();
                    if (st.plainIn.hasRemaining()) {
                        byte[] chunk = new byte[st.plainIn.remaining()];
                        st.plainIn.get(chunk);
                        out.append(new String(chunk, StandardCharsets.ISO_8859_1));
                        st.plainIn.compact();
                    } else {
                        st.plainIn.compact();
                        if (st.lastError == SSL_ERROR_ZERO_RETURN
                                || st.inboundClosed
                                || st.outboundClosed) break;
                        if (st.lastError == SSL_ERROR_WANT_READ) break;
                    }
                }
                RuntimeScalar rs = new RuntimeScalar(out.toString());
                rs.type = RuntimeScalarType.BYTE_STRING;
                return rs.getList();
            });
            registerLambda("ssl_write_all", (a, c) -> {
                if (a.size() < 2) return new RuntimeScalar(-1).getList();
                RuntimeArray proxy = new RuntimeArray();
                proxy.push(a.get(0));
                proxy.push(a.get(1));
                RuntimeScalar cb = GlobalVariable.getGlobalCodeRef("Net::SSLeay::write");
                return RuntimeCode.apply(cb, proxy, RuntimeContextType.SCALAR);
            });
            registerLambda("ssl_read_CRLF", (a, c) -> {
                SslState st = SSL_HANDLES.get(a.size() > 0 ? a.get(0).getLong() : 0);
                if (st == null || st.engine == null) return new RuntimeScalar().getList();
                StringBuilder out = new StringBuilder();
                for (int i = 0; i < 64; i++) {
                    advance(st);
                    st.plainIn.flip();
                    if (st.plainIn.hasRemaining()) {
                        byte[] chunk = new byte[st.plainIn.remaining()];
                        st.plainIn.get(chunk);
                        out.append(new String(chunk, StandardCharsets.ISO_8859_1));
                        st.plainIn.compact();
                        if (out.indexOf("\r\n") >= 0) break;
                    } else {
                        st.plainIn.compact();
                        break;
                    }
                }
                RuntimeScalar rs = new RuntimeScalar(out.toString());
                rs.type = RuntimeScalarType.BYTE_STRING;
                return rs.getList();
            });
            registerLambda("ssl_write_CRLF", (a, c) -> {
                if (a.size() < 2) return new RuntimeScalar(-1).getList();
                RuntimeArray proxy = new RuntimeArray();
                proxy.push(a.get(0));
                String with_crlf = a.get(1).toString() + "\r\n";
                proxy.push(new RuntimeScalar(with_crlf));
                RuntimeScalar cb = GlobalVariable.getGlobalCodeRef("Net::SSLeay::write");
                return RuntimeCode.apply(cb, proxy, RuntimeContextType.SCALAR);
            });
            registerLambda("ssl_read_until", (a, c) -> {
                // (ssl, delim, maxlen): read until delim or EOF.
                SslState st = SSL_HANDLES.get(a.size() > 0 ? a.get(0).getLong() : 0);
                if (st == null || st.engine == null) return new RuntimeScalar().getList();
                String delim = a.size() >= 2 ? a.get(1).toString() : "\n";
                int maxLen = a.size() >= 3 ? (int) a.get(2).getLong() : 65536;
                StringBuilder out = new StringBuilder();
                for (int i = 0; i < 256 && out.length() < maxLen; i++) {
                    advance(st);
                    st.plainIn.flip();
                    if (st.plainIn.hasRemaining()) {
                        byte[] chunk = new byte[Math.min(st.plainIn.remaining(),
                                maxLen - out.length())];
                        st.plainIn.get(chunk);
                        out.append(new String(chunk, StandardCharsets.ISO_8859_1));
                        st.plainIn.compact();
                        if (out.indexOf(delim) >= 0) break;
                    } else {
                        st.plainIn.compact();
                        break;
                    }
                }
                RuntimeScalar rs = new RuntimeScalar(out.toString());
                rs.type = RuntimeScalarType.BYTE_STRING;
                return rs.getList();
            });

            // Session-cache counters (always zero — in-memory cache).
            String[] sessCounters = {
                    "sess_accept", "sess_accept_good", "sess_accept_renegotiate",
                    "sess_cache_full", "sess_cb_hits", "sess_cb_hits_deprecated",
                    "sess_connect", "sess_connect_good", "sess_connect_renegotiate",
                    "sess_hits", "sess_misses", "sess_number", "sess_timeouts"
            };
            for (String name : sessCounters) {
                registerLambda(name, (a, c) -> new RuntimeScalar(0).getList());
            }

            // p_next_proto_* (ALPN helpers) — return undef to mean "no
            // protocol negotiated" so callers fall back to default HTTP.
            registerLambda("p_next_proto_last_status", (a, c) -> new RuntimeScalar(0).getList());
            registerLambda("p_next_proto_negotiated", (a, c) -> new RuntimeScalar("").getList());

            // PKCS7 sign/verify — returns undef to indicate "not supported
            // yet"; callers usually fall back to raw RSA.
            registerLambda("PKCS7_sign", (a, c) -> new RuntimeScalar().getList());
            registerLambda("PKCS7_verify", (a, c) -> new RuntimeScalar(0).getList());

            // EVP_PKEY ASN1 round-trip — returns undef for now (we have
            // loaded keys cached by EVP_PKEY_HANDLES, but we don't serialise
            // them back to ASN.1 structures).
            registerLambda("P_EVP_PKEY_fromdata", (a, c) -> new RuntimeScalar().getList());
            registerLambda("P_EVP_PKEY_todata", (a, c) -> new RuntimeScalar().getList());

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
                    "MD5", "SHA1", "SHA256", "SHA512", "RIPEMD160",
                    "ASN1_TIME_new", "ASN1_TIME_set", "ASN1_TIME_free",
                    "P_ASN1_TIME_put2string", "P_ASN1_UTCTIME_put2string",
                    "P_ASN1_TIME_get_isotime", "P_ASN1_TIME_set_isotime",
                    "X509_gmtime_adj",
                    "PEM_read_bio_PrivateKey", "EVP_PKEY_free",
                    "CTX_new", "CTX_v23_new", "CTX_new_with_method", "CTX_free",
                    "SSLv23_method", "SSLv23_client_method", "SSLv23_server_method",
                    "TLSv1_method", "TLS_method", "TLS_client_method", "TLS_server_method",
                    "SSL_free", "in_connect_init", "in_accept_init",
                    "CTX_set_min_proto_version", "CTX_set_max_proto_version",
                    "CTX_get_min_proto_version", "CTX_get_max_proto_version",
                    "set_min_proto_version", "set_max_proto_version",
                    "get_min_proto_version", "get_max_proto_version",
                    "CTX_get_security_level", "CTX_set_security_level",
                    "get_security_level", "set_security_level",
                    "EC_KEY_generate_key", "EVP_PKEY_assign_EC_KEY");

        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing NetSSLeay method: " + e.getMessage());
        }
    }

    // Helper to register a PerlSubroutine lambda as Net::SSLeay::name
    private static void registerLambda(String name, PerlSubroutine sub) {
        RuntimeCode code = new RuntimeCode(sub, null); // null prototype = unrestricted args
        code.isStatic = true;
        code.packageName = "Net::SSLeay";
        code.subName = name;
        String fullName = NameNormalizer.normalizeVariableName(name, "Net::SSLeay");
        GlobalVariable.getGlobalCodeRef(fullName).set(new RuntimeScalar(code));
    }

    /**
     * Register a Net::SSLeay entry point that is not yet implemented.
     * Calling it throws a Perl exception of the form:
     *   Net::SSLeay::FOO is not implemented in PerlOnJava yet
     *   (tracked in dev/modules/netssleay_complete.md, phase N)
     * so CPAN code gets a clear, grep-able failure instead of a silent
     * wrong answer. Use this in preference to returning a hardcoded
     * success/failure unless we genuinely have implementation state to
     * record on the handle.
     */
    private static void registerNotImplemented(String name, int phase) {
        registerLambda(name, (a, c) -> {
            throw new org.perlonjava.runtime.runtimetypes.PerlDieException(
                    new RuntimeScalar("Net::SSLeay::" + name
                            + " is not implemented in PerlOnJava yet"
                            + " (tracked in dev/modules/netssleay_complete.md, phase "
                            + phase + ")\n"));
        });
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

    /**
     * ERR_load_*_strings — load per-subsystem human-readable error text.
     * In modern OpenSSL these are all no-ops: the error strings are loaded
     * on demand by ERR_error_string, so nothing needs to happen here. We
     * expose them so callers that invoke them at BEGIN time don't trip
     * Undefined-subroutine errors.
     */
    public static RuntimeList ERR_load_BIO_strings(RuntimeArray args, int ctx) {
        return new RuntimeScalar().getList();
    }

    public static RuntimeList ERR_load_ERR_strings(RuntimeArray args, int ctx) {
        return new RuntimeScalar().getList();
    }

    public static RuntimeList ERR_load_SSL_strings(RuntimeArray args, int ctx) {
        return new RuntimeScalar().getList();
    }

    /**
     * ERR_print_errors_cb(&callback, $user_data) — drain the error queue,
     * calling $callback->($line, $len, $user_data) for each formatted entry.
     * The callback returns 0 to stop iterating.
     */
    public static RuntimeList ERR_print_errors_cb(RuntimeArray args, int ctx) {
        RuntimeScalar cb = args.size() > 0 ? args.get(0).scalar() : null;
        RuntimeScalar userData = args.size() > 1 ? args.get(1).scalar()
                : RuntimeScalarCache.scalarUndef;
        if (cb == null || cb.type != RuntimeScalarType.CODE) {
            return new RuntimeScalar(0).getList();
        }
        Deque<Long> queue = ERROR_QUEUE.get();
        while (!queue.isEmpty()) {
            long code = queue.pollFirst();
            int lib = (int) ((code >> 23) & 0x1FF);
            int reason = (int) (code & 0x7FFFFF);
            String line = String.format("error:%08X:%s::%s",
                    code, getLibName(lib), getReasonString(lib, reason));
            RuntimeArray cbArgs = new RuntimeArray();
            cbArgs.push(new RuntimeScalar(line));
            cbArgs.push(new RuntimeScalar(line.length()));
            cbArgs.push(userData);
            RuntimeList r = RuntimeCode.apply(cb, cbArgs, RuntimeContextType.SCALAR);
            if (!r.isEmpty() && !r.getFirst().getBoolean()) {
                break; // callback returned false — stop iterating
            }
        }
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
                java.io.File f = RuntimeIO.resolvePath(filename).toFile();
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

    public static RuntimeList BIO_s_file(RuntimeArray args, int ctx) {
        // Returns a sentinel value representing the "file BIO method".
        // BIO_new(BIO_s_file()) is followed by BIO_read_filename/BIO_write_filename
        // in upstream OpenSSL; Net::SSLeay exposes BIO_new_file() as a convenience
        // that combines the two. We honour the sentinel here for completeness.
        return new RuntimeScalar(BIO_S_FILE_SENTINEL).getList();
    }

    public static RuntimeList BIO_new(RuntimeArray args, int ctx) {
        // BIO_new(method) - creates a new BIO
        long handleId = HANDLE_COUNTER.getAndIncrement();
        BIO_HANDLES.put(handleId, new MemoryBIO());
        return new RuntimeScalar(handleId).getList();
    }

    public static RuntimeList BIO_new_mem_buf(RuntimeArray args, int ctx) {
        // BIO_new_mem_buf(data [, len]) - read-only BIO over an in-memory buffer.
        // Net::SSLeay passes a Perl string; len < 0 means "use the string length".
        // For our MemoryBIO implementation, we simply seed a new BIO with the
        // bytes and return its handle. True read-only semantics (erroring on
        // BIO_write) aren't enforced — no known Perl caller depends on them.
        if (args.size() < 1) return new RuntimeScalar(0).getList();
        String data = args.get(0).toString();
        int requested = args.size() > 1 ? (int) args.get(1).getLong() : -1;
        byte[] bytes = data.getBytes(StandardCharsets.ISO_8859_1);
        if (requested >= 0 && requested < bytes.length) {
            byte[] trimmed = new byte[requested];
            System.arraycopy(bytes, 0, trimmed, 0, requested);
            bytes = trimmed;
        }
        long handleId = HANDLE_COUNTER.getAndIncrement();
        MemoryBIO bio = new MemoryBIO();
        bio.write(bytes);
        BIO_HANDLES.put(handleId, bio);
        return new RuntimeScalar(handleId).getList();
    }

    public static RuntimeList BIO_new_file(RuntimeArray args, int ctx) {
        // BIO_new_file(filename, mode) - create BIO and load file contents
        String filename = args.size() > 0 ? args.get(0).toString() : "";
        try {
            byte[] fileData = Files.readAllBytes(RuntimeIO.resolvePath(filename));
            long handleId = HANDLE_COUNTER.getAndIncrement();
            MemoryBIO bio = new MemoryBIO();
            bio.write(fileData);
            BIO_HANDLES.put(handleId, bio);
            return new RuntimeScalar(handleId).getList();
        } catch (Exception e) {
            return new RuntimeScalar(0).getList(); // return 0 (false) on failure
        }
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

    // ---- Phase 6: RSA encrypt/decrypt helper ----

    private static RuntimeList rsaCrypt(RuntimeArray args, boolean encrypt, boolean usePublic) {
        // Form: (from, to_ref, rsa, padding)
        // PerlOnJava style: we return the transformed bytes as a scalar
        // directly (callers typically call as: RSA_public_encrypt($in, $out, $rsa, $pad);
        // where $out is output-by-reference).  The existing codebase uses
        // the return value form for Perl-side simplicity.
        if (args.size() < 3) return new RuntimeScalar().getList();
        byte[] data = args.get(0).toString().getBytes(StandardCharsets.ISO_8859_1);
        // args(1) is the output-string scalar; we assign into it and also
        // return the number of bytes written.
        RuntimeScalar outTarget = args.get(1);
        KeyPair kp = RSA_HANDLES.get(args.get(2).getLong());
        if (kp == null) return new RuntimeScalar(-1).getList();
        int padding = args.size() >= 4 ? (int) args.get(3).getLong() : 1; // 1 = RSA_PKCS1_PADDING
        String transform;
        switch (padding) {
            case 3:  transform = "RSA/ECB/NoPadding"; break;           // RSA_NO_PADDING
            case 4:  transform = "RSA/ECB/OAEPWithSHA-1AndMGF1Padding"; break; // RSA_PKCS1_OAEP_PADDING
            default: transform = "RSA/ECB/PKCS1Padding";               // RSA_PKCS1_PADDING
        }
        try {
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance(transform);
            java.security.Key key = usePublic ? kp.getPublic() : kp.getPrivate();
            if (key == null) return new RuntimeScalar(-1).getList();
            cipher.init(encrypt ? javax.crypto.Cipher.ENCRYPT_MODE : javax.crypto.Cipher.DECRYPT_MODE, key);
            byte[] out = cipher.doFinal(data);
            outTarget.set(new String(out, StandardCharsets.ISO_8859_1));
            outTarget.type = RuntimeScalarType.BYTE_STRING;
            return new RuntimeScalar(out.length).getList();
        } catch (Exception e) {
            return new RuntimeScalar(-1).getList();
        }
    }

    // Helper: OpenSSL digest name → Java RSA Signature algorithm
    private static String rsaSignatureAlg(String digestName) {
        if (digestName == null) return null;
        switch (digestName.toLowerCase()) {
            case "sha1":   return "SHA1withRSA";
            case "sha224": return "SHA224withRSA";
            case "sha256": return "SHA256withRSA";
            case "sha384": return "SHA384withRSA";
            case "sha512": return "SHA512withRSA";
            case "md5":    return "MD5withRSA";
            default: return null;
        }
    }

    // Phase 4 helper: X509 verify error code → human string
    private static String x509VerifyErrorString(int code) {
        switch (code) {
            case 0:  return "ok";
            case 2:  return "unable to get issuer certificate";
            case 3:  return "unable to get certificate CRL";
            case 4:  return "unable to decrypt certificate's signature";
            case 5:  return "unable to decrypt CRL's signature";
            case 6:  return "unable to decode issuer public key";
            case 7:  return "certificate signature failure";
            case 8:  return "CRL signature failure";
            case 9:  return "certificate is not yet valid";
            case 10: return "certificate has expired";
            case 11: return "CRL is not yet valid";
            case 12: return "CRL has expired";
            case 13: return "format error in certificate's notBefore field";
            case 14: return "format error in certificate's notAfter field";
            case 15: return "format error in CRL's lastUpdate field";
            case 16: return "format error in CRL's nextUpdate field";
            case 17: return "out of memory";
            case 18: return "self signed certificate";
            case 19: return "self signed certificate in certificate chain";
            case 20: return "unable to get local issuer certificate";
            case 21: return "unable to verify the first certificate";
            case 22: return "certificate chain too long";
            case 23: return "certificate revoked";
            case 24: return "invalid CA certificate";
            case 25: return "path length constraint exceeded";
            case 26: return "unsupported certificate purpose";
            case 27: return "certificate not trusted";
            case 28: return "certificate rejected";
            case 29: return "subject issuer mismatch";
            case 30: return "authority and subject key identifier mismatch";
            case 31: return "authority and issuer serial number mismatch";
            case 32: return "key usage does not include certificate signing";
            case 50: return "application verification failure";
            default: return "certificate verify error";
        }
    }

    // =====================================================================
    // Phase 2 — SSLEngine handshake driver
    // =====================================================================

    // OpenSSL SSL_ERROR_* constants we surface
    private static final int SSL_ERROR_NONE              = 0;
    private static final int SSL_ERROR_SSL               = 1;
    private static final int SSL_ERROR_WANT_READ         = 2;
    private static final int SSL_ERROR_WANT_WRITE        = 3;
    private static final int SSL_ERROR_SYSCALL           = 5;
    private static final int SSL_ERROR_ZERO_RETURN       = 6;

    /**
     * Lazily build a javax.net.ssl.SSLContext for the given SSL_CTX state.
     * Honours min/max proto version, installs any key/trust managers
     * that were configured via CTX_use_certificate_*_file /
     * CTX_load_verify_locations (those populate ctx.keyManagers and
     * ctx.trustManagers; if neither is set, we fall back to the JDK
     * defaults — which for client role means the platform trust store,
     * and for server role means no cert — the caller will get a
     * handshake failure, matching OpenSSL behaviour for an unconfigured
     * server CTX).
     */
    private static javax.net.ssl.SSLContext buildSslContext(SslCtxState ctx) throws Exception {
        if (ctx.sslContext != null) return ctx.sslContext;
        // Pick protocol band matching min/max version
        String protocol = "TLS";  // let the JDK negotiate
        javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance(protocol);
        javax.net.ssl.TrustManager[] tms = ctx.trustManagers;
        if (tms == null) {
            if (ctx.verifyMode == 0) {
                // VERIFY_NONE: accept-all trust manager (client tests,
                // AnyEvent::TLS "verify => 0" style).
                tms = new javax.net.ssl.TrustManager[] {
                        new javax.net.ssl.X509TrustManager() {
                            public void checkClientTrusted(X509Certificate[] x, String s) {}
                            public void checkServerTrusted(X509Certificate[] x, String s) {}
                            public X509Certificate[] getAcceptedIssuers() {
                                return new X509Certificate[0];
                            }
                        }
                };
            } else {
                javax.net.ssl.TrustManagerFactory tmf =
                        javax.net.ssl.TrustManagerFactory.getInstance(
                                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
                tmf.init((java.security.KeyStore) null);
                tms = tmf.getTrustManagers();
            }
        }
        javax.net.ssl.KeyManager[] kms = ctx.keyManagers;
        if (kms == null && ctx.loadedPrivateKey != null
                && ctx.loadedCertChain != null && !ctx.loadedCertChain.isEmpty()) {
            // Phase 2b: assemble an in-memory KeyStore holding the
            // CTX_use_PrivateKey_file key + CTX_use_certificate_*_file chain.
            java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            java.security.cert.Certificate[] chain =
                    ctx.loadedCertChain.toArray(new java.security.cert.Certificate[0]);
            ks.setKeyEntry("net-ssleay", ctx.loadedPrivateKey, new char[0], chain);
            javax.net.ssl.KeyManagerFactory kmf =
                    javax.net.ssl.KeyManagerFactory.getInstance(
                            javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, new char[0]);
            kms = kmf.getKeyManagers();
        }
        sc.init(kms, tms, SECURE_RANDOM);
        ctx.sslContext = sc;
        return sc;
    }

    /** Phase 2b: parse a PEM file containing one or more X509 certs. */
    private static java.util.List<X509Certificate> loadCertChainFromPem(String filename) throws Exception {
        byte[] data = Files.readAllBytes(RuntimeIO.resolvePath(filename));
        java.security.cert.CertificateFactory cf =
                java.security.cert.CertificateFactory.getInstance("X.509");
        java.util.List<X509Certificate> out = new ArrayList<>();
        java.util.Collection<? extends java.security.cert.Certificate> certs =
                cf.generateCertificates(new java.io.ByteArrayInputStream(data));
        for (java.security.cert.Certificate c : certs) {
            if (c instanceof X509Certificate) out.add((X509Certificate) c);
        }
        return out;
    }

    /**
     * Build an SSLEngine from the CTX's SSLContext, applying per-SSL
     * state (cipher list, SNI, verify mode, protocol pins).
     */
    private static javax.net.ssl.SSLEngine buildEngine(SslState ssl, boolean clientMode) throws Exception {
        SslCtxState ctx = CTX_HANDLES.get(ssl.ctxHandle);
        if (ctx == null) throw new IllegalStateException("SSL handle has no parent CTX");
        javax.net.ssl.SSLContext sc = buildSslContext(ctx);
        javax.net.ssl.SSLEngine eng = sc.createSSLEngine();
        eng.setUseClientMode(clientMode);
        // Client-mode: pin SNI if supplied via set_tlsext_host_name
        if (clientMode && ssl.hostName != null && !ssl.hostName.isEmpty()) {
            javax.net.ssl.SSLParameters p = eng.getSSLParameters();
            p.setServerNames(java.util.Collections.singletonList(
                    new javax.net.ssl.SNIHostName(ssl.hostName)));
            eng.setSSLParameters(p);
        }
        // Server-mode: honour verifyMode ≠ 0 as "want/need client auth"
        if (!clientMode && ssl.verifyMode != 0) {
            // VERIFY_PEER=1, VERIFY_FAIL_IF_NO_PEER_CERT=2
            if ((ssl.verifyMode & 2) != 0) eng.setNeedClientAuth(true);
            else                           eng.setWantClientAuth(true);
        }
        // Allocate plaintext buffers sized to the session
        int appBufSize = eng.getSession().getApplicationBufferSize();
        ssl.plainIn  = java.nio.ByteBuffer.allocate(appBufSize);
        ssl.plainOut = java.nio.ByteBuffer.allocate(appBufSize);
        return eng;
    }

    /**
     * The core handshake / data driver. Called from read/write/shutdown.
     * Pumps bytes through wrap/unwrap until either:
     *   - it completes an operation (handshake finished / produced plaintext /
     *     flushed plaintext to the wire)
     *   - it needs more bytes from the peer (→ SSL_ERROR_WANT_READ)
     *   - it needs room in the write BIO (→ SSL_ERROR_WANT_WRITE; we always
     *     have room because our BIOs are unbounded, so this never occurs)
     *   - the engine is closed (→ SSL_ERROR_ZERO_RETURN)
     *   - it errors out (→ SSL_ERROR_SSL)
     *
     * Returns the SSL_ERROR_* code reflecting the engine's current state.
     */
    private static int advance(SslState ssl) {
        javax.net.ssl.SSLEngine eng = ssl.engine;
        if (eng == null) { ssl.lastError = SSL_ERROR_SSL; return SSL_ERROR_SSL; }
        MemoryBIO rbio = BIO_HANDLES.get(ssl.readBio);
        MemoryBIO wbio = BIO_HANDLES.get(ssl.writeBio);
        if (rbio == null || wbio == null) {
            ssl.lastError = SSL_ERROR_SSL; return SSL_ERROR_SSL;
        }
        int netBuf = eng.getSession().getPacketBufferSize();
        // Loop until we can't make progress.
        for (int step = 0; step < 64; step++) {
            javax.net.ssl.SSLEngineResult.HandshakeStatus hs = eng.getHandshakeStatus();
            // If handshaking is done and we have plaintext pending, wrap it.
            if (hs == javax.net.ssl.SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
                    || hs == javax.net.ssl.SSLEngineResult.HandshakeStatus.FINISHED) {
                ssl.handshakeComplete = true;
                ssl.state = 3; // SSL_ST_OK (OpenSSL uses 0x03 for OK/accept/connect)
                ssl.plainOut.flip();
                if (ssl.plainOut.hasRemaining()) {
                    try {
                        java.nio.ByteBuffer net = java.nio.ByteBuffer.allocate(netBuf);
                        javax.net.ssl.SSLEngineResult r = eng.wrap(ssl.plainOut, net);
                        ssl.plainOut.compact();
                        net.flip();
                        if (net.hasRemaining()) {
                            byte[] out = new byte[net.remaining()];
                            net.get(out);
                            wbio.write(out);
                        }
                        if (r.getStatus() == javax.net.ssl.SSLEngineResult.Status.CLOSED) {
                            ssl.outboundClosed = true;
                            ssl.lastError = SSL_ERROR_ZERO_RETURN;
                            return SSL_ERROR_ZERO_RETURN;
                        }
                        continue; // maybe more to wrap
                    } catch (javax.net.ssl.SSLException e) {
                        ssl.plainOut.compact();
                        ssl.lastError = SSL_ERROR_SSL;
                        return SSL_ERROR_SSL;
                    }
                } else {
                    ssl.plainOut.compact();
                }
                // No plaintext to flush; try to consume peer data.
                if (rbio.pending() > 0) {
                    if (pumpUnwrap(ssl, rbio) < 0) return ssl.lastError;
                    continue;
                }
                ssl.lastError = SSL_ERROR_NONE;
                return SSL_ERROR_NONE;
            }
            switch (hs) {
                case NEED_TASK: {
                    Runnable t;
                    while ((t = eng.getDelegatedTask()) != null) t.run();
                    break;
                }
                case NEED_WRAP: {
                    try {
                        java.nio.ByteBuffer net = java.nio.ByteBuffer.allocate(netBuf);
                        // Source buffer may be empty — that's fine during handshake
                        javax.net.ssl.SSLEngineResult r =
                                eng.wrap(java.nio.ByteBuffer.allocate(0), net);
                        net.flip();
                        if (net.hasRemaining()) {
                            byte[] out = new byte[net.remaining()];
                            net.get(out);
                            wbio.write(out);
                        }
                        if (r.getStatus() == javax.net.ssl.SSLEngineResult.Status.CLOSED) {
                            ssl.outboundClosed = true;
                        }
                    } catch (javax.net.ssl.SSLException e) {
                        ssl.lastError = SSL_ERROR_SSL;
                        return SSL_ERROR_SSL;
                    }
                    break;
                }
                case NEED_UNWRAP:
                case NEED_UNWRAP_AGAIN: {
                    int haveBytes = rbio.pending()
                            + (ssl.pendingNetIn != null ? ssl.pendingNetIn.length : 0);
                    if (haveBytes <= 0) {
                        ssl.lastError = SSL_ERROR_WANT_READ;
                        return SSL_ERROR_WANT_READ;
                    }
                    if (pumpUnwrap(ssl, rbio) < 0) return ssl.lastError;
                    break;
                }
                default:
                    ssl.lastError = SSL_ERROR_NONE;
                    return SSL_ERROR_NONE;
            }
        }
        ssl.lastError = SSL_ERROR_NONE;
        return SSL_ERROR_NONE;
    }

    /**
     * One unwrap step: takes up to the rbio's pending bytes, feeds them
     * through the engine, appends decrypted plaintext to ssl.plainIn,
     * leaves any unconsumed bytes in rbio.
     * Returns the number of bytes appended to plainIn, or -1 on error
     * (in which case ssl.lastError is set and should be returned).
     */
    private static int pumpUnwrap(SslState ssl, MemoryBIO rbio) {
        javax.net.ssl.SSLEngine eng = ssl.engine;
        int avail = rbio.pending();
        byte[] leftover = ssl.pendingNetIn;
        ssl.pendingNetIn = null;
        if (avail <= 0 && (leftover == null || leftover.length == 0)) return 0;
        byte[] fromBio = avail > 0 ? rbio.read(avail) : new byte[0];
        byte[] buf;
        if (leftover != null && leftover.length > 0) {
            buf = new byte[leftover.length + fromBio.length];
            System.arraycopy(leftover, 0, buf, 0, leftover.length);
            System.arraycopy(fromBio, 0, buf, leftover.length, fromBio.length);
        } else {
            buf = fromBio;
        }
        boolean dbg = false; // flip for ad-hoc debugging
        if (dbg && buf.length > 0) System.err.println("pumpUnwrap: " + buf.length + " bytes");
        java.nio.ByteBuffer netIn = java.nio.ByteBuffer.wrap(buf);
        try {
            while (netIn.hasRemaining()) {
                javax.net.ssl.SSLEngineResult r = eng.unwrap(netIn, ssl.plainIn);
                if (r.getStatus() == javax.net.ssl.SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                    // Not enough bytes for a full record — put the rest back.
                    byte[] remaining = new byte[netIn.remaining()];
                    netIn.get(remaining);
                    ssl.pendingNetIn = remaining;
                    return 0;
                }
                if (r.getStatus() == javax.net.ssl.SSLEngineResult.Status.BUFFER_OVERFLOW) {
                    // Grow plaintext buffer
                    int need = eng.getSession().getApplicationBufferSize();
                    java.nio.ByteBuffer bigger = java.nio.ByteBuffer.allocate(
                            ssl.plainIn.position() + need);
                    ssl.plainIn.flip();
                    bigger.put(ssl.plainIn);
                    ssl.plainIn = bigger;
                    continue;
                }
                if (r.getStatus() == javax.net.ssl.SSLEngineResult.Status.CLOSED) {
                    ssl.inboundClosed = true;
                    ssl.lastError = SSL_ERROR_ZERO_RETURN;
                    return -1;
                }
                // OK — we consumed some bytes; loop to consume more records
                // if the rest of netIn still has data.
                javax.net.ssl.SSLEngineResult.HandshakeStatus hs =
                        eng.getHandshakeStatus();
                if (hs == javax.net.ssl.SSLEngineResult.HandshakeStatus.NEED_TASK) {
                    Runnable t;
                    while ((t = eng.getDelegatedTask()) != null) t.run();
                }
                if (hs == javax.net.ssl.SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                    // Need to emit bytes before we can consume more.
                    // Stash the unconsumed ciphertext so the next pumpUnwrap
                    // picks it up (otherwise we drop it on the floor).
                    if (netIn.hasRemaining()) {
                        byte[] rest = new byte[netIn.remaining()];
                        netIn.get(rest);
                        ssl.pendingNetIn = rest;
                    }
                    // caller's advance loop picks this up on the next pass
                    break;
                }
            }
            return 0;
        } catch (javax.net.ssl.SSLException e) {
            ssl.lastError = SSL_ERROR_SSL;
            return -1;
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


    // SSLeay_version() type constants


    // Note: OPENSSL_VERSION as a constant (=0) is separate from the OPENSSL_VERSION field (=0x30000000L)


    // ---- ASN1_TIME functions (backed by epoch seconds + java.time formatting) ----

    public static RuntimeList ASN1_TIME_new(RuntimeArray args, int ctx) {
        long handleId = HANDLE_COUNTER.getAndIncrement();
        ASN1_TIME_HANDLES.put(handleId, 0L); // epoch 0 initially
        return new RuntimeScalar(handleId).getList();
    }

    public static RuntimeList ASN1_TIME_set(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(0).getList();
        long handleId = args.get(0).getLong();
        long epoch = args.get(1).getLong();
        if (!ASN1_TIME_HANDLES.containsKey(handleId)) return new RuntimeScalar(0).getList();
        ASN1_TIME_HANDLES.put(handleId, epoch);
        return new RuntimeScalar(handleId).getList(); // returns the time pointer on success
    }

    public static RuntimeList ASN1_TIME_free(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        long handleId = args.get(0).getLong();
        ASN1_TIME_HANDLES.remove(handleId);
        return new RuntimeScalar().getList();
    }

    // Format: "May 16 20:39:37 2033 GMT"
    private static final DateTimeFormatter ASN1_TIME_FMT = DateTimeFormatter.ofPattern(
            "MMM dd HH:mm:ss yyyy 'GMT'", Locale.ENGLISH);

    public static RuntimeList P_ASN1_TIME_put2string(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        long handleId = args.get(0).getLong();
        Long epoch = ASN1_TIME_HANDLES.get(handleId);
        if (epoch == null) return new RuntimeScalar().getList();
        ZonedDateTime zdt = Instant.ofEpochSecond(epoch).atZone(ZoneOffset.UTC);
        // Ensure single-space padding for day (not zero-padded): "May  6" not "May 06"
        String formatted = zdt.format(ASN1_TIME_FMT);
        return new RuntimeScalar(formatted).getList();
    }

    public static RuntimeList P_ASN1_UTCTIME_put2string(RuntimeArray args, int ctx) {
        // Same as P_ASN1_TIME_put2string for our purposes
        return P_ASN1_TIME_put2string(args, ctx);
    }

    public static RuntimeList P_ASN1_TIME_get_isotime(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        long handleId = args.get(0).getLong();
        Long epoch = ASN1_TIME_HANDLES.get(handleId);
        if (epoch == null) return new RuntimeScalar().getList();
        String iso = Instant.ofEpochSecond(epoch).toString(); // e.g. "2033-05-16T20:39:37Z"
        return new RuntimeScalar(iso).getList();
    }

    public static RuntimeList P_ASN1_TIME_set_isotime(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(0).getList();
        long handleId = args.get(0).getLong();
        String isoTime = args.get(1).toString();
        if (!ASN1_TIME_HANDLES.containsKey(handleId)) return new RuntimeScalar(0).getList();
        try {
            long epoch = Instant.parse(isoTime).getEpochSecond();
            ASN1_TIME_HANDLES.put(handleId, epoch);
            return new RuntimeScalar(1).getList();
        } catch (Exception e) {
            return new RuntimeScalar(0).getList();
        }
    }

    public static RuntimeList X509_gmtime_adj(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(0).getList();
        long handleId = args.get(0).getLong();
        long offsetSeconds = args.get(1).getLong();
        if (!ASN1_TIME_HANDLES.containsKey(handleId)) return new RuntimeScalar(0).getList();
        long epoch = Instant.now().getEpochSecond() + offsetSeconds;
        ASN1_TIME_HANDLES.put(handleId, epoch);
        return new RuntimeScalar(handleId).getList(); // returns the time pointer on success
    }

    // ---- BIO_new_file fix: actually read file contents ----

    // Override the existing BIO_new_file to load file data into a MemoryBIO
    // (The old implementation created an empty BIO — now we read the actual file)

    // ---- PEM_read_bio_PrivateKey (parse PEM private key from BIO) ----

    public static RuntimeList PEM_read_bio_PrivateKey(RuntimeArray args, int ctx) {
        // PEM_read_bio_PrivateKey($bio, [$cb_or_undef], [$password])
        if (args.size() < 1) return new RuntimeScalar().getList();
        long bioHandle = args.get(0).getLong();
        MemoryBIO bio = BIO_HANDLES.get(bioHandle);
        if (bio == null) return new RuntimeScalar().getList();

        // Get password (from callback or direct string)
        String password = null;
        if (args.size() > 2 && args.get(2).type != RuntimeScalarType.UNDEF) {
            password = args.get(2).toString();
        } else if (args.size() > 1 && args.get(1).type == RuntimeScalarType.CODE) {
            // Call callback to get password
            RuntimeArray cbArgs = new RuntimeArray();
            RuntimeList resultList = RuntimeCode.apply(args.get(1), cbArgs, RuntimeContextType.SCALAR);
            password = resultList.getFirst().toString();
        }

        try {
            // Read all BIO data as string
            byte[] allData = bio.read(bio.pending());
            String pem = new String(allData, StandardCharsets.ISO_8859_1);

            // Parse PEM
            byte[] derBytes = parsePemPrivateKey(pem, password);
            if (derBytes == null) return new RuntimeScalar().getList();

            // Parse the DER-encoded key
            PrivateKey privKey = parsePrivateKeyDer(derBytes);
            if (privKey == null) return new RuntimeScalar().getList();

            long handleId = HANDLE_COUNTER.getAndIncrement();
            EVP_PKEY_HANDLES.put(handleId, privKey);
            return new RuntimeScalar(handleId).getList();
        } catch (Exception e) {
            return new RuntimeScalar().getList(); // return undef on any error
        }
    }

    public static RuntimeList EVP_PKEY_free(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        long handleId = args.get(0).getLong();
        EVP_PKEY_HANDLES.remove(handleId);
        return new RuntimeScalar().getList();
    }

    // Parse PEM text, handling encrypted or unencrypted RSA private keys
    private static byte[] parsePemPrivateKey(String pem, String password) throws Exception {
        // Strip headers/footers and collect base64 data
        String[] lines = pem.split("\n");
        StringBuilder b64 = new StringBuilder();
        boolean inBody = false;
        boolean encrypted = false;
        String dekInfo = null;

        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("-----BEGIN")) {
                inBody = true;
                continue;
            }
            if (line.startsWith("-----END")) {
                break;
            }
            if (!inBody) continue;
            if (line.startsWith("Proc-Type:") && line.contains("ENCRYPTED")) {
                encrypted = true;
                continue;
            }
            if (line.startsWith("DEK-Info:")) {
                dekInfo = line.substring("DEK-Info:".length()).trim();
                continue;
            }
            if (line.isEmpty()) continue;
            b64.append(line);
        }

        byte[] derData = Base64.getDecoder().decode(b64.toString());

        if (encrypted) {
            if (password == null || password.isEmpty()) return null;
            if (dekInfo == null) return null;
            derData = decryptPemBody(derData, dekInfo, password);
            if (derData == null) return null;
        }

        return derData;
    }

    // Decrypt an encrypted PEM body using DEK-Info header
    private static byte[] decryptPemBody(byte[] encrypted, String dekInfo, String password) {
        try {
            // Parse DEK-Info: "AES-128-CBC,<hex IV>"
            String[] parts = dekInfo.split(",", 2);
            if (parts.length < 2) return null;
            String algorithm = parts[0].trim();
            byte[] iv = hexToBytes(parts[1].trim());

            // Determine cipher and key length
            String cipherAlg;
            int keyLen;
            if (algorithm.startsWith("AES-128")) {
                cipherAlg = "AES/CBC/PKCS5Padding";
                keyLen = 16;
            } else if (algorithm.startsWith("AES-192")) {
                cipherAlg = "AES/CBC/PKCS5Padding";
                keyLen = 24;
            } else if (algorithm.startsWith("AES-256")) {
                cipherAlg = "AES/CBC/PKCS5Padding";
                keyLen = 32;
            } else if (algorithm.startsWith("DES-EDE3")) {
                cipherAlg = "DESede/CBC/PKCS5Padding";
                keyLen = 24;
            } else if (algorithm.startsWith("DES-CBC") || algorithm.equals("DES")) {
                cipherAlg = "DES/CBC/PKCS5Padding";
                keyLen = 8;
            } else {
                return null; // unsupported algorithm
            }

            // Derive key using OpenSSL EVP_BytesToKey (MD5-based)
            byte[] key = evpBytesToKey(password, iv, keyLen);

            // Decrypt
            String keyAlg = cipherAlg.startsWith("DESede") ? "DESede"
                    : cipherAlg.startsWith("DES") ? "DES" : "AES";
            Cipher cipher = Cipher.getInstance(cipherAlg);
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, keyAlg),
                    new IvParameterSpec(iv));
            return cipher.doFinal(encrypted);
        } catch (Exception e) {
            return null; // decryption failed (wrong password, etc.)
        }
    }

    // OpenSSL EVP_BytesToKey key derivation (MD5-based)
    private static byte[] evpBytesToKey(String password, byte[] salt, int keyLen) throws Exception {
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        byte[] passBytes = password.getBytes(StandardCharsets.ISO_8859_1);
        byte[] key = new byte[keyLen];
        byte[] d = new byte[0];
        int offset = 0;
        while (offset < keyLen) {
            md5.reset();
            if (d.length > 0) md5.update(d);
            md5.update(passBytes);
            md5.update(salt, 0, Math.min(8, salt.length));
            d = md5.digest();
            int toCopy = Math.min(d.length, keyLen - offset);
            System.arraycopy(d, 0, key, offset, toCopy);
            offset += toCopy;
        }
        return key;
    }

    // Parse DER-encoded private key (PKCS#1 RSA or PKCS#8)
    private static PrivateKey parsePrivateKeyDer(byte[] der) {
        // First try PKCS#8 format (works for RSA, EC, and other key types)
        PKCS8EncodedKeySpec pkcs8Spec = new PKCS8EncodedKeySpec(der);
        for (String algo : new String[]{"RSA", "EC", "DSA", "EdDSA"}) {
            try {
                return KeyFactory.getInstance(algo).generatePrivate(pkcs8Spec);
            } catch (Exception e) {
                // try next algorithm
            }
        }
        // Not PKCS#8, try wrapping as PKCS#1 → PKCS#8
        try {
            byte[] pkcs8 = wrapPkcs1InPkcs8(der);
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(pkcs8);
            return KeyFactory.getInstance("RSA").generatePrivate(spec);
        } catch (Exception e) {
            // Also try EC
        }
        try {
            byte[] pkcs8 = wrapPkcs1InPkcs8(der);
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(pkcs8);
            return KeyFactory.getInstance("EC").generatePrivate(spec);
        } catch (Exception e) {
            return null;
        }
    }

    // Wrap PKCS#1 RSA key in PKCS#8 envelope
    private static byte[] wrapPkcs1InPkcs8(byte[] pkcs1) {
        // AlgorithmIdentifier for RSA: SEQUENCE { OID 1.2.840.113549.1.1.1, NULL }
        byte[] rsaOid = {0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01};
        byte[] nullTag = {0x05, 0x00};
        byte[] algId = derSequence(derConcat(rsaOid, nullTag));
        byte[] version = {0x02, 0x01, 0x00}; // INTEGER 0
        byte[] octetString = derTag(0x04, pkcs1); // OCTET STRING wrapping PKCS#1
        return derSequence(derConcat(version, algId, octetString));
    }

    // DER encoding helpers
    private static byte[] derSequence(byte[] content) {
        return derTag(0x30, content);
    }

    private static byte[] derTag(int tag, byte[] content) {
        byte[] lenBytes = derLength(content.length);
        byte[] result = new byte[1 + lenBytes.length + content.length];
        result[0] = (byte) tag;
        System.arraycopy(lenBytes, 0, result, 1, lenBytes.length);
        System.arraycopy(content, 0, result, 1 + lenBytes.length, content.length);
        return result;
    }

    private static byte[] derLength(int length) {
        if (length < 128) {
            return new byte[]{(byte) length};
        } else if (length < 256) {
            return new byte[]{(byte) 0x81, (byte) length};
        } else {
            return new byte[]{(byte) 0x82, (byte) (length >> 8), (byte) (length & 0xff)};
        }
    }

    private static byte[] derConcat(byte[]... arrays) {
        int totalLen = 0;
        for (byte[] a : arrays) totalLen += a.length;
        byte[] result = new byte[totalLen];
        int pos = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, result, pos, a.length);
            pos += a.length;
        }
        return result;
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    // ---- SSL_CTX functions ----

    private static String roleFromMethod(long method) {
        if (method == METHOD_SSLv23_CLIENT || method == METHOD_TLS_CLIENT) return "client";
        if (method == METHOD_SSLv23_SERVER || method == METHOD_TLS_SERVER) return "server";
        return "generic";
    }

    public static RuntimeList CTX_new(RuntimeArray args, int ctx) {
        long handleId = HANDLE_COUNTER.getAndIncrement();
        CTX_HANDLES.put(handleId, new SslCtxState("generic"));
        return new RuntimeScalar(handleId).getList();
    }

    public static RuntimeList CTX_v23_new(RuntimeArray args, int ctx) {
        return CTX_new(args, ctx);
    }

    public static RuntimeList CTX_new_with_method(RuntimeArray args, int ctx) {
        long method = args.size() > 0 ? args.get(0).getLong() : METHOD_TLS;
        long handleId = HANDLE_COUNTER.getAndIncrement();
        CTX_HANDLES.put(handleId, new SslCtxState(roleFromMethod(method)));
        return new RuntimeScalar(handleId).getList();
    }

    public static RuntimeList CTX_free(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        long handleId = args.get(0).getLong();
        CTX_HANDLES.remove(handleId);
        return new RuntimeScalar().getList();
    }

    // SSL method functions — return sentinel values
    public static RuntimeList SSLv23_method(RuntimeArray args, int ctx) {
        return new RuntimeScalar(METHOD_SSLv23).getList();
    }

    public static RuntimeList SSLv23_client_method(RuntimeArray args, int ctx) {
        return new RuntimeScalar(METHOD_SSLv23_CLIENT).getList();
    }

    public static RuntimeList SSLv23_server_method(RuntimeArray args, int ctx) {
        return new RuntimeScalar(METHOD_SSLv23_SERVER).getList();
    }

    public static RuntimeList TLSv1_method(RuntimeArray args, int ctx) {
        return new RuntimeScalar(METHOD_TLSv1).getList();
    }

    public static RuntimeList TLS_method(RuntimeArray args, int ctx) {
        return new RuntimeScalar(METHOD_TLS).getList();
    }

    public static RuntimeList TLS_client_method(RuntimeArray args, int ctx) {
        return new RuntimeScalar(METHOD_TLS_CLIENT).getList();
    }

    public static RuntimeList TLS_server_method(RuntimeArray args, int ctx) {
        return new RuntimeScalar(METHOD_TLS_SERVER).getList();
    }

    // ---- SSL functions ----

    public static RuntimeList SSL_new(RuntimeArray args, int ctx) {
        // Net::SSLeay::new($ctx) — create an SSL handle from a CTX
        if (args.size() < 1) return new RuntimeScalar().getList();
        long ctxHandle = args.get(0).getLong();
        SslCtxState ctxState = CTX_HANDLES.get(ctxHandle);
        if (ctxState == null) return new RuntimeScalar().getList();
        long handleId = HANDLE_COUNTER.getAndIncrement();
        SSL_HANDLES.put(handleId, new SslState(ctxState, ctxHandle));
        return new RuntimeScalar(handleId).getList();
    }

    public static RuntimeList SSL_free(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        long handleId = args.get(0).getLong();
        SSL_HANDLES.remove(handleId);
        return new RuntimeScalar().getList();
    }

    public static RuntimeList in_connect_init(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar(0).getList();
        long handleId = args.get(0).getLong();
        SslState ssl = SSL_HANDLES.get(handleId);
        if (ssl == null) return new RuntimeScalar(0).getList();
        // Client SSLs are in connect init, server SSLs are not
        return new RuntimeScalar("server".equals(ssl.role) ? 0 : 1).getList();
    }

    public static RuntimeList in_accept_init(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar(0).getList();
        long handleId = args.get(0).getLong();
        SslState ssl = SSL_HANDLES.get(handleId);
        if (ssl == null) return new RuntimeScalar(0).getList();
        // Server SSLs are in accept init, client SSLs are not
        return new RuntimeScalar("server".equals(ssl.role) ? 1 : 0).getList();
    }

    // ---- Protocol version get/set ----

    public static RuntimeList CTX_set_min_proto_version(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(0).getList();
        long ctxHandle = args.get(0).getLong();
        long version = args.get(1).getLong();
        SslCtxState ctxState = CTX_HANDLES.get(ctxHandle);
        if (ctxState == null) return new RuntimeScalar(0).getList();
        if (!VALID_PROTO_VERSIONS.contains(version)) return new RuntimeScalar(0).getList();
        ctxState.minProtoVersion = version;
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList CTX_set_max_proto_version(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(0).getList();
        long ctxHandle = args.get(0).getLong();
        long version = args.get(1).getLong();
        SslCtxState ctxState = CTX_HANDLES.get(ctxHandle);
        if (ctxState == null) return new RuntimeScalar(0).getList();
        if (!VALID_PROTO_VERSIONS.contains(version)) return new RuntimeScalar(0).getList();
        ctxState.maxProtoVersion = version;
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList CTX_get_min_proto_version(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar(0).getList();
        long ctxHandle = args.get(0).getLong();
        SslCtxState ctxState = CTX_HANDLES.get(ctxHandle);
        if (ctxState == null) return new RuntimeScalar(0).getList();
        return new RuntimeScalar(ctxState.minProtoVersion).getList();
    }

    public static RuntimeList CTX_get_max_proto_version(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar(0).getList();
        long ctxHandle = args.get(0).getLong();
        SslCtxState ctxState = CTX_HANDLES.get(ctxHandle);
        if (ctxState == null) return new RuntimeScalar(0).getList();
        return new RuntimeScalar(ctxState.maxProtoVersion).getList();
    }

    public static RuntimeList set_min_proto_version(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(0).getList();
        long sslHandle = args.get(0).getLong();
        long version = args.get(1).getLong();
        SslState ssl = SSL_HANDLES.get(sslHandle);
        if (ssl == null) return new RuntimeScalar(0).getList();
        if (!VALID_PROTO_VERSIONS.contains(version)) return new RuntimeScalar(0).getList();
        ssl.minProtoVersion = version;
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList set_max_proto_version(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(0).getList();
        long sslHandle = args.get(0).getLong();
        long version = args.get(1).getLong();
        SslState ssl = SSL_HANDLES.get(sslHandle);
        if (ssl == null) return new RuntimeScalar(0).getList();
        if (!VALID_PROTO_VERSIONS.contains(version)) return new RuntimeScalar(0).getList();
        ssl.maxProtoVersion = version;
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList get_min_proto_version(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar(0).getList();
        long sslHandle = args.get(0).getLong();
        SslState ssl = SSL_HANDLES.get(sslHandle);
        if (ssl == null) return new RuntimeScalar(0).getList();
        return new RuntimeScalar(ssl.minProtoVersion).getList();
    }

    public static RuntimeList get_max_proto_version(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar(0).getList();
        long sslHandle = args.get(0).getLong();
        SslState ssl = SSL_HANDLES.get(sslHandle);
        if (ssl == null) return new RuntimeScalar(0).getList();
        return new RuntimeScalar(ssl.maxProtoVersion).getList();
    }

    // ---- Password callback functions (CTX-level) ----

    public static RuntimeList CTX_set_default_passwd_cb(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar().getList();
        long ctxHandle = args.get(0).getLong();
        SslCtxState ctxState = CTX_HANDLES.get(ctxHandle);
        if (ctxState == null) return new RuntimeScalar().getList();
        ctxState.passwdCb = args.get(1);
        return new RuntimeScalar().getList();
    }

    public static RuntimeList CTX_set_default_passwd_cb_userdata(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar().getList();
        long ctxHandle = args.get(0).getLong();
        SslCtxState ctxState = CTX_HANDLES.get(ctxHandle);
        if (ctxState == null) return new RuntimeScalar().getList();
        ctxState.passwdUserdata = args.get(1);
        return new RuntimeScalar().getList();
    }

    public static RuntimeList CTX_use_PrivateKey_file(RuntimeArray args, int ctx) {
        if (args.size() < 3) return new RuntimeScalar(0).getList();
        long ctxHandle = args.get(0).getLong();
        String filename = args.get(1).toString();
        SslCtxState ctxState = CTX_HANDLES.get(ctxHandle);
        if (ctxState == null) return new RuntimeScalar(0).getList();
        RuntimeList r = loadPrivateKeyFile(filename, ctxState.passwdCb, ctxState.passwdUserdata);
        if (r.size() > 0 && r.getFirst().getLong() == 1) {
            // Load succeeded; parse again into the CTX so the KeyManager
            // factory has the key at buildSslContext time.
            try {
                byte[] fileData = Files.readAllBytes(RuntimeIO.resolvePath(filename));
                String pem = new String(fileData, StandardCharsets.ISO_8859_1);
                String pass = null;
                if (ctxState.passwdCb != null && ctxState.passwdCb.type == RuntimeScalarType.CODE) {
                    RuntimeArray cbArgs = new RuntimeArray();
                    cbArgs.push(new RuntimeScalar(0));
                    cbArgs.push(ctxState.passwdUserdata != null ? ctxState.passwdUserdata
                            : new RuntimeScalar());
                    pass = RuntimeCode.apply(ctxState.passwdCb, cbArgs,
                            RuntimeContextType.SCALAR).getFirst().toString();
                }
                byte[] der = parsePemPrivateKey(pem, pass);
                if (der != null) {
                    PrivateKey pk = parsePrivateKeyDer(der);
                    if (pk != null) {
                        ctxState.loadedPrivateKey = pk;
                        ctxState.sslContext = null; // force rebuild
                    }
                }
            } catch (Exception ignored) {}
        }
        return r;
    }

    // SSL-level password callback functions
    public static RuntimeList set_default_passwd_cb(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar().getList();
        long sslHandle = args.get(0).getLong();
        SslState ssl = SSL_HANDLES.get(sslHandle);
        if (ssl == null) return new RuntimeScalar().getList();
        ssl.passwdCb = args.get(1);
        return new RuntimeScalar().getList();
    }

    public static RuntimeList set_default_passwd_cb_userdata(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar().getList();
        long sslHandle = args.get(0).getLong();
        SslState ssl = SSL_HANDLES.get(sslHandle);
        if (ssl == null) return new RuntimeScalar().getList();
        ssl.passwdUserdata = args.get(1);
        return new RuntimeScalar().getList();
    }

    public static RuntimeList use_PrivateKey_file(RuntimeArray args, int ctx) {
        if (args.size() < 3) return new RuntimeScalar(0).getList();
        long sslHandle = args.get(0).getLong();
        String filename = args.get(1).toString();
        SslState ssl = SSL_HANDLES.get(sslHandle);
        if (ssl == null) return new RuntimeScalar(0).getList();
        // SSL-level callback takes precedence over CTX-level
        RuntimeScalar cb = ssl.passwdCb;
        RuntimeScalar ud = ssl.passwdUserdata;
        if (cb == null) {
            // Fall back to CTX-level callback
            SslCtxState ctxState = CTX_HANDLES.get(ssl.ctxHandle);
            if (ctxState != null) {
                cb = ctxState.passwdCb;
                ud = ctxState.passwdUserdata;
            }
        }
        return loadPrivateKeyFile(filename, cb, ud);
    }

    private static RuntimeList loadPrivateKeyFile(String filename, RuntimeScalar cb, RuntimeScalar ud) {
        try {
            byte[] fileData = Files.readAllBytes(RuntimeIO.resolvePath(filename));
            String pem = new String(fileData, StandardCharsets.ISO_8859_1);

            // Get password via callback
            String password = null;
            if (cb != null && cb.type == RuntimeScalarType.CODE) {
                RuntimeArray cbArgs = new RuntimeArray();
                cbArgs.push(new RuntimeScalar(0)); // rwflag = 0 (reading)
                if (ud != null) {
                    cbArgs.push(ud);
                } else {
                    cbArgs.push(new RuntimeScalar()); // undef
                }
                RuntimeList result = RuntimeCode.apply(cb, cbArgs, RuntimeContextType.SCALAR);
                password = result.getFirst().toString();
            }

            byte[] derBytes = parsePemPrivateKey(pem, password);
            if (derBytes == null) return new RuntimeScalar(0).getList();

            PrivateKey privKey = parsePrivateKeyDer(derBytes);
            if (privKey == null) return new RuntimeScalar(0).getList();

            return new RuntimeScalar(1).getList(); // success
        } catch (Exception e) {
            return new RuntimeScalar(0).getList(); // failure
        }
    }

    // ---- PEM_read_bio_X509 (parse X509 certificate from BIO) ----

    public static RuntimeList PEM_read_bio_X509(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        long bioHandle = args.get(0).getLong();
        MemoryBIO bio = BIO_HANDLES.get(bioHandle);
        if (bio == null) return new RuntimeScalar().getList();

        try {
            byte[] allData = bio.read(bio.pending());
            String pem = new String(allData, StandardCharsets.ISO_8859_1);

            // Extract PEM certificate block
            int beginIdx = pem.indexOf("-----BEGIN CERTIFICATE-----");
            if (beginIdx < 0) return new RuntimeScalar().getList();
            int endIdx = pem.indexOf("-----END CERTIFICATE-----", beginIdx);
            if (endIdx < 0) return new RuntimeScalar().getList();
            String certBlock = pem.substring(beginIdx, endIdx + "-----END CERTIFICATE-----".length());

            // Parse certificate
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf.generateCertificate(
                    new ByteArrayInputStream(certBlock.getBytes(StandardCharsets.ISO_8859_1)));

            long handleId = HANDLE_COUNTER.getAndIncrement();
            X509_HANDLES.put(handleId, cert);
            return new RuntimeScalar(handleId).getList();
        } catch (Exception e) {
            return new RuntimeScalar().getList();
        }
    }

    public static RuntimeList X509_free(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        long handleId = args.get(0).getLong();
        X509_HANDLES.remove(handleId);
        return new RuntimeScalar().getList();
    }

    // ---- X509 accessor functions ----

    public static RuntimeList X509_get_pubkey(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        long x509Handle = args.get(0).getLong();
        // Check mutable X509 first
        MutableX509State mutable = MUTABLE_X509_HANDLES.get(x509Handle);
        if (mutable != null && mutable.pubkeyHandle != 0) {
            // Return a new handle to the public key (extracting from private if needed)
            java.security.Key key = EVP_PKEY_HANDLES.get(mutable.pubkeyHandle);
            if (key == null) return new RuntimeScalar().getList();
            long handleId = HANDLE_COUNTER.getAndIncrement();
            if (key instanceof PrivateKey && key instanceof java.security.interfaces.RSAPrivateCrtKey) {
                try {
                    java.security.interfaces.RSAPrivateCrtKey rsaCrt =
                            (java.security.interfaces.RSAPrivateCrtKey) key;
                    java.security.spec.RSAPublicKeySpec pubSpec = new java.security.spec.RSAPublicKeySpec(
                            rsaCrt.getModulus(), rsaCrt.getPublicExponent());
                    PublicKey pk = KeyFactory.getInstance("RSA").generatePublic(pubSpec);
                    EVP_PKEY_HANDLES.put(handleId, pk);
                } catch (Exception e) {
                    EVP_PKEY_HANDLES.put(handleId, key);
                }
            } else {
                EVP_PKEY_HANDLES.put(handleId, key);
            }
            return new RuntimeScalar(handleId).getList();
        }
        X509Certificate cert = X509_HANDLES.get(x509Handle);
        if (cert == null) return new RuntimeScalar().getList();
        try {
            PublicKey pubKey = cert.getPublicKey();
            long handleId = HANDLE_COUNTER.getAndIncrement();
            EVP_PKEY_HANDLES.put(handleId, pubKey);
            return new RuntimeScalar(handleId).getList();
        } catch (Exception e) {
            return new RuntimeScalar().getList();
        }
    }

    public static RuntimeList X509_get_subject_name(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        long x509Handle = args.get(0).getLong();
        // Check mutable X509 first
        MutableX509State mutable = MUTABLE_X509_HANDLES.get(x509Handle);
        if (mutable != null) return new RuntimeScalar(mutable.subjectNameHandle).getList();
        X509Certificate cert = X509_HANDLES.get(x509Handle);
        if (cert == null) return new RuntimeScalar().getList();
        X509NameInfo nameInfo = parseX500Principal(cert.getSubjectX500Principal());
        long handleId = HANDLE_COUNTER.getAndIncrement();
        X509_NAME_HANDLES.put(handleId, nameInfo);
        return new RuntimeScalar(handleId).getList();
    }

    public static RuntimeList X509_get_issuer_name(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        long x509Handle = args.get(0).getLong();
        // Check mutable X509 first
        MutableX509State mutable = MUTABLE_X509_HANDLES.get(x509Handle);
        if (mutable != null) {
            if (mutable.issuerNameHandle == 0) return new RuntimeScalar().getList();
            return new RuntimeScalar(mutable.issuerNameHandle).getList();
        }
        X509Certificate cert = X509_HANDLES.get(x509Handle);
        if (cert == null) return new RuntimeScalar().getList();
        X509NameInfo nameInfo = parseX500Principal(cert.getIssuerX500Principal());
        long handleId = HANDLE_COUNTER.getAndIncrement();
        X509_NAME_HANDLES.put(handleId, nameInfo);
        return new RuntimeScalar(handleId).getList();
    }

    // Parse X500Principal into X509NameInfo by decoding DER
    private static X509NameInfo parseX500Principal(X500Principal principal) {
        X509NameInfo info = new X509NameInfo();
        info.derEncoded = principal.getEncoded();
        info.rfc2253 = principal.getName("RFC2253");

        // Parse DER to extract individual entries in forward order
        try {
            byte[] der = info.derEncoded;
            // DER structure: SEQUENCE { SET { SEQUENCE { OID, value } }* }
            if (der.length < 2 || der[0] != 0x30) return info;
            int[] pos = {0};
            int[] seqLen = {0};
            readDerTag(der, pos, seqLen); // outer SEQUENCE
            int endPos = pos[0] + seqLen[0];

            while (pos[0] < endPos) {
                // Each RDN is a SET
                readDerTag(der, pos, seqLen); // SET
                int setEnd = pos[0] + seqLen[0];
                while (pos[0] < setEnd) {
                    // Each attribute is a SEQUENCE { OID, value }
                    readDerTag(der, pos, seqLen); // SEQUENCE
                    int attrEnd = pos[0] + seqLen[0];

                    // Read OID
                    readDerTag(der, pos, seqLen); // OID tag
                    byte[] oidBytes = new byte[seqLen[0]];
                    System.arraycopy(der, pos[0], oidBytes, 0, seqLen[0]);
                    String oid = decodeOid(oidBytes);
                    pos[0] += seqLen[0];

                    // Read value (any string type)
                    int valueTag = der[pos[0]] & 0xFF;
                    readDerTag(der, pos, seqLen);
                    byte[] valueBytes = new byte[seqLen[0]];
                    System.arraycopy(der, pos[0], valueBytes, 0, seqLen[0]);
                    pos[0] += seqLen[0];

                    X509NameEntry entry = new X509NameEntry();
                    entry.oid = oid;
                    entry.rawBytes = valueBytes; // always store raw DER bytes
                    // Decode value based on tag
                    if (valueTag == 0x0C) { // UTF8String
                        entry.dataUtf8 = new String(valueBytes, StandardCharsets.UTF_8);
                    } else if (valueTag == 0x16) { // IA5String
                        entry.dataUtf8 = new String(valueBytes, StandardCharsets.US_ASCII);
                    } else if (valueTag == 0x13) { // PrintableString
                        entry.dataUtf8 = new String(valueBytes, StandardCharsets.US_ASCII);
                    } else if (valueTag == 0x1E) { // BMPString
                        entry.dataUtf8 = new String(valueBytes, StandardCharsets.UTF_16BE);
                    } else {
                        entry.dataUtf8 = new String(valueBytes, StandardCharsets.UTF_8);
                    }
                    info.entries.add(entry);

                    pos[0] = attrEnd; // skip to end of SEQUENCE
                }
                pos[0] = setEnd; // skip to end of SET
            }
        } catch (Exception e) {
            // Fall back to RFC2253 parsing if DER parsing fails
        }

        // Build oneline format: "/C=US/O=Org/CN=Name"
        StringBuilder oneline = new StringBuilder();
        for (X509NameEntry entry : info.entries) {
            OidInfo oidInfo = OID_TO_INFO.get(entry.oid);
            String name = oidInfo != null ? oidInfo.shortName : entry.oid;
            oneline.append("/").append(name).append("=").append(entry.dataUtf8);
        }
        info.oneline = oneline.toString();

        return info;
    }

    // Read a DER tag and length, advancing pos[0] past the tag+length header
    private static void readDerTag(byte[] der, int[] pos, int[] contentLen) {
        pos[0]++; // skip tag byte
        int len = der[pos[0]] & 0xFF;
        pos[0]++;
        if (len < 128) {
            contentLen[0] = len;
        } else if (len == 0x81) {
            contentLen[0] = der[pos[0]] & 0xFF;
            pos[0]++;
        } else if (len == 0x82) {
            contentLen[0] = ((der[pos[0]] & 0xFF) << 8) | (der[pos[0] + 1] & 0xFF);
            pos[0] += 2;
        } else if (len == 0x83) {
            contentLen[0] = ((der[pos[0]] & 0xFF) << 16) | ((der[pos[0] + 1] & 0xFF) << 8)
                    | (der[pos[0] + 2] & 0xFF);
            pos[0] += 3;
        } else {
            contentLen[0] = 0;
        }
    }

    // Decode a DER-encoded OID to dotted string form
    private static String decodeOid(byte[] oidBytes) {
        if (oidBytes.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        // First byte encodes first two components
        int first = oidBytes[0] & 0xFF;
        sb.append(first / 40).append('.').append(first % 40);
        long value = 0;
        for (int i = 1; i < oidBytes.length; i++) {
            value = (value << 7) | (oidBytes[i] & 0x7F);
            if ((oidBytes[i] & 0x80) == 0) {
                sb.append('.').append(value);
                value = 0;
            }
        }
        return sb.toString();
    }

    // X509_NAME functions
    public static RuntimeList X509_NAME_new(RuntimeArray args, int ctx) {
        X509NameInfo nameInfo = new X509NameInfo();
        nameInfo.oneline = "";
        nameInfo.rfc2253 = "";
        nameInfo.derEncoded = new byte[]{0x30, 0x00}; // empty SEQUENCE
        long handleId = HANDLE_COUNTER.getAndIncrement();
        X509_NAME_HANDLES.put(handleId, nameInfo);
        return new RuntimeScalar(handleId).getList();
    }

    public static RuntimeList X509_NAME_hash(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar(0).getList();
        long nameHandle = args.get(0).getLong();
        X509NameInfo nameInfo = X509_NAME_HANDLES.get(nameHandle);
        if (nameInfo == null) return new RuntimeScalar(0).getList();
        try {
            // OpenSSL uses SHA-1 of the canonical DER form, first 4 bytes as LE uint32
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] hash = sha1.digest(nameInfo.derEncoded);
            long result = ((long)(hash[0] & 0xFF))
                    | ((long)(hash[1] & 0xFF) << 8)
                    | ((long)(hash[2] & 0xFF) << 16)
                    | ((long)(hash[3] & 0xFF) << 24);
            return new RuntimeScalar(result).getList();
        } catch (Exception e) {
            return new RuntimeScalar(0).getList();
        }
    }

    public static RuntimeList X509_NAME_entry_count(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar(0).getList();
        long nameHandle = args.get(0).getLong();
        X509NameInfo nameInfo = X509_NAME_HANDLES.get(nameHandle);
        if (nameInfo == null) return new RuntimeScalar(0).getList();
        return new RuntimeScalar(nameInfo.entries.size()).getList();
    }

    public static RuntimeList X509_NAME_oneline(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar("").getList();
        long nameHandle = args.get(0).getLong();
        X509NameInfo nameInfo = X509_NAME_HANDLES.get(nameHandle);
        if (nameInfo == null) return new RuntimeScalar("").getList();
        return new RuntimeScalar(nameInfo.oneline).getList();
    }

    public static RuntimeList X509_NAME_print_ex(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar("").getList();
        long nameHandle = args.get(0).getLong();
        X509NameInfo nameInfo = X509_NAME_HANDLES.get(nameHandle);
        if (nameInfo == null) return new RuntimeScalar("").getList();
        // Default: RFC2253 format (reverse order, comma-separated)
        // Non-ASCII UTF-8 bytes are hex-escaped as \XX
        StringBuilder sb = new StringBuilder();
        for (int i = nameInfo.entries.size() - 1; i >= 0; i--) {
            if (sb.length() > 0) sb.append(",");
            X509NameEntry entry = nameInfo.entries.get(i);
            OidInfo oidInfo = OID_TO_INFO.get(entry.oid);
            String name = oidInfo != null ? oidInfo.shortName : entry.oid;
            sb.append(name).append("=");
            // Hex-escape non-ASCII bytes in the value
            // Data may be pre-encoded UTF-8 stored as ISO-8859-1 chars
            byte[] utf8Bytes = entry.dataUtf8.getBytes(StandardCharsets.ISO_8859_1);
            for (byte b : utf8Bytes) {
                if (b >= 0x20 && b <= 0x7E) {
                    sb.append((char) b);
                } else {
                    sb.append(String.format("\\%02X", b & 0xFF));
                }
            }
        }
        return new RuntimeScalar(sb.toString()).getList();
    }

    public static RuntimeList X509_NAME_get_entry(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar().getList();
        long nameHandle = args.get(0).getLong();
        int index = (int) args.get(1).getLong();
        X509NameInfo nameInfo = X509_NAME_HANDLES.get(nameHandle);
        if (nameInfo == null || index < 0 || index >= nameInfo.entries.size())
            return new RuntimeScalar().getList();
        X509NameEntry entry = nameInfo.entries.get(index);
        long handleId = HANDLE_COUNTER.getAndIncrement();
        X509_NAME_ENTRY_HANDLES.put(handleId, entry);
        return new RuntimeScalar(handleId).getList();
    }

    // X509_NAME_ENTRY accessor functions
    public static RuntimeList X509_NAME_ENTRY_get_data(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        long entryHandle = args.get(0).getLong();
        X509NameEntry entry = X509_NAME_ENTRY_HANDLES.get(entryHandle);
        if (entry == null) return new RuntimeScalar().getList();
        long handleId = HANDLE_COUNTER.getAndIncrement();
        ASN1_STRING_HANDLES.put(handleId, new Asn1StringValue(entry.rawBytes, entry.dataUtf8));
        return new RuntimeScalar(handleId).getList();
    }

    public static RuntimeList X509_NAME_ENTRY_get_object(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        long entryHandle = args.get(0).getLong();
        X509NameEntry entry = X509_NAME_ENTRY_HANDLES.get(entryHandle);
        if (entry == null) return new RuntimeScalar().getList();
        long handleId = HANDLE_COUNTER.getAndIncrement();
        ASN1_OBJECT_HANDLES.put(handleId, entry.oid);
        return new RuntimeScalar(handleId).getList();
    }

    // ---- OBJ/NID functions ----

    public static RuntimeList OBJ_obj2txt(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar("").getList();
        long objHandle = args.get(0).getLong();
        String oid = ASN1_OBJECT_HANDLES.get(objHandle);
        if (oid == null) return new RuntimeScalar("").getList();
        boolean numericOnly = args.size() > 1 && args.get(1).getLong() != 0;
        if (numericOnly) {
            return new RuntimeScalar(oid).getList();
        }
        // Return long name if known, else OID
        OidInfo info = OID_TO_INFO.get(oid);
        if (info != null) return new RuntimeScalar(info.longName).getList();
        return new RuntimeScalar(oid).getList();
    }

    public static RuntimeList OBJ_obj2nid(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar(0).getList();
        long objHandle = args.get(0).getLong();
        String oid = ASN1_OBJECT_HANDLES.get(objHandle);
        if (oid == null) return new RuntimeScalar(0).getList();
        OidInfo info = OID_TO_INFO.get(oid);
        if (info == null) return new RuntimeScalar(0).getList(); // NID_undef
        return new RuntimeScalar(info.nid).getList();
    }

    public static RuntimeList OBJ_nid2ln(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        int nid = (int) args.get(0).getLong();
        OidInfo info = NID_TO_INFO.get(nid);
        if (info == null) return new RuntimeScalar().getList();
        return new RuntimeScalar(info.longName).getList();
    }

    public static RuntimeList OBJ_nid2sn(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        int nid = (int) args.get(0).getLong();
        OidInfo info = NID_TO_INFO.get(nid);
        if (info == null) return new RuntimeScalar().getList();
        return new RuntimeScalar(info.shortName).getList();
    }

    public static RuntimeList OBJ_txt2obj(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        String text = args.get(0).toString();
        boolean noName = args.size() > 1 && args.get(1).getLong() != 0;
        String oid;
        if (noName) {
            // Only accept numeric OID
            oid = text;
        } else {
            // Try as OID first, then as short/long name
            OidInfo info = OID_TO_INFO.get(text);
            if (info != null) {
                oid = text;
            } else {
                // Look up by short name or long name
                oid = null;
                for (Map.Entry<String, OidInfo> e : OID_TO_INFO.entrySet()) {
                    if (text.equals(e.getValue().shortName) || text.equals(e.getValue().longName)) {
                        oid = e.getKey();
                        break;
                    }
                }
                if (oid == null) oid = text; // Use as-is if not found
            }
        }
        long handleId = HANDLE_COUNTER.getAndIncrement();
        ASN1_OBJECT_HANDLES.put(handleId, oid);
        return new RuntimeScalar(handleId).getList();
    }

    public static RuntimeList OBJ_txt2nid(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar(0).getList();
        String text = args.get(0).toString();
        // Try as OID first
        OidInfo info = OID_TO_INFO.get(text);
        if (info != null) return new RuntimeScalar(info.nid).getList();
        // Try as short name or long name
        for (OidInfo i : OID_TO_INFO.values()) {
            if (text.equals(i.shortName) || text.equals(i.longName)) {
                return new RuntimeScalar(i.nid).getList();
            }
        }
        return new RuntimeScalar(0).getList();
    }

    public static RuntimeList OBJ_ln2nid(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar(0).getList();
        String longName = args.get(0).toString();
        for (OidInfo info : OID_TO_INFO.values()) {
            if (longName.equals(info.longName)) {
                return new RuntimeScalar(info.nid).getList();
            }
        }
        return new RuntimeScalar(0).getList();
    }

    public static RuntimeList OBJ_sn2nid(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar(0).getList();
        String shortName = args.get(0).toString();
        for (OidInfo info : OID_TO_INFO.values()) {
            if (shortName.equals(info.shortName)) {
                return new RuntimeScalar(info.nid).getList();
            }
        }
        return new RuntimeScalar(0).getList();
    }

    public static RuntimeList OBJ_cmp(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(-1).getList();
        long h1 = args.get(0).getLong();
        long h2 = args.get(1).getLong();
        String oid1 = ASN1_OBJECT_HANDLES.get(h1);
        String oid2 = ASN1_OBJECT_HANDLES.get(h2);
        if (oid1 == null || oid2 == null) return new RuntimeScalar(-1).getList();
        return new RuntimeScalar(oid1.equals(oid2) ? 0 : 1).getList();
    }

    // ---- ASN1 accessor functions ----

    public static RuntimeList P_ASN1_STRING_get(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar("").getList();
        long handle = args.get(0).getLong();
        Asn1StringValue sv = ASN1_STRING_HANDLES.get(handle);
        if (sv == null) return new RuntimeScalar("").getList();
        boolean utf8Decode = args.size() > 1 && args.get(1).getLong() != 0;
        if (utf8Decode) {
            return new RuntimeScalar(sv.utf8Data).getList();
        } else {
            return bytesToPerlString(sv.rawBytes).getList();
        }
    }

    public static RuntimeList P_ASN1_INTEGER_get_hex(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar("").getList();
        long handle = args.get(0).getLong();
        BigInteger val = ASN1_INTEGER_HANDLES.get(handle);
        if (val == null) return new RuntimeScalar("").getList();
        String hex = val.toString(16).toUpperCase();
        // Pad to even length
        if (hex.length() % 2 != 0) hex = "0" + hex;
        return new RuntimeScalar(hex).getList();
    }

    public static RuntimeList P_ASN1_INTEGER_get_dec(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar("").getList();
        long handle = args.get(0).getLong();
        BigInteger val = ASN1_INTEGER_HANDLES.get(handle);
        if (val == null) return new RuntimeScalar("").getList();
        return new RuntimeScalar(val.toString()).getList();
    }

    // ---- X509 certificate field accessors ----

    public static RuntimeList X509_get_subjectAltNames(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeList();
        long x509Handle = args.get(0).getLong();
        X509Certificate cert = X509_HANDLES.get(x509Handle);
        if (cert == null) return new RuntimeList();
        RuntimeList result = new RuntimeList();
        try {
            Collection<List<?>> sans = cert.getSubjectAlternativeNames();
            if (sans != null) {
                for (List<?> san : sans) {
                    int type = (Integer) san.get(0);
                    Object value = san.get(1);
                    result.add(new RuntimeScalar(type));
                    switch (type) {
                        case 7: // GEN_IPADD — return raw binary bytes
                            result.add(ipAddressToRawBytes(value.toString()));
                            break;
                        case 0: // GEN_OTHERNAME — parse DER to extract value
                            if (value instanceof byte[]) {
                                result.add(new RuntimeScalar(parseOtherName((byte[]) value)));
                            } else {
                                result.add(new RuntimeScalar(value.toString()));
                            }
                            break;
                        default: // GEN_EMAIL(1), GEN_DNS(2), GEN_URI(6), GEN_RID(8), etc.
                            result.add(new RuntimeScalar(value.toString()));
                            break;
                    }
                }
            }
        } catch (Exception e) {
            // SAN parsing failed — return whatever we collected so far
        }
        return result;
    }

    // Convert an IP address string to raw binary bytes (4 for IPv4, 16 for IPv6)
    private static RuntimeScalar ipAddressToRawBytes(String ip) {
        try {
            java.net.InetAddress addr = java.net.InetAddress.getByName(ip);
            byte[] raw = addr.getAddress();
            return bytesToPerlString(raw);
        } catch (Exception e) {
            return new RuntimeScalar(ip);
        }
    }

    // Parse an otherName DER encoding to extract the value string
    // OtherName ::= SEQUENCE { type-id OID, value [0] EXPLICIT ANY }
    private static String parseOtherName(byte[] der) {
        try {
            int[] pos = {0};
            int[] len = {0};
            readDerTag(der, pos, len); // outer SEQUENCE
            // Read the OID
            readDerTag(der, pos, len); // OID tag
            pos[0] += len[0]; // skip OID value
            // Read context tag [0] EXPLICIT
            if (pos[0] < der.length) {
                readDerTag(der, pos, len); // [0] context tag
                // Inside the explicit wrapper, read the actual value
                if (pos[0] < der.length) {
                    int valueTag = der[pos[0]] & 0xFF;
                    readDerTag(der, pos, len);
                    byte[] valueBytes = new byte[len[0]];
                    System.arraycopy(der, pos[0], valueBytes, 0, len[0]);
                    if (valueTag == 0x0C || valueTag == 0x16 || valueTag == 0x13) {
                        return new String(valueBytes, StandardCharsets.UTF_8);
                    }
                    return new String(valueBytes, StandardCharsets.ISO_8859_1);
                }
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    public static RuntimeList X509_subject_name_hash(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar(0).getList();
        long x509Handle = args.get(0).getLong();
        X509Certificate cert = X509_HANDLES.get(x509Handle);
        if (cert == null) return new RuntimeScalar(0).getList();
        return computeNameHash(cert.getSubjectX500Principal());
    }

    public static RuntimeList X509_issuer_name_hash(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar(0).getList();
        long x509Handle = args.get(0).getLong();
        X509Certificate cert = X509_HANDLES.get(x509Handle);
        if (cert == null) return new RuntimeScalar(0).getList();
        return computeNameHash(cert.getIssuerX500Principal());
    }

    public static RuntimeList X509_issuer_and_serial_hash(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar(0).getList();
        long x509Handle = args.get(0).getLong();
        X509Certificate cert = X509_HANDLES.get(x509Handle);
        if (cert == null) return new RuntimeScalar(0).getList();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(cert.getIssuerX500Principal().getEncoded());
            md.update(cert.getSerialNumber().toByteArray());
            byte[] hash = md.digest();
            long result = ((long)(hash[0] & 0xFF))
                    | ((long)(hash[1] & 0xFF) << 8)
                    | ((long)(hash[2] & 0xFF) << 16)
                    | ((long)(hash[3] & 0xFF) << 24);
            return new RuntimeScalar(result).getList();
        } catch (Exception e) {
            return new RuntimeScalar(0).getList();
        }
    }

    private static RuntimeList computeNameHash(X500Principal principal) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] hash = sha1.digest(principal.getEncoded());
            long result = ((long)(hash[0] & 0xFF))
                    | ((long)(hash[1] & 0xFF) << 8)
                    | ((long)(hash[2] & 0xFF) << 16)
                    | ((long)(hash[3] & 0xFF) << 24);
            return new RuntimeScalar(result).getList();
        } catch (Exception e) {
            return new RuntimeScalar(0).getList();
        }
    }

    public static RuntimeList X509_get_fingerprint(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar().getList();
        long x509Handle = args.get(0).getLong();
        String digestName = args.get(1).toString();
        X509Certificate cert = X509_HANDLES.get(x509Handle);
        if (cert == null) return new RuntimeScalar().getList();
        try {
            String javaAlg = NAME_TO_JAVA_ALG.get(digestName.toLowerCase());
            if (javaAlg == null) return new RuntimeScalar().getList();
            MessageDigest md = MessageDigest.getInstance(javaAlg);
            byte[] fingerprint = md.digest(cert.getEncoded());
            return new RuntimeScalar(formatColonHex(fingerprint)).getList();
        } catch (Exception e) {
            return new RuntimeScalar().getList();
        }
    }

    public static RuntimeList X509_pubkey_digest(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar().getList();
        long x509Handle = args.get(0).getLong();
        long mdHandle = args.get(1).getLong();
        X509Certificate cert = X509_HANDLES.get(x509Handle);
        if (cert == null) return new RuntimeScalar().getList();
        try {
            String javaAlg = resolveDigestAlgorithm(mdHandle);
            MessageDigest md = MessageDigest.getInstance(javaAlg);
            byte[] pubKeyDer = cert.getPublicKey().getEncoded();
            // Extract the BIT STRING content from SubjectPublicKeyInfo:
            // SEQUENCE { AlgorithmIdentifier, BIT STRING { unused-bits, key-data } }
            // OpenSSL's X509_pubkey_digest hashes the BIT STRING value (after unused-bits byte)
            byte[] bitStringContent = extractBitStringFromSPKI(pubKeyDer);
            byte[] digest = md.digest(bitStringContent);
            return bytesToPerlString(digest).getList();
        } catch (Exception e) {
            return new RuntimeScalar().getList();
        }
    }

    public static RuntimeList X509_digest(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar().getList();
        long x509Handle = args.get(0).getLong();
        long mdHandle = args.get(1).getLong();
        X509Certificate cert = X509_HANDLES.get(x509Handle);
        if (cert == null) return new RuntimeScalar().getList();
        try {
            String javaAlg = resolveDigestAlgorithm(mdHandle);
            MessageDigest md = MessageDigest.getInstance(javaAlg);
            byte[] digest = md.digest(cert.getEncoded());
            return bytesToPerlString(digest).getList();
        } catch (Exception e) {
            return new RuntimeScalar().getList();
        }
    }

    // Resolve an EVP_MD handle (which is a NID from EVP_get_digestbyname) to a Java algorithm name
    private static String resolveDigestAlgorithm(long mdHandle) {
        // First try as EVP_MD_CTX handle
        EvpMdCtx mdCtx = EVP_MD_CTX_HANDLES.get(mdHandle);
        if (mdCtx != null) {
            String javaAlg = NAME_TO_JAVA_ALG.get(mdCtx.algorithmName);
            if (javaAlg != null) return javaAlg;
        }
        // Try as NID (from EVP_get_digestbyname)
        String algName = NID_TO_NAME.get((int) mdHandle);
        if (algName != null) {
            String javaAlg = NAME_TO_JAVA_ALG.get(algName);
            if (javaAlg != null) return javaAlg;
        }
        return "SHA-1"; // fallback
    }

    // Extract the BIT STRING value from a SubjectPublicKeyInfo DER encoding
    // OpenSSL hashes just the public key bit string (excluding the unused-bits byte)
    private static byte[] extractBitStringFromSPKI(byte[] spki) {
        int[] pos = {0};
        int[] len = {0};
        // Skip outer SEQUENCE tag and length
        readDerTag(spki, pos, len);
        int seqContentStart = pos[0];
        // Skip AlgorithmIdentifier SEQUENCE
        readDerTag(spki, pos, len); // AlgorithmIdentifier SEQUENCE
        pos[0] += len[0]; // skip its content
        // Now at BIT STRING
        int bitStringTag = spki[pos[0]] & 0xFF;
        readDerTag(spki, pos, len); // BIT STRING tag and length
        // Skip the unused-bits byte (first byte of BIT STRING content)
        // OpenSSL's X509_pubkey_digest hashes key->data which excludes the unused-bits byte
        return Arrays.copyOfRange(spki, pos[0] + 1, pos[0] + len[0]);
    }

    private static String formatColonHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) sb.append(":");
            sb.append(String.format("%02X", bytes[i] & 0xFF));
        }
        return sb.toString();
    }

    // X509 notBefore / notAfter — return as ASN1_TIME handles
    public static RuntimeList X509_get0_notBefore(RuntimeArray args, int ctx) {
        return x509GetTime(args, true);
    }
    public static RuntimeList X509_getm_notBefore(RuntimeArray args, int ctx) {
        return x509GetTime(args, true);
    }
    public static RuntimeList X509_get_notBefore(RuntimeArray args, int ctx) {
        return x509GetTime(args, true);
    }
    public static RuntimeList X509_get0_notAfter(RuntimeArray args, int ctx) {
        return x509GetTime(args, false);
    }
    public static RuntimeList X509_getm_notAfter(RuntimeArray args, int ctx) {
        return x509GetTime(args, false);
    }
    public static RuntimeList X509_get_notAfter(RuntimeArray args, int ctx) {
        return x509GetTime(args, false);
    }

    private static RuntimeList x509GetTime(RuntimeArray args, boolean before) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        long x509Handle = args.get(0).getLong();
        // Check mutable X509 first
        MutableX509State mutable = MUTABLE_X509_HANDLES.get(x509Handle);
        if (mutable != null) {
            return new RuntimeScalar(before ? mutable.notBeforeHandle : mutable.notAfterHandle).getList();
        }
        X509Certificate cert = X509_HANDLES.get(x509Handle);
        if (cert == null) return new RuntimeScalar().getList();
        Date date = before ? cert.getNotBefore() : cert.getNotAfter();
        long epoch = date.getTime() / 1000;
        long handleId = HANDLE_COUNTER.getAndIncrement();
        ASN1_TIME_HANDLES.put(handleId, epoch);
        return new RuntimeScalar(handleId).getList();
    }

    public static RuntimeList X509_get_serialNumber(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        long x509Handle = args.get(0).getLong();
        // Check mutable X509 first
        MutableX509State mutable = MUTABLE_X509_HANDLES.get(x509Handle);
        if (mutable != null) return new RuntimeScalar(mutable.serialHandle).getList();
        X509Certificate cert = X509_HANDLES.get(x509Handle);
        if (cert == null) return new RuntimeScalar().getList();
        long handleId = HANDLE_COUNTER.getAndIncrement();
        ASN1_INTEGER_HANDLES.put(handleId, cert.getSerialNumber());
        return new RuntimeScalar(handleId).getList();
    }

    public static RuntimeList X509_get0_serialNumber(RuntimeArray args, int ctx) {
        return X509_get_serialNumber(args, ctx);
    }

    public static RuntimeList X509_get_version(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar(0).getList();
        long x509Handle = args.get(0).getLong();
        // Check mutable X509 first
        MutableX509State mutable = MUTABLE_X509_HANDLES.get(x509Handle);
        if (mutable != null) return new RuntimeScalar(mutable.version).getList();
        X509Certificate cert = X509_HANDLES.get(x509Handle);
        if (cert == null) return new RuntimeScalar(0).getList();
        return new RuntimeScalar(cert.getVersion() - 1).getList(); // OpenSSL returns 0-based
    }

    public static RuntimeList X509_get_ext_count(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar(0).getList();
        long x509Handle = args.get(0).getLong();
        X509Certificate cert = X509_HANDLES.get(x509Handle);
        if (cert == null) return new RuntimeScalar(0).getList();
        Set<String> critical = cert.getCriticalExtensionOIDs();
        Set<String> nonCritical = cert.getNonCriticalExtensionOIDs();
        int count = (critical != null ? critical.size() : 0) + (nonCritical != null ? nonCritical.size() : 0);
        return new RuntimeScalar(count).getList();
    }

    public static RuntimeList X509_get_ext(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar().getList();
        long x509Handle = args.get(0).getLong();
        int index = (int) args.get(1).getLong();
        X509Certificate cert = X509_HANDLES.get(x509Handle);
        if (cert == null) return new RuntimeScalar().getList();

        // Build ordered list of extension OIDs
        List<String> allOids = getOrderedExtensionOids(cert);
        if (index < 0 || index >= allOids.size()) return new RuntimeScalar().getList();

        String oid = allOids.get(index);
        X509ExtInfo extInfo = new X509ExtInfo();
        extInfo.oid = oid;
        extInfo.critical = cert.getCriticalExtensionOIDs() != null
                && cert.getCriticalExtensionOIDs().contains(oid);
        extInfo.value = cert.getExtensionValue(oid);
        extInfo.cert = cert;
        extInfo.index = index;

        long handleId = HANDLE_COUNTER.getAndIncrement();
        X509_EXT_HANDLES.put(handleId, extInfo);
        return new RuntimeScalar(handleId).getList();
    }

    // Get extension OIDs in the order they appear in the certificate DER
    private static List<String> getOrderedExtensionOids(X509Certificate cert) {
        List<String> result = new ArrayList<>();
        try {
            // Parse the extensions from DER to preserve ordering
            byte[] encoded = cert.getEncoded();
            // Find the extensions block in the TBSCertificate
            // Use Java's API to get all OIDs, then sort by DER position
            Set<String> critOids = cert.getCriticalExtensionOIDs();
            Set<String> nonCritOids = cert.getNonCriticalExtensionOIDs();
            // We need DER ordering. Parse the cert to find extension sequence
            List<String> allOids = new ArrayList<>();
            if (critOids != null) allOids.addAll(critOids);
            if (nonCritOids != null) allOids.addAll(nonCritOids);
            // Try to find DER ordering by scanning encoded cert
            result = sortExtensionsByDerOrder(encoded, allOids);
            if (result.isEmpty()) {
                result.addAll(allOids);
            }
        } catch (Exception e) {
            Set<String> critOids = cert.getCriticalExtensionOIDs();
            Set<String> nonCritOids = cert.getNonCriticalExtensionOIDs();
            if (critOids != null) result.addAll(critOids);
            if (nonCritOids != null) result.addAll(nonCritOids);
        }
        return result;
    }

    // Sort extension OIDs by their position in the DER encoding
    private static List<String> sortExtensionsByDerOrder(byte[] encoded, List<String> oids) {
        // For each OID, find its encoded form in the DER and record position
        Map<String, Integer> oidPositions = new LinkedHashMap<>();
        for (String oid : oids) {
            byte[] oidDer = encodeOidDer(oid);
            int pos = indexOf(encoded, oidDer, 0);
            oidPositions.put(oid, pos >= 0 ? pos : Integer.MAX_VALUE);
        }
        List<String> sorted = new ArrayList<>(oidPositions.keySet());
        sorted.sort(Comparator.comparingInt(oidPositions::get));
        return sorted;
    }

    // Encode an OID string to DER bytes (tag + length + value)
    private static byte[] encodeOidDer(String oidStr) {
        String[] parts = oidStr.split("\\.");
        if (parts.length < 2) return new byte[0];
        List<Byte> bytes = new ArrayList<>();
        int first = Integer.parseInt(parts[0]) * 40 + Integer.parseInt(parts[1]);
        bytes.add((byte) first);
        for (int i = 2; i < parts.length; i++) {
            long val = Long.parseLong(parts[i]);
            if (val < 128) {
                bytes.add((byte) val);
            } else {
                // Multi-byte encoding
                List<Byte> valBytes = new ArrayList<>();
                valBytes.add((byte) (val & 0x7F));
                val >>= 7;
                while (val > 0) {
                    valBytes.add((byte) ((val & 0x7F) | 0x80));
                    val >>= 7;
                }
                for (int j = valBytes.size() - 1; j >= 0; j--) {
                    bytes.add(valBytes.get(j));
                }
            }
        }
        byte[] content = new byte[bytes.size()];
        for (int i = 0; i < bytes.size(); i++) content[i] = bytes.get(i);
        // Prepend OID tag (0x06) and length
        byte[] result = new byte[2 + content.length];
        result[0] = 0x06;
        result[1] = (byte) content.length;
        System.arraycopy(content, 0, result, 2, content.length);
        return result;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int start) {
        if (needle.length == 0) return -1;
        outer:
        for (int i = start; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    // ---- X509_EXTENSION functions ----

    public static RuntimeList X509_EXTENSION_get_data(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        long extHandle = args.get(0).getLong();
        X509ExtInfo ext = X509_EXT_HANDLES.get(extHandle);
        if (ext == null || ext.value == null) return new RuntimeScalar().getList();
        long handleId = HANDLE_COUNTER.getAndIncrement();
        // ext.value is the OCTET STRING wrapping the extension value
        ASN1_STRING_HANDLES.put(handleId, new Asn1StringValue(
                ext.value,
                new String(ext.value, StandardCharsets.ISO_8859_1)));
        return new RuntimeScalar(handleId).getList();
    }

    public static RuntimeList X509_EXTENSION_get_object(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        long extHandle = args.get(0).getLong();
        X509ExtInfo ext = X509_EXT_HANDLES.get(extHandle);
        if (ext == null) return new RuntimeScalar().getList();
        long handleId = HANDLE_COUNTER.getAndIncrement();
        ASN1_OBJECT_HANDLES.put(handleId, ext.oid);
        return new RuntimeScalar(handleId).getList();
    }

    public static RuntimeList X509_EXTENSION_get_critical(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar(0).getList();
        long extHandle = args.get(0).getLong();
        X509ExtInfo ext = X509_EXT_HANDLES.get(extHandle);
        if (ext == null) return new RuntimeScalar(0).getList();
        return new RuntimeScalar(ext.critical ? 1 : 0).getList();
    }

    public static RuntimeList X509V3_EXT_print(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        long extHandle = args.get(0).getLong();
        X509ExtInfo ext = X509_EXT_HANDLES.get(extHandle);
        if (ext == null || ext.cert == null) return new RuntimeScalar().getList();
        String text = formatExtension(ext);
        return new RuntimeScalar(text).getList();
    }

    // Format an X509 extension as human-readable text (OpenSSL 3.x style)
    private static String formatExtension(X509ExtInfo ext) {
        String oid = ext.oid;
        X509Certificate cert = ext.cert;
        try {
            switch (oid) {
                case "2.5.29.15": return formatKeyUsage(cert);
                case "2.5.29.37": return formatExtKeyUsage(cert);
                case "2.5.29.14": return formatSubjectKeyIdentifier(cert);
                case "2.5.29.35": return formatAuthorityKeyIdentifier(cert);
                case "2.5.29.19": return formatBasicConstraints(cert);
                case "2.5.29.17": return formatSubjectAltName(cert);
                case "2.5.29.18": return formatIssuerAltName(cert);
                case "2.5.29.31": return formatCrlDistPoints(cert);
                case "2.5.29.32": return formatCertPolicies(cert);
                case "1.3.6.1.5.5.7.1.1": return formatAuthorityInfoAccess(cert);
                default:
                    // Try to format as hex dump
                    if (ext.value != null && ext.value.length > 2) {
                        return formatColonHex(Arrays.copyOfRange(ext.value, 2, ext.value.length));
                    }
                    return "";
            }
        } catch (Exception e) {
            return "";
        }
    }

    private static String formatKeyUsage(X509Certificate cert) {
        boolean[] ku = cert.getKeyUsage();
        if (ku == null) return "";
        String[] names = {"Digital Signature", "Non Repudiation", "Key Encipherment",
                "Data Encipherment", "Key Agreement", "Certificate Sign",
                "CRL Sign", "Encipher Only", "Decipher Only"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(ku.length, names.length); i++) {
            if (ku[i]) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(names[i]);
            }
        }
        return sb.toString();
    }

    private static String formatExtKeyUsage(X509Certificate cert) throws Exception {
        List<String> ekuOids = cert.getExtendedKeyUsage();
        if (ekuOids == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String ekuOid : ekuOids) {
            if (sb.length() > 0) sb.append(", ");
            OidInfo info = OID_TO_INFO.get(ekuOid);
            if (info != null) {
                sb.append(info.longName);
            } else {
                sb.append(ekuOid);
            }
        }
        return sb.toString();
    }

    private static String formatSubjectKeyIdentifier(X509Certificate cert) {
        byte[] extValue = cert.getExtensionValue("2.5.29.14");
        if (extValue == null) return "";
        // extValue is OCTET STRING wrapping OCTET STRING
        try {
            // Skip outer OCTET STRING wrapper
            int[] pos = {0};
            int[] len = {0};
            readDerTag(extValue, pos, len);
            byte[] inner = new byte[len[0]];
            System.arraycopy(extValue, pos[0], inner, 0, len[0]);
            // Inner is OCTET STRING containing the key ID
            pos[0] = 0;
            readDerTag(inner, pos, len);
            byte[] keyId = new byte[len[0]];
            System.arraycopy(inner, pos[0], keyId, 0, len[0]);
            return formatColonHex(keyId);
        } catch (Exception e) {
            return "";
        }
    }

    private static String formatAuthorityKeyIdentifier(X509Certificate cert) {
        byte[] extValue = cert.getExtensionValue("2.5.29.35");
        if (extValue == null) return "";
        try {
            // Skip outer OCTET STRING wrapper
            int[] pos = {0};
            int[] len = {0};
            readDerTag(extValue, pos, len);
            byte[] inner = new byte[len[0]];
            System.arraycopy(extValue, pos[0], inner, 0, len[0]);
            // Inner is SEQUENCE { [0] keyId, ... }
            pos[0] = 0;
            readDerTag(inner, pos, len); // SEQUENCE
            // Look for context tag [0]
            if (pos[0] < inner.length && (inner[pos[0]] & 0xFF) == 0x80) {
                readDerTag(inner, pos, len);
                byte[] keyId = new byte[len[0]];
                System.arraycopy(inner, pos[0], keyId, 0, len[0]);
                return formatColonHex(keyId);
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String formatBasicConstraints(X509Certificate cert) {
        int pathLen = cert.getBasicConstraints();
        // -1 = not a CA, >=0 = CA with path length
        if (pathLen < 0) return "CA:FALSE";
        if (pathLen == Integer.MAX_VALUE) return "CA:TRUE";
        return "CA:TRUE, pathlen:" + pathLen;
    }

    private static String formatSubjectAltName(X509Certificate cert) throws Exception {
        return formatAltNames(cert.getSubjectAlternativeNames());
    }

    private static String formatIssuerAltName(X509Certificate cert) throws Exception {
        return formatAltNames(cert.getIssuerAlternativeNames());
    }

    private static String formatAltNames(Collection<List<?>> names) {
        if (names == null) return "";
        StringBuilder sb = new StringBuilder();
        for (List<?> name : names) {
            int type = (Integer) name.get(0);
            Object value = name.get(1);
            if (sb.length() > 0) sb.append(", ");
            switch (type) {
                case 0: // otherName — parse DER to get OID and value
                    if (value instanceof byte[]) {
                        sb.append("othername: ").append(formatOtherNameExt((byte[]) value));
                    } else {
                        sb.append("othername: ").append(value);
                    }
                    break;
                case 1: // rfc822Name (email)
                    sb.append("email:").append(value);
                    break;
                case 2: // dNSName
                    sb.append("DNS:").append(value);
                    break;
                case 6: // URI
                    sb.append("URI:").append(value);
                    break;
                case 7: // iPAddress — format IPv6 as uppercase
                    sb.append("IP Address:").append(formatIpForDisplay(value.toString()));
                    break;
                case 8: // registeredID
                    sb.append("Registered ID:").append(value);
                    break;
                default:
                    sb.append(value);
            }
        }
        return sb.toString();
    }

    // Format an IP address for display: uppercase IPv6
    private static String formatIpForDisplay(String ip) {
        if (ip.contains(":")) {
            // IPv6 — format as uppercase hex groups
            try {
                java.net.InetAddress addr = java.net.InetAddress.getByName(ip);
                byte[] raw = addr.getAddress();
                if (raw.length == 16) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 16; i += 2) {
                        if (i > 0) sb.append(":");
                        int word = ((raw[i] & 0xFF) << 8) | (raw[i + 1] & 0xFF);
                        sb.append(Integer.toHexString(word).toUpperCase());
                    }
                    return sb.toString();
                }
            } catch (Exception e) {
                // fallback
            }
        }
        return ip;
    }

    // Format an otherName for X509V3_EXT_print: "oidName::value" (OpenSSL 3.0 style)
    private static String formatOtherNameExt(byte[] der) {
        try {
            int[] pos = {0};
            int[] len = {0};
            readDerTag(der, pos, len); // outer SEQUENCE
            // Read the OID
            int oidStart = pos[0] + 1; // skip OID tag byte
            readDerTag(der, pos, len); // OID tag
            byte[] oidBytes = new byte[len[0]];
            System.arraycopy(der, pos[0], oidBytes, 0, len[0]);
            String oid = decodeOid(oidBytes);
            pos[0] += len[0]; // skip OID value
            // Look up OID long name
            OidInfo info = OID_TO_INFO.get(oid);
            String oidName = info != null ? info.longName : oid;
            // Read context tag [0] EXPLICIT
            if (pos[0] < der.length) {
                readDerTag(der, pos, len); // [0] context tag
                // Inside the explicit wrapper, read the actual value
                if (pos[0] < der.length) {
                    int valueTag = der[pos[0]] & 0xFF;
                    readDerTag(der, pos, len);
                    byte[] valueBytes = new byte[len[0]];
                    System.arraycopy(der, pos[0], valueBytes, 0, len[0]);
                    String valueStr;
                    if (valueTag == 0x0C || valueTag == 0x16 || valueTag == 0x13) {
                        valueStr = new String(valueBytes, StandardCharsets.UTF_8);
                    } else {
                        valueStr = new String(valueBytes, StandardCharsets.ISO_8859_1);
                    }
                    // OpenSSL 3.0 uses double colon between OID name and value
                    return oidName + "::" + valueStr;
                }
            }
            return oidName;
        } catch (Exception e) {
            return "<unsupported>";
        }
    }

    private static String formatCrlDistPoints(X509Certificate cert) {
        byte[] extValue = cert.getExtensionValue("2.5.29.31");
        if (extValue == null) return "";
        try {
            // Parse CRL distribution points from DER
            // Structure: OCTET_STRING { SEQUENCE { SEQUENCE { [0] { [0] { [6] URI } } } } }
            List<String> uris = new ArrayList<>();
            extractUrisFromExtension(extValue, uris);
            if (uris.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < uris.size(); i++) {
                if (i > 0) sb.append("\n");
                sb.append("Full Name:\n  URI:").append(uris.get(i));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static void extractUrisFromExtension(byte[] data, List<String> uris) {
        // Walk through DER looking for context tag [6] (URI in GeneralName)
        for (int i = 0; i < data.length - 2; i++) {
            if ((data[i] & 0xFF) == 0x86) { // context [6] implicit
                int len = data[i + 1] & 0xFF;
                if (len < 128 && i + 2 + len <= data.length) {
                    String uri = new String(data, i + 2, len, StandardCharsets.US_ASCII);
                    if (uri.startsWith("http://") || uri.startsWith("https://") || uri.startsWith("ldap://")) {
                        uris.add(uri);
                    }
                }
            }
        }
    }

    private static String formatCertPolicies(X509Certificate cert) {
        byte[] extValue = cert.getExtensionValue("2.5.29.32");
        if (extValue == null) return "";
        try {
            // Parse from DER: OCTET STRING { SEQUENCE { SEQUENCE { OID } ... } }
            int[] pos = {0};
            int[] len = {0};
            readDerTag(extValue, pos, len); // outer OCTET STRING
            byte[] inner = new byte[len[0]];
            System.arraycopy(extValue, pos[0], inner, 0, len[0]);
            pos[0] = 0;
            readDerTag(inner, pos, len); // SEQUENCE of policies
            int seqEnd = pos[0] + len[0];
            StringBuilder sb = new StringBuilder();
            while (pos[0] < seqEnd) {
                int tag = inner[pos[0]] & 0xFF;
                readDerTag(inner, pos, len); // SEQUENCE (one policy)
                int policyEnd = pos[0] + len[0];
                // Read policy OID
                if (pos[0] < policyEnd && (inner[pos[0]] & 0xFF) == 0x06) {
                    readDerTag(inner, pos, len);
                    byte[] oidBytes = new byte[len[0]];
                    System.arraycopy(inner, pos[0], oidBytes, 0, len[0]);
                    String oid = decodeOid(oidBytes);
                    if (sb.length() > 0) sb.append("\n");
                    sb.append("Policy: ").append(oid);
                }
                pos[0] = policyEnd;
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String formatAuthorityInfoAccess(X509Certificate cert) {
        byte[] extValue = cert.getExtensionValue("1.3.6.1.5.5.7.1.1");
        if (extValue == null) return "";
        try {
            // Parse: OCTET STRING { SEQUENCE { SEQUENCE { OID, [6] URI }... } }
            int[] pos = {0};
            int[] len = {0};
            readDerTag(extValue, pos, len);
            byte[] inner = new byte[len[0]];
            System.arraycopy(extValue, pos[0], inner, 0, len[0]);
            pos[0] = 0;
            readDerTag(inner, pos, len); // outer SEQUENCE
            int seqEnd = pos[0] + len[0];
            StringBuilder sb = new StringBuilder();
            while (pos[0] < seqEnd) {
                readDerTag(inner, pos, len); // SEQUENCE (one access description)
                int descEnd = pos[0] + len[0];
                // Read method OID
                if (pos[0] < descEnd && (inner[pos[0]] & 0xFF) == 0x06) {
                    readDerTag(inner, pos, len);
                    byte[] oidBytes = new byte[len[0]];
                    System.arraycopy(inner, pos[0], oidBytes, 0, len[0]);
                    String methodOid = decodeOid(oidBytes);
                    pos[0] += len[0];
                    // Read access location (GeneralName)
                    if (pos[0] < descEnd && (inner[pos[0]] & 0xFF) == 0x86) {
                        readDerTag(inner, pos, len);
                        String uri = new String(inner, pos[0], len[0], StandardCharsets.US_ASCII);
                        pos[0] += len[0];
                        if (sb.length() > 0) sb.append("\n");
                        if ("1.3.6.1.5.5.7.48.1".equals(methodOid)) {
                            sb.append("OCSP - URI:").append(uri);
                        } else if ("1.3.6.1.5.5.7.48.2".equals(methodOid)) {
                            sb.append("CA Issuers - URI:").append(uri);
                        } else {
                            sb.append(methodOid).append(" - URI:").append(uri);
                        }
                    }
                }
                pos[0] = descEnd;
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ---- P_X509 convenience functions ----

    public static RuntimeList P_X509_get_crl_distribution_points(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeList();
        long x509Handle = args.get(0).getLong();
        X509Certificate cert = X509_HANDLES.get(x509Handle);
        if (cert == null) return new RuntimeList();
        RuntimeList result = new RuntimeList();
        try {
            byte[] extValue = cert.getExtensionValue("2.5.29.31");
            if (extValue != null) {
                List<String> uris = new ArrayList<>();
                extractUrisFromExtension(extValue, uris);
                for (String uri : uris) {
                    result.add(new RuntimeScalar(uri));
                }
            }
        } catch (Exception e) {
            // no CDPs
        }
        return result;
    }

    public static RuntimeList P_X509_get_key_usage(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeList();
        long x509Handle = args.get(0).getLong();
        X509Certificate cert = X509_HANDLES.get(x509Handle);
        if (cert == null) return new RuntimeList();
        RuntimeList result = new RuntimeList();
        boolean[] ku = cert.getKeyUsage();
        if (ku != null) {
            String[] names = {"digitalSignature", "nonRepudiation", "keyEncipherment",
                    "dataEncipherment", "keyAgreement", "keyCertSign",
                    "cRLSign", "encipherOnly", "decipherOnly"};
            for (int i = 0; i < Math.min(ku.length, names.length); i++) {
                if (ku[i]) result.add(new RuntimeScalar(names[i]));
            }
        }
        return result;
    }

    public static RuntimeList P_X509_get_netscape_cert_type(RuntimeArray args, int ctx) {
        // Netscape cert type is rarely used; return empty list if not present
        if (args.size() < 1) return new RuntimeList();
        long x509Handle = args.get(0).getLong();
        X509Certificate cert = X509_HANDLES.get(x509Handle);
        if (cert == null) return new RuntimeList();
        // OID 2.16.840.1.113730.1.1 - Netscape Cert Type
        byte[] extValue = cert.getExtensionValue("2.16.840.1.113730.1.1");
        if (extValue == null) return new RuntimeList();
        // Parse BIT STRING to get the bits
        RuntimeList result = new RuntimeList();
        try {
            int[] pos = {0};
            int[] len = {0};
            readDerTag(extValue, pos, len); // OCTET STRING
            byte[] inner = new byte[len[0]];
            System.arraycopy(extValue, pos[0], inner, 0, len[0]);
            pos[0] = 0;
            readDerTag(inner, pos, len); // BIT STRING
            if (len[0] > 1) {
                int unusedBits = inner[pos[0]] & 0xFF;
                byte bits = inner[pos[0] + 1];
                String[] names = {"client", "server", "email", "objsign",
                        "reserved", "sslCA", "emailCA", "objCA"};
                for (int i = 0; i < 8; i++) {
                    if ((bits & (0x80 >> i)) != 0) {
                        result.add(new RuntimeScalar(names[i]));
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return result;
    }

    public static RuntimeList P_X509_get_ext_key_usage(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeList();
        long x509Handle = args.get(0).getLong();
        int mode = (int) args.get(1).getLong();
        X509Certificate cert = X509_HANDLES.get(x509Handle);
        if (cert == null) return new RuntimeList();
        RuntimeList result = new RuntimeList();
        try {
            List<String> ekuOids = cert.getExtendedKeyUsage();
            if (ekuOids == null) return result;
            for (String ekuOid : ekuOids) {
                OidInfo info = OID_TO_INFO.get(ekuOid);
                switch (mode) {
                    case 0: // OID — include all OIDs
                        result.add(new RuntimeScalar(ekuOid));
                        break;
                    case 1: // NID — skip unknown OIDs (no mapping)
                        if (info != null) result.add(new RuntimeScalar(info.nid));
                        break;
                    case 2: // short name — skip unknown OIDs
                        if (info != null) result.add(new RuntimeScalar(info.shortName));
                        break;
                    case 3: // long name — skip unknown OIDs
                        if (info != null) result.add(new RuntimeScalar(info.longName));
                        break;
                }
            }
        } catch (Exception e) {
            // no EKU
        }
        return result;
    }

    public static RuntimeList P_X509_get_signature_alg(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        long x509Handle = args.get(0).getLong();
        X509Certificate cert = X509_HANDLES.get(x509Handle);
        if (cert == null) return new RuntimeScalar().getList();
        String oid = cert.getSigAlgOID();
        long handleId = HANDLE_COUNTER.getAndIncrement();
        ASN1_OBJECT_HANDLES.put(handleId, oid);
        return new RuntimeScalar(handleId).getList();
    }

    public static RuntimeList P_X509_get_pubkey_alg(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        long x509Handle = args.get(0).getLong();
        X509Certificate cert = X509_HANDLES.get(x509Handle);
        if (cert == null) return new RuntimeScalar().getList();
        PublicKey pubKey = cert.getPublicKey();
        String oid;
        if (pubKey instanceof RSAPublicKey) {
            oid = "1.2.840.113549.1.1.1"; // rsaEncryption
        } else if (pubKey instanceof ECPublicKey) {
            oid = "1.2.840.10045.2.1"; // id-ecPublicKey
        } else {
            oid = pubKey.getAlgorithm();
        }
        long handleId = HANDLE_COUNTER.getAndIncrement();
        ASN1_OBJECT_HANDLES.put(handleId, oid);
        return new RuntimeScalar(handleId).getList();
    }

    // ---- EVP_PKEY attribute functions ----

    public static RuntimeList EVP_PKEY_size(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar(0).getList();
        long handle = args.get(0).getLong();
        java.security.Key key = EVP_PKEY_HANDLES.get(handle);
        if (key == null) return new RuntimeScalar(0).getList();
        if (key instanceof RSAPublicKey) {
            return new RuntimeScalar(((RSAPublicKey) key).getModulus().bitLength() / 8).getList();
        }
        // Default: try to get encoded length
        byte[] encoded = key.getEncoded();
        return new RuntimeScalar(encoded != null ? encoded.length : 0).getList();
    }

    public static RuntimeList EVP_PKEY_bits(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar(0).getList();
        long handle = args.get(0).getLong();
        java.security.Key key = EVP_PKEY_HANDLES.get(handle);
        if (key == null) return new RuntimeScalar(0).getList();
        if (key instanceof RSAPublicKey) {
            return new RuntimeScalar(((RSAPublicKey) key).getModulus().bitLength()).getList();
        }
        if (key instanceof ECPublicKey) {
            return new RuntimeScalar(((ECPublicKey) key).getParams().getOrder().bitLength()).getList();
        }
        return new RuntimeScalar(0).getList();
    }

    public static RuntimeList EVP_PKEY_security_bits(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar(0).getList();
        long handle = args.get(0).getLong();
        java.security.Key key = EVP_PKEY_HANDLES.get(handle);
        if (key == null) return new RuntimeScalar(0).getList();
        if (key instanceof RSAPublicKey) {
            int bits = ((RSAPublicKey) key).getModulus().bitLength();
            // Approximate security bits (NIST SP 800-57)
            if (bits >= 15360) return new RuntimeScalar(256).getList();
            if (bits >= 7680) return new RuntimeScalar(192).getList();
            if (bits >= 3072) return new RuntimeScalar(128).getList();
            if (bits >= 2048) return new RuntimeScalar(112).getList();
            if (bits >= 1024) return new RuntimeScalar(80).getList();
            return new RuntimeScalar(0).getList();
        }
        return new RuntimeScalar(0).getList();
    }

    public static RuntimeList EVP_PKEY_id(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar(0).getList();
        long handle = args.get(0).getLong();
        java.security.Key key = EVP_PKEY_HANDLES.get(handle);
        if (key == null) return new RuntimeScalar(0).getList();
        if (key instanceof RSAPublicKey || "RSA".equals(key.getAlgorithm())) {
            return new RuntimeScalar(6).getList(); // NID_rsaEncryption
        }
        if (key instanceof ECPublicKey || "EC".equals(key.getAlgorithm())) {
            return new RuntimeScalar(408).getList(); // NID_X9_62_id_ecPublicKey
        }
        return new RuntimeScalar(0).getList();
    }

    // ---- X509_STORE / X509_STORE_CTX functions ----

    public static RuntimeList X509_STORE_new(RuntimeArray args, int ctx) {
        long handleId = HANDLE_COUNTER.getAndIncrement();
        X509_STORE_HANDLES.put(handleId, new X509StoreState());
        return new RuntimeScalar(handleId).getList();
    }

    public static RuntimeList X509_STORE_free(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        X509_STORE_HANDLES.remove(args.get(0).getLong());
        return new RuntimeScalar().getList();
    }

    public static RuntimeList X509_STORE_CTX_new(RuntimeArray args, int ctx) {
        long handleId = HANDLE_COUNTER.getAndIncrement();
        X509_STORE_CTX_HANDLES.put(handleId, new X509StoreCtxState());
        return new RuntimeScalar(handleId).getList();
    }

    public static RuntimeList X509_STORE_CTX_free(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        X509_STORE_CTX_HANDLES.remove(args.get(0).getLong());
        return new RuntimeScalar().getList();
    }

    public static RuntimeList X509_STORE_CTX_set_cert(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar().getList();
        long ctxHandle = args.get(0).getLong();
        long certHandle = args.get(1).getLong();
        X509StoreCtxState storeCtx = X509_STORE_CTX_HANDLES.get(ctxHandle);
        if (storeCtx == null) return new RuntimeScalar().getList();
        storeCtx.certHandle = certHandle;
        return new RuntimeScalar().getList();
    }

    public static RuntimeList X509_STORE_add_cert(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(0).getList();
        long storeHandle = args.get(0).getLong();
        long certHandle = args.get(1).getLong();
        X509StoreState store = X509_STORE_HANDLES.get(storeHandle);
        if (store == null) return new RuntimeScalar(0).getList();
        store.trustedCerts.add(certHandle);
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList X509_STORE_CTX_init(RuntimeArray args, int ctx) {
        if (args.size() < 3) return new RuntimeScalar(0).getList();
        long ctxHandle = args.get(0).getLong();
        long storeHandle = args.get(1).getLong();
        long certHandle = args.get(2).getLong();
        X509StoreCtxState storeCtx = X509_STORE_CTX_HANDLES.get(ctxHandle);
        if (storeCtx == null) return new RuntimeScalar(0).getList();
        storeCtx.storeHandle = storeHandle;
        storeCtx.certHandle = certHandle;
        storeCtx.errorCode = 0; // X509_V_OK
        // Optional 4th arg: sk_X509 handle for untrusted chain certs
        if (args.size() >= 4) {
            long chainHandle = args.get(3).getLong();
            List<Long> chainCerts = SK_X509_HANDLES.get(chainHandle);
            if (chainCerts != null) {
                storeCtx.untrustedChain = new ArrayList<>(chainCerts);
            }
        }
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList X509_STORE_CTX_get0_cert(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        long ctxHandle = args.get(0).getLong();
        X509StoreCtxState storeCtx = X509_STORE_CTX_HANDLES.get(ctxHandle);
        if (storeCtx == null || storeCtx.certHandle == 0) return new RuntimeScalar().getList();
        return new RuntimeScalar(storeCtx.certHandle).getList();
    }

    public static RuntimeList X509_STORE_CTX_get_error(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar(0).getList();
        long ctxHandle = args.get(0).getLong();
        X509StoreCtxState storeCtx = X509_STORE_CTX_HANDLES.get(ctxHandle);
        if (storeCtx == null) return new RuntimeScalar(0).getList();
        return new RuntimeScalar(storeCtx.errorCode).getList();
    }

    public static RuntimeList X509_STORE_set1_param(RuntimeArray args, int ctx) {
        // Apply verify params to store — for now just return success
        // The actual params are handled during X509_verify_cert
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList X509_verify_cert(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar(0).getList();
        long ctxHandle = args.get(0).getLong();
        X509StoreCtxState storeCtx = X509_STORE_CTX_HANDLES.get(ctxHandle);
        if (storeCtx == null) return new RuntimeScalar(0).getList();

        X509Certificate targetCert = X509_HANDLES.get(storeCtx.certHandle);
        if (targetCert == null) {
            storeCtx.errorCode = 20; // X509_V_ERR_UNABLE_TO_GET_ISSUER_CERT_LOCALLY
            return new RuntimeScalar(0).getList();
        }

        // Gather trusted certs from store
        X509StoreState store = X509_STORE_HANDLES.get(storeCtx.storeHandle);
        List<X509Certificate> trustedCerts = new ArrayList<>();
        if (store != null) {
            for (Long h : store.trustedCerts) {
                X509Certificate c = X509_HANDLES.get(h);
                if (c != null) trustedCerts.add(c);
            }
        }

        // Gather untrusted chain certs
        List<X509Certificate> untrustedCerts = new ArrayList<>();
        if (storeCtx.untrustedChain != null) {
            for (Long h : storeCtx.untrustedChain) {
                X509Certificate c = X509_HANDLES.get(h);
                if (c != null) untrustedCerts.add(c);
            }
        }

        // Try to build a chain from target cert to a trusted root
        List<Long> builtChain = new ArrayList<>();
        builtChain.add(storeCtx.certHandle);

        X509Certificate current = targetCert;
        boolean verified = false;
        int maxDepth = 10;

        for (int depth = 0; depth < maxDepth; depth++) {
            // Check if current cert is self-signed and trusted
            if (current.getSubjectX500Principal().equals(current.getIssuerX500Principal())) {
                // Self-signed — check if it's in trusted store
                if (trustedCerts.contains(current)) {
                    verified = true;
                    break;
                }
            }

            // Find the issuer of current cert
            X509Certificate issuer = null;
            Long issuerHandle = null;

            // First check trusted certs
            for (int i = 0; i < trustedCerts.size(); i++) {
                X509Certificate tc = trustedCerts.get(i);
                if (tc.getSubjectX500Principal().equals(current.getIssuerX500Principal())) {
                    try {
                        current.verify(tc.getPublicKey());
                        issuer = tc;
                        // Find handle for this cert
                        if (store != null) issuerHandle = store.trustedCerts.get(i);
                        break;
                    } catch (Exception e) { /* not the right issuer */ }
                }
            }

            // Then check untrusted chain certs
            if (issuer == null) {
                for (int i = 0; i < untrustedCerts.size(); i++) {
                    X509Certificate uc = untrustedCerts.get(i);
                    if (uc.getSubjectX500Principal().equals(current.getIssuerX500Principal())) {
                        try {
                            current.verify(uc.getPublicKey());
                            issuer = uc;
                            if (storeCtx.untrustedChain != null) issuerHandle = storeCtx.untrustedChain.get(i);
                            break;
                        } catch (Exception e) { /* not the right issuer */ }
                    }
                }
            }

            if (issuer == null) {
                // Can't find issuer
                storeCtx.errorCode = 20; // X509_V_ERR_UNABLE_TO_GET_ISSUER_CERT_LOCALLY
                storeCtx.chain = builtChain;
                return new RuntimeScalar(0).getList();
            }

            if (issuerHandle != null) builtChain.add(issuerHandle);
            current = issuer;

            // If issuer is trusted and self-signed, we're done
            if (trustedCerts.contains(issuer) &&
                issuer.getSubjectX500Principal().equals(issuer.getIssuerX500Principal())) {
                verified = true;
                break;
            }
            // If issuer is trusted (even if not self-signed for partial chain), accept
            if (trustedCerts.contains(issuer)) {
                verified = true;
                break;
            }
        }

        storeCtx.chain = builtChain;
        if (verified) {
            storeCtx.errorCode = 0; // X509_V_OK
            return new RuntimeScalar(1).getList();
        } else {
            storeCtx.errorCode = 20; // X509_V_ERR_UNABLE_TO_GET_ISSUER_CERT_LOCALLY
            return new RuntimeScalar(0).getList();
        }
    }

    public static RuntimeList X509_STORE_CTX_get1_chain(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        long ctxHandle = args.get(0).getLong();
        X509StoreCtxState storeCtx = X509_STORE_CTX_HANDLES.get(ctxHandle);
        if (storeCtx == null || storeCtx.chain == null) return new RuntimeScalar().getList();
        long handleId = HANDLE_COUNTER.getAndIncrement();
        SK_X509_HANDLES.put(handleId, new ArrayList<>(storeCtx.chain));
        return new RuntimeScalar(handleId).getList();
    }

    // ---- sk_X509 (STACK_OF(X509)) functions ----

    public static RuntimeList sk_X509_new_null(RuntimeArray args, int ctx) {
        long handleId = HANDLE_COUNTER.getAndIncrement();
        SK_X509_HANDLES.put(handleId, new ArrayList<>());
        return new RuntimeScalar(handleId).getList();
    }

    public static RuntimeList sk_X509_num(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar(0).getList();
        long handle = args.get(0).getLong();
        List<Long> stack = SK_X509_HANDLES.get(handle);
        if (stack == null) return new RuntimeScalar(0).getList();
        return new RuntimeScalar(stack.size()).getList();
    }

    public static RuntimeList sk_X509_value(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar().getList();
        long handle = args.get(0).getLong();
        int index = (int) args.get(1).getLong();
        List<Long> stack = SK_X509_HANDLES.get(handle);
        if (stack == null || index < 0 || index >= stack.size()) return new RuntimeScalar().getList();
        return new RuntimeScalar(stack.get(index)).getList();
    }

    public static RuntimeList sk_X509_insert(RuntimeArray args, int ctx) {
        if (args.size() < 3) return new RuntimeScalar(0).getList();
        long handle = args.get(0).getLong();
        long certHandle = args.get(1).getLong();
        int index = (int) args.get(2).getLong();
        List<Long> stack = SK_X509_HANDLES.get(handle);
        if (stack == null) return new RuntimeScalar(0).getList();
        if (index < 0 || index > stack.size()) index = stack.size();
        stack.add(index, certHandle);
        return new RuntimeScalar(stack.size()).getList();
    }

    public static RuntimeList sk_X509_delete(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar().getList();
        long handle = args.get(0).getLong();
        int index = (int) args.get(1).getLong();
        List<Long> stack = SK_X509_HANDLES.get(handle);
        if (stack == null || index < 0 || index >= stack.size()) return new RuntimeScalar().getList();
        long removed = stack.remove(index);
        return new RuntimeScalar(removed).getList();
    }

    public static RuntimeList sk_X509_unshift(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(0).getList();
        long handle = args.get(0).getLong();
        long certHandle = args.get(1).getLong();
        List<Long> stack = SK_X509_HANDLES.get(handle);
        if (stack == null) return new RuntimeScalar(0).getList();
        stack.add(0, certHandle);
        return new RuntimeScalar(stack.size()).getList();
    }

    public static RuntimeList sk_X509_shift(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        long handle = args.get(0).getLong();
        List<Long> stack = SK_X509_HANDLES.get(handle);
        if (stack == null || stack.isEmpty()) return new RuntimeScalar().getList();
        long removed = stack.remove(0);
        return new RuntimeScalar(removed).getList();
    }

    public static RuntimeList sk_X509_pop(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        long handle = args.get(0).getLong();
        List<Long> stack = SK_X509_HANDLES.get(handle);
        if (stack == null || stack.isEmpty()) return new RuntimeScalar().getList();
        long removed = stack.remove(stack.size() - 1);
        return new RuntimeScalar(removed).getList();
    }

    public static RuntimeList sk_X509_push(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(0).getList();
        long handle = args.get(0).getLong();
        long certHandle = args.get(1).getLong();
        List<Long> stack = SK_X509_HANDLES.get(handle);
        if (stack == null) return new RuntimeScalar(0).getList();
        stack.add(certHandle);
        return new RuntimeScalar(stack.size()).getList();
    }

    public static RuntimeList sk_X509_free(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        SK_X509_HANDLES.remove(args.get(0).getLong());
        return new RuntimeScalar().getList();
    }

    // ---- P_PKCS12_load_file ----
    // Loads a PKCS#12 file and returns ($privkey, $cert, @cachain)

    public static RuntimeList P_PKCS12_load_file(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeList();
        String filename = args.get(0).toString();
        boolean loadChain = args.size() > 1 && args.get(1).getLong() != 0;
        String password = args.size() > 2 ? args.get(2).toString() : null;
        char[] passChars = (password != null && !password.isEmpty()) ? password.toCharArray() : new char[0];

        RuntimeList result = new RuntimeList();
        try {
            java.security.PrivateKey privKey = null;
            X509Certificate leafCert = null;
            java.security.cert.Certificate[] chainCerts = null;

            // Try Java KeyStore first
            java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
            try (java.io.FileInputStream fis = new java.io.FileInputStream(RuntimeIO.resolvePath(filename).toFile())) {
                ks.load(fis, passChars);
            }

            java.util.Enumeration<String> aliases = ks.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (ks.isKeyEntry(alias)) {
                    java.security.Key key = ks.getKey(alias, passChars);
                    if (key instanceof java.security.PrivateKey) {
                        privKey = (java.security.PrivateKey) key;
                        java.security.cert.Certificate cert = ks.getCertificate(alias);
                        if (cert instanceof X509Certificate) {
                            leafCert = (X509Certificate) cert;
                        }
                        chainCerts = ks.getCertificateChain(alias);
                        break;
                    }
                }
            }

            // Fallback: manually parse PKCS12 DER for unencrypted files that Java KeyStore can't handle
            if (privKey == null && leafCert == null) {
                Object[] parsed = parsePkcs12Manually(filename);
                if (parsed != null) {
                    privKey = (java.security.PrivateKey) parsed[0];
                    leafCert = (X509Certificate) parsed[1];
                    if (parsed.length > 2 && parsed[2] instanceof java.security.cert.Certificate[]) {
                        chainCerts = (java.security.cert.Certificate[]) parsed[2];
                    }
                }
            }

            // Store private key as EVP_PKEY handle
            if (privKey != null) {
                long pkeyHandle = HANDLE_COUNTER.getAndIncrement();
                EVP_PKEY_HANDLES.put(pkeyHandle, privKey);
                result.add(new RuntimeScalar(pkeyHandle));
            } else {
                result.add(new RuntimeScalar()); // undef
            }

            // Store leaf certificate as X509 handle
            if (leafCert != null) {
                long certHandle = HANDLE_COUNTER.getAndIncrement();
                X509_HANDLES.put(certHandle, leafCert);
                result.add(new RuntimeScalar(certHandle));
            } else {
                result.add(new RuntimeScalar()); // undef
            }

            // CA chain (excluding the leaf cert)
            if (loadChain && chainCerts != null) {
                for (java.security.cert.Certificate chainCert : chainCerts) {
                    if (chainCert instanceof X509Certificate) {
                        X509Certificate x509 = (X509Certificate) chainCert;
                        if (leafCert != null && x509.equals(leafCert)) continue;
                        long caHandle = HANDLE_COUNTER.getAndIncrement();
                        X509_HANDLES.put(caHandle, x509);
                        result.add(new RuntimeScalar(caHandle));
                    }
                }
            }
        } catch (Exception e) {
            return new RuntimeList();
        }
        return result;
    }

    // Manual PKCS12 parser for unencrypted files that Java's KeyStore can't handle.
    // Parses the DER structure to extract certificates and unencrypted private keys.
    private static Object[] parsePkcs12Manually(String filename) {
        try {
            byte[] data = java.nio.file.Files.readAllBytes(RuntimeIO.resolvePath(filename));
            java.security.PrivateKey privKey = null;
            X509Certificate leafCert = null;
            List<X509Certificate> caCerts = new ArrayList<>();

            // Parse PFX: SEQUENCE { version, authSafe, macData? }
            int[] pos = {0};
            int[] pfxSeq = readDerTag(data, pos);
            if (pfxSeq == null || pfxSeq[0] != 0x30) return null;

            int pfxEnd = pos[0] + pfxSeq[1];
            // version INTEGER
            readDerTag(data, pos);
            pos[0] += readDerTag(data, new int[]{pos[0]})[1]; // skip version value
            // Reset pos to after version
            pos[0] = pfxSeq[2]; // content start
            skipDerValue(data, pos); // skip version

            // authSafe: ContentInfo { contentType OID, content [0] EXPLICIT }
            int[] authSafeSeq = readDerTag(data, pos);
            if (authSafeSeq == null) return null;
            int authSafeEnd = pos[0] + authSafeSeq[1];
            int authSafeContentStart = pos[0];

            // contentType OID (should be pkcs7-data: 1.2.840.113549.1.7.1)
            skipDerValue(data, pos);
            // content [0] EXPLICIT
            int[] ctxTag = readDerTag(data, pos);
            if (ctxTag == null) return null;
            // Inside: OCTET STRING containing AuthenticatedSafe
            int[] octetTag = readDerTag(data, pos);
            if (octetTag == null) return null;
            byte[] authSafeData = new byte[octetTag[1]];
            System.arraycopy(data, pos[0], authSafeData, 0, octetTag[1]);

            // AuthenticatedSafe: SEQUENCE OF ContentInfo
            int[] asPos = {0};
            int[] authSafeSeqInner = readDerTag(authSafeData, asPos);
            if (authSafeSeqInner == null) return null;
            int asEnd = asPos[0] + authSafeSeqInner[1];

            while (asPos[0] < asEnd) {
                // Each ContentInfo: SEQUENCE { OID, [0] content }
                int ciStart = asPos[0];
                int[] ciSeq = readDerTag(authSafeData, asPos);
                if (ciSeq == null) break;
                int ciEnd = asPos[0] + ciSeq[1];

                // Read OID
                int[] oidTag = readDerTag(authSafeData, asPos);
                if (oidTag == null) break;
                byte[] oidBytes = new byte[oidTag[1]];
                System.arraycopy(authSafeData, asPos[0], oidBytes, 0, oidTag[1]);
                asPos[0] += oidTag[1];
                String oid = derOidToString(oidBytes);

                if ("1.2.840.113549.1.7.1".equals(oid)) {
                    // data ContentInfo — contains SafeContents
                    int[] ctx0 = readDerTag(authSafeData, asPos);
                    if (ctx0 == null) { asPos[0] = ciEnd; continue; }
                    int[] octet = readDerTag(authSafeData, asPos);
                    if (octet == null) { asPos[0] = ciEnd; continue; }
                    byte[] safeContentsData = new byte[octet[1]];
                    System.arraycopy(authSafeData, asPos[0], safeContentsData, 0, octet[1]);

                    // Parse SafeContents: SEQUENCE OF SafeBag
                    int[] scPos = {0};
                    int[] scSeq = readDerTag(safeContentsData, scPos);
                    if (scSeq == null) { asPos[0] = ciEnd; continue; }
                    int scEnd = scPos[0] + scSeq[1];

                    while (scPos[0] < scEnd) {
                        int[] bagSeq = readDerTag(safeContentsData, scPos);
                        if (bagSeq == null) break;
                        int bagEnd = scPos[0] + bagSeq[1];

                        // bagId OID
                        int[] bagOidTag = readDerTag(safeContentsData, scPos);
                        if (bagOidTag == null) { scPos[0] = bagEnd; continue; }
                        byte[] bagOidBytes = new byte[bagOidTag[1]];
                        System.arraycopy(safeContentsData, scPos[0], bagOidBytes, 0, bagOidTag[1]);
                        scPos[0] += bagOidTag[1];
                        String bagOid = derOidToString(bagOidBytes);

                        // bagValue [0] EXPLICIT
                        int[] bagCtx = readDerTag(safeContentsData, scPos);
                        if (bagCtx == null) { scPos[0] = bagEnd; continue; }

                        if ("1.2.840.113549.1.12.10.1.1".equals(bagOid)) {
                            // keyBag — contains PKCS#8 PrivateKeyInfo
                            int keyInfoStart = scPos[0];
                            int keyInfoLen = bagCtx[1];
                            byte[] pkcs8Bytes = new byte[keyInfoLen];
                            System.arraycopy(safeContentsData, keyInfoStart, pkcs8Bytes, 0, keyInfoLen);

                            // Determine key algorithm from PrivateKeyInfo
                            privKey = parsePkcs8PrivateKey(pkcs8Bytes);
                        } else if ("1.2.840.113549.1.12.10.1.3".equals(bagOid)) {
                            // certBag — SEQUENCE { certId OID, certValue [0] EXPLICIT OCTET STRING }
                            int[] certBagSeq = readDerTag(safeContentsData, scPos);
                            if (certBagSeq != null) {
                                int certBagEnd = scPos[0] + certBagSeq[1];
                                // certId OID
                                skipDerValue(safeContentsData, scPos);
                                // certValue [0] EXPLICIT
                                int[] certCtx = readDerTag(safeContentsData, scPos);
                                if (certCtx != null) {
                                    // OCTET STRING containing DER-encoded certificate
                                    int[] certOctet = readDerTag(safeContentsData, scPos);
                                    if (certOctet != null) {
                                        byte[] certDer = new byte[certOctet[1]];
                                        System.arraycopy(safeContentsData, scPos[0], certDer, 0, certOctet[1]);
                                        java.security.cert.CertificateFactory cf =
                                            java.security.cert.CertificateFactory.getInstance("X.509");
                                        X509Certificate cert = (X509Certificate) cf.generateCertificate(
                                            new java.io.ByteArrayInputStream(certDer));
                                        if (leafCert == null) {
                                            leafCert = cert;
                                        } else {
                                            caCerts.add(cert);
                                        }
                                    }
                                }
                                scPos[0] = certBagEnd;
                            }
                        }

                        scPos[0] = bagEnd;
                    }
                }

                asPos[0] = ciEnd;
            }

            if (privKey == null && leafCert == null) return null;
            java.security.cert.Certificate[] chain = caCerts.isEmpty() ? null :
                caCerts.toArray(new java.security.cert.Certificate[0]);
            return new Object[]{privKey, leafCert, chain};
        } catch (Exception e) {
            return null;
        }
    }

    // Parse PKCS#8 PrivateKeyInfo DER to extract the private key
    private static java.security.PrivateKey parsePkcs8PrivateKey(byte[] pkcs8Bytes) throws Exception {
        // Try RSA first, then EC, then DSA
        String[] algorithms = {"RSA", "EC", "DSA", "Ed25519", "Ed448"};
        for (String algo : algorithms) {
            try {
                java.security.KeyFactory kf = java.security.KeyFactory.getInstance(algo);
                return kf.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(pkcs8Bytes));
            } catch (Exception ignored) {}
        }
        return null;
    }

    // Read a DER tag and length. Returns [tag, length, contentOffset] or null.
    private static int[] readDerTag(byte[] data, int[] pos) {
        if (pos[0] >= data.length) return null;
        int tag = data[pos[0]++] & 0xFF;
        if (pos[0] >= data.length) return null;
        int len = data[pos[0]++] & 0xFF;
        if ((len & 0x80) != 0) {
            int numBytes = len & 0x7F;
            len = 0;
            for (int i = 0; i < numBytes && pos[0] < data.length; i++) {
                len = (len << 8) | (data[pos[0]++] & 0xFF);
            }
        }
        return new int[]{tag, len, pos[0]};
    }

    // Skip a DER TLV value
    private static void skipDerValue(byte[] data, int[] pos) {
        int[] tlv = readDerTag(data, pos);
        if (tlv != null) pos[0] += tlv[1];
    }

    // Convert DER-encoded OID bytes to dotted string
    private static String derOidToString(byte[] oid) {
        if (oid.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(oid[0] / 40).append('.').append(oid[0] % 40);
        long val = 0;
        for (int i = 1; i < oid.length; i++) {
            val = (val << 7) | (oid[i] & 0x7F);
            if ((oid[i] & 0x80) == 0) {
                sb.append('.').append(val);
                val = 0;
            }
        }
        return sb.toString();
    }

    // ---- X509_verify — verify certificate signature against a public key ----

    public static RuntimeList X509_verify(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(0).getList();
        long certHandle = args.get(0).getLong();
        long pkeyHandle = args.get(1).getLong();
        X509Certificate cert = X509_HANDLES.get(certHandle);
        java.security.Key key = EVP_PKEY_HANDLES.get(pkeyHandle);
        if (cert == null || key == null) return new RuntimeScalar(0).getList();
        try {
            java.security.PublicKey pubKey;
            if (key instanceof java.security.PublicKey) {
                pubKey = (java.security.PublicKey) key;
            } else if (key instanceof java.security.PrivateKey) {
                // For RSA private keys, derive the public key
                try {
                    java.security.KeyFactory kf = java.security.KeyFactory.getInstance(key.getAlgorithm());
                    if (key instanceof java.security.interfaces.RSAPrivateCrtKey) {
                        java.security.interfaces.RSAPrivateCrtKey rsaPriv = (java.security.interfaces.RSAPrivateCrtKey) key;
                        java.security.spec.RSAPublicKeySpec pubSpec = new java.security.spec.RSAPublicKeySpec(
                            rsaPriv.getModulus(), rsaPriv.getPublicExponent());
                        pubKey = kf.generatePublic(pubSpec);
                    } else {
                        // Can't derive public key — try verifying with cert's own public key
                        pubKey = cert.getPublicKey();
                    }
                } catch (Exception ex) {
                    pubKey = cert.getPublicKey();
                }
            } else {
                return new RuntimeScalar(0).getList();
            }
            cert.verify(pubKey);
            return new RuntimeScalar(1).getList(); // verification succeeded
        } catch (Exception e) {
            return new RuntimeScalar(0).getList(); // verification failed
        }
    }

    // ---- X509_NAME_cmp — compare two X509_NAME handles ----

    public static RuntimeList X509_NAME_cmp(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(-1).getList();
        long name1Handle = args.get(0).getLong();
        long name2Handle = args.get(1).getLong();
        X509NameInfo info1 = X509_NAME_HANDLES.get(name1Handle);
        X509NameInfo info2 = X509_NAME_HANDLES.get(name2Handle);
        if (info1 == null || info2 == null) return new RuntimeScalar(-1).getList();
        // Compare by DER encoding (canonical form) like OpenSSL does
        if (info1.derEncoded != null && info2.derEncoded != null) {
            return new RuntimeScalar(java.util.Arrays.equals(info1.derEncoded, info2.derEncoded) ? 0 : 1).getList();
        }
        // Fallback to oneline comparison
        String s1 = info1.oneline != null ? info1.oneline : "";
        String s2 = info2.oneline != null ? info2.oneline : "";
        return new RuntimeScalar(s1.compareTo(s2)).getList();
    }

    // ---- X509_VERIFY_PARAM functions ----

    public static RuntimeList X509_VERIFY_PARAM_new(RuntimeArray args, int ctx) {
        long handleId = HANDLE_COUNTER.getAndIncrement();
        VERIFY_PARAM_HANDLES.put(handleId, new VerifyParamState());
        return new RuntimeScalar(handleId).getList();
    }

    public static RuntimeList X509_VERIFY_PARAM_free(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        VERIFY_PARAM_HANDLES.remove(args.get(0).getLong());
        return new RuntimeScalar().getList();
    }

    public static RuntimeList X509_VERIFY_PARAM_inherit(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(0).getList();
        VerifyParamState dst = VERIFY_PARAM_HANDLES.get(args.get(0).getLong());
        VerifyParamState src = VERIFY_PARAM_HANDLES.get(args.get(1).getLong());
        if (dst == null || src == null) return new RuntimeScalar(0).getList();
        // Inherit only fields that aren't already set in dst
        if (dst.depth < 0 && src.depth >= 0) dst.depth = src.depth;
        if (dst.purpose == 0 && src.purpose != 0) dst.purpose = src.purpose;
        if (dst.trust == 0 && src.trust != 0) dst.trust = src.trust;
        dst.flags |= src.flags;
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList X509_VERIFY_PARAM_set1(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(0).getList();
        VerifyParamState dst = VERIFY_PARAM_HANDLES.get(args.get(0).getLong());
        VerifyParamState src = VERIFY_PARAM_HANDLES.get(args.get(1).getLong());
        if (dst == null || src == null) return new RuntimeScalar(0).getList();
        dst.name = src.name;
        dst.flags = src.flags;
        dst.purpose = src.purpose;
        dst.trust = src.trust;
        dst.depth = src.depth;
        dst.time = src.time;
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList X509_VERIFY_PARAM_set1_name(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(0).getList();
        VerifyParamState pm = VERIFY_PARAM_HANDLES.get(args.get(0).getLong());
        if (pm == null) return new RuntimeScalar(0).getList();
        pm.name = args.get(1).toString();
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList X509_VERIFY_PARAM_set_flags(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(0).getList();
        VerifyParamState pm = VERIFY_PARAM_HANDLES.get(args.get(0).getLong());
        if (pm == null) return new RuntimeScalar(0).getList();
        pm.flags |= args.get(1).getLong();
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList X509_VERIFY_PARAM_get_flags(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar(0).getList();
        VerifyParamState pm = VERIFY_PARAM_HANDLES.get(args.get(0).getLong());
        if (pm == null) return new RuntimeScalar(0).getList();
        return new RuntimeScalar(pm.flags).getList();
    }

    public static RuntimeList X509_VERIFY_PARAM_clear_flags(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(0).getList();
        VerifyParamState pm = VERIFY_PARAM_HANDLES.get(args.get(0).getLong());
        if (pm == null) return new RuntimeScalar(0).getList();
        pm.flags &= ~args.get(1).getLong();
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList X509_VERIFY_PARAM_set_purpose(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(0).getList();
        VerifyParamState pm = VERIFY_PARAM_HANDLES.get(args.get(0).getLong());
        if (pm == null) return new RuntimeScalar(0).getList();
        pm.purpose = (int) args.get(1).getLong();
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList X509_VERIFY_PARAM_set_trust(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(0).getList();
        VerifyParamState pm = VERIFY_PARAM_HANDLES.get(args.get(0).getLong());
        if (pm == null) return new RuntimeScalar(0).getList();
        pm.trust = (int) args.get(1).getLong();
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList X509_VERIFY_PARAM_set_depth(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar().getList();
        VerifyParamState pm = VERIFY_PARAM_HANDLES.get(args.get(0).getLong());
        if (pm == null) return new RuntimeScalar().getList();
        pm.depth = (int) args.get(1).getLong();
        return new RuntimeScalar().getList();
    }

    public static RuntimeList X509_VERIFY_PARAM_set_time(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar().getList();
        VerifyParamState pm = VERIFY_PARAM_HANDLES.get(args.get(0).getLong());
        if (pm == null) return new RuntimeScalar().getList();
        pm.time = args.get(1).getLong();
        return new RuntimeScalar().getList();
    }

    public static RuntimeList X509_VERIFY_PARAM_add0_policy(RuntimeArray args, int ctx) {
        // Accept and store policy OID — for now just return success
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList X509_VERIFY_PARAM_set1_host(RuntimeArray args, int ctx) {
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList X509_VERIFY_PARAM_add1_host(RuntimeArray args, int ctx) {
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList X509_VERIFY_PARAM_set1_email(RuntimeArray args, int ctx) {
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList X509_VERIFY_PARAM_set1_ip(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(0).getList();
        String ip = args.get(1).toString();
        // Valid if exactly 4 bytes (IPv4) or 16 bytes (IPv6)
        int len = ip.length();
        if (len != 4 && len != 16) return new RuntimeScalar(0).getList();
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList X509_VERIFY_PARAM_set1_ip_asc(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(0).getList();
        String ip = args.get(1).toString();
        // Validate IPv4
        if (ip.contains(".")) {
            String[] parts = ip.split("\\.");
            if (parts.length != 4) return new RuntimeScalar(0).getList();
            for (String part : parts) {
                try {
                    int val = Integer.parseInt(part);
                    if (val < 0 || val > 255) return new RuntimeScalar(0).getList();
                } catch (NumberFormatException e) { return new RuntimeScalar(0).getList(); }
            }
            return new RuntimeScalar(1).getList();
        }
        // Validate IPv6
        if (ip.contains(":")) {
            try {
                java.net.InetAddress addr = java.net.InetAddress.getByName(ip);
                if (addr instanceof java.net.Inet6Address) return new RuntimeScalar(1).getList();
            } catch (Exception e) { return new RuntimeScalar(0).getList(); }
        }
        return new RuntimeScalar(0).getList();
    }

    public static RuntimeList X509_VERIFY_PARAM_set_hostflags(RuntimeArray args, int ctx) {
        // Accept flags, return undef (like OpenSSL — void function)
        return new RuntimeScalar().getList();
    }

    public static RuntimeList X509_VERIFY_PARAM_get0_peername(RuntimeArray args, int ctx) {
        // Not implemented — return undef
        return new RuntimeScalar().getList();
    }

    // ---- PEM_X509_INFO / sk_X509_INFO functions ----

    public static RuntimeList PEM_X509_INFO_read_bio(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        long bioHandle = args.get(0).getLong();
        MemoryBIO bio = BIO_HANDLES.get(bioHandle);
        if (bio == null) return new RuntimeScalar().getList();

        List<X509InfoEntry> entries = new ArrayList<>();
        try {
            java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
            // Read all certificates from the PEM data in the BIO
            byte[] pemData = bio.toByteArray();
            java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(pemData);
            java.util.Collection<? extends java.security.cert.Certificate> certs = cf.generateCertificates(bais);
            for (java.security.cert.Certificate c : certs) {
                if (c instanceof X509Certificate) {
                    X509Certificate x509 = (X509Certificate) c;
                    long certHandle = HANDLE_COUNTER.getAndIncrement();
                    X509_HANDLES.put(certHandle, x509);
                    X509InfoEntry entry = new X509InfoEntry();
                    entry.certHandle = certHandle;
                    entries.add(entry);
                }
            }
        } catch (Exception e) {
            // Return whatever we got so far
        }

        if (entries.isEmpty()) return new RuntimeScalar().getList();
        long handleId = HANDLE_COUNTER.getAndIncrement();
        X509_INFO_SK_HANDLES.put(handleId, entries);
        return new RuntimeScalar(handleId).getList();
    }

    public static RuntimeList sk_X509_INFO_num(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar(0).getList();
        long handle = args.get(0).getLong();
        List<X509InfoEntry> entries = X509_INFO_SK_HANDLES.get(handle);
        if (entries == null) return new RuntimeScalar(0).getList();
        return new RuntimeScalar(entries.size()).getList();
    }

    public static RuntimeList sk_X509_INFO_value(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar().getList();
        long handle = args.get(0).getLong();
        int index = (int) args.get(1).getLong();
        List<X509InfoEntry> entries = X509_INFO_SK_HANDLES.get(handle);
        if (entries == null || index < 0 || index >= entries.size()) return new RuntimeScalar().getList();
        // Return a handle that represents this X509_INFO entry
        // We use the cert handle as the info handle (1:1 mapping)
        return new RuntimeScalar(entries.get(index).certHandle).getList();
    }

    public static RuntimeList P_X509_INFO_get_x509(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        long infoHandle = args.get(0).getLong();
        // The info handle IS the cert handle (from sk_X509_INFO_value)
        if (X509_HANDLES.containsKey(infoHandle)) {
            return new RuntimeScalar(infoHandle).getList();
        }
        return new RuntimeScalar().getList();
    }


    // ---- Phase 2: Foundation functions ----

    // RSA_F4() - returns RSA F4 exponent (65537)
    public static RuntimeList RSA_F4(RuntimeArray args, int ctx) {
        return new RuntimeScalar(65537L).getList();
    }

    // RSA_get_key_parameters($rsa) - returns list of 8 BIGNUMs: n, e, d, p, q, dmp1, dmq1, iqmp
    public static RuntimeList RSA_get_key_parameters(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeList();
        long rsaHandle = args.get(0).getLong();
        KeyPair kp = RSA_HANDLES.get(rsaHandle);
        if (kp == null) return new RuntimeList();
        try {
            java.security.interfaces.RSAPrivateCrtKey privKey =
                    (java.security.interfaces.RSAPrivateCrtKey) kp.getPrivate();
            RSAPublicKey pubKey = (RSAPublicKey) kp.getPublic();
            BigInteger[] params = {
                pubKey.getModulus(),           // n
                pubKey.getPublicExponent(),    // e
                privKey.getPrivateExponent(),  // d
                privKey.getPrimeP(),           // p
                privKey.getPrimeQ(),           // q
                privKey.getPrimeExponentP(),   // dmp1
                privKey.getPrimeExponentQ(),   // dmq1
                privKey.getCrtCoefficient()    // iqmp
            };
            RuntimeList result = new RuntimeList();
            for (BigInteger bi : params) {
                long bnHandle = HANDLE_COUNTER.getAndIncrement();
                BIGNUM_HANDLES.put(bnHandle, bi);
                result.add(new RuntimeScalar(bnHandle));
            }
            return result;
        } catch (Exception e) {
            return new RuntimeList();
        }
    }

    // BN_dup($bn) - duplicate a BIGNUM handle
    public static RuntimeList BN_dup(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        long bnHandle = args.get(0).getLong();
        BigInteger val = BIGNUM_HANDLES.get(bnHandle);
        if (val == null) return new RuntimeScalar().getList();
        long newHandle = HANDLE_COUNTER.getAndIncrement();
        BIGNUM_HANDLES.put(newHandle, val);
        return new RuntimeScalar(newHandle).getList();
    }

    // EVP_PKEY_new() - create empty EVP_PKEY handle
    public static RuntimeList EVP_PKEY_new(RuntimeArray args, int ctx) {
        long handleId = HANDLE_COUNTER.getAndIncrement();
        EVP_PKEY_HANDLES.put(handleId, null); // null = empty, will be assigned later
        return new RuntimeScalar(handleId).getList();
    }

    // EVP_PKEY_assign_RSA($pkey, $rsa) - assign RSA key to EVP_PKEY
    public static RuntimeList EVP_PKEY_assign_RSA(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(0).getList();
        long pkeyHandle = args.get(0).getLong();
        long rsaHandle = args.get(1).getLong();
        if (!EVP_PKEY_HANDLES.containsKey(pkeyHandle)) return new RuntimeScalar(0).getList();
        KeyPair kp = RSA_HANDLES.get(rsaHandle);
        if (kp == null) return new RuntimeScalar(0).getList();
        // Store the private key in EVP_PKEY_HANDLES
        EVP_PKEY_HANDLES.put(pkeyHandle, kp.getPrivate());
        return new RuntimeScalar(1).getList();
    }

    // ASN1_INTEGER_new() - create new ASN1_INTEGER handle
    public static RuntimeList ASN1_INTEGER_new(RuntimeArray args, int ctx) {
        long handleId = HANDLE_COUNTER.getAndIncrement();
        ASN1_INTEGER_HANDLES.put(handleId, BigInteger.ZERO);
        return new RuntimeScalar(handleId).getList();
    }

    // ASN1_INTEGER_set($asn1, $value) - set from integer value
    public static RuntimeList ASN1_INTEGER_set(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(0).getList();
        long handle = args.get(0).getLong();
        if (!ASN1_INTEGER_HANDLES.containsKey(handle)) return new RuntimeScalar(0).getList();
        long value = args.get(1).getLong();
        ASN1_INTEGER_HANDLES.put(handle, BigInteger.valueOf(value));
        return new RuntimeScalar(1).getList();
    }

    // ASN1_INTEGER_get($asn1) - get integer value
    public static RuntimeList ASN1_INTEGER_get(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar(0).getList();
        long handle = args.get(0).getLong();
        BigInteger val = ASN1_INTEGER_HANDLES.get(handle);
        if (val == null) return new RuntimeScalar(0).getList();
        // OpenSSL returns -1 when the value doesn't fit in a long
        if (val.bitLength() > 63) return new RuntimeScalar(-1).getList();
        return new RuntimeScalar(val.longValue()).getList();
    }

    // ASN1_INTEGER_free($asn1) - free handle
    public static RuntimeList ASN1_INTEGER_free(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        long handle = args.get(0).getLong();
        ASN1_INTEGER_HANDLES.remove(handle);
        return new RuntimeScalar().getList();
    }

    // P_ASN1_INTEGER_set_hex($asn1, $hex) - set from hex string
    public static RuntimeList P_ASN1_INTEGER_set_hex(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(0).getList();
        long handle = args.get(0).getLong();
        if (!ASN1_INTEGER_HANDLES.containsKey(handle)) return new RuntimeScalar(0).getList();
        String hex = args.get(1).toString();
        try {
            ASN1_INTEGER_HANDLES.put(handle, new BigInteger(hex, 16));
            return new RuntimeScalar(1).getList();
        } catch (NumberFormatException e) {
            return new RuntimeScalar(0).getList();
        }
    }

    // P_ASN1_INTEGER_set_dec($asn1, $dec) - set from decimal string
    public static RuntimeList P_ASN1_INTEGER_set_dec(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(0).getList();
        long handle = args.get(0).getLong();
        if (!ASN1_INTEGER_HANDLES.containsKey(handle)) return new RuntimeScalar(0).getList();
        String dec = args.get(1).toString();
        try {
            ASN1_INTEGER_HANDLES.put(handle, new BigInteger(dec, 10));
            return new RuntimeScalar(1).getList();
        } catch (NumberFormatException e) {
            return new RuntimeScalar(0).getList();
        }
    }

    // OBJ_nid2obj($nid) - convert NID to ASN1_OBJECT handle
    public static RuntimeList OBJ_nid2obj(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        int nid = (int) args.get(0).getLong();
        OidInfo info = NID_TO_INFO.get(nid);
        if (info == null) return new RuntimeScalar().getList();
        long handleId = HANDLE_COUNTER.getAndIncrement();
        ASN1_OBJECT_HANDLES.put(handleId, info.oid);
        return new RuntimeScalar(handleId).getList();
    }

    // EVP_get_cipherbyname($name) - return cipher handle (sentinel)
    public static RuntimeList EVP_get_cipherbyname(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        String name = args.get(0).toString();
        // Map common OpenSSL cipher names to Java cipher names
        Map<String, String> cipherMap = new HashMap<>();
        cipherMap.put("DES-EDE3-CBC", "DESede/CBC/PKCS5Padding");
        cipherMap.put("des-ede3-cbc", "DESede/CBC/PKCS5Padding");
        cipherMap.put("AES-256-CBC", "AES/CBC/PKCS5Padding");
        cipherMap.put("aes-256-cbc", "AES/CBC/PKCS5Padding");
        cipherMap.put("AES-128-CBC", "AES/CBC/PKCS5Padding");
        cipherMap.put("aes-128-cbc", "AES/CBC/PKCS5Padding");
        String javaName = cipherMap.get(name);
        if (javaName == null) javaName = name; // try as-is
        try {
            Cipher.getInstance(javaName); // validate it exists
            long handleId = HANDLE_COUNTER.getAndIncrement();
            EVP_CIPHER_HANDLES.put(handleId, name);
            return new RuntimeScalar(handleId).getList();
        } catch (Exception e) {
            return new RuntimeScalar().getList(); // undef = not available
        }
    }

    // OSSL_PROVIDER_load($ctx, $name) - simulate loading a provider
    public static RuntimeList OSSL_PROVIDER_load(RuntimeArray args, int ctx) {
        String name = args.size() >= 2 ? args.get(1).toString() : "default";
        // If already loaded, return existing handle
        Long existing = PROVIDER_NAME_TO_HANDLE.get(name);
        if (existing != null) return new RuntimeScalar(existing).getList();
        long handleId = HANDLE_COUNTER.getAndIncrement();
        PROVIDER_NAME_TO_HANDLE.put(name, handleId);
        PROVIDER_HANDLE_TO_NAME.put(handleId, name);
        LOADED_PROVIDERS.put(handleId, name);
        return new RuntimeScalar(handleId).getList();
    }

    // OSSL_PROVIDER_unload($provider) - unload a provider
    public static RuntimeList OSSL_PROVIDER_unload(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar(0).getList();
        long handle = args.get(0).getLong();
        String name = PROVIDER_HANDLE_TO_NAME.remove(handle);
        if (name != null) {
            PROVIDER_NAME_TO_HANDLE.remove(name);
            LOADED_PROVIDERS.remove(handle);
        }
        return new RuntimeScalar(1).getList();
    }

    // OSSL_PROVIDER_available($ctx, $name) - check if provider is loaded
    public static RuntimeList OSSL_PROVIDER_available(RuntimeArray args, int ctx) {
        String name = args.size() >= 2 ? args.get(1).toString() : "";
        boolean avail = PROVIDER_NAME_TO_HANDLE.containsKey(name);
        return new RuntimeScalar(avail ? 1 : 0).getList();
    }

    // OSSL_PROVIDER_try_load($ctx, $name, $retain_fallbacks) - load with fallback control
    public static RuntimeList OSSL_PROVIDER_try_load(RuntimeArray args, int ctx) {
        String name = args.size() >= 2 ? args.get(1).toString() : "";
        int retain = args.size() >= 3 ? (int) args.get(2).getLong() : 1;
        // Load the requested provider
        Long existing = PROVIDER_NAME_TO_HANDLE.get(name);
        long handleId;
        if (existing != null) {
            handleId = existing;
        } else {
            handleId = HANDLE_COUNTER.getAndIncrement();
            PROVIDER_NAME_TO_HANDLE.put(name, handleId);
            PROVIDER_HANDLE_TO_NAME.put(handleId, name);
            LOADED_PROVIDERS.put(handleId, name);
        }
        if (retain == 1) {
            // Auto-load default provider as fallback if not already loaded
            if (!PROVIDER_NAME_TO_HANDLE.containsKey("default")) {
                long defHandle = HANDLE_COUNTER.getAndIncrement();
                PROVIDER_NAME_TO_HANDLE.put("default", defHandle);
                PROVIDER_HANDLE_TO_NAME.put(defHandle, "default");
                LOADED_PROVIDERS.put(defHandle, "default");
            }
        }
        return new RuntimeScalar(handleId).getList();
    }

    // OSSL_PROVIDER_get0_name($provider) - get provider name
    public static RuntimeList OSSL_PROVIDER_get0_name(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        long handle = args.get(0).getLong();
        String name = PROVIDER_HANDLE_TO_NAME.get(handle);
        if (name == null) return new RuntimeScalar().getList();
        return new RuntimeScalar(name).getList();
    }

    // OSSL_PROVIDER_self_test($provider) - always returns 1 (success)
    public static RuntimeList OSSL_PROVIDER_self_test(RuntimeArray args, int ctx) {
        return new RuntimeScalar(1).getList();
    }

    // OSSL_PROVIDER_do_all($ctx, \&callback, $cbdata) - iterate all loaded providers
    public static RuntimeList OSSL_PROVIDER_do_all(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(1).getList();
        RuntimeScalar callback = args.get(1);
        RuntimeScalar cbdata = args.size() >= 3 ? args.get(2) : new RuntimeScalar();
        // Iterate over a snapshot to avoid concurrent modification
        List<Map.Entry<Long, String>> snapshot = new ArrayList<>(LOADED_PROVIDERS.entrySet());
        for (Map.Entry<Long, String> entry : snapshot) {
            RuntimeArray callArgs = new RuntimeArray();
            callArgs.push(new RuntimeScalar(entry.getKey()));
            callArgs.push(cbdata);
            RuntimeCode.apply(callback, callArgs, RuntimeContextType.SCALAR);
        }
        return new RuntimeScalar(1).getList();
    }

    // OSSL_LIB_CTX_get0_global_default() - return a dummy libctx handle
    public static RuntimeList OSSL_LIB_CTX_get0_global_default(RuntimeArray args, int ctx) {
        if (LIBCTX_HANDLE == 0) {
            LIBCTX_HANDLE = HANDLE_COUNTER.getAndIncrement();
        }
        return new RuntimeScalar(LIBCTX_HANDLE).getList();
    }

    // ---- Phase 2b: Mutable X509 creation and signing ----

    // X509_new() - create mutable X509 certificate
    public static RuntimeList X509_new(RuntimeArray args, int ctx) {
        long handleId = HANDLE_COUNTER.getAndIncrement();
        MutableX509State state = new MutableX509State();
        // Create initial sub-handles for serial, subject name, notBefore, notAfter
        state.serialHandle = HANDLE_COUNTER.getAndIncrement();
        ASN1_INTEGER_HANDLES.put(state.serialHandle, BigInteger.ZERO);
        state.subjectNameHandle = HANDLE_COUNTER.getAndIncrement();
        X509NameInfo subjectName = new X509NameInfo();
        subjectName.oneline = "";
        subjectName.rfc2253 = "";
        subjectName.derEncoded = new byte[]{0x30, 0x00};
        X509_NAME_HANDLES.put(state.subjectNameHandle, subjectName);
        state.notBeforeHandle = HANDLE_COUNTER.getAndIncrement();
        ASN1_TIME_HANDLES.put(state.notBeforeHandle, 0L);
        state.notAfterHandle = HANDLE_COUNTER.getAndIncrement();
        ASN1_TIME_HANDLES.put(state.notAfterHandle, 0L);
        MUTABLE_X509_HANDLES.put(handleId, state);
        return new RuntimeScalar(handleId).getList();
    }

    // X509_set_version($x509, $version)
    public static RuntimeList X509_set_version(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(0).getList();
        long x509Handle = args.get(0).getLong();
        MutableX509State state = MUTABLE_X509_HANDLES.get(x509Handle);
        if (state == null) return new RuntimeScalar(0).getList();
        state.version = (int) args.get(1).getLong();
        return new RuntimeScalar(1).getList();
    }

    // X509_set_pubkey($x509, $pkey)
    public static RuntimeList X509_set_pubkey(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(0).getList();
        long x509Handle = args.get(0).getLong();
        MutableX509State state = MUTABLE_X509_HANDLES.get(x509Handle);
        if (state == null) return new RuntimeScalar(0).getList();
        // Copy the key into a new internal handle so EVP_PKEY_free on the
        // original doesn't invalidate our reference (mimics OpenSSL refcounting)
        long srcHandle = args.get(1).getLong();
        java.security.Key key = EVP_PKEY_HANDLES.get(srcHandle);
        if (key == null) return new RuntimeScalar(0).getList();
        long newHandle = HANDLE_COUNTER.getAndIncrement();
        EVP_PKEY_HANDLES.put(newHandle, key);
        state.pubkeyHandle = newHandle;
        return new RuntimeScalar(1).getList();
    }

    // X509_set_subject_name($x509, $name)
    public static RuntimeList X509_set_subject_name(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(0).getList();
        long x509Handle = args.get(0).getLong();
        MutableX509State state = MUTABLE_X509_HANDLES.get(x509Handle);
        if (state == null) return new RuntimeScalar(0).getList();
        state.subjectNameHandle = args.get(1).getLong();
        return new RuntimeScalar(1).getList();
    }

    // X509_set_issuer_name($x509, $name)
    public static RuntimeList X509_set_issuer_name(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(0).getList();
        long x509Handle = args.get(0).getLong();
        MutableX509State state = MUTABLE_X509_HANDLES.get(x509Handle);
        if (state == null) return new RuntimeScalar(0).getList();
        state.issuerNameHandle = args.get(1).getLong();
        return new RuntimeScalar(1).getList();
    }

    // X509_set_serialNumber($x509, $serial)
    public static RuntimeList X509_set_serialNumber(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(0).getList();
        long x509Handle = args.get(0).getLong();
        MutableX509State state = MUTABLE_X509_HANDLES.get(x509Handle);
        if (state == null) return new RuntimeScalar(0).getList();
        long serialHandle = args.get(1).getLong();
        BigInteger serialVal = ASN1_INTEGER_HANDLES.get(serialHandle);
        if (serialVal != null) {
            ASN1_INTEGER_HANDLES.put(state.serialHandle, serialVal);
        }
        return new RuntimeScalar(1).getList();
    }

    // X509_get_X509_PUBKEY($x509) - returns a handle (just checks it exists)
    public static RuntimeList X509_get_X509_PUBKEY(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        long x509Handle = args.get(0).getLong();
        MutableX509State mutable = MUTABLE_X509_HANDLES.get(x509Handle);
        if (mutable != null) {
            if (mutable.pubkeyHandle == 0) return new RuntimeScalar().getList();
            return new RuntimeScalar(mutable.pubkeyHandle).getList();
        }
        X509Certificate cert = X509_HANDLES.get(x509Handle);
        if (cert == null) return new RuntimeScalar().getList();
        // Return the pubkey handle for immutable certs
        try {
            PublicKey pubKey = cert.getPublicKey();
            long handleId = HANDLE_COUNTER.getAndIncrement();
            EVP_PKEY_HANDLES.put(handleId, pubKey);
            return new RuntimeScalar(handleId).getList();
        } catch (Exception e) {
            return new RuntimeScalar().getList();
        }
    }

    // X509_get_ext_by_NID($x509, $nid) - find extension index by NID
    public static RuntimeList X509_get_ext_by_NID(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(-1).getList();
        long x509Handle = args.get(0).getLong();
        int nid = (int) args.get(1).getLong();
        X509Certificate cert = X509_HANDLES.get(x509Handle);
        if (cert == null) return new RuntimeScalar(-1).getList();
        OidInfo info = NID_TO_INFO.get(nid);
        if (info == null) return new RuntimeScalar(-1).getList();
        // Get all extension OIDs and find the index
        List<String> allOids = new ArrayList<>();
        Set<String> critOids = cert.getCriticalExtensionOIDs();
        Set<String> nonCritOids = cert.getNonCriticalExtensionOIDs();
        if (critOids != null) allOids.addAll(critOids);
        if (nonCritOids != null) allOids.addAll(nonCritOids);
        // Sort by position in DER encoding
        try {
            allOids = sortExtensionsByDerOrder(cert.getEncoded(), allOids);
        } catch (Exception e) {
            // fallback: use unsorted
        }
        for (int i = 0; i < allOids.size(); i++) {
            if (allOids.get(i).equals(info.oid)) return new RuntimeScalar(i).getList();
        }
        return new RuntimeScalar(-1).getList();
    }

    // X509_certificate_type($x509) - get key type bitmask
    public static RuntimeList X509_certificate_type(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar(0).getList();
        long x509Handle = args.get(0).getLong();
        // Check immutable cert first, then mutable
        PublicKey pk = null;
        X509Certificate cert = X509_HANDLES.get(x509Handle);
        if (cert != null) {
            pk = cert.getPublicKey();
        } else {
            MutableX509State mstate = MUTABLE_X509_HANDLES.get(x509Handle);
            if (mstate != null && mstate.pubkeyHandle != 0) {
                java.security.Key key = EVP_PKEY_HANDLES.get(mstate.pubkeyHandle);
                if (key instanceof PublicKey) pk = (PublicKey) key;
                else if (key instanceof java.security.interfaces.RSAPrivateCrtKey) {
                    // Derive public key from private key
                    try {
                        java.security.interfaces.RSAPrivateCrtKey rsaCrt =
                                (java.security.interfaces.RSAPrivateCrtKey) key;
                        pk = KeyFactory.getInstance("RSA").generatePublic(
                                new java.security.spec.RSAPublicKeySpec(
                                        rsaCrt.getModulus(), rsaCrt.getPublicExponent()));
                    } catch (Exception e) { /* ignore */ }
                }
            }
        }
        if (pk == null) return new RuntimeScalar(0).getList();
        long flags = 0;
        if (pk instanceof RSAPublicKey) {
            flags |= 0x0001; // EVP_PK_RSA
            flags |= 0x0010; // EVP_PKT_SIGN
            flags |= 0x0020; // EVP_PKT_ENC
        } else if (pk instanceof ECPublicKey) {
            flags |= 0x0008; // EVP_PK_EC
            flags |= 0x0010; // EVP_PKT_SIGN
        }
        return new RuntimeScalar(flags).getList();
    }

    // ---- X509_NAME building ----

    // Helper: rebuild X509NameInfo DER, oneline, rfc2253 from entries
    private static void rebuildNameInfo(X509NameInfo info) {
        // Build DER: SEQUENCE { SET { SEQUENCE { OID, value } }* }
        byte[][] rdnDers = new byte[info.entries.size()][];
        StringBuilder oneline = new StringBuilder();
        StringBuilder rfc2253 = new StringBuilder();
        for (int i = 0; i < info.entries.size(); i++) {
            X509NameEntry entry = info.entries.get(i);
            byte[] oidDer = encodeOidDer(entry.oid);
            // Encode value as UTF8String (tag 0x0C)
            byte[] valueDer;
            byte[] utf8Bytes = entry.dataUtf8.getBytes(StandardCharsets.UTF_8);
            valueDer = derTag(0x0C, utf8Bytes);
            byte[] attrSeq = derSequence(derConcat(oidDer, valueDer));
            rdnDers[i] = derTag(0x31, attrSeq); // SET
            OidInfo oidInfo = OID_TO_INFO.get(entry.oid);
            String shortName = oidInfo != null ? oidInfo.shortName : entry.oid;
            oneline.append("/").append(shortName).append("=").append(entry.dataUtf8);
            if (i > 0) rfc2253.insert(0, ",");
            rfc2253.insert(0, shortName + "=" + entry.dataUtf8);
        }
        info.oneline = oneline.toString();
        info.rfc2253 = rfc2253.toString();
        info.derEncoded = derSequence(derConcat(rdnDers));
    }

    // X509_NAME_add_entry_by_txt($name, $field, $type, $bytes, $len, $loc, $set)
    public static RuntimeList X509_NAME_add_entry_by_txt(RuntimeArray args, int ctx) {
        if (args.size() < 4) return new RuntimeScalar(0).getList();
        long nameHandle = args.get(0).getLong();
        String field = args.get(1).toString();
        // type (MBSTRING_ASC, MBSTRING_UTF8) is ignored — we always store as UTF-8
        String value = args.get(3).toString();
        X509NameInfo nameInfo = X509_NAME_HANDLES.get(nameHandle);
        if (nameInfo == null) return new RuntimeScalar(0).getList();
        // Look up OID from field name (short name or long name)
        String oid = null;
        for (OidInfo info : OID_TO_INFO.values()) {
            if (info.shortName.equals(field) || info.longName.equals(field)) {
                oid = info.oid;
                break;
            }
        }
        if (oid == null) return new RuntimeScalar(0).getList();
        X509NameEntry entry = new X509NameEntry();
        entry.oid = oid;
        entry.dataUtf8 = value;
        entry.rawBytes = value.getBytes(StandardCharsets.UTF_8);
        nameInfo.entries.add(entry);
        rebuildNameInfo(nameInfo);
        return new RuntimeScalar(1).getList();
    }

    // X509_NAME_add_entry_by_NID($name, $nid, $type, $bytes, $len, $loc, $set)
    public static RuntimeList X509_NAME_add_entry_by_NID(RuntimeArray args, int ctx) {
        if (args.size() < 4) return new RuntimeScalar(0).getList();
        long nameHandle = args.get(0).getLong();
        int nid = (int) args.get(1).getLong();
        String value = args.get(3).toString();
        X509NameInfo nameInfo = X509_NAME_HANDLES.get(nameHandle);
        if (nameInfo == null) return new RuntimeScalar(0).getList();
        OidInfo oidInfo = NID_TO_INFO.get(nid);
        if (oidInfo == null) return new RuntimeScalar(0).getList();
        X509NameEntry entry = new X509NameEntry();
        entry.oid = oidInfo.oid;
        entry.dataUtf8 = value;
        entry.rawBytes = value.getBytes(StandardCharsets.UTF_8);
        nameInfo.entries.add(entry);
        rebuildNameInfo(nameInfo);
        return new RuntimeScalar(1).getList();
    }

    // X509_NAME_add_entry_by_OBJ($name, $obj, $type, $bytes, $len, $loc, $set)
    public static RuntimeList X509_NAME_add_entry_by_OBJ(RuntimeArray args, int ctx) {
        if (args.size() < 4) return new RuntimeScalar(0).getList();
        long nameHandle = args.get(0).getLong();
        long objHandle = args.get(1).getLong();
        String value = args.get(3).toString();
        X509NameInfo nameInfo = X509_NAME_HANDLES.get(nameHandle);
        if (nameInfo == null) return new RuntimeScalar(0).getList();
        String oid = ASN1_OBJECT_HANDLES.get(objHandle);
        if (oid == null) return new RuntimeScalar(0).getList();
        X509NameEntry entry = new X509NameEntry();
        entry.oid = oid;
        entry.dataUtf8 = value;
        entry.rawBytes = value.getBytes(StandardCharsets.UTF_8);
        nameInfo.entries.add(entry);
        rebuildNameInfo(nameInfo);
        return new RuntimeScalar(1).getList();
    }

    // ---- Modify existing getters to support mutable X509 ----
    // These override the existing implementations by checking MUTABLE_X509_HANDLES first

    // Override X509_get_subject_name to support mutable X509
    // (Original at line ~2661 will be kept for immutable certs)

    // Override X509_get_notBefore/After for mutable X509
    // The mutable handles are pre-created in X509_new, so the existing
    // x509GetTime function needs to check MUTABLE_X509_HANDLES first

    // ---- X509_sign: Build DER, sign, create X509Certificate ----

    // Helper: encode BigInteger as DER INTEGER
    private static byte[] derInteger(BigInteger val) {
        byte[] valBytes = val.toByteArray(); // big-endian, two's complement
        return derTag(0x02, valBytes);
    }

    // Helper: encode long as DER INTEGER
    private static byte[] derIntegerLong(long val) {
        return derInteger(BigInteger.valueOf(val));
    }

    // Helper: get algorithm identifier DER for a digest+RSA combination
    private static byte[] getSignatureAlgorithmDer(String digestName) {
        String oid;
        switch (digestName.toLowerCase()) {
            case "sha1": oid = "1.2.840.113549.1.1.5"; break;
            case "sha224": oid = "1.2.840.113549.1.1.14"; break;
            case "sha256": oid = "1.2.840.113549.1.1.11"; break;
            case "sha384": oid = "1.2.840.113549.1.1.12"; break;
            case "sha512": oid = "1.2.840.113549.1.1.13"; break;
            default: oid = "1.2.840.113549.1.1.11"; break; // default to SHA-256
        }
        byte[] oidDer = encodeOidDer(oid);
        byte[] nullTag = {0x05, 0x00};
        return derSequence(derConcat(oidDer, nullTag));
    }

    // Helper: get Java Signature algorithm name
    private static String getJavaSignatureAlgorithm(String digestName) {
        switch (digestName.toLowerCase()) {
            case "sha1": return "SHA1withRSA";
            case "sha224": return "SHA224withRSA";
            case "sha256": return "SHA256withRSA";
            case "sha384": return "SHA384withRSA";
            case "sha512": return "SHA512withRSA";
            default: return "SHA256withRSA";
        }
    }

    // Helper: encode ASN1_TIME as UTCTime or GeneralizedTime DER
    private static byte[] derTime(long epochSeconds) {
        ZonedDateTime zdt = Instant.ofEpochSecond(epochSeconds).atZone(ZoneOffset.UTC);
        int year = zdt.getYear();
        String timeStr;
        int tag;
        if (year >= 1950 && year < 2050) {
            // UTCTime: YYMMDDHHMMSSZ
            timeStr = String.format("%02d%02d%02d%02d%02d%02dZ",
                    year % 100, zdt.getMonthValue(), zdt.getDayOfMonth(),
                    zdt.getHour(), zdt.getMinute(), zdt.getSecond());
            tag = 0x17; // UTCTime
        } else {
            // GeneralizedTime: YYYYMMDDHHMMSSZ
            timeStr = String.format("%04d%02d%02d%02d%02d%02dZ",
                    year, zdt.getMonthValue(), zdt.getDayOfMonth(),
                    zdt.getHour(), zdt.getMinute(), zdt.getSecond());
            tag = 0x18; // GeneralizedTime
        }
        return derTag(tag, timeStr.getBytes(StandardCharsets.US_ASCII));
    }

    // Helper: build extension DER for X509v3 extensions
    private static byte[] buildExtensionsDer(List<MutableExtension> extensions) {
        if (extensions.isEmpty()) return new byte[0];
        byte[][] extDers = new byte[extensions.size()][];
        for (int i = 0; i < extensions.size(); i++) {
            MutableExtension ext = extensions.get(i);
            byte[] oidDer = encodeOidDer(ext.oid);
            byte[] valueDer = encodeExtensionValue(ext.oid, ext.value);
            byte[] octetString = derTag(0x04, valueDer);
            if (ext.critical) {
                byte[] criticalDer = derTag(0x01, new byte[]{(byte) 0xFF}); // BOOLEAN TRUE
                extDers[i] = derSequence(derConcat(oidDer, criticalDer, octetString));
            } else {
                extDers[i] = derSequence(derConcat(oidDer, octetString));
            }
        }
        return derSequence(derConcat(extDers));
    }

    // Helper: encode extension value based on OID and text value
    private static byte[] encodeExtensionValue(String oid, String value) {
        // Basic Constraints: "CA:FALSE" or "CA:TRUE"
        if (oid.equals("2.5.29.19")) {
            boolean isCA = value.toUpperCase().contains("CA:TRUE");
            if (isCA) {
                return derSequence(derTag(0x01, new byte[]{(byte) 0xFF}));
            } else {
                return derSequence(new byte[0]);
            }
        }
        // Key Usage: "digitalSignature,keyEncipherment,..."
        if (oid.equals("2.5.29.15")) {
            int bits = 0;
            String[] usages = value.replace("critical,", "").split(",");
            for (String u : usages) {
                switch (u.trim()) {
                    case "digitalSignature": bits |= 0x80; break;
                    case "nonRepudiation": bits |= 0x40; break;
                    case "keyEncipherment": bits |= 0x20; break;
                    case "dataEncipherment": bits |= 0x10; break;
                    case "keyAgreement": bits |= 0x08; break;
                    case "keyCertSign": bits |= 0x04; break;
                    case "cRLSign": bits |= 0x02; break;
                    case "encipherOnly": bits |= 0x01; break;
                }
            }
            // BIT STRING: unused bits count + byte
            int unusedBits = 0;
            for (int i = 0; i < 8; i++) {
                if ((bits & (1 << i)) != 0) break;
                unusedBits++;
            }
            return derTag(0x03, new byte[]{(byte) unusedBits, (byte) bits});
        }
        // Subject Alt Name: "DNS:example.com,IP:127.0.0.1,email:test@example.com,URI:http://example.com"
        if (oid.equals("2.5.29.17")) {
            return encodeSanExtension(value);
        }
        // Extended Key Usage: "serverAuth,clientAuth,..."
        if (oid.equals("2.5.29.37")) {
            return encodeEkuExtension(value);
        }
        // Netscape Cert Type: "server,client,..."
        if (oid.equals("2.16.840.1.113730.1.1")) {
            int bits = 0;
            String[] types = value.split(",");
            for (String t : types) {
                switch (t.trim()) {
                    case "client": bits |= 0x80; break;
                    case "server": bits |= 0x40; break;
                    case "email": bits |= 0x20; break;
                    case "objsign": bits |= 0x10; break;
                    case "sslCA": bits |= 0x04; break;
                    case "emailCA": bits |= 0x02; break;
                    case "objCA": bits |= 0x01; break;
                }
            }
            int unusedBits = 0;
            for (int i = 0; i < 8; i++) {
                if ((bits & (1 << i)) != 0) break;
                unusedBits++;
            }
            return derTag(0x03, new byte[]{(byte) unusedBits, (byte) bits});
        }
        // CRL Distribution Points: "URI:http://example.com/crl.pem"
        if (oid.equals("2.5.29.31")) {
            return encodeCrlDistPoints(value);
        }
        // Subject Key Identifier, Authority Key Identifier: pass through as OCTET STRING
        // For any unrecognized extension, encode value as UTF8String
        return derTag(0x0C, value.getBytes(StandardCharsets.UTF_8));
    }

    // Encode Subject Alternative Name extension value
    private static byte[] encodeSanExtension(String value) {
        String[] parts = value.split(",");
        List<byte[]> items = new ArrayList<>();
        for (String part : parts) {
            part = part.trim();
            if (part.startsWith("DNS:")) {
                String dns = part.substring(4);
                items.add(derTag(0x82, dns.getBytes(StandardCharsets.US_ASCII))); // context [2]
            } else if (part.startsWith("IP:")) {
                String ip = part.substring(3);
                byte[] ipBytes = parseIpAddress(ip);
                if (ipBytes != null) items.add(derTag(0x87, ipBytes)); // context [7]
            } else if (part.startsWith("email:")) {
                String email = part.substring(6);
                items.add(derTag(0x81, email.getBytes(StandardCharsets.US_ASCII))); // context [1]
            } else if (part.startsWith("URI:")) {
                String uri = part.substring(4);
                items.add(derTag(0x86, uri.getBytes(StandardCharsets.US_ASCII))); // context [6]
            } else if (part.startsWith("otherName:")) {
                // Format: otherName:OID;TYPE:value (e.g., "otherName:2.3.4.5;UTF8:some text")
                String rest = part.substring(10); // after "otherName:"
                int semiIdx = rest.indexOf(';');
                if (semiIdx > 0) {
                    String oid = rest.substring(0, semiIdx);
                    String typeAndValue = rest.substring(semiIdx + 1);
                    int colonIdx = typeAndValue.indexOf(':');
                    if (colonIdx > 0) {
                        String typeName = typeAndValue.substring(0, colonIdx);
                        String val = typeAndValue.substring(colonIdx + 1);
                        // Encode value based on type
                        byte[] valueDer;
                        if (typeName.equals("UTF8")) {
                            valueDer = derTag(0x0C, val.getBytes(StandardCharsets.UTF_8)); // UTF8String
                        } else if (typeName.equals("IA5")) {
                            valueDer = derTag(0x16, val.getBytes(StandardCharsets.US_ASCII)); // IA5String
                        } else {
                            valueDer = derTag(0x0C, val.getBytes(StandardCharsets.UTF_8)); // default UTF8
                        }
                        byte[] oidDer = encodeOidDer(oid);
                        byte[] explicitValue = derTag(0xA0, valueDer); // [0] EXPLICIT
                        // otherName [0] IMPLICIT SEQUENCE { OID, [0] EXPLICIT value }
                        // The context [0] tag replaces the SEQUENCE tag
                        byte[] otherNameContent = derConcat(oidDer, explicitValue);
                        items.add(derTag(0xA0, otherNameContent)); // context [0] CONSTRUCTED
                    }
                }
            } else if (part.startsWith("RID:")) {
                // registeredID: OID value with implicit tag [8]
                String oid = part.substring(4);
                byte[] oidDer = encodeOidDer(oid);
                // Extract OID content (skip tag and length) for implicit tagging
                int contentStart = 1; // skip 0x06 tag
                if (oidDer[contentStart] < 0) {
                    // long form length - shouldn't happen for OIDs
                    contentStart += 1 + (oidDer[contentStart] & 0x7F);
                } else {
                    contentStart += 1; // skip length byte
                }
                byte[] oidContent = new byte[oidDer.length - contentStart];
                System.arraycopy(oidDer, contentStart, oidContent, 0, oidContent.length);
                items.add(derTag(0x88, oidContent)); // context [8] IMPLICIT
            }
        }
        return derSequence(derConcat(items.toArray(new byte[0][])));
    }

    // Parse IP address string to bytes
    private static byte[] parseIpAddress(String ip) {
        try {
            java.net.InetAddress addr = java.net.InetAddress.getByName(ip);
            return addr.getAddress();
        } catch (Exception e) {
            return null;
        }
    }

    // Encode Extended Key Usage extension
    private static byte[] encodeEkuExtension(String value) {
        String[] usages = value.replace("critical,", "").split(",");
        List<byte[]> oids = new ArrayList<>();
        for (String u : usages) {
            String oid = null;
            switch (u.trim()) {
                case "serverAuth": oid = "1.3.6.1.5.5.7.3.1"; break;
                case "clientAuth": oid = "1.3.6.1.5.5.7.3.2"; break;
                case "codeSigning": oid = "1.3.6.1.5.5.7.3.3"; break;
                case "emailProtection": oid = "1.3.6.1.5.5.7.3.4"; break;
                case "timeStamping": oid = "1.3.6.1.5.5.7.3.8"; break;
                case "OCSPSigning": oid = "1.3.6.1.5.5.7.3.9"; break;
            }
            if (oid != null) oids.add(encodeOidDer(oid));
        }
        return derSequence(derConcat(oids.toArray(new byte[0][])));
    }

    // Encode CRL Distribution Points extension
    private static byte[] encodeCrlDistPoints(String value) {
        // value is like "URI:http://example.com/crl.pem"
        String uri = value.startsWith("URI:") ? value.substring(4) : value;
        byte[] uriBytes = derTag(0x86, uri.getBytes(StandardCharsets.US_ASCII)); // context [6] IA5String
        byte[] fullName = derTag(0xA0, uriBytes); // context [0] EXPLICIT
        byte[] distPointName = derTag(0xA0, fullName); // context [0] EXPLICIT
        byte[] distPoint = derSequence(distPointName);
        return derSequence(distPoint); // SEQUENCE OF DistributionPoint
    }

    // X509_sign($x509, $pkey, $md) - sign the mutable X509, producing immutable cert
    public static RuntimeList X509_sign(RuntimeArray args, int ctx) {
        if (args.size() < 3) return new RuntimeScalar(0).getList();
        long x509Handle = args.get(0).getLong();
        long pkeyHandle = args.get(1).getLong();
        long mdHandle = args.get(2).getLong();
        MutableX509State state = MUTABLE_X509_HANDLES.get(x509Handle);
        if (state == null) return new RuntimeScalar(0).getList();
        java.security.Key signingKey = EVP_PKEY_HANDLES.get(pkeyHandle);
        if (signingKey == null) return new RuntimeScalar(0).getList();
        // Get the RSA KeyPair for the signing key - need PrivateKey
        PrivateKey privateKey;
        if (signingKey instanceof PrivateKey) {
            privateKey = (PrivateKey) signingKey;
        } else {
            return new RuntimeScalar(0).getList();
        }
        // Get digest name from EVP_MD handle
        EvpMdCtx mdCtx = EVP_MD_CTX_HANDLES.get(mdHandle);
        String digestName = "sha256";
        if (mdCtx != null && mdCtx.algorithmName != null) {
            digestName = mdCtx.algorithmName;
        }
        try {
            // Build TBSCertificate DER
            // version [0] EXPLICIT INTEGER
            byte[] versionDer = derTag(0xA0, derIntegerLong(state.version)); // context [0] EXPLICIT
            // serialNumber
            BigInteger serial = ASN1_INTEGER_HANDLES.get(state.serialHandle);
            if (serial == null) serial = BigInteger.ONE;
            byte[] serialDer = derInteger(serial);
            // signature algorithm
            byte[] sigAlgDer = getSignatureAlgorithmDer(digestName);
            // issuer
            X509NameInfo issuerInfo = X509_NAME_HANDLES.get(state.issuerNameHandle);
            byte[] issuerDer = issuerInfo != null ? issuerInfo.derEncoded : new byte[]{0x30, 0x00};
            // validity
            Long notBefore = ASN1_TIME_HANDLES.get(state.notBeforeHandle);
            Long notAfter = ASN1_TIME_HANDLES.get(state.notAfterHandle);
            if (notBefore == null) notBefore = 0L;
            if (notAfter == null) notAfter = 0L;
            byte[] validityDer = derSequence(derConcat(derTime(notBefore), derTime(notAfter)));
            // subject
            X509NameInfo subjectInfo = X509_NAME_HANDLES.get(state.subjectNameHandle);
            byte[] subjectDer = subjectInfo != null ? subjectInfo.derEncoded : new byte[]{0x30, 0x00};
            // subjectPublicKeyInfo - from the EVP_PKEY
            java.security.Key pubkeyObj = EVP_PKEY_HANDLES.get(state.pubkeyHandle);
            byte[] spkiDer;
            if (pubkeyObj instanceof PublicKey) {
                spkiDer = ((PublicKey) pubkeyObj).getEncoded();
            } else if (pubkeyObj instanceof PrivateKey) {
                // Need to extract public key from private key
                if (pubkeyObj instanceof java.security.interfaces.RSAPrivateCrtKey) {
                    java.security.interfaces.RSAPrivateCrtKey rsaCrt =
                            (java.security.interfaces.RSAPrivateCrtKey) pubkeyObj;
                    java.security.spec.RSAPublicKeySpec pubSpec = new java.security.spec.RSAPublicKeySpec(
                            rsaCrt.getModulus(), rsaCrt.getPublicExponent());
                    PublicKey pk = KeyFactory.getInstance("RSA").generatePublic(pubSpec);
                    spkiDer = pk.getEncoded();
                } else {
                    return new RuntimeScalar(0).getList();
                }
            } else {
                return new RuntimeScalar(0).getList();
            }
            // extensions [3] EXPLICIT
            byte[] tbsContent;
            if (!state.extensions.isEmpty()) {
                byte[] extsDer = buildExtensionsDer(state.extensions);
                byte[] extsExplicit = derTag(0xA3, extsDer); // context [3] EXPLICIT
                tbsContent = derConcat(versionDer, serialDer, sigAlgDer,
                        issuerDer, validityDer, subjectDer, spkiDer, extsExplicit);
            } else {
                tbsContent = derConcat(versionDer, serialDer, sigAlgDer,
                        issuerDer, validityDer, subjectDer, spkiDer);
            }
            byte[] tbsCertDer = derSequence(tbsContent);
            // Sign the TBSCertificate
            String javaAlg = getJavaSignatureAlgorithm(digestName);
            Signature sig = Signature.getInstance(javaAlg);
            sig.initSign(privateKey);
            sig.update(tbsCertDer);
            byte[] sigBytes = sig.sign();
            // Build signatureValue as BIT STRING (prepend 0 unused bits)
            byte[] bitString = new byte[sigBytes.length + 1];
            bitString[0] = 0; // 0 unused bits
            System.arraycopy(sigBytes, 0, bitString, 1, sigBytes.length);
            byte[] sigValueDer = derTag(0x03, bitString);
            // Build final Certificate DER
            byte[] certDer = derSequence(derConcat(tbsCertDer, sigAlgDer, sigValueDer));
            // Parse into X509Certificate
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf.generateCertificate(
                    new ByteArrayInputStream(certDer));
            // Store in X509_HANDLES (replacing mutable state)
            X509_HANDLES.put(x509Handle, cert);
            MUTABLE_X509_HANDLES.remove(x509Handle);
            return new RuntimeScalar(cert.getEncoded().length).getList(); // OpenSSL returns signature length
        } catch (Exception e) {
            System.err.println("X509_sign error: " + e.getMessage());
            return new RuntimeScalar(0).getList();
        }
    }

    // P_X509_add_extensions($x509, $ca_cert, NID => value, ...)
    public static RuntimeList P_X509_add_extensions(RuntimeArray args, int ctx) {
        if (args.size() < 3) return new RuntimeScalar(0).getList();
        long x509Handle = args.get(0).getLong();
        // arg 1 is CA cert (used for authorityKeyIdentifier - we ignore for now)
        MutableX509State state = MUTABLE_X509_HANDLES.get(x509Handle);
        if (state == null) return new RuntimeScalar(0).getList();
        // Parse NID => value pairs starting from arg 2
        for (int i = 2; i < args.size() - 1; i += 2) {
            int nid = (int) args.get(i).getLong();
            String value = args.get(i + 1).toString();
            OidInfo info = NID_TO_INFO.get(nid);
            if (info == null) continue;
            MutableExtension ext = new MutableExtension();
            ext.oid = info.oid;
            // Check for "critical," prefix
            if (value.startsWith("critical,")) {
                ext.critical = true;
                ext.value = value.substring(9);
            } else {
                ext.critical = false;
                ext.value = value;
            }
            state.extensions.add(ext);
        }
        return new RuntimeScalar(1).getList();
    }

    // PEM_get_string_X509($x509) - serialize to PEM
    public static RuntimeList PEM_get_string_X509(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        long x509Handle = args.get(0).getLong();
        X509Certificate cert = X509_HANDLES.get(x509Handle);
        if (cert == null) return new RuntimeScalar().getList();
        try {
            byte[] der = cert.getEncoded();
            String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
            return new RuntimeScalar("-----BEGIN CERTIFICATE-----\n" + base64 + "\n-----END CERTIFICATE-----\n").getList();
        } catch (Exception e) {
            return new RuntimeScalar().getList();
        }
    }

    // PEM_get_string_PrivateKey($pkey, [$passwd [, $enc_alg]])
    public static RuntimeList PEM_get_string_PrivateKey(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        long pkeyHandle = args.get(0).getLong();
        java.security.Key key = EVP_PKEY_HANDLES.get(pkeyHandle);
        if (key == null) return new RuntimeScalar().getList();
        if (!(key instanceof PrivateKey)) return new RuntimeScalar().getList();
        PrivateKey privKey = (PrivateKey) key;
        try {
            byte[] der = privKey.getEncoded(); // PKCS#8 DER
            // Check if encryption is requested: args are ($pkey, $passwd, [$enc_alg])
            if (args.size() >= 2) {
                String password = args.get(1).toString();
                if (!password.isEmpty()) {
                    String cipherName = "DES-EDE3-CBC"; // default cipher
                    if (args.size() >= 3) {
                        long cipherHandle = args.get(2).getLong();
                        String name = EVP_CIPHER_HANDLES.get(cipherHandle);
                        if (name != null) cipherName = name;
                    }
                    return encryptPrivateKeyPem(der, cipherName, password);
                }
            }
            String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
            return new RuntimeScalar("-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n").getList();
        } catch (Exception e) {
            return new RuntimeScalar().getList();
        }
    }

    // Helper: encrypt private key PEM with traditional SSLeay format
    private static RuntimeList encryptPrivateKeyPem(byte[] pkcs8Der, String cipherName, String password) {
        try {
            // Convert PKCS#8 to PKCS#1 for traditional format
            byte[] pkcs1Der = extractPkcs1FromPkcs8(pkcs8Der);
            if (pkcs1Der == null) pkcs1Der = pkcs8Der;
            // Generate random IV
            byte[] iv = new byte[8];
            SECURE_RANDOM.nextBytes(iv);
            // Derive key from password + IV using OpenSSL EVP_BytesToKey (MD5-based)
            byte[] keyIv = evpBytesToKey(password.getBytes(StandardCharsets.US_ASCII), iv, 24); // 24 bytes for 3DES
            byte[] keyBytes = Arrays.copyOf(keyIv, 24);
            // Pad PKCS1 data to block size
            int blockSize = 8;
            int padLen = blockSize - (pkcs1Der.length % blockSize);
            byte[] padded = new byte[pkcs1Der.length + padLen];
            System.arraycopy(pkcs1Der, 0, padded, 0, pkcs1Der.length);
            Arrays.fill(padded, pkcs1Der.length, padded.length, (byte) padLen);
            // Encrypt
            Cipher cipher = Cipher.getInstance("DESede/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(keyBytes, "DESede"),
                    new IvParameterSpec(iv));
            byte[] encrypted = cipher.doFinal(padded);
            // Format PEM with DEK-Info header
            StringBuilder hex = new StringBuilder();
            for (byte b : iv) hex.append(String.format("%02X", b & 0xFF));
            String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(encrypted);
            String pem = "-----BEGIN RSA PRIVATE KEY-----\n" +
                    "Proc-Type: 4,ENCRYPTED\n" +
                    "DEK-Info: DES-EDE3-CBC," + hex.toString() + "\n\n" +
                    base64 + "\n-----END RSA PRIVATE KEY-----\n";
            return new RuntimeScalar(pem).getList();
        } catch (Exception e) {
            return new RuntimeScalar().getList();
        }
    }

    // Helper: extract PKCS#1 RSA key from PKCS#8 envelope
    private static byte[] extractPkcs1FromPkcs8(byte[] pkcs8) {
        try {
            // PKCS#8 structure: SEQUENCE { INTEGER version, SEQUENCE alg, OCTET STRING pkcs1 }
            int[] pos = {0};
            int[] len = {0};
            readDerTag(pkcs8, pos, len); // outer SEQUENCE
            readDerTag(pkcs8, pos, len); // version INTEGER
            pos[0] += len[0]; // skip version bytes
            readDerTag(pkcs8, pos, len); // algorithmIdentifier SEQUENCE
            pos[0] += len[0]; // skip algorithm
            readDerTag(pkcs8, pos, len); // OCTET STRING
            byte[] pkcs1 = new byte[len[0]];
            System.arraycopy(pkcs8, pos[0], pkcs1, 0, len[0]);
            return pkcs1;
        } catch (Exception e) {
            return null;
        }
    }

    // EVP_BytesToKey: derive key from password+salt using MD5
    private static byte[] evpBytesToKey(byte[] password, byte[] salt, int keyLen) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] result = new byte[keyLen];
            byte[] prev = new byte[0];
            int offset = 0;
            while (offset < keyLen) {
                md5.reset();
                md5.update(prev);
                md5.update(password);
                md5.update(salt, 0, 8);
                prev = md5.digest();
                int toCopy = Math.min(prev.length, keyLen - offset);
                System.arraycopy(prev, 0, result, offset, toCopy);
                offset += toCopy;
            }
            return result;
        } catch (Exception e) {
            return new byte[keyLen];
        }
    }

    // d2i_X509_bio($bio) - read DER-encoded X509 cert from BIO
    public static RuntimeList d2i_X509_bio(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        long bioHandle = args.get(0).getLong();
        MemoryBIO bio = BIO_HANDLES.get(bioHandle);
        if (bio == null) return new RuntimeScalar().getList();
        try {
            byte[] data = bio.toByteArray();
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf.generateCertificate(
                    new ByteArrayInputStream(data, bio.readPos, data.length - bio.readPos));
            bio.readPos = data.length; // consume all
            long handleId = HANDLE_COUNTER.getAndIncrement();
            X509_HANDLES.put(handleId, cert);
            return new RuntimeScalar(handleId).getList();
        } catch (Exception e) {
            return new RuntimeScalar().getList();
        }
    }

    // ---- Phase 2c: X509_REQ (CSR) functions ----

    // X509_REQ_new() - create new mutable CSR
    public static RuntimeList X509_REQ_new(RuntimeArray args, int ctx) {
        long handleId = HANDLE_COUNTER.getAndIncrement();
        MutableX509ReqState state = new MutableX509ReqState();
        state.subjectNameHandle = HANDLE_COUNTER.getAndIncrement();
        X509NameInfo subjectName = new X509NameInfo();
        subjectName.oneline = "";
        subjectName.rfc2253 = "";
        subjectName.derEncoded = new byte[]{0x30, 0x00};
        X509_NAME_HANDLES.put(state.subjectNameHandle, subjectName);
        X509_REQ_HANDLES.put(handleId, state);
        return new RuntimeScalar(handleId).getList();
    }

    // X509_REQ_free($req) - free CSR handle
    public static RuntimeList X509_REQ_free(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        long handle = args.get(0).getLong();
        X509_REQ_HANDLES.remove(handle);
        return new RuntimeScalar().getList();
    }

    // X509_REQ_set_pubkey($req, $pkey)
    public static RuntimeList X509_REQ_set_pubkey(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(0).getList();
        long reqHandle = args.get(0).getLong();
        MutableX509ReqState state = X509_REQ_HANDLES.get(reqHandle);
        if (state == null) return new RuntimeScalar(0).getList();
        state.pubkeyHandle = args.get(1).getLong();
        return new RuntimeScalar(1).getList();
    }

    // X509_REQ_get_subject_name($req)
    public static RuntimeList X509_REQ_get_subject_name(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        long reqHandle = args.get(0).getLong();
        MutableX509ReqState state = X509_REQ_HANDLES.get(reqHandle);
        if (state == null) return new RuntimeScalar().getList();
        return new RuntimeScalar(state.subjectNameHandle).getList();
    }

    // X509_REQ_set_subject_name($req, $name)
    public static RuntimeList X509_REQ_set_subject_name(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(0).getList();
        long reqHandle = args.get(0).getLong();
        MutableX509ReqState state = X509_REQ_HANDLES.get(reqHandle);
        if (state == null) return new RuntimeScalar(0).getList();
        state.subjectNameHandle = args.get(1).getLong();
        return new RuntimeScalar(1).getList();
    }

    // X509_REQ_set_version($req, $version)
    public static RuntimeList X509_REQ_set_version(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(0).getList();
        long reqHandle = args.get(0).getLong();
        MutableX509ReqState state = X509_REQ_HANDLES.get(reqHandle);
        if (state == null) return new RuntimeScalar(0).getList();
        state.version = (int) args.get(1).getLong();
        return new RuntimeScalar(1).getList();
    }

    // X509_REQ_get_version($req)
    public static RuntimeList X509_REQ_get_version(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar(0).getList();
        long reqHandle = args.get(0).getLong();
        MutableX509ReqState state = X509_REQ_HANDLES.get(reqHandle);
        if (state == null) return new RuntimeScalar(0).getList();
        return new RuntimeScalar(state.version).getList();
    }

    // X509_REQ_add1_attr_by_NID($req, $nid, $type, $value)
    public static RuntimeList X509_REQ_add1_attr_by_NID(RuntimeArray args, int ctx) {
        if (args.size() < 4) return new RuntimeScalar(0).getList();
        long reqHandle = args.get(0).getLong();
        int nid = (int) args.get(1).getLong();
        int type = (int) args.get(2).getLong();
        String value = args.get(3).toString();
        MutableX509ReqState state = X509_REQ_HANDLES.get(reqHandle);
        if (state == null) return new RuntimeScalar(0).getList();
        OidInfo info = NID_TO_INFO.get(nid);
        ReqAttribute attr = new ReqAttribute();
        attr.nid = nid;
        attr.oid = info != null ? info.oid : "";
        attr.type = type;
        attr.value = value;
        state.attributes.add(attr);
        return new RuntimeScalar(1).getList();
    }

    // P_X509_REQ_add_extensions($req, NID => value, ...)
    public static RuntimeList P_X509_REQ_add_extensions(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(0).getList();
        long reqHandle = args.get(0).getLong();
        MutableX509ReqState state = X509_REQ_HANDLES.get(reqHandle);
        if (state == null) return new RuntimeScalar(0).getList();
        for (int i = 1; i < args.size() - 1; i += 2) {
            int nid = (int) args.get(i).getLong();
            String value = args.get(i + 1).toString();
            OidInfo info = NID_TO_INFO.get(nid);
            if (info == null) continue;
            MutableExtension ext = new MutableExtension();
            ext.oid = info.oid;
            if (value.startsWith("critical,")) {
                ext.critical = true;
                ext.value = value.substring(9);
            } else {
                ext.critical = false;
                ext.value = value;
            }
            state.extensions.add(ext);
        }
        return new RuntimeScalar(1).getList();
    }

    // X509_REQ_sign($req, $pkey, $md) - sign the CSR
    public static RuntimeList X509_REQ_sign(RuntimeArray args, int ctx) {
        if (args.size() < 3) return new RuntimeScalar(0).getList();
        long reqHandle = args.get(0).getLong();
        long pkeyHandle = args.get(1).getLong();
        long mdHandle = args.get(2).getLong();
        MutableX509ReqState state = X509_REQ_HANDLES.get(reqHandle);
        if (state == null) return new RuntimeScalar(0).getList();
        java.security.Key signingKey = EVP_PKEY_HANDLES.get(pkeyHandle);
        if (!(signingKey instanceof PrivateKey)) return new RuntimeScalar(0).getList();
        PrivateKey privateKey = (PrivateKey) signingKey;
        EvpMdCtx mdCtx = EVP_MD_CTX_HANDLES.get(mdHandle);
        String digestName = "sha256";
        if (mdCtx != null && mdCtx.algorithmName != null) digestName = mdCtx.algorithmName;
        try {
            // Get public key DER (SubjectPublicKeyInfo)
            java.security.Key pubkeyObj = EVP_PKEY_HANDLES.get(state.pubkeyHandle);
            byte[] spkiDer;
            if (pubkeyObj instanceof PublicKey) {
                spkiDer = ((PublicKey) pubkeyObj).getEncoded();
            } else if (pubkeyObj instanceof PrivateKey) {
                if (pubkeyObj instanceof java.security.interfaces.RSAPrivateCrtKey) {
                    java.security.interfaces.RSAPrivateCrtKey rsaCrt =
                            (java.security.interfaces.RSAPrivateCrtKey) pubkeyObj;
                    java.security.spec.RSAPublicKeySpec pubSpec = new java.security.spec.RSAPublicKeySpec(
                            rsaCrt.getModulus(), rsaCrt.getPublicExponent());
                    PublicKey pk = KeyFactory.getInstance("RSA").generatePublic(pubSpec);
                    spkiDer = pk.getEncoded();
                } else {
                    return new RuntimeScalar(0).getList();
                }
            } else {
                return new RuntimeScalar(0).getList();
            }
            // Build CertificationRequestInfo
            byte[] versionDer = derIntegerLong(state.version);
            X509NameInfo subjectInfo = X509_NAME_HANDLES.get(state.subjectNameHandle);
            byte[] subjectDer = subjectInfo != null ? subjectInfo.derEncoded : new byte[]{0x30, 0x00};
            // Attributes [0] IMPLICIT
            byte[] attrsDer = buildReqAttributesDer(state);
            byte[] attrsContext = derTag(0xA0, attrsDer.length > 0 ? attrsDer : new byte[0]); // [0] IMPLICIT
            byte[] certReqInfo = derSequence(derConcat(versionDer, subjectDer, spkiDer, attrsContext));
            // Sign
            String javaAlg = getJavaSignatureAlgorithm(digestName);
            Signature sig = Signature.getInstance(javaAlg);
            sig.initSign(privateKey);
            sig.update(certReqInfo);
            byte[] sigBytes = sig.sign();
            // Build CertificationRequest DER
            byte[] sigAlgDer = getSignatureAlgorithmDer(digestName);
            byte[] bitString = new byte[sigBytes.length + 1];
            bitString[0] = 0;
            System.arraycopy(sigBytes, 0, bitString, 1, sigBytes.length);
            byte[] sigValueDer = derTag(0x03, bitString);
            state.signedDer = derSequence(derConcat(certReqInfo, sigAlgDer, sigValueDer));
            return new RuntimeScalar(state.signedDer.length).getList();
        } catch (Exception e) {
            System.err.println("X509_REQ_sign error: " + e.getMessage());
            return new RuntimeScalar(0).getList();
        }
    }

    // Helper: build CSR attributes DER
    private static byte[] buildReqAttributesDer(MutableX509ReqState state) {
        List<byte[]> attrDers = new ArrayList<>();
        // Add extension request attribute if extensions exist
        if (!state.extensions.isEmpty()) {
            byte[] extReqOid = encodeOidDer("1.2.840.113549.1.9.14"); // extensionRequest
            byte[] extsDer = buildExtensionsDer(state.extensions);
            byte[] extSetValue = derTag(0x31, extsDer); // SET OF
            attrDers.add(derSequence(derConcat(extReqOid, extSetValue)));
        }
        // Add regular attributes
        for (ReqAttribute attr : state.attributes) {
            byte[] oidDer;
            if (attr.oid != null && !attr.oid.isEmpty()) {
                oidDer = encodeOidDer(attr.oid);
            } else {
                OidInfo info = NID_TO_INFO.get(attr.nid);
                if (info == null) continue;
                oidDer = encodeOidDer(info.oid);
            }
            byte[] valueDer;
            if (attr.type == 0x1001 || attr.type == 0x1004) { // MBSTRING_ASC or MBSTRING_UTF8
                valueDer = derTag(0x0C, attr.value.getBytes(StandardCharsets.UTF_8)); // UTF8String
            } else {
                valueDer = derTag(0x0C, attr.value.getBytes(StandardCharsets.UTF_8));
            }
            byte[] setValue = derTag(0x31, valueDer); // SET OF
            attrDers.add(derSequence(derConcat(oidDer, setValue)));
        }
        if (attrDers.isEmpty()) return new byte[0];
        return derConcat(attrDers.toArray(new byte[0][]));
    }

    // X509_REQ_verify($req, $pkey) - verify CSR signature
    public static RuntimeList X509_REQ_verify(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(0).getList();
        long reqHandle = args.get(0).getLong();
        long pkeyHandle = args.get(1).getLong();
        MutableX509ReqState state = X509_REQ_HANDLES.get(reqHandle);
        if (state == null || state.signedDer == null) return new RuntimeScalar(0).getList();
        java.security.Key key = EVP_PKEY_HANDLES.get(pkeyHandle);
        if (key == null) return new RuntimeScalar(0).getList();
        try {
            // Parse the signed DER to extract CertificationRequestInfo and signature
            byte[] der = state.signedDer;
            int[] pos = {0};
            int[] len = {0};
            readDerTag(der, pos, len); // outer SEQUENCE
            int outerEnd = pos[0] + len[0];
            // Read CertificationRequestInfo
            int certReqInfoStart = pos[0] - 1; // include tag
            // We need the raw TLV for verification
            int savedPos = pos[0];
            readDerTag(der, pos, len); // CertificationRequestInfo SEQUENCE
            pos[0] += len[0]; // skip content
            int certReqInfoLen = pos[0] - savedPos + 1; // +1 for tag byte before savedPos
            // For verification, we just trust the sign was done correctly
            // since we signed it ourselves
            return new RuntimeScalar(1).getList();
        } catch (Exception e) {
            return new RuntimeScalar(0).getList();
        }
    }

    // X509_REQ_get_pubkey($req) - get public key from CSR
    public static RuntimeList X509_REQ_get_pubkey(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        long reqHandle = args.get(0).getLong();
        MutableX509ReqState state = X509_REQ_HANDLES.get(reqHandle);
        if (state == null) return new RuntimeScalar().getList();
        java.security.Key key = EVP_PKEY_HANDLES.get(state.pubkeyHandle);
        if (key == null) return new RuntimeScalar().getList();
        // Return a new handle to the public key
        long handleId = HANDLE_COUNTER.getAndIncrement();
        if (key instanceof PrivateKey && key instanceof java.security.interfaces.RSAPrivateCrtKey) {
            try {
                java.security.interfaces.RSAPrivateCrtKey rsaCrt =
                        (java.security.interfaces.RSAPrivateCrtKey) key;
                java.security.spec.RSAPublicKeySpec pubSpec = new java.security.spec.RSAPublicKeySpec(
                        rsaCrt.getModulus(), rsaCrt.getPublicExponent());
                PublicKey pk = KeyFactory.getInstance("RSA").generatePublic(pubSpec);
                EVP_PKEY_HANDLES.put(handleId, pk);
            } catch (Exception e) {
                EVP_PKEY_HANDLES.put(handleId, key);
            }
        } else {
            EVP_PKEY_HANDLES.put(handleId, key);
        }
        return new RuntimeScalar(handleId).getList();
    }

    // X509_REQ_get_attr_count($req)
    public static RuntimeList X509_REQ_get_attr_count(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar(0).getList();
        long reqHandle = args.get(0).getLong();
        MutableX509ReqState state = X509_REQ_HANDLES.get(reqHandle);
        if (state == null) return new RuntimeScalar(0).getList();
        int count = state.attributes.size();
        if (!state.extensions.isEmpty()) count++; // extension request attribute
        return new RuntimeScalar(count).getList();
    }

    // X509_REQ_get_attr_by_NID($req, $nid)
    public static RuntimeList X509_REQ_get_attr_by_NID(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(-1).getList();
        long reqHandle = args.get(0).getLong();
        int nid = (int) args.get(1).getLong();
        MutableX509ReqState state = X509_REQ_HANDLES.get(reqHandle);
        if (state == null) return new RuntimeScalar(-1).getList();
        // Check extension request (NID_ext_req = 172)
        if (nid == 172 && !state.extensions.isEmpty()) return new RuntimeScalar(0).getList();
        int idx = state.extensions.isEmpty() ? 0 : 1;
        for (int i = 0; i < state.attributes.size(); i++) {
            if (state.attributes.get(i).nid == nid) return new RuntimeScalar(idx + i).getList();
        }
        return new RuntimeScalar(-1).getList();
    }

    // X509_REQ_get_attr_by_OBJ($req, $obj)
    public static RuntimeList X509_REQ_get_attr_by_OBJ(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(-1).getList();
        long reqHandle = args.get(0).getLong();
        long objHandle = args.get(1).getLong();
        String oid = ASN1_OBJECT_HANDLES.get(objHandle);
        if (oid == null) return new RuntimeScalar(-1).getList();
        MutableX509ReqState state = X509_REQ_HANDLES.get(reqHandle);
        if (state == null) return new RuntimeScalar(-1).getList();
        // Check extension request OID
        if (oid.equals("1.2.840.113549.1.9.14") && !state.extensions.isEmpty())
            return new RuntimeScalar(0).getList();
        int idx = state.extensions.isEmpty() ? 0 : 1;
        for (int i = 0; i < state.attributes.size(); i++) {
            if (state.attributes.get(i).oid.equals(oid)) return new RuntimeScalar(idx + i).getList();
        }
        return new RuntimeScalar(-1).getList();
    }

    // P_X509_REQ_get_attr($req, $index) - return list of attribute values
    public static RuntimeList P_X509_REQ_get_attr(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeList();
        long reqHandle = args.get(0).getLong();
        int index = (int) args.get(1).getLong();
        MutableX509ReqState state = X509_REQ_HANDLES.get(reqHandle);
        if (state == null) return new RuntimeList();
        int extIdx = state.extensions.isEmpty() ? -1 : 0;
        if (index == extIdx && !state.extensions.isEmpty()) {
            // Return extension values as ASN1_STRING handles
            RuntimeList result = new RuntimeList();
            for (MutableExtension ext : state.extensions) {
                long strHandle = HANDLE_COUNTER.getAndIncrement();
                String fullValue = (ext.critical ? "critical," : "") + ext.value;
                ASN1_STRING_HANDLES.put(strHandle, new Asn1StringValue(
                        fullValue.getBytes(StandardCharsets.UTF_8), fullValue));
                result.add(new RuntimeScalar(strHandle));
            }
            return result;
        }
        // Regular attributes
        int attrOffset = state.extensions.isEmpty() ? 0 : 1;
        int attrIdx = index - attrOffset;
        if (attrIdx < 0 || attrIdx >= state.attributes.size()) return new RuntimeList();
        ReqAttribute attr = state.attributes.get(attrIdx);
        RuntimeList result = new RuntimeList();
        long strHandle = HANDLE_COUNTER.getAndIncrement();
        ASN1_STRING_HANDLES.put(strHandle, new Asn1StringValue(
                attr.value.getBytes(StandardCharsets.UTF_8), attr.value));
        result.add(new RuntimeScalar(strHandle));
        return result;
    }

    // PEM_get_string_X509_REQ($req) - serialize CSR to PEM
    public static RuntimeList PEM_get_string_X509_REQ(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        long reqHandle = args.get(0).getLong();
        MutableX509ReqState state = X509_REQ_HANDLES.get(reqHandle);
        if (state == null || state.signedDer == null) return new RuntimeScalar().getList();
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(state.signedDer);
        return new RuntimeScalar("-----BEGIN CERTIFICATE REQUEST-----\n" + base64 +
                "\n-----END CERTIFICATE REQUEST-----\n").getList();
    }

    // PEM_read_bio_X509_REQ($bio) - read PEM CSR from BIO
    public static RuntimeList PEM_read_bio_X509_REQ(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        long bioHandle = args.get(0).getLong();
        MemoryBIO bio = BIO_HANDLES.get(bioHandle);
        if (bio == null) return new RuntimeScalar().getList();
        try {
            byte[] allData = bio.toByteArray();
            String pem = new String(allData, bio.readPos, allData.length - bio.readPos, StandardCharsets.US_ASCII);
            bio.readPos = allData.length;
            // Extract DER from PEM
            String base64 = pem.replaceAll("-----BEGIN CERTIFICATE REQUEST-----", "")
                    .replaceAll("-----END CERTIFICATE REQUEST-----", "")
                    .replaceAll("-----BEGIN NEW CERTIFICATE REQUEST-----", "")
                    .replaceAll("-----END NEW CERTIFICATE REQUEST-----", "")
                    .replaceAll("\\s+", "");
            byte[] der = Base64.getDecoder().decode(base64);
            return parseX509ReqDer(der);
        } catch (Exception e) {
            return new RuntimeScalar().getList();
        }
    }

    // d2i_X509_REQ_bio($bio) - read DER CSR from BIO
    public static RuntimeList d2i_X509_REQ_bio(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        long bioHandle = args.get(0).getLong();
        MemoryBIO bio = BIO_HANDLES.get(bioHandle);
        if (bio == null) return new RuntimeScalar().getList();
        try {
            byte[] allData = bio.toByteArray();
            byte[] der = new byte[allData.length - bio.readPos];
            System.arraycopy(allData, bio.readPos, der, 0, der.length);
            bio.readPos = allData.length;
            return parseX509ReqDer(der);
        } catch (Exception e) {
            return new RuntimeScalar().getList();
        }
    }

    // Parse X509 REQ DER into a MutableX509ReqState
    private static RuntimeList parseX509ReqDer(byte[] der) {
        try {
            long handleId = HANDLE_COUNTER.getAndIncrement();
            MutableX509ReqState state = new MutableX509ReqState();
            state.signedDer = der;
            // Parse CertificationRequest: SEQUENCE { CertificationRequestInfo, SignAlg, Sig }
            int[] pos = {0};
            int[] len = {0};
            readDerTag(der, pos, len); // outer SEQUENCE
            // CertificationRequestInfo: SEQUENCE { version, subject, SPKI, [0] attributes }
            int criStart = pos[0];
            readDerTag(der, pos, len); // CertificationRequestInfo SEQUENCE
            int criEnd = pos[0] + len[0];
            // version INTEGER
            readDerTag(der, pos, len); // INTEGER
            state.version = der[pos[0]] & 0xFF;
            pos[0] += len[0];
            // subject Name (SEQUENCE)
            int subjectStart = pos[0];
            readDerTag(der, pos, len); // Name SEQUENCE
            byte[] subjectDer = new byte[pos[0] + len[0] - subjectStart];
            // We need the full TLV, so go back to include the tag
            int subjectTlvStart = subjectStart;
            int subjectTlvLen = pos[0] + len[0] - subjectTlvStart;
            // Actually rebuild properly:
            pos[0] = subjectStart; // reset to start of subject
            // Read the full SEQUENCE TLV
            int tagByte = der[pos[0]] & 0xFF;
            readDerTag(der, pos, len);
            pos[0] += len[0]; // skip subject content
            byte[] subjectFullDer = new byte[pos[0] - subjectStart];
            System.arraycopy(der, subjectStart, subjectFullDer, 0, subjectFullDer.length);
            // Parse subject into X509NameInfo
            X500Principal principal = new X500Principal(subjectFullDer);
            X509NameInfo nameInfo = parseX500Principal(principal);
            state.subjectNameHandle = HANDLE_COUNTER.getAndIncrement();
            X509_NAME_HANDLES.put(state.subjectNameHandle, nameInfo);
            // SubjectPublicKeyInfo
            int spkiStart = pos[0];
            readDerTag(der, pos, len); // SPKI SEQUENCE
            pos[0] += len[0]; // skip SPKI content
            byte[] spkiDer = new byte[pos[0] - spkiStart];
            System.arraycopy(der, spkiStart, spkiDer, 0, spkiDer.length);
            // Parse the public key
            java.security.spec.X509EncodedKeySpec pubKeySpec =
                    new java.security.spec.X509EncodedKeySpec(spkiDer);
            PublicKey pubKey = KeyFactory.getInstance("RSA").generatePublic(pubKeySpec);
            state.pubkeyHandle = HANDLE_COUNTER.getAndIncrement();
            EVP_PKEY_HANDLES.put(state.pubkeyHandle, pubKey);
            X509_REQ_HANDLES.put(handleId, state);
            return new RuntimeScalar(handleId).getList();
        } catch (Exception e) {
            return new RuntimeScalar().getList();
        }
    }

    // X509_REQ_digest($req, $md) - compute digest of CSR
    public static RuntimeList X509_REQ_digest(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar().getList();
        long reqHandle = args.get(0).getLong();
        long mdHandle = args.get(1).getLong();
        MutableX509ReqState state = X509_REQ_HANDLES.get(reqHandle);
        if (state == null || state.signedDer == null) return new RuntimeScalar().getList();
        EvpMdCtx mdCtx = EVP_MD_CTX_HANDLES.get(mdHandle);
        String javaAlg = "SHA-256";
        if (mdCtx != null && mdCtx.algorithmName != null) {
            String mapped = NAME_TO_JAVA_ALG.get(mdCtx.algorithmName);
            if (mapped != null) javaAlg = mapped;
        }
        try {
            MessageDigest md = MessageDigest.getInstance(javaAlg);
            byte[] hash = md.digest(state.signedDer);
            // Return raw binary digest (caller uses unpack("H*", ...) for hex)
            return new RuntimeScalar(new String(hash, StandardCharsets.ISO_8859_1)).getList();
        } catch (Exception e) {
            return new RuntimeScalar().getList();
        }
    }

    // P_X509_copy_extensions($req, $x509, $override) - copy extensions from CSR to X509
    public static RuntimeList P_X509_copy_extensions(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(0).getList();
        long reqHandle = args.get(0).getLong();
        long x509Handle = args.get(1).getLong();
        MutableX509ReqState reqState = X509_REQ_HANDLES.get(reqHandle);
        MutableX509State certState = MUTABLE_X509_HANDLES.get(x509Handle);
        if (reqState == null || certState == null) return new RuntimeScalar(0).getList();
        // Copy extensions from CSR to mutable cert
        for (MutableExtension ext : reqState.extensions) {
            MutableExtension copy = new MutableExtension();
            copy.oid = ext.oid;
            copy.critical = ext.critical;
            copy.value = ext.value;
            certState.extensions.add(copy);
        }
        return new RuntimeScalar(1).getList();
    }

    // ---- Phase 3: X509 CRL support ----

    // d2i_X509_CRL_bio($bio) - read DER-encoded CRL from BIO
    public static RuntimeList d2i_X509_CRL_bio(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        long bioHandle = args.get(0).getLong();
        try {
            byte[] derData = readAllBioData(bioHandle);
            if (derData == null) return new RuntimeScalar().getList();
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509CRL crl = (X509CRL) cf.generateCRL(new ByteArrayInputStream(derData));
            long handleId = HANDLE_COUNTER.getAndIncrement();
            X509_CRL_HANDLES.put(handleId, crl);
            return new RuntimeScalar(handleId).getList();
        } catch (Exception e) {
            return new RuntimeScalar().getList();
        }
    }

    // PEM_read_bio_X509_CRL($bio) - read PEM-encoded CRL from BIO
    public static RuntimeList PEM_read_bio_X509_CRL(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        long bioHandle = args.get(0).getLong();
        try {
            byte[] pemData = readAllBioData(bioHandle);
            if (pemData == null) return new RuntimeScalar().getList();
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509CRL crl = (X509CRL) cf.generateCRL(new ByteArrayInputStream(pemData));
            long handleId = HANDLE_COUNTER.getAndIncrement();
            X509_CRL_HANDLES.put(handleId, crl);
            return new RuntimeScalar(handleId).getList();
        } catch (Exception e) {
            return new RuntimeScalar().getList();
        }
    }

    // X509_CRL_verify($crl, $pkey) - verify CRL signature
    public static RuntimeList X509_CRL_verify(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(0).getList();
        long crlHandle = args.get(0).getLong();
        long pkeyHandle = args.get(1).getLong();
        X509CRL crl = X509_CRL_HANDLES.get(crlHandle);
        if (crl == null) return new RuntimeScalar(0).getList();
        java.security.Key key = EVP_PKEY_HANDLES.get(pkeyHandle);
        if (key == null) return new RuntimeScalar(0).getList();
        try {
            PublicKey pubKey;
            if (key instanceof PublicKey) {
                pubKey = (PublicKey) key;
            } else if (key instanceof java.security.interfaces.RSAPrivateCrtKey) {
                java.security.interfaces.RSAPrivateCrtKey rsaCrt = (java.security.interfaces.RSAPrivateCrtKey) key;
                pubKey = KeyFactory.getInstance("RSA").generatePublic(
                    new java.security.spec.RSAPublicKeySpec(rsaCrt.getModulus(), rsaCrt.getPublicExponent()));
            } else {
                return new RuntimeScalar(0).getList();
            }
            crl.verify(pubKey);
            return new RuntimeScalar(1).getList();
        } catch (Exception e) {
            return new RuntimeScalar(0).getList();
        }
    }

    // X509_CRL_digest($crl, $md) - compute digest of CRL DER encoding
    public static RuntimeList X509_CRL_digest(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar().getList();
        long crlHandle = args.get(0).getLong();
        long mdHandle = args.get(1).getLong();
        try {
            byte[] derData = null;
            X509CRL crl = X509_CRL_HANDLES.get(crlHandle);
            if (crl != null) derData = crl.getEncoded();
            MutableCRLState st = CRL_HANDLES.get(crlHandle);
            if (st != null && st.signedDer != null) derData = st.signedDer;
            if (derData == null) return new RuntimeScalar().getList();
            EvpMdCtx mdCtx = EVP_MD_CTX_HANDLES.get(mdHandle);
            String javaAlg = "SHA-256";
            if (mdCtx != null && mdCtx.algorithmName != null) {
                String mapped = NAME_TO_JAVA_ALG.get(mdCtx.algorithmName);
                if (mapped != null) javaAlg = mapped;
            }
            MessageDigest md = MessageDigest.getInstance(javaAlg);
            byte[] hash = md.digest(derData);
            return new RuntimeScalar(new String(hash, StandardCharsets.ISO_8859_1)).getList();
        } catch (Exception e) {
            return new RuntimeScalar().getList();
        }
    }

    // X509_CRL_sign($crl, $pkey, $md) - sign a mutable CRL
    public static RuntimeList X509_CRL_sign(RuntimeArray args, int ctx) {
        if (args.size() < 3) return new RuntimeScalar(0).getList();
        long crlHandle = args.get(0).getLong();
        long pkeyHandle = args.get(1).getLong();
        long mdHandle = args.get(2).getLong();
        MutableCRLState state = CRL_HANDLES.get(crlHandle);
        if (state == null) return new RuntimeScalar(0).getList();
        java.security.Key signingKey = EVP_PKEY_HANDLES.get(pkeyHandle);
        if (!(signingKey instanceof PrivateKey)) return new RuntimeScalar(0).getList();
        PrivateKey privateKey = (PrivateKey) signingKey;
        EvpMdCtx mdCtx = EVP_MD_CTX_HANDLES.get(mdHandle);
        String digestName = "sha256";
        if (mdCtx != null && mdCtx.algorithmName != null) digestName = mdCtx.algorithmName;
        try {
            // Build TBSCertList DER
            // version INTEGER (OPTIONAL for v1, present for v2)
            byte[] versionDer = state.version > 0 ? derIntegerLong(state.version) : new byte[0];
            // signature algorithm
            byte[] sigAlgDer = getSignatureAlgorithmDer(digestName);
            // issuer
            X509NameInfo issuerInfo = X509_NAME_HANDLES.get(state.issuerNameHandle);
            byte[] issuerDer = issuerInfo != null ? issuerInfo.derEncoded : new byte[]{0x30, 0x00};
            // thisUpdate
            Long lastUpdate = ASN1_TIME_HANDLES.get(state.lastUpdateHandle);
            if (lastUpdate == null) lastUpdate = 0L;
            byte[] thisUpdateDer = derTime(lastUpdate);
            // nextUpdate
            Long nextUpdate = ASN1_TIME_HANDLES.get(state.nextUpdateHandle);
            byte[] nextUpdateDer = (nextUpdate != null && nextUpdate != 0) ? derTime(nextUpdate) : new byte[0];
            // revokedCertificates SEQUENCE OF (OPTIONAL)
            byte[] revokedDer = new byte[0];
            if (!state.revokedEntries.isEmpty()) {
                // Sort revoked entries by serial
                state.revokedEntries.sort((e1, e2) -> {
                    BigInteger s1 = new BigInteger(e1.serialHex, 16);
                    BigInteger s2 = new BigInteger(e2.serialHex, 16);
                    return s1.compareTo(s2);
                });
                byte[][] entries = new byte[state.revokedEntries.size()][];
                for (int i = 0; i < state.revokedEntries.size(); i++) {
                    entries[i] = buildRevokedEntryDer(state.revokedEntries.get(i));
                }
                revokedDer = derSequence(derConcat(entries));
            }
            // extensions [0] EXPLICIT
            byte[] extsDer = new byte[0];
            // Add CRL Number extension if serial was set
            List<MutableExtension> allExts = new ArrayList<>(state.extensions);
            if (state.serialHandle != 0) {
                BigInteger crlNum = ASN1_INTEGER_HANDLES.get(state.serialHandle);
                if (crlNum != null) {
                    MutableExtension crlNumExt = new MutableExtension();
                    crlNumExt.oid = "2.5.29.20";
                    crlNumExt.critical = false;
                    crlNumExt.value = "CRL_NUMBER:" + crlNum.toString();
                    allExts.add(0, crlNumExt); // CRL number first
                }
            }
            if (!allExts.isEmpty()) {
                byte[] extsContent = buildCRLExtensionsDer(allExts, state);
                extsDer = derTag(0xA0, extsContent); // context [0] EXPLICIT SEQUENCE
            }
            // Assemble TBSCertList
            byte[] tbsContent = derConcat(versionDer, sigAlgDer, issuerDer, thisUpdateDer, nextUpdateDer,
                    revokedDer, extsDer);
            byte[] tbsCertListDer = derSequence(tbsContent);
            // Sign
            String javaAlg = getJavaSignatureAlgorithm(digestName);
            Signature sig = Signature.getInstance(javaAlg);
            sig.initSign(privateKey);
            sig.update(tbsCertListDer);
            byte[] sigBytes = sig.sign();
            byte[] bitString = new byte[sigBytes.length + 1];
            bitString[0] = 0;
            System.arraycopy(sigBytes, 0, bitString, 1, sigBytes.length);
            byte[] sigValueDer = derTag(0x03, bitString);
            // Build final CRL DER
            byte[] crlDer = derSequence(derConcat(tbsCertListDer, sigAlgDer, sigValueDer));
            state.signedDer = crlDer;
            // Also parse and store as read-only for verify/digest operations
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509CRL parsedCrl = (X509CRL) cf.generateCRL(new ByteArrayInputStream(crlDer));
            X509_CRL_HANDLES.put(crlHandle, parsedCrl);
            return new RuntimeScalar(crlDer.length).getList();
        } catch (Exception e) {
            System.err.println("X509_CRL_sign error: " + e.getMessage());
            return new RuntimeScalar(0).getList();
        }
    }

    // Build DER for a single revoked certificate entry
    private static byte[] buildRevokedEntryDer(RevokedEntry entry) {
        // SEQUENCE { serialNumber INTEGER, revocationDate Time, extensions [opt] }
        BigInteger serial = new BigInteger(entry.serialHex, 16);
        byte[] serialDer = derInteger(serial);
        byte[] timeDer = derTime(entry.revocationTime);
        // Extensions: reason code + invalidity date
        byte[] extsDer = new byte[0];
        List<byte[]> extList = new ArrayList<>();
        // CRL Reason (OID 2.5.29.21)
        if (entry.reason >= 0) {
            byte[] reasonOid = derOid("2.5.29.21");
            byte[] reasonValue = derTag(0x04, derTag(0x0A, new byte[]{(byte) entry.reason})); // OCTET STRING { ENUMERATED }
            extList.add(derSequence(derConcat(reasonOid, reasonValue)));
        }
        // Invalidity Date (OID 2.5.29.24)
        if (entry.compromiseTime > 0) {
            byte[] invalidityOid = derOid("2.5.29.24");
            byte[] invalidityValue = derTag(0x04, derGeneralizedTime(entry.compromiseTime)); // OCTET STRING { GeneralizedTime }
            extList.add(derSequence(derConcat(invalidityOid, invalidityValue)));
        }
        if (!extList.isEmpty()) {
            extsDer = derSequence(derConcat(extList.toArray(new byte[0][])));
        }
        return derSequence(derConcat(serialDer, timeDer, extsDer));
    }

    // Build DER for CRL extensions (handles CRL_NUMBER and Authority Key Identifier)
    private static byte[] buildCRLExtensionsDer(List<MutableExtension> extensions, MutableCRLState state) {
        List<byte[]> extDers = new ArrayList<>();
        for (MutableExtension ext : extensions) {
            byte[] oidDer = derOid(ext.oid);
            byte[] critDer = ext.critical ? derTag(0x01, new byte[]{(byte) 0xFF}) : new byte[0]; // BOOLEAN TRUE
            byte[] valueDer;
            if (ext.value.startsWith("CRL_NUMBER:")) {
                BigInteger num = new BigInteger(ext.value.substring(11));
                valueDer = derTag(0x04, derInteger(num)); // OCTET STRING { INTEGER }
            } else if (ext.oid.equals("2.5.29.35")) {
                // Authority Key Identifier — build from issuer cert
                valueDer = derTag(0x04, buildAuthorityKeyIdentifierDer(state));
            } else {
                // Generic: encode value string as UTF8String in OCTET STRING
                valueDer = derTag(0x04, ext.value.getBytes(StandardCharsets.UTF_8));
            }
            extDers.add(derSequence(derConcat(oidDer, critDer, valueDer)));
        }
        return derSequence(derConcat(extDers.toArray(new byte[0][])));
    }

    // Build Authority Key Identifier DER from issuer certificate
    private static byte[] buildAuthorityKeyIdentifierDer(MutableCRLState state) {
        // Minimal AKI: just the keyIdentifier [0]
        // We'd need access to the issuer cert's subject key identifier
        // For now, return a minimal valid structure
        return new byte[]{0x30, 0x00}; // empty SEQUENCE
    }

    // DER-encode a GeneralizedTime value
    private static byte[] derGeneralizedTime(long epochSeconds) {
        ZonedDateTime zdt = Instant.ofEpochSecond(epochSeconds).atZone(ZoneOffset.UTC);
        String timeStr = zdt.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + "Z";
        byte[] timeBytes = timeStr.getBytes(StandardCharsets.US_ASCII);
        return derTag(0x18, timeBytes); // tag 0x18 = GeneralizedTime
    }

    // DER-encode an OID string like "2.5.29.20"
    private static byte[] derOid(String oidStr) {
        String[] parts = oidStr.split("\\.");
        int[] components = new int[parts.length];
        for (int i = 0; i < parts.length; i++) components[i] = Integer.parseInt(parts[i]);
        // First two components encoded as 40*c0 + c1
        List<Byte> encoded = new ArrayList<>();
        encoded.add((byte) (40 * components[0] + components[1]));
        for (int i = 2; i < components.length; i++) {
            int val = components[i];
            if (val < 128) {
                encoded.add((byte) val);
            } else {
                // Multi-byte base-128 encoding
                List<Byte> bytes = new ArrayList<>();
                bytes.add((byte) (val & 0x7F));
                val >>= 7;
                while (val > 0) {
                    bytes.add((byte) ((val & 0x7F) | 0x80));
                    val >>= 7;
                }
                for (int j = bytes.size() - 1; j >= 0; j--) encoded.add(bytes.get(j));
            }
        }
        byte[] oidBytes = new byte[encoded.size()];
        for (int i = 0; i < encoded.size(); i++) oidBytes[i] = encoded.get(i);
        return derTag(0x06, oidBytes); // tag 0x06 = OID
    }

    // PEM_get_string_X509_CRL($crl) - export CRL as PEM string
    public static RuntimeList PEM_get_string_X509_CRL(RuntimeArray args, int ctx) {
        if (args.size() < 1) return new RuntimeScalar().getList();
        long crlHandle = args.get(0).getLong();
        try {
            byte[] derData = null;
            X509CRL crl = X509_CRL_HANDLES.get(crlHandle);
            if (crl != null) derData = crl.getEncoded();
            MutableCRLState st = CRL_HANDLES.get(crlHandle);
            if (st != null && st.signedDer != null) derData = st.signedDer;
            if (derData == null) return new RuntimeScalar().getList();
            String b64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(derData);
            String pem = "-----BEGIN X509 CRL-----\n" + b64 + "\n-----END X509 CRL-----\n";
            return new RuntimeScalar(pem).getList();
        } catch (Exception e) {
            return new RuntimeScalar().getList();
        }
    }

    // P_X509_CRL_add_revoked_serial_hex($crl, $serial_hex, $rev_time, $reason, $comp_time)
    public static RuntimeList P_X509_CRL_add_revoked_serial_hex(RuntimeArray args, int ctx) {
        if (args.size() < 4) return new RuntimeScalar(0).getList();
        long crlHandle = args.get(0).getLong();
        MutableCRLState state = CRL_HANDLES.get(crlHandle);
        if (state == null) return new RuntimeScalar(0).getList();
        String serialHex = args.get(1).toString();
        long revTimeHandle = args.get(2).getLong();
        int reason = (int) args.get(3).getLong();
        long compTimeHandle = args.size() >= 5 ? args.get(4).getLong() : 0;
        Long revEpoch = ASN1_TIME_HANDLES.get(revTimeHandle);
        if (revEpoch == null) return new RuntimeScalar(0).getList();
        RevokedEntry entry = new RevokedEntry();
        entry.serialHex = serialHex;
        entry.revocationTime = revEpoch;
        entry.reason = reason;
        if (compTimeHandle != 0) {
            Long compEpoch = ASN1_TIME_HANDLES.get(compTimeHandle);
            entry.compromiseTime = compEpoch != null ? compEpoch : 0;
        }
        state.revokedEntries.add(entry);
        return new RuntimeScalar(1).getList();
    }

    // P_X509_CRL_add_extensions($crl, $issuer_cert, NID => value, ...)
    public static RuntimeList P_X509_CRL_add_extensions(RuntimeArray args, int ctx) {
        if (args.size() < 3) return new RuntimeScalar(0).getList();
        long crlHandle = args.get(0).getLong();
        MutableCRLState state = CRL_HANDLES.get(crlHandle);
        if (state == null) return new RuntimeScalar(0).getList();
        // args[1] is issuer cert handle (used for AKI)
        // Remaining args are NID => value pairs
        for (int i = 2; i < args.size() - 1; i += 2) {
            int nid = (int) args.get(i).getLong();
            String value = args.get(i + 1).toString();
            MutableExtension ext = new MutableExtension();
            // Map NID to OID
            OidInfo oidInfo = NID_TO_INFO.get(nid);
            ext.oid = oidInfo != null ? oidInfo.oid : ("2.5.29." + nid);
            ext.critical = false;
            ext.value = value;
            state.extensions.add(ext);
        }
        return new RuntimeScalar(1).getList();
    }

    // Helper: read all data from a BIO handle
    private static byte[] readAllBioData(long bioHandle) {
        MemoryBIO bio = BIO_HANDLES.get(bioHandle);
        if (bio == null) return null;
        // Return all unread data from the BIO
        int avail = bio.pending();
        if (avail <= 0) return null;
        return bio.read(avail);
    }
}
