package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.io.SocketIO;
import org.perlonjava.runtime.runtimetypes.*;

import javax.net.ssl.*;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

/**
 * Java XS backend for IO::Socket::SSL.
 * Provides SSL/TLS socket upgrade using javax.net.ssl.
 * <p>
 * The Perl-side IO/Socket/SSL.pm inherits from IO::Socket::IP for TCP,
 * then calls _start_ssl() to upgrade the connection to TLS.
 */
public class IOSocketSSL extends PerlModuleBase {

    public IOSocketSSL() {
        super("IO::Socket::SSL", false);
    }

    public static void initialize() {
        IOSocketSSL mod = new IOSocketSSL();

        try {
            mod.registerMethod("_start_ssl", null);
            mod.registerMethod("_get_cipher", null);
            mod.registerMethod("_get_sslversion", null);
            mod.registerMethod("_peer_certificate_subject", null);
            mod.registerMethod("_peer_certificate_issuer", null);
            mod.registerMethod("_peer_certificate", null);
            mod.registerMethod("_stop_ssl", null);
            mod.registerMethod("_is_ssl", null);
            mod.registerMethod("can_client_sni", "");
            mod.registerMethod("can_server_sni", "");
            mod.registerMethod("can_alpn", "");
            mod.registerMethod("can_npn", "");
            mod.registerMethod("can_ecdh", "");
            mod.registerMethod("can_ipv6", "");
            mod.registerMethod("can_ocsp", "");
            mod.registerMethod("can_ticket_keycb", "");
            mod.registerMethod("can_multi_cert", "");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing IOSocketSSL method: " + e.getMessage());
        }
    }

    /**
     * _start_ssl($glob, $host, $port, $verify_mode, $ssl_ca_file, $ssl_ca_path)
     * Upgrades an existing connected socket to SSL/TLS.
     * Returns 1 on success, sets $SSL_ERROR and returns 0 on failure.
     */
    public static RuntimeList _start_ssl(RuntimeArray args, int ctx) {
        try {
            RuntimeScalar selfScalar = args.get(0);
            String host = args.size() > 1 ? args.get(1).toString() : "";
            int port = args.size() > 2 ? args.get(2).getInt() : 443;
            int verifyMode = args.size() > 3 ? args.get(3).getInt() : 1; // VERIFY_PEER
            String caFile = args.size() > 4 && args.get(4).getDefinedBoolean() ? args.get(4).toString() : null;
            String caPath = args.size() > 5 && args.get(5).getDefinedBoolean() ? args.get(5).toString() : null;

            // Get the SocketIO from the glob
            SocketIO socketIO = getSocketIO(selfScalar);
            if (socketIO == null) {
                setSSLError("No socket available for SSL upgrade");
                return new RuntimeScalar(0).getList();
            }

            // Build SSLContext
            SSLContext sslContext;
            if (verifyMode == 0) {
                // No verification — trust all certificates
                sslContext = createInsecureSSLContext();
            } else if (caFile != null || caPath != null) {
                // Custom CA trust store
                sslContext = createCustomSSLContext(caFile, caPath);
            } else {
                // Default JVM trust store (includes standard CAs)
                sslContext = SSLContext.getDefault();
            }

            // Perform the SSL upgrade
            // When verify_mode=0, we need to skip hostname verification,
            // so we do the upgrade inline instead of using upgradeToSSL's defaults.
            java.net.Socket rawSocket = socketIO.getSocket();
            SSLSocketFactory factory = sslContext.getSocketFactory();
            SSLSocket sslSocket = (SSLSocket) factory.createSocket(
                    rawSocket, host, port, true /* autoClose */);

            SSLParameters params = sslSocket.getSSLParameters();
            if (host != null && !host.isEmpty() && !host.matches("^[\\d.]+$") && !host.contains(":")) {
                params.setServerNames(java.util.List.of(new SNIHostName(host)));
            }
            if (verifyMode != 0) {
                params.setEndpointIdentificationAlgorithm("HTTPS");
            }
            sslSocket.setSSLParameters(params);
            sslSocket.startHandshake();

            // Replace the socket inside SocketIO
            socketIO.replaceSocket(sslSocket);

            return new RuntimeScalar(1).getList();
        } catch (javax.net.ssl.SSLHandshakeException e) {
            setSSLError("SSL handshake failed: " + e.getMessage());
            return new RuntimeScalar(0).getList();
        } catch (Exception e) {
            setSSLError(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return new RuntimeScalar(0).getList();
        }
    }

    /**
     * _get_cipher($glob)
     * Returns the SSL cipher suite name.
     */
    public static RuntimeList _get_cipher(RuntimeArray args, int ctx) {
        try {
            SocketIO socketIO = getSocketIO(args.get(0));
            if (socketIO != null && socketIO.getSocket() instanceof SSLSocket sslSocket) {
                SSLSession session = sslSocket.getSession();
                return new RuntimeScalar(session.getCipherSuite()).getList();
            }
        } catch (Exception e) {
            // ignore
        }
        return new RuntimeScalar("").getList();
    }

    /**
     * _get_sslversion($glob)
     * Returns the TLS protocol version string (e.g., "TLSv1.3").
     */
    public static RuntimeList _get_sslversion(RuntimeArray args, int ctx) {
        try {
            SocketIO socketIO = getSocketIO(args.get(0));
            if (socketIO != null && socketIO.getSocket() instanceof SSLSocket sslSocket) {
                SSLSession session = sslSocket.getSession();
                return new RuntimeScalar(session.getProtocol()).getList();
            }
        } catch (Exception e) {
            // ignore
        }
        return new RuntimeScalar("").getList();
    }

    /**
     * _peer_certificate_subject($glob)
     * Returns the peer certificate subject name.
     */
    public static RuntimeList _peer_certificate_subject(RuntimeArray args, int ctx) {
        try {
            SocketIO socketIO = getSocketIO(args.get(0));
            if (socketIO != null && socketIO.getSocket() instanceof SSLSocket sslSocket) {
                SSLSession session = sslSocket.getSession();
                java.security.cert.Certificate[] certs = session.getPeerCertificates();
                if (certs.length > 0 && certs[0] instanceof X509Certificate x509) {
                    return new RuntimeScalar(x509.getSubjectX500Principal().getName()).getList();
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return new RuntimeScalar("").getList();
    }

    /**
     * _peer_certificate_issuer($glob)
     * Returns the peer certificate issuer name.
     */
    public static RuntimeList _peer_certificate_issuer(RuntimeArray args, int ctx) {
        try {
            SocketIO socketIO = getSocketIO(args.get(0));
            if (socketIO != null && socketIO.getSocket() instanceof SSLSocket sslSocket) {
                SSLSession session = sslSocket.getSession();
                java.security.cert.Certificate[] certs = session.getPeerCertificates();
                if (certs.length > 0 && certs[0] instanceof X509Certificate x509) {
                    return new RuntimeScalar(x509.getIssuerX500Principal().getName()).getList();
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return new RuntimeScalar("").getList();
    }

    /**
     * _peer_certificate($glob, $field)
     * Returns peer certificate info. If $field is provided, returns that field.
     * Without a field, returns 1 if a peer certificate exists, 0 otherwise.
     */
    public static RuntimeList _peer_certificate(RuntimeArray args, int ctx) {
        try {
            SocketIO socketIO = getSocketIO(args.get(0));
            String field = args.size() > 1 ? args.get(1).toString() : "";
            if (socketIO != null && socketIO.getSocket() instanceof SSLSocket sslSocket) {
                SSLSession session = sslSocket.getSession();
                java.security.cert.Certificate[] certs = session.getPeerCertificates();
                if (certs.length > 0 && certs[0] instanceof X509Certificate x509) {
                    if (field.isEmpty()) {
                        return new RuntimeScalar(1).getList();
                    }
                    switch (field) {
                        case "subject_name":
                        case "commonName":
                            return new RuntimeScalar(x509.getSubjectX500Principal().getName()).getList();
                        case "issuer_name":
                            return new RuntimeScalar(x509.getIssuerX500Principal().getName()).getList();
                        case "not_before":
                            return new RuntimeScalar(x509.getNotBefore().getTime() / 1000).getList();
                        case "not_after":
                            return new RuntimeScalar(x509.getNotAfter().getTime() / 1000).getList();
                        default:
                            return new RuntimeScalar("").getList();
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return new RuntimeScalar(0).getList();
    }

    /**
     * _stop_ssl($glob)
     * Not fully implementable with SSLSocket wrapping approach,
     * but we can close the SSL session.
     */
    public static RuntimeList _stop_ssl(RuntimeArray args, int ctx) {
        // SSLSocket doesn't support downgrading back to plain socket
        // Just return success — the socket will be closed normally
        return new RuntimeScalar(1).getList();
    }

    /**
     * _is_ssl($glob)
     * Returns 1 if the socket is currently SSL-wrapped.
     */
    public static RuntimeList _is_ssl(RuntimeArray args, int ctx) {
        try {
            SocketIO socketIO = getSocketIO(args.get(0));
            if (socketIO != null && socketIO.getSocket() instanceof SSLSocket) {
                return new RuntimeScalar(1).getList();
            }
        } catch (Exception e) {
            // ignore
        }
        return new RuntimeScalar(0).getList();
    }

    // Capability queries — Java supports most of these natively
    public static RuntimeList can_client_sni(RuntimeArray args, int ctx) {
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList can_server_sni(RuntimeArray args, int ctx) {
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList can_alpn(RuntimeArray args, int ctx) {
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList can_npn(RuntimeArray args, int ctx) {
        return new RuntimeScalar(0).getList(); // NPN deprecated, Java doesn't support it
    }

    public static RuntimeList can_ecdh(RuntimeArray args, int ctx) {
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList can_ipv6(RuntimeArray args, int ctx) {
        return new RuntimeScalar(1).getList(); // Java supports IPv6
    }

    public static RuntimeList can_ocsp(RuntimeArray args, int ctx) {
        return new RuntimeScalar(0).getList(); // OCSP not implemented
    }

    public static RuntimeList can_ticket_keycb(RuntimeArray args, int ctx) {
        return new RuntimeScalar(0).getList();
    }

    public static RuntimeList can_multi_cert(RuntimeArray args, int ctx) {
        return new RuntimeScalar(0).getList();
    }

    // ---- Helper methods ----

    /**
     * Extract SocketIO from a Perl glob/filehandle scalar.
     * Uses RuntimeIO.getRuntimeIO() which handles all glob/reference variants.
     */
    private static SocketIO getSocketIO(RuntimeScalar scalar) {
        try {
            RuntimeIO runtimeIO = RuntimeIO.getRuntimeIO(scalar);
            if (runtimeIO != null && runtimeIO.ioHandle instanceof SocketIO socketIO) {
                return socketIO;
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    /**
     * Create an SSLContext that trusts all certificates (verify_mode=0).
     */
    private static SSLContext createInsecureSSLContext() throws Exception {
        TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }

                    public void checkClientTrusted(X509Certificate[] certs, String authType) {
                    }

                    public void checkServerTrusted(X509Certificate[] certs, String authType) {
                    }
                }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, trustAllCerts, new java.security.SecureRandom());
        return ctx;
    }

    /**
     * Create an SSLContext with a custom CA trust store loaded from file or directory.
     */
    private static SSLContext createCustomSSLContext(String caFile, String caPath) throws Exception {
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null); // Initialize empty

        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        int certCount = 0;

        // Load certificates from CA file
        if (caFile != null && !caFile.isEmpty()) {
            try (InputStream is = new FileInputStream(caFile)) {
                for (java.security.cert.Certificate cert : cf.generateCertificates(is)) {
                    trustStore.setCertificateEntry("ca-" + certCount++, cert);
                }
            }
        }

        // Load certificates from CA directory (*.pem, *.crt files)
        if (caPath != null && !caPath.isEmpty()) {
            Path dir = Path.of(caPath);
            if (Files.isDirectory(dir)) {
                try (var stream = Files.newDirectoryStream(dir, "*.{pem,crt,cer}")) {
                    for (Path file : stream) {
                        try (InputStream is = new FileInputStream(file.toFile())) {
                            for (java.security.cert.Certificate cert : cf.generateCertificates(is)) {
                                trustStore.setCertificateEntry("ca-" + certCount++, cert);
                            }
                        } catch (Exception e) {
                            // Skip invalid cert files
                        }
                    }
                }
            }
        }

        // If no certs were loaded, fall back to default trust store
        if (certCount == 0) {
            return SSLContext.getDefault();
        }

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), new java.security.SecureRandom());
        return ctx;
    }

    /**
     * Set $IO::Socket::SSL::SSL_ERROR for error reporting.
     */
    private static void setSSLError(String message) {
        GlobalVariable.getGlobalVariable("IO::Socket::SSL::SSL_ERROR").set(new RuntimeScalar(message));
    }
}
