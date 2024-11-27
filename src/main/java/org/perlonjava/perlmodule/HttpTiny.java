package org.perlonjava.perlmodule;

import org.perlonjava.runtime.RuntimeArray;
import org.perlonjava.runtime.RuntimeHash;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.perlonjava.runtime.RuntimeHash.createHash;

public class HttpTiny extends PerlModuleBase {
    private static final String USER_AGENT = "HTTP-Tiny/0.090";
    private static final long TIMEOUT = 60; // 60 seconds
    private static final boolean VERIFY_SSL = true;
    private static final RuntimeHash defaultHeaders = new RuntimeHash();

    public HttpTiny() {
        super("HTTP::Tiny");
    }

    public static void initialize() {
        HttpTiny httpTiny = new HttpTiny();
        httpTiny.initializeExporter();
        httpTiny.defineExport("EXPORT", "new", "get", "post", "request");
        try {
            httpTiny.registerMethod("new", "newInstance", "");
            httpTiny.registerMethod("get", "$");
            httpTiny.registerMethod("post", "$");
            httpTiny.registerMethod("request", "$$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing HttpTiny method: " + e.getMessage());
        }
    }

    public static RuntimeList newInstance(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for HTTP::Tiny->new");
        }
        RuntimeScalar className = args.shift();
        RuntimeHash instanceHash = createHash(args);
        RuntimeScalar instance = instanceHash.createReference();
        instance.bless(className);

        //    *   "agent" — A user-agent string (defaults to 'HTTP-Tiny/$VERSION'). If
        //        "agent" — ends in a space character, the default user-agent string
        //        is appended.
        String agent = instanceHash.get("agent").toString();
        if (agent.isEmpty() || agent.endsWith(" ")) {
            instanceHash.get("agent").set(agent + USER_AGENT);
        }


        //    *   "timeout" — Request timeout in seconds (default is 60) If a socket
        //        open, read or write takes longer than the timeout, the request
        //        response status code will be 599.
        String timeout = instanceHash.get("timeout").toString();
        if (timeout.isEmpty()) {
            instanceHash.get("timeout").set(TIMEOUT);
        }

        //    *   "verify_SSL" — A boolean that indicates whether to validate the
        //        TLS/SSL certificate of an "https" — connection (default is true).
        if (!instanceHash.exists("verify_SSL").getBoolean()) {
            instanceHash.get("verify_SSL").set(VERIFY_SSL);
        }

        //    *   "default_headers" — A hashref of default headers to apply to
        //        requests
        if (!instanceHash.get("default_headers").getBoolean()) {
            instanceHash.get("default_headers").set(RuntimeHash.createHash(defaultHeaders).createReference());
        }

        return instance.getList();
    }

    public static RuntimeList get(RuntimeArray args, int ctx) throws Exception {
        if (args.size() != 2) {
            throw new IllegalStateException("Bad number of arguments for HTTP::Tiny->get");
        }
        RuntimeScalar self = args.get(0);
        RuntimeScalar url = args.get(1);
        return request(new RuntimeArray(List.of(
                self,
                new RuntimeScalar("GET"),
                url,
                new RuntimeHash().createReference())), ctx);
    }

    public static RuntimeList post(RuntimeArray args, int ctx) throws Exception {
        if (args.size() != 3) {
            throw new IllegalStateException("Bad number of arguments for HTTP::Tiny->post");
        }
        RuntimeScalar self = args.get(0);
        RuntimeScalar url = args.get(1);
        RuntimeScalar content = args.get(2);
        return request(new RuntimeArray(List.of(
                self,
                new RuntimeScalar("POST"),
                url,
                content)), ctx);
    }

    public static RuntimeList request(RuntimeArray args, int ctx) throws Exception {
        if (args.size() < 3) {
            throw new IllegalStateException("Bad number of arguments for HTTP::Tiny->request");
        }

        RuntimeScalar self = args.get(0);
        String method = args.get(1).toString();
        String url = args.get(2).toString();
        RuntimeHash options = args.size() > 3 ? args.get(3).hashDeref() : new RuntimeHash();

        RuntimeHash instanceHash = self.hashDeref();

        // Handle options
        RuntimeHash headers = options.exists("headers").getBoolean()
                ? options.get("headers").hashDeref() : new RuntimeHash();
        String content = options.get("content") != null ? options.get("content").toString() : "";

        // Create request builder
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .method(method, HttpRequest.BodyPublishers.ofString(content));

        // Set headers
        requestBuilder.header("User-Agent", instanceHash.get("agent").toString());
        // requestBuilder.header("Connection", "keep-alive");

        // Add default and custom headers
        headers.elements.forEach((key, value) ->
                requestBuilder.header(key, value.toString())
        );

        // Build and send request
        HttpRequest request = requestBuilder.build();
        try {
            HttpClient client = createHttpClient(
                    instanceHash.get("timeout").getLong(),
                    instanceHash.get("verify_SSL").getBoolean());
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            // Prepare response map
            RuntimeHash responseMap = new RuntimeHash();
            responseMap.put("success", new RuntimeScalar(response.statusCode() >= 200 && response.statusCode() < 300));
            responseMap.put("status", new RuntimeScalar(response.statusCode()));
            responseMap.put("reason", new RuntimeScalar(getStatusReason(response.statusCode())));
            responseMap.put("content", new RuntimeScalar(response.body()));

            // Collect headers
            RuntimeHash responseHeaders = new RuntimeHash();
            response.headers().map().forEach((key, value) ->
                    responseHeaders.put(key.toLowerCase(), new RuntimeScalar(String.join(", ", value)))
            );
            responseMap.put("headers", responseHeaders.createReference());

            return responseMap.createReference().getList();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("HTTP request failed", e);
        }
    }

    private static String getStatusReason(int statusCode) {
        // Simple status reason mapping (you might want to expand this)
        return switch (statusCode) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 500 -> "Internal Server Error";
            default -> "Unknown Status";
        };
    }

    private static HttpClient createHttpClient(long timeout, boolean verifySSL) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeout * 1000L))
                .version(HttpClient.Version.HTTP_1_1);

        if (!verifySSL) {
//            try {
//                builder.sslContext(createInsecureSSLContext())
//                        .hostnameVerifier((hostname, session) -> true);
//            } catch (Exception e) {
//                throw new RuntimeException("Failed to create insecure SSL context", e);
//            }
        }

        return builder.build();
    }
}