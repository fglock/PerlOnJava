package org.perlonjava;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;
import org.perlonjava.runtime.io.StandardIO;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeIO;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

@Tag("unit")
class IOSocketSSLChunkedBodyTest extends PerlRuntimeTestBase {
    @Test
    void temporarySocketArgumentRemainsOpenThroughChunkedBodyReads() throws Exception {
        for (boolean useInterpreter : new boolean[] { false, true }) {
            String output = executeFixture(useInterpreter);
            assertFalse(output.contains("not ok"), output);
            assertTrue(output.contains("the TLS response body is read to completion"), output);
            assertTrue(output.contains("the complete chunked TLS response body is preserved"), output);
        }
    }

    private String executeFixture(boolean useInterpreter) throws Exception {
        SSLServerSocket server = (SSLServerSocket) serverContext().getServerSocketFactory()
                .createServerSocket(0, 1, InetAddress.getLoopbackAddress());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> response = executor.submit(() -> serveOneChunkedResponse(server));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalSystemOut = System.out;
        RuntimeIO originalStdout = RuntimeIO.getStdout();
        PrintStream capturedOut = new PrintStream(output, true, StandardCharsets.UTF_8);
        try {
            PerlLanguageProvider.resetAll();
            System.setOut(capturedOut);
            RuntimeIO.setStdout(new RuntimeIO(new StandardIO(capturedOut, true)));
            GlobalVariable.getGlobalIO("main::STDOUT").setIO(RuntimeIO.getStdout());

            String fixture = readFixture().replace("__PORT__", Integer.toString(server.getLocalPort()));
            CompilerOptions options = new CompilerOptions();
            options.code = fixture;
            options.fileName = "iosocketssl_chunked_tls_body.pl";
            options.useInterpreter = useInterpreter;
            RuntimeArray.push(options.inc, new RuntimeScalar("src/main/perl/lib"));

            PerlLanguageProvider.executePerlCode(options, true);
            RuntimeIO.getStdout().flush();
            response.get(15, TimeUnit.SECONDS);
            return output.toString(StandardCharsets.UTF_8);
        } finally {
            server.close();
            executor.shutdownNow();
            RuntimeIO.setStdout(originalStdout);
            GlobalVariable.getGlobalIO("main::STDOUT").setIO(originalStdout);
            System.setOut(originalSystemOut);
            capturedOut.close();
            PerlLanguageProvider.resetAll();
        }
    }

    private static String readFixture() throws Exception {
        try (InputStream input = IOSocketSSLChunkedBodyTest.class.getResourceAsStream(
                "/fixtures/iosocketssl_chunked_tls_body.pl")) {
            assertNotNull(input, "chunked TLS Perl fixture is available");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void serveOneChunkedResponse(SSLServerSocket server) {
        try (SSLSocket socket = (SSLSocket) server.accept()) {
            socket.setSoTimeout(10_000);
            socket.startHandshake();
            int matched = 0;
            while (matched < 4) {
                int value = socket.getInputStream().read();
                if (value < 0) throw new IllegalStateException("client closed before HTTP request");
                matched = switch (matched) {
                    case 0 -> value == '\r' ? 1 : 0;
                    case 1 -> value == '\n' ? 2 : value == '\r' ? 1 : 0;
                    case 2 -> value == '\r' ? 3 : 0;
                    default -> value == '\n' ? 4 : 0;
                };
            }

            byte[] body = "x".repeat(8192).getBytes(StandardCharsets.US_ASCII);
            String responseHeaders = "HTTP/1.1 200 OK\r\n"
                    + "Transfer-Encoding: chunked\r\n"
                    + "Connection: close\r\n\r\n";
            String chunkHeader = Integer.toHexString(body.length) + "\r\n";
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            response.write(responseHeaders.getBytes(StandardCharsets.US_ASCII));
            response.write(chunkHeader.getBytes(StandardCharsets.US_ASCII));
            response.write(body);
            response.write("\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            OutputStream output = socket.getOutputStream();
            output.write(response.toByteArray());
            output.flush();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static SSLContext serverContext() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream input = IOSocketSSLChunkedBodyTest.class.getResourceAsStream(
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
}
