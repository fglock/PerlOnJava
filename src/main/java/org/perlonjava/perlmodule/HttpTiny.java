package org.perlonjava.perlmodule;

import org.perlonjava.runtime.RuntimeArray;
import org.perlonjava.runtime.RuntimeHash;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

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
            HttpClient client = createHttpClient(instanceHash);
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

    private static HttpClient createHttpClient(RuntimeHash options) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(options.get("timeout").getLong() * 1000L))
                .version(HttpClient.Version.HTTP_1_1);

        // Configure SSL context if SSL verification is disabled
        if (!options.get("verify_SSL").getBoolean()) {
            try {
                builder.sslContext(createInsecureSSLContext());
            } catch (Exception e) {
                throw new RuntimeException("Failed to create insecure SSL context", e);
            }
        }

        // Configure proxy settings
        String httpProxy = options.exists("http_proxy").getBoolean() ? options.get("http_proxy").toString() : System.getenv("http_proxy");
        String httpsProxy = options.exists("https_proxy").getBoolean() ? options.get("https_proxy").toString() : System.getenv("https_proxy");
        String genericProxy = options.exists("proxy").getBoolean() ? options.get("proxy").toString() : System.getenv("all_proxy");
        String noProxy = options.exists("no_proxy").getBoolean() ? options.get("no_proxy").toString() : System.getenv("no_proxy");

        if (genericProxy != null) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(URI.create(genericProxy).getHost(), URI.create(genericProxy).getPort())));
        } else if (httpProxy != null || httpsProxy != null) {
            builder.proxy(new ProxySelector() {
                @Override
                public List<Proxy> select(URI uri) {
                    if (shouldNotProxy(uri.getHost(), noProxy)) {
                        return List.of(Proxy.NO_PROXY);
                    }
                    if ("http".equalsIgnoreCase(uri.getScheme()) && httpProxy != null) {
                        return List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(URI.create(httpProxy).getHost(), URI.create(httpProxy).getPort())));
                    } else if ("https".equalsIgnoreCase(uri.getScheme()) && httpsProxy != null) {
                        return List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(URI.create(httpsProxy).getHost(), URI.create(httpsProxy).getPort())));
                    }
                    return List.of(Proxy.NO_PROXY);
                }

                @Override
                public void connectFailed(URI uri, java.net.SocketAddress sa, IOException ioe) {
                    System.err.println("Proxy connection failed: " + ioe.getMessage());
                }
            });
        }

        return builder.build();
    }

    private static SSLContext createInsecureSSLContext() throws Exception {
        TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }

                    public void checkClientTrusted(X509Certificate[] certs, String authType) {
                        // Trust all client certificates
                    }

                    public void checkServerTrusted(X509Certificate[] certs, String authType) {
                        // Trust all server certificates
                    }
                }
        };

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
        return sslContext;
    }

    private static boolean shouldNotProxy(String host, String noProxy) {
        if (noProxy == null || noProxy.isEmpty()) {
            return false;
        }
        List<String> noProxyList = Arrays.stream(noProxy.split(","))
                .map(String::trim)
                .toList();
        return noProxyList.stream().anyMatch(host::endsWith);
    }
}