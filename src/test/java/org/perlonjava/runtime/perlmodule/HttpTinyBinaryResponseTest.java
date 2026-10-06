package org.perlonjava.runtime.perlmodule;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeHash;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;

import java.net.InetSocketAddress;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpTinyBinaryResponseTest {

    @Tag("unit")
    @Test
    void requestPreservesBinaryResponseOctets() throws Exception {
        byte[] payload = {
                0x50, 0x4b, 0x03, 0x04, (byte) 0x96, (byte) 0xa7,
                (byte) 0xff, 0x00, (byte) 0x80, 0x0a
        };
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/archive", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, payload.length);
            try (var output = exchange.getResponseBody()) {
                output.write(payload);
            }
        });
        server.start();
        try {
            RuntimeHash instance = new RuntimeHash();
            instance.put("agent", new RuntimeScalar("PerlOnJava-test"));
            instance.put("timeout", new RuntimeScalar(10));
            instance.put("verify_SSL", new RuntimeScalar(false));

            RuntimeHash options = new RuntimeHash();
            RuntimeArray args = new RuntimeArray();
            args.elements.add(instance.createReference());
            args.elements.add(new RuntimeScalar("GET"));
            args.elements.add(new RuntimeScalar("http://127.0.0.1:"
                    + server.getAddress().getPort() + "/archive"));
            args.elements.add(options.createReference());

            RuntimeList result = HttpTiny.request(args, 0);
            assertNotNull(result);
            RuntimeHash response = ((RuntimeScalar) result.elements.getFirst()).hashDeref();
            RuntimeScalar content = response.get("content");
            assertNotNull(content);
            assertEquals(RuntimeScalarType.BYTE_STRING, content.type,
                    "HTTP response content is represented as Perl octets");
            String actual = content.toString();
            assertEquals(payload.length, actual.length());
            for (int i = 0; i < payload.length; i++) {
                assertEquals(payload[i] & 0xff, actual.charAt(i),
                        "response byte at offset " + i + " is preserved");
            }
            assertTrue(response.get("success").getBoolean());
        } finally {
            server.stop(0);
        }
    }
}
