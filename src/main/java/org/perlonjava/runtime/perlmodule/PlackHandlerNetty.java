package org.perlonjava.runtime.perlmodule;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import org.perlonjava.runtime.io.ScalarBackedIO;
import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.runtimetypes.*;

import java.nio.charset.StandardCharsets;

/**
 * PlackHandlerNetty - PSGI server implementation using Netty.
 *
 * This class implements a high-performance HTTP server that bridges Perl web frameworks
 * (Dancer2, Catalyst, Mojolicious) to Java's Netty async I/O engine. It implements the
 * PSGI (Perl Web Server Gateway Interface) specification v1.1.
 *
 * Key Features:
 * - Full PSGI v1.1 environment hash construction
 * - Synchronous array response support (Phase 1)
 * - Single-threaded event loop (PerlOnJava thread-safety requirement)
 * - Error handling
 * - HTTP/1.1 with keep-alive support
 *
 * Thread Safety:
 * PerlOnJava is currently NOT thread-safe. This server uses a single-threaded
 * event loop (NioEventLoopGroup(1)) to avoid race conditions. Multiple concurrent
 * connections are handled via Netty's async I/O on one thread.
 *
 * Usage:
 * <pre>
 *   // Perl side: Plack::Handler::Netty->new(port => 5000)->run($app);
 *   // Java side:
 *   RuntimeScalar psgiApp = ...; // PSGI coderef
 *   PlackHandlerNetty server = new PlackHandlerNetty();
 *   // Method calls via Perl: $server = Plack::Handler::Netty->new(5000, $app, \%config);
 * </pre>
 *
 * @see <a href="https://metacpan.org/pod/PSGI">PSGI Specification</a>
 */
public class PlackHandlerNetty extends PerlModuleBase {

    /**
     * Creates a new PSGI server module instance for XSLoader.
     */
    public PlackHandlerNetty() {
        super("Plack::Handler::Netty");
    }

    /**
     * XSLoader entry point - called when XSLoader::load() loads this class.
     */
    public static void initialize() {
        PlackHandlerNetty module = new PlackHandlerNetty();
        try {
            module.registerMethod("new", "new_handler", null);
            module.registerMethod("run", "run_handler", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing PlackHandlerNetty method: " + e.getMessage());
        }
    }

    /**
     * Perl-side factory: creates a configuration object.
     * Called as: my $handler = Plack::Handler::Netty->new(host => '0.0.0.0', port => 5000);
     */
    public static RuntimeList new_handler(RuntimeArray args, int ctx) {
        // args[0] = class name (Plack::Handler::Netty)
        // args[1+] = hash of options

        RuntimeHash config = new RuntimeHash();

        // Collect all args into a hash (odd/even pairs)
        for (int i = 1; i < args.size(); i += 2) {
            if (i + 1 < args.size()) {
                config.put(args.get(i).toString(), args.get(i + 1));
            }
        }

        // Create a blessed hash with validated defaults
        RuntimeHash handler = new RuntimeHash();

        // Host - default to 0.0.0.0 (all interfaces)
        RuntimeScalar hostScalar = config.get("host");
        String host;
        if (hostScalar != null && hostScalar.type != RuntimeScalarType.UNDEF && !hostScalar.toString().isEmpty()) {
            host = hostScalar.toString();
        } else {
            host = "0.0.0.0";
        }
        handler.put("host", new RuntimeScalar(host));

        // Port - default to 5000
        RuntimeScalar portScalar = config.get("port");
        int port;
        if (portScalar != null && portScalar.type != RuntimeScalarType.UNDEF && portScalar.getInt() > 0) {
            port = portScalar.getInt();
        } else {
            port = 5000;
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535, got: " + port);
        }
        handler.put("port", new RuntimeScalar(port));

        // Backlog - default to 128 (standard value)
        RuntimeScalar backlogScalar = config.get("backlog");
        int backlog;
        if (backlogScalar != null && backlogScalar.type != RuntimeScalarType.UNDEF && backlogScalar.getInt() > 0) {
            backlog = backlogScalar.getInt();
        } else {
            backlog = 128;
        }
        handler.put("backlog", new RuntimeScalar(backlog));

        // Keep-alive - default to 30 seconds (standard HTTP keep-alive)
        RuntimeScalar keepaliveScalar = config.get("keepalive");
        int keepalive;
        if (keepaliveScalar != null && keepaliveScalar.type != RuntimeScalarType.UNDEF) {
            keepalive = keepaliveScalar.getInt();
        } else {
            keepalive = 30;
        }
        handler.put("keepalive", new RuntimeScalar(keepalive));

        // Max request size - default to 10MB (10485760 bytes)
        RuntimeScalar maxRequestSizeScalar = config.get("max_request_size");
        int maxRequestSize;
        if (maxRequestSizeScalar != null && maxRequestSizeScalar.type != RuntimeScalarType.UNDEF && maxRequestSizeScalar.getInt() > 0) {
            maxRequestSize = maxRequestSizeScalar.getInt();
        } else {
            maxRequestSize = 10485760;
        }
        handler.put("max_request_size", new RuntimeScalar(maxRequestSize));

        RuntimeScalar blessed = ReferenceOperators.bless(
            handler.createReferenceWithTrackedElements(),
            new RuntimeScalar("Plack::Handler::Netty")
        );

        return blessed.getList();
    }

    /**
     * Perl-side run method: starts the Netty server.
     * Called as: $handler->run($app);
     */
    public static RuntimeList run_handler(RuntimeArray args, int ctx) {
        // args[0] = blessed handler object
        // args[1] = PSGI app coderef

        RuntimeHash handler = args.get(0).hashDeref();
        RuntimeScalar psgiApp = args.get(1);

        int port = handler.get("port").getInt();
        String host = handler.get("host").toString();
        int backlog = handler.get("backlog").getInt();
        int keepalive = handler.get("keepalive").getInt();
        int maxRequestSize = handler.get("max_request_size").getInt();

        try {
            startNettyServer(port, host, psgiApp, backlog, maxRequestSize, keepalive > 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return new RuntimeList();
    }

    /**
     * Starts the Netty PSGI server. This method blocks until the server is shut down.
     *
     * @param port Listen port
     * @param host Listen address
     * @param psgiApp PSGI application coderef
     * @param backlog TCP connection backlog queue size
     * @param maxRequestSize Maximum HTTP request body size in bytes
     * @param keepAlive Enable HTTP keep-alive connections
     * @throws InterruptedException if the server is interrupted during startup or operation
     */
    private static void startNettyServer(int port, String host, RuntimeScalar psgiApp,
                                        int backlog, int maxRequestSize, boolean keepAlive) throws InterruptedException {
        // Single-threaded event loop to avoid PerlOnJava thread-safety issues
        // This still handles many concurrent connections via async I/O
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup(1);

        // Add shutdown hook for graceful shutdown on SIGTERM/SIGINT
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.err.println("Shutting down Netty PSGI server gracefully...");
            try {
                bossGroup.shutdownGracefully().sync();
                workerGroup.shutdownGracefully().sync();
                System.err.println("Server shut down complete.");
            } catch (InterruptedException e) {
                System.err.println("Shutdown interrupted: " + e.getMessage());
                Thread.currentThread().interrupt();
            }
        }));

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
             .channel(NioServerSocketChannel.class)
             .childHandler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 protected void initChannel(SocketChannel ch) {
                     ChannelPipeline pipeline = ch.pipeline();
                     pipeline.addLast(new HttpServerCodec());
                     pipeline.addLast(new HttpObjectAggregator(maxRequestSize));
                     pipeline.addLast(new PSGIRequestHandler(psgiApp, host, port, keepAlive));
                 }
             })
             .option(ChannelOption.SO_BACKLOG, backlog)
             .childOption(ChannelOption.SO_KEEPALIVE, keepAlive);

            System.err.println("Binding to " + host + ":" + port + "...");
            ChannelFuture f = b.bind(host, port).sync();
            System.err.println("Server started successfully on " + host + ":" + port);

            // Wait until the server socket is closed
            f.channel().closeFuture().sync();
        } catch (Exception e) {
            System.err.println("Server startup failed: " + e.getMessage());
            e.printStackTrace(System.err);
            throw e;
        } finally {
            // Shutdown event loops if not already shutdown
            if (!bossGroup.isShutdown()) {
                bossGroup.shutdownGracefully();
            }
            if (!workerGroup.isShutdown()) {
                workerGroup.shutdownGracefully();
            }
        }
    }

    /**
     * PSGIRequestHandler - Netty channel handler that processes HTTP requests via PSGI.
     *
     * For each HTTP request:
     * 1. Builds PSGI environment hash from Netty HttpRequest
     * 2. Calls PSGI app: $response = $app->($env)
     * 3. Converts PSGI response [status, headers, body] to Netty HttpResponse
     * 4. Writes response and optionally closes connection
     */
    static class PSGIRequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

        private final RuntimeScalar psgiApp;
        private final String serverName;
        private final int serverPort;
        private final boolean keepAlive;

        public PSGIRequestHandler(RuntimeScalar psgiApp, String serverName,
                                  int serverPort, boolean keepAlive) {
            this.psgiApp = psgiApp;
            this.serverName = serverName;
            this.serverPort = serverPort;
            this.keepAlive = keepAlive;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
            RuntimeHash env = null;
            try {
                // Build PSGI environment hash
                env = buildPSGIEnvironment(req);

                // Call PSGI app: $response = $app->($env)
                RuntimeArray args = new RuntimeArray();
                RuntimeArray.push(args, RuntimeHash.createHashRef(env));

                RuntimeList resultList = RuntimeCode.apply(psgiApp, args, RuntimeContextType.SCALAR);
                RuntimeScalar result = resultList.scalar();

                // Handle streaming responses (coderef) and synchronous responses (arrayref)
                if (result.type == RuntimeScalarType.CODE) {
                    // Streaming response - create responder callback
                    handleStreamingResponse(ctx, req, result, keepAlive);
                } else if (result.type == RuntimeScalarType.ARRAYREFERENCE) {
                    // Synchronous array response
                    handleArrayResponse(ctx, req, result, keepAlive);
                } else {
                    sendErrorResponse(ctx, req,
                        HttpResponseStatus.INTERNAL_SERVER_ERROR,
                        "PSGI app must return arrayref [status, headers, body] or coderef for streaming");
                }

            } catch (Exception e) {
                // Catch all exceptions from PSGI app and return 500
                // Log the full stack trace for debugging
                System.err.println("PSGI application error:");
                System.err.println("  Request: " + req.method() + " " + req.uri());
                System.err.println("  Exception: " + e.getClass().getName() + ": " + e.getMessage());
                e.printStackTrace(System.err);

                // Send user-friendly error response
                String errorMessage = "Internal Server Error";
                if (e.getMessage() != null && !e.getMessage().isEmpty()) {
                    errorMessage += ": " + e.getMessage();
                }

                sendErrorResponse(ctx, req,
                    HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    errorMessage);
            }
        }

        /**
         * Handles streaming PSGI responses (coderef).
         * The app returns a coderef that calls $responder with [status, headers, body].
         *
         * Strategy: Delegate to Perl helper function _handle_streaming_response()
         * which creates a native Perl responder callback. This is vastly simpler
         * than trying to create Perl-callable callbacks from Java.
         */
        private void handleStreamingResponse(ChannelHandlerContext ctx, FullHttpRequest req,
                                            RuntimeScalar streamingCoderef, boolean keepAlive) {
            try {
                // Create a Java-side callback that Perl can invoke to send HTTP response
                CallableHttpResponse responseCallback = new CallableHttpResponse(ctx, req, keepAlive);

                // Call Perl helper: Plack::Handler::Netty::_handle_streaming_response($coderef, $callback)
                RuntimeArray args = new RuntimeArray();
                RuntimeArray.push(args, streamingCoderef);
                // Wrap the callback as a RuntimeScalar coderef
                RuntimeArray.push(args, new RuntimeScalar(responseCallback));

                // Get the Perl helper method and invoke it
                RuntimeScalar helper = GlobalVariable.getGlobalCodeRef("Plack::Handler::Netty::_handle_streaming_response");
                if (helper.type == RuntimeScalarType.CODE) {
                    RuntimeCode.apply(helper, args, RuntimeContextType.VOID);
                } else {
                    sendErrorResponse(ctx, req,
                        HttpResponseStatus.INTERNAL_SERVER_ERROR,
                        "Perl streaming helper not loaded");
                }

            } catch (Exception e) {
                sendErrorResponse(ctx, req,
                    HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    "Streaming response error: " + e.getMessage());
            }
        }

        /**
         * Sends an array response (both sync and streaming array responses).
         */
        private void sendArrayResponse(ChannelHandlerContext ctx, FullHttpRequest req,
                                      int status, RuntimeArray headersArray,
                                      RuntimeArray bodyArray, boolean keepAlive) {
            FullHttpResponse response = buildHttpResponse(status, headersArray, bodyArray);

            if (keepAlive && HttpUtil.isKeepAlive(req)) {
                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
                ctx.writeAndFlush(response);
            } else {
                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
                ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
            }
        }

        /**
         * Handles synchronous PSGI responses (arrayref).
         */
        private void handleArrayResponse(ChannelHandlerContext ctx, FullHttpRequest req,
                                        RuntimeScalar result, boolean keepAlive) {
            // Parse PSGI response: [status, headers, body]
            RuntimeArray responseArray = result.arrayDeref();
            if (responseArray.size() != 3) {
                sendErrorResponse(ctx, req,
                    HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    "PSGI response must have 3 elements [status, headers, body]");
                return;
            }

            // Extract status
            int status = responseArray.get(0).getInt();

            // Extract headers (arrayref of pairs: ['Content-Type', 'text/html', ...])
            RuntimeScalar headersScalar = responseArray.get(1);
            if (headersScalar.type != RuntimeScalarType.ARRAYREFERENCE) {
                sendErrorResponse(ctx, req,
                    HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    "PSGI headers must be arrayref");
                return;
            }
            RuntimeArray headersArray = headersScalar.arrayDeref();

            // Extract body (arrayref of strings)
            RuntimeScalar bodyScalar = responseArray.get(2);
            if (bodyScalar.type != RuntimeScalarType.ARRAYREFERENCE) {
                sendErrorResponse(ctx, req,
                    HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    "PSGI body must be arrayref");
                return;
            }
            RuntimeArray bodyArray = bodyScalar.arrayDeref();

            // Send the response
            sendArrayResponse(ctx, req, status, headersArray, bodyArray, keepAlive);
        }

        /**
         * Builds the PSGI environment hash from Netty's HttpRequest.
         *
         * Implements PSGI v1.1 specification:
         * - REQUEST_METHOD, PATH_INFO, QUERY_STRING, etc.
         * - HTTP_* headers
         * - psgi.* special keys
         *
         * @param req Netty FullHttpRequest
         * @return PSGI environment hash
         */
        private RuntimeHash buildPSGIEnvironment(FullHttpRequest req) {
            RuntimeHash env = new RuntimeHash();

            // Parse URI into path and query string
            String uri = req.uri();
            QueryStringDecoder queryDecoder = new QueryStringDecoder(uri);
            String path = queryDecoder.path();
            String queryString = "";
            if (uri.contains("?")) {
                queryString = uri.substring(uri.indexOf("?") + 1);
            }

            // Required CGI variables
            env.put("REQUEST_METHOD", new RuntimeScalar(req.method().name()));
            env.put("SCRIPT_NAME", new RuntimeScalar("")); // Empty for root mount
            env.put("PATH_INFO", new RuntimeScalar(path));
            env.put("REQUEST_URI", new RuntimeScalar(uri));
            env.put("QUERY_STRING", new RuntimeScalar(queryString));
            env.put("SERVER_NAME", new RuntimeScalar(getServerName(req)));
            env.put("SERVER_PORT", new RuntimeScalar(serverPort));
            env.put("SERVER_PROTOCOL", new RuntimeScalar(req.protocolVersion().text()));

            // Content-Length and Content-Type (not in HTTP_* namespace)
            String contentLength = req.headers().get(HttpHeaderNames.CONTENT_LENGTH);
            if (contentLength != null && !contentLength.isEmpty()) {
                env.put("CONTENT_LENGTH", new RuntimeScalar(contentLength));
            } else {
                env.put("CONTENT_LENGTH", new RuntimeScalar(""));
            }

            String contentType = req.headers().get(HttpHeaderNames.CONTENT_TYPE);
            if (contentType != null && !contentType.isEmpty()) {
                env.put("CONTENT_TYPE", new RuntimeScalar(contentType));
            } else {
                env.put("CONTENT_TYPE", new RuntimeScalar(""));
            }

            // HTTP_* headers (convert all headers to HTTP_HEADER_NAME format)
            for (var entry : req.headers()) {
                String headerName = entry.getKey().toUpperCase().replace('-', '_');
                // Skip Content-Length and Content-Type (already added above)
                if (!headerName.equals("CONTENT_LENGTH") && !headerName.equals("CONTENT_TYPE")) {
                    env.put("HTTP_" + headerName, new RuntimeScalar(entry.getValue()));
                }
            }

            // psgi.version - [1, 1] for PSGI v1.1
            RuntimeArray version = new RuntimeArray();
            RuntimeArray.push(version, new RuntimeScalar(1));
            RuntimeArray.push(version, new RuntimeScalar(1));
            env.put("psgi.version", version.createReference());

            // psgi.url_scheme - http or https
            env.put("psgi.url_scheme", new RuntimeScalar("http")); // TODO: detect HTTPS

            // psgi.input - request body as IO::Handle
            ByteBuf content = req.content();
            byte[] bodyBytes = new byte[content.readableBytes()];
            content.getBytes(content.readerIndex(), bodyBytes);
            String bodyString = new String(bodyBytes, StandardCharsets.ISO_8859_1);
            RuntimeScalar bodyScalar = new RuntimeScalar(bodyString);
            ScalarBackedIO inputIO = new ScalarBackedIO(bodyScalar);
            RuntimeIO psgiInput = new RuntimeIO(inputIO);
            // Wrap in a new RuntimeScalar to ensure it's stored correctly
            env.put("psgi.input", new RuntimeScalar(psgiInput));

            // psgi.errors - stderr for error logging
            // Wrap in a new RuntimeScalar to ensure it's stored correctly
            env.put("psgi.errors", new RuntimeScalar(RuntimeIO.stderr));

            // psgi.multithread - \0 (PerlOnJava doesn't support threads)
            env.put("psgi.multithread", new RuntimeScalar(0));

            // psgi.multiprocess - \0 (PerlOnJava doesn't support fork)
            env.put("psgi.multiprocess", new RuntimeScalar(0));

            // psgi.run_once - \0 (persistent server)
            env.put("psgi.run_once", new RuntimeScalar(0));

            // psgi.nonblocking - \1 (Netty is async)
            env.put("psgi.nonblocking", new RuntimeScalar(1));

            // psgi.streaming - \1 (Phase 3 will implement streaming)
            env.put("psgi.streaming", new RuntimeScalar(1));

            return env;
        }

        /**
         * Extracts server name from Host header or uses default.
         *
         * @param req HTTP request
         * @return Server name (hostname without port)
         */
        private String getServerName(FullHttpRequest req) {
            String host = req.headers().get(HttpHeaderNames.HOST);
            if (host != null && !host.isEmpty()) {
                // Remove port if present
                int colonPos = host.indexOf(':');
                if (colonPos > 0) {
                    return host.substring(0, colonPos);
                }
                return host;
            }
            return serverName;
        }

        /**
         * Builds Netty HttpResponse from PSGI response array.
         *
         * @param status       HTTP status code
         * @param headersArray PSGI headers arrayref (flat list of name-value pairs)
         * @param bodyArray    PSGI body arrayref (array of strings)
         * @return Netty FullHttpResponse
         */
        private FullHttpResponse buildHttpResponse(int status, RuntimeArray headersArray,
                                                   RuntimeArray bodyArray) {
            // Build response body by concatenating all body parts
            StringBuilder bodyBuilder = new StringBuilder();
            for (int i = 0; i < bodyArray.size(); i++) {
                bodyBuilder.append(bodyArray.get(i).toString());
            }
            String bodyString = bodyBuilder.toString();

            // Create Netty response
            HttpResponseStatus httpStatus = HttpResponseStatus.valueOf(status);
            FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                httpStatus,
                Unpooled.copiedBuffer(bodyString, CharsetUtil.UTF_8)
            );

            // Add PSGI headers (flat array: name1, value1, name2, value2, ...)
            for (int i = 0; i < headersArray.size() - 1; i += 2) {
                String headerName = headersArray.get(i).toString();
                String headerValue = headersArray.get(i + 1).toString();
                response.headers().set(headerName, headerValue);
            }

            // Set Content-Length if not already set
            if (!response.headers().contains(HttpHeaderNames.CONTENT_LENGTH)) {
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH,
                    response.content().readableBytes());
            }

            return response;
        }

        /**
         * Sends an error response with given status and message.
         *
         * @param ctx     Channel context
         * @param req     Original request
         * @param status  HTTP status
         * @param message Error message
         */
        private void sendErrorResponse(ChannelHandlerContext ctx, FullHttpRequest req,
                                      HttpResponseStatus status, String message) {
            String body = "<html><body><h1>" + status + "</h1><p>" +
                         escapeHtml(message) + "</p></body></html>";

            FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                Unpooled.copiedBuffer(body, CharsetUtil.UTF_8)
            );

            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=utf-8");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH,
                response.content().readableBytes());
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);

            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }

        /**
         * Simple HTML escaping for error messages.
         *
         * @param text Text to escape
         * @return HTML-escaped text
         */
        private String escapeHtml(String text) {
            if (text == null) return "";
            return text.replace("&", "&amp;")
                      .replace("<", "&lt;")
                      .replace(">", "&gt;")
                      .replace("\"", "&quot;")
                      .replace("'", "&#39;");
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            // Log exception details
            System.err.println("Channel exception caught:");
            System.err.println("  Channel: " + ctx.channel().remoteAddress());
            System.err.println("  Exception: " + cause.getClass().getName() + ": " + cause.getMessage());
            cause.printStackTrace(System.err);

            // Close the connection
            if (ctx.channel().isActive()) {
                ctx.close();
            }
        }
    }

    /**
     * CallableHttpResponse - A RuntimeCode that can be invoked from Perl
     * to send HTTP responses during streaming.
     *
     * Perl calls this with: $callback->([status, headers, body])
     * and it sends the Netty HTTP response.
     */
    static class CallableHttpResponse extends RuntimeCode implements PerlSubroutine {
        private final ChannelHandlerContext ctx;
        private final FullHttpRequest req;
        private final boolean keepAlive;

        /**
         * Create a responder callback for the given HTTP context.
         *
         * @param ctx      Netty channel context for sending response
         * @param req      Original HTTP request (for keep-alive detection)
         * @param keepAlive Whether to keep connection alive
         */
        public CallableHttpResponse(ChannelHandlerContext ctx, FullHttpRequest req, boolean keepAlive) {
            // Pass 'this' as the PerlSubroutine implementation
            super((PerlSubroutine) null, null);
            // Set the subroutine after construction
            this.subroutine = this;
            this.ctx = ctx;
            this.req = req;
            this.keepAlive = keepAlive;
        }

        /**
         * Invoke the responder with [status, headers, body].
         * Called by Perl code: $responder->([200, ['Content-Type', 'text/plain'], ['Hello']])
         */
        @Override
        public RuntimeList apply(RuntimeArray args, int context) {
            try {
                if (args.size() < 1) {
                    throw new IllegalArgumentException("Responder requires at least 1 argument");
                }

                // First argument should be [status, headers, body] arrayref
                RuntimeScalar arg = args.get(0);
                if (arg.type != RuntimeScalarType.ARRAYREFERENCE) {
                    throw new IllegalArgumentException(
                        "Responder requires arrayref [status, headers, body], got " + arg.type);
                }

                RuntimeArray responseArray = arg.arrayDeref();
                if (responseArray.size() != 3) {
                    throw new IllegalArgumentException(
                        "Responder requires [status, headers, body] (3 elements), got " +
                        responseArray.size());
                }

                // Extract components
                int status = responseArray.get(0).getInt();
                RuntimeScalar headersScalar = responseArray.get(1);
                RuntimeScalar bodyScalar = responseArray.get(2);

                if (headersScalar.type != RuntimeScalarType.ARRAYREFERENCE) {
                    throw new IllegalArgumentException(
                        "Headers must be arrayref, got " + headersScalar.type);
                }
                if (bodyScalar.type != RuntimeScalarType.ARRAYREFERENCE) {
                    throw new IllegalArgumentException(
                        "Body must be arrayref, got " + bodyScalar.type);
                }

                RuntimeArray headersArray = headersScalar.arrayDeref();
                RuntimeArray bodyArray = bodyScalar.arrayDeref();

                // Send the HTTP response
                sendArrayResponse(ctx, req, status, headersArray, bodyArray, keepAlive);
                return new RuntimeList();

            } catch (Exception e) {
                throw new RuntimeException("HTTP response error: " + e.getMessage(), e);
            }
        }

        /**
         * Builds and sends the HTTP response. Extracted from PSGIRequestHandler
         * so both sync and streaming paths can use it.
         */
        private void sendArrayResponse(ChannelHandlerContext ctx, FullHttpRequest req,
                                      int status, RuntimeArray headersArray,
                                      RuntimeArray bodyArray, boolean keepAlive) {
            FullHttpResponse response = buildHttpResponse(status, headersArray, bodyArray);

            if (keepAlive && HttpUtil.isKeepAlive(req)) {
                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
                ctx.writeAndFlush(response);
            } else {
                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
                ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
            }
        }

        /**
         * Builds Netty HttpResponse from PSGI response array.
         */
        private FullHttpResponse buildHttpResponse(int status, RuntimeArray headersArray,
                                                   RuntimeArray bodyArray) {
            // Build response body by concatenating all body parts
            StringBuilder bodyBuilder = new StringBuilder();
            for (int i = 0; i < bodyArray.size(); i++) {
                bodyBuilder.append(bodyArray.get(i).toString());
            }

            String bodyStr = bodyBuilder.toString();
            FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.valueOf(status),
                Unpooled.copiedBuffer(bodyStr, CharsetUtil.UTF_8)
            );

            // Process PSGI headers (flat array of pairs: key1, val1, key2, val2, ...)
            for (int i = 0; i < headersArray.size(); i += 2) {
                if (i + 1 < headersArray.size()) {
                    String headerName = headersArray.get(i).toString();
                    String headerValue = headersArray.get(i + 1).toString();
                    response.headers().set(headerName, headerValue);
                }
            }

            // Set Content-Length if not already set
            if (!response.headers().contains(HttpHeaderNames.CONTENT_LENGTH)) {
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH,
                    response.content().readableBytes());
            }

            return response;
        }
    }
}
