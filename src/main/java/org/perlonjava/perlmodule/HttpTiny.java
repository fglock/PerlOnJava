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

public class HttpTiny extends PerlModuleBase {

    public HttpTiny() {
        super("HTTP::Tiny", false);
    }

    public static void initialize() {
        HttpTiny httpTiny = new HttpTiny();
        httpTiny.initializeExporter();
        httpTiny.defineExport("EXPORT", "new", "get", "post", "request");
        try {
            httpTiny.registerMethod("request", "$$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing HttpTiny method: " + e.getMessage());
        }
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