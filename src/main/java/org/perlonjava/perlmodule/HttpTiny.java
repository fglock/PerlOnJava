package org.perlonjava.perlmodule;

import org.perlonjava.runtime.RuntimeArray;
import org.perlonjava.runtime.RuntimeHash;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;

import javax.net.ssl.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code HttpTiny} class emulates the HTTP::Tiny Perl module, providing methods for HTTP requests.
 */
public class HttpTiny extends PerlModuleBase {

    // Define attributes
    private static final String userAgent = "HTTP-Tiny/0.090";
    private static final int timeout = 60000; // 60 seconds
    private static final boolean verifySSL = true;
    private static final Map<String, String> defaultHeaders = new HashMap<>();
    private static String localAddress;
    private static final boolean keepAlive = true;
    private static final int maxRedirect = 5;
    private static int maxSize;
    private static String httpProxy;
    private static String httpsProxy;
    private static String proxy = null;
    private static List<String> noProxy;
    private static Map<String, Object> sslOptions;

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
        RuntimeScalar className = args.get(0);
        RuntimeScalar instance = new RuntimeHash().createReference();
        instance.bless(className);
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

    /**
     * Performs a POST request.
     *
     * @param args the runtime array containing the URL and content
     * @param ctx  the runtime context
     * @return a {@link RuntimeList} containing the response
     * @throws Exception if an error occurs during the request
     */
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

        // Handle options like headers, content, etc.
        RuntimeHash headers = options.exists("headers").getBoolean()
                ? options.get("headers").hashDeref() : new RuntimeHash();
        String content = options.get("content").toString();

        URL obj = new URL(url);
        HttpURLConnection con;

        // Handle proxy settings
        if (proxy != null && !isNoProxy(url)) {
            Proxy proxyObj = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxy, 80));
            con = (HttpURLConnection) obj.openConnection(proxyObj);
        } else {
            con = (HttpURLConnection) obj.openConnection();
        }

        con.setRequestMethod(method);

        // Set default headers
        con.setRequestProperty("User-Agent", userAgent);
        for (Map.Entry<String, String> entry : defaultHeaders.entrySet()) {
            con.setRequestProperty(entry.getKey(), entry.getValue());
        }

        // Override with specific headers if provided
        for (Map.Entry<String, RuntimeScalar> entry : headers.elements.entrySet()) {
            con.setRequestProperty(entry.getKey(), entry.getValue().toString());
        }

        // Set timeouts
        con.setConnectTimeout(timeout);
        con.setReadTimeout(timeout);

        // Handle SSL verification
        if (!verifySSL && con instanceof HttpsURLConnection) {
            ((HttpsURLConnection) con).setSSLSocketFactory(createInsecureSSLSocketFactory());
            ((HttpsURLConnection) con).setHostnameVerifier((hostname, session) -> true);
        }

        // Send request body if present
        if (!content.isEmpty()) {
            con.setDoOutput(true);
            con.setRequestProperty("Content-Length", Integer.toString(content.length()));
            try (OutputStream os = con.getOutputStream()) {
                os.write(content.getBytes());
                os.flush();
            }
        }

        // Get response
        int responseCode = con.getResponseCode();
        BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
        String inputLine;
        StringBuilder response = new StringBuilder();
        while ((inputLine = in.readLine()) != null) {
            response.append(inputLine);
        }
        in.close();

        // Prepare response map
        RuntimeHash responseMap = new RuntimeHash();
        responseMap.put("success", new RuntimeScalar(responseCode >= 200 && responseCode < 300));
        responseMap.put("status", new RuntimeScalar(responseCode));
        responseMap.put("reason", new RuntimeScalar(con.getResponseMessage()));
        responseMap.put("content", new RuntimeScalar(response.toString()));

        // Collect headers
        RuntimeHash responseHeaders = new RuntimeHash();
        con.getHeaderFields().forEach((key, value) -> {
            if (key != null) {
                responseHeaders.put(key.toLowerCase(), new RuntimeScalar(String.join(", ", value)));
            }
        });
        responseMap.put("headers", responseHeaders.createReference());

        return responseMap.createReference().getList();
    }

    // Helper method to check if a URL should bypass the proxy
    private static boolean isNoProxy(String url) {
        if (noProxy == null) return false;
        for (String host : noProxy) {
            if (url.contains(host)) {
                return true;
            }
        }
        return false;
    }

    // Helper method to create an insecure SSL socket factory
    private static SSLSocketFactory createInsecureSSLSocketFactory() {
        try {
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{new X509TrustManager() {
                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return null;
                }

                public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {
                }

                public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {
                }
            }}, new java.security.SecureRandom());
            return sc.getSocketFactory();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create an insecure SSL socket factory", e);
        }
    }
}

