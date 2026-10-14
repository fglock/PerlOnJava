package org.perlonjava;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.InetAddress;
import java.security.KeyStore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.io.SocketIO;
import org.perlonjava.runtime.perlmodule.IOSocketSSL;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;
import org.perlonjava.runtime.runtimetypes.RuntimeIO;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

@Tag("unit")
class IOSocketSSLVersionMetadataTest extends PerlRuntimeTestBase {
    @Test
    void negotiatedProtocolIsReturnedAsPerlScalarMetadata() throws Exception {
        SSLContext serverContext = serverContext();
        SSLServerSocket server = (SSLServerSocket) serverContext.getServerSocketFactory()
                .createServerSocket(0, 1, InetAddress.getLoopbackAddress());
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<?> accepted = executor.submit(() -> {
                try (SSLSocket socket = (SSLSocket) server.accept()) {
                    socket.startHandshake();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            SSLContext clientContext = trustAllClientContext();
            try (SSLSocket client = (SSLSocket) clientContext.getSocketFactory()
                    .createSocket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                client.startHandshake();
                RuntimeIO io = new RuntimeIO(new SocketIO(client));
                RuntimeScalar protocol = IOSocketSSL._get_sslversion(
                        new RuntimeArray(new RuntimeScalar(io)), RuntimeContextType.SCALAR).scalar();

                assertTrue(protocol.toString().matches("TLSv\\d(?:\\.\\d)?"),
                        "negotiated TLS protocol is exposed to Perl as a scalar: " + protocol);
            }
            accepted.get();
        } finally {
            server.close();
            executor.shutdownNow();
        }
    }

    private static SSLContext serverContext() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream input = IOSocketSSLVersionMetadataTest.class.getResourceAsStream(
                "/module/Net-SSLeay/t/data/simple-cert.enc.p12")) {
            assertNotNull(input, "bundled TLS test keystore is available");
            keyStore.load(input, "test".toCharArray());
        }

        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, "test".toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), null, null);
        return context;
    }

    private static SSLContext trustAllClientContext() throws Exception {
        TrustManager[] trustManagers = { new X509TrustManager() {
            @Override
            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                return new java.security.cert.X509Certificate[0];
            }

            @Override
            public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) { }

            @Override
            public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) { }
        } };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers, null);
        return context;
    }
}
