package org.perlonjava;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.perlonjava.app.cli.ArgumentParser;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeIO;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.io.StandardIO;

@Tag("unit")
class IOSocketSSLChunkedBodyTest extends PerlRuntimeTestBase {
    private static final int BODY_SIZE = 8192;
    private static final String FIXTURE = "/fixtures/iosocketssl_chunked_tls_body.pl";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void readsCompleteChunkedBodyAfterHeaderPrefetch(boolean interpreter) throws Exception {
        try (TlsChunkedServer server = new TlsChunkedServer()) {
            String source = fixtureSource().replace("__PORT__", Integer.toString(server.port()));
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            RuntimeIO previousStdout = RuntimeIO.getStdout();
            RuntimeIO testStdout = new RuntimeIO(new StandardIO(stdout, true));
            RuntimeIO.setStdout(testStdout);
            GlobalVariable.getGlobalIO("main::STDOUT").setIO(testStdout);

            try {
                CompilerOptions options = ArgumentParser.parseArguments(new String[] {"-e", source});
                options.fileName = "<iosocketssl-chunked-body-test>";
                options.useInterpreter = interpreter;
                RuntimeArray.push(options.inc, new RuntimeScalar("src/main/perl/lib"));
                RuntimeArray.push(options.inc, new RuntimeScalar("src/test/resources/unit/lib"));

                PerlLanguageProvider.executePerlCode(options, true);
                server.awaitResponse();

                String output = stdout.toString(StandardCharsets.UTF_8);
                assertTrue(output.contains("ok 1 - the TLS header read also prefetched chunk-body bytes"), output);
                assertTrue(output.contains("ok 2 - the TLS response body is read to completion"), output);
                assertTrue(output.contains("ok 3 - the complete chunked TLS response body is preserved"), output);
            } finally {
                RuntimeIO.setStdout(previousStdout);
                GlobalVariable.getGlobalIO("main::STDOUT").setIO(previousStdout);
                PerlLanguageProvider.resetAll();
            }
        }
    }

    @Test
    void systemPerlConfirmsTheFixtureSemantics() throws Exception {
        assumeTrue(systemPerlHasIoSocketSsl(), "system Perl with IO::Socket::SSL is optional");
        try (TlsChunkedServer server = new TlsChunkedServer()) {
            Path script = Files.createTempFile("perlonjava-1957-tls-", ".pl");
            try {
                String source = fixtureSource().replace("__PORT__", Integer.toString(server.port()));
                Files.writeString(script, source, StandardCharsets.UTF_8);
                Process perl = new ProcessBuilder("perl", script.toString())
                        .redirectErrorStream(true)
                        .start();
                boolean finished = perl.waitFor(15, TimeUnit.SECONDS);
                if (!finished) {
                    perl.destroyForcibly();
                }
                assertTrue(finished, "system Perl TLS fixture timed out");
                String output = new String(perl.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals(0, perl.exitValue(), output);
                assertTrue(output.contains("ok 1 - the TLS header read also prefetched chunk-body bytes"), output);
                assertTrue(output.contains("ok 2 - the TLS response body is read to completion"), output);
                assertTrue(output.contains("ok 3 - the complete chunked TLS response body is preserved"), output);
                server.awaitResponse();
            } finally {
                Files.deleteIfExists(script);
            }
        }
    }

    private static boolean systemPerlHasIoSocketSsl() throws Exception {
        Process perl = new ProcessBuilder("perl", "-MIO::Socket::SSL", "-e", "1")
                .redirectErrorStream(true)
                .start();
        boolean finished = perl.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            perl.destroyForcibly();
            return false;
        }
        return perl.exitValue() == 0;
    }

    private static String fixtureSource() throws Exception {
        try (InputStream input = IOSocketSSLChunkedBodyTest.class.getResourceAsStream(FIXTURE)) {
            assertNotNull(input, "chunked TLS Perl fixture is available");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
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

    private static final class TlsChunkedServer implements AutoCloseable {
        private final SSLServerSocket server;
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final Future<?> response;

        private TlsChunkedServer() throws Exception {
            SSLContext context = serverContext();
            server = (SSLServerSocket) context.getServerSocketFactory()
                    .createServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
            server.setSoTimeout(10_000);
            response = executor.submit(this::serveOneResponse);
        }

        int port() {
            return server.getLocalPort();
        }

        private void serveOneResponse() {
            try (SSLSocket socket = (SSLSocket) server.accept()) {
                socket.setSoTimeout(10_000);
                socket.startHandshake();
                BufferedReader request = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.ISO_8859_1));
                while (true) {
                    String line = request.readLine();
                    if (line == null || line.isEmpty()) {
                        break;
                    }
                }

                String payload = "x".repeat(BODY_SIZE);
                String responseText = "HTTP/1.1 200 OK\r\n"
                        + "Transfer-Encoding: chunked\r\n"
                        + "Connection: close\r\n\r\n"
                        + Integer.toHexString(payload.length()) + "\r\n"
                        + payload + "\r\n0\r\n\r\n";
                socket.getOutputStream().write(responseText.getBytes(StandardCharsets.ISO_8859_1));
                socket.getOutputStream().flush();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        void awaitResponse() throws Exception {
            response.get(15, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws Exception {
            server.close();
            executor.shutdownNow();
        }
    }
}
