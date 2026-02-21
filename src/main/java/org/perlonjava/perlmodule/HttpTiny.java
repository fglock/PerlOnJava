package org.perlonjava.perlmodule;

import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeHash;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.*;

public class HttpTiny extends PerlModuleBase {

    public HttpTiny() {
        super("HTTP::Tiny", false);
    }

    public static void initialize() {
        HttpTiny httpTiny = new HttpTiny();
        httpTiny.initializeExporter();
        httpTiny.defineExport("EXPORT", "new", "get", "post", "request");
        try {
            httpTiny.registerMethod("request", null);
            httpTiny.registerMethod("mirror", null);
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

    public static RuntimeList mirror(RuntimeArray args, int ctx) throws Exception {
        if (args.size() < 3) {
            throw new IllegalStateException("Bad number of arguments for HTTP::Tiny->mirror");
        }

        RuntimeScalar self = args.get(0);
        String url = args.get(1).toString();
        String filePath = args.get(2).toString();
        RuntimeHash options = args.size() > 3 ? args.get(3).hashDeref() : new RuntimeHash();

        File file = new File(filePath);
        if (file.exists()) {
            // Set If-Modified-Since header
            long lastModified = file.lastModified();
            SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
            dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
            String ifModifiedSince = dateFormat.format(new Date(lastModified));

            RuntimeHash headers = options.exists("headers").getBoolean()
                    ? options.get("headers").hashDeref() : new RuntimeHash();
            headers.put("If-Modified-Since", new RuntimeScalar(ifModifiedSince));
            options.put("headers", headers.createReference());
        }

        // Perform the request
        RuntimeArray requestArgs = new RuntimeArray();
        RuntimeArray.push(requestArgs, self);
        RuntimeArray.push(requestArgs, new RuntimeScalar("GET"));
        RuntimeArray.push(requestArgs, new RuntimeScalar(url));
        RuntimeArray.push(requestArgs, options.createReference());

        RuntimeList response = request(requestArgs, ctx);
        RuntimeHash responseHash = ((RuntimeScalar) response.elements.get(0)).hashDeref();

        // Check if the request was successful or not modified
        boolean success = responseHash.get("success").getBoolean() || responseHash.get("status").getLong() == 304;
        if (success && responseHash.get("status").getLong() != 304) {
            // Write response content to file
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(responseHash.get("content").toString().getBytes());
            }

            // Update file modification time if Last-Modified header is present
            RuntimeHash headers = responseHash.get("headers").hashDeref();
            if (headers.exists("last-modified").getBoolean()) {
                String lastModifiedStr = headers.get("last-modified").toString();
                SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
                dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
                Date lastModifiedDate = dateFormat.parse(lastModifiedStr);
                Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(lastModifiedDate.getTime()));
            }
        }

        return response;
    }
}