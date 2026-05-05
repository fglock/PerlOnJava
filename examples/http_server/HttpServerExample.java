package examples.http_server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;
import org.perlonjava.runtime.runtimetypes.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * HTTP Server Example using Netty and PerlOnJava.
 *
 * This example demonstrates how to create a web server where request handlers
 * are written in Perl. The Perl handler is loaded from a separate file for
 * better code organization.
 *
 * IMPORTANT: Thread Safety Considerations
 *
 * PerlOnJava is currently NOT thread-safe. All global state (variables, arrays,
 * hashes) is stored in static fields without synchronization. This example uses
 * a SINGLE-THREADED event loop to avoid race conditions:
 *
 *   - All requests are handled by one event loop thread
 *   - Multiple concurrent connections are supported (via async I/O)
 *   - No locks needed since only one thread accesses Perl state
 *
 * Alternative approach (not used here):
 *   - Use a global lock: synchronized(PERL_LOCK) { ... }
 *   - This serializes all requests but works with multi-threaded event loops
 *
 * Future: PerlRuntime Pool
 *   Once multiplicity is implemented (see dev/design/concurrency.md), you can
 *   use a pool of isolated Perl runtimes for true parallel request handling.
 *
 * Prerequisites:
 *   1. Build the fat jar:
 *        make
 *      or:
 *        ./gradlew shadowJar
 *   2. Download Netty JARs (this will be done by the Makefile)
 *
 * Run:
 *   make run
 *
 * Test:
 *   curl http://localhost:8080/
 *   curl http://localhost:8080/api/users
 *   curl -X POST http://localhost:8080/form -d "name=Alice"
 */
public class HttpServerExample {

    private static RuntimeScalar perlHandler;

    public static void main(String[] args) throws Exception {
        int port = 8080;
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }

        System.out.println("Initializing PerlOnJava...");
        initializePerlHandler();
        System.out.println("Perl handler loaded successfully.");

        // Use a SINGLE event loop thread to avoid thread-safety issues
        // This still handles many concurrent connections efficiently via async I/O
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup(1);  // Single worker thread

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
             .channel(NioServerSocketChannel.class)
             .childHandler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 protected void initChannel(SocketChannel ch) {
                     ch.pipeline().addLast(new HttpServerCodec());
                     ch.pipeline().addLast(new HttpObjectAggregator(65536));
                     ch.pipeline().addLast(new HttpRequestHandler());
                 }
             })
             .option(ChannelOption.SO_BACKLOG, 128)
             .childOption(ChannelOption.SO_KEEPALIVE, true);

            ChannelFuture f = b.bind(port).sync();
            System.out.println("\n" +
                "=======================================================\n" +
                "  HTTP Server started on http://localhost:" + port + "\n" +
                "  Thread model: Single event loop (thread-safe)\n" +
                "  Press Ctrl+C to stop\n" +
                "=======================================================\n");

            f.channel().closeFuture().sync();
        } finally {
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }

    /**
     * Initialize PerlOnJava and load the Perl handler from handler.pl
     */
    private static void initializePerlHandler() throws Exception {
        // Initialize PerlOnJava runtime
        PerlLanguageProvider.resetAll();

        // Read the Perl handler script from file
        String handlerPath = "examples/http_server/handler.pl";
        String perlCode = new String(Files.readAllBytes(Paths.get(handlerPath)));

        // Compile and execute the Perl script
        CompilerOptions options = new CompilerOptions();
        options.fileName = handlerPath;
        options.code = perlCode;

        PerlLanguageProvider.executePerlCode(options, true);

        // Get reference to the handle_request subroutine
        perlHandler = GlobalVariable.getGlobalCodeRef("main::handle_request");

        if (perlHandler == null || perlHandler.value == null) {
            throw new RuntimeException(
                "Failed to load handle_request subroutine from " + handlerPath);
        }
    }

    /**
     * Netty handler that processes HTTP requests by calling Perl code
     */
    static class HttpRequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
            // Extract request information
            String method = req.method().name();
            String uri = req.uri();
            String body = req.content().toString(CharsetUtil.UTF_8);

            // Build query parameters hash
            RuntimeHash queryParams = new RuntimeHash();
            QueryStringDecoder queryDecoder = new QueryStringDecoder(uri);
            queryDecoder.parameters().forEach((key, values) -> {
                if (!values.isEmpty()) {
                    queryParams.put(key, new RuntimeScalar(values.get(0)));
                }
            });

            // Build headers hash
            RuntimeHash headers = new RuntimeHash();
            req.headers().forEach(entry -> {
                headers.put(entry.getKey().toLowerCase(), new RuntimeScalar(entry.getValue()));
            });

            // Build request hash
            RuntimeHash request = new RuntimeHash();
            request.put("method", new RuntimeScalar(method));
            request.put("path", new RuntimeScalar(queryDecoder.path()));
            request.put("uri", new RuntimeScalar(uri));
            request.put("body", new RuntimeScalar(body));
            request.put("query", new RuntimeScalar(queryParams));
            request.put("headers", new RuntimeScalar(headers));

            // Call Perl handler: handle_request(\%request)
            // Create a proper hash reference using the PerlOnJava API
            RuntimeScalar hashRef = RuntimeHash.createHashRef(request);

            RuntimeArray args = new RuntimeArray();
            RuntimeArray.push(args, hashRef);

            RuntimeList resultList;
            try {
                resultList = RuntimeCode.apply(perlHandler, args, RuntimeContextType.SCALAR);
            } catch (Exception e) {
                // Error in Perl handler
                e.printStackTrace();
                sendResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    "text/plain", "Internal Server Error: " + e.getMessage());
                return;
            }

            // Get the scalar result from the list
            RuntimeScalar resultScalar = resultList.scalar();

            // Parse response from Perl
            if (resultScalar.value instanceof RuntimeHash) {
                RuntimeHash response = resultScalar.hashDeref();

                int status = response.get("status").getInt();
                String contentType = response.get("content_type").toString();
                String responseBody = response.get("body").toString();

                HttpResponseStatus httpStatus = HttpResponseStatus.valueOf(status);
                sendResponse(ctx, httpStatus, contentType, responseBody);
            } else {
                // Perl handler returned a simple string
                sendResponse(ctx, HttpResponseStatus.OK, "text/plain", resultScalar.toString());
            }
        }

        private void sendResponse(ChannelHandlerContext ctx, HttpResponseStatus status,
                                 String contentType, String body) {
            FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                Unpooled.copiedBuffer(body, CharsetUtil.UTF_8)
            );

            response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType + "; charset=utf-8");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);

            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cause.printStackTrace();
            ctx.close();
        }
    }
}
