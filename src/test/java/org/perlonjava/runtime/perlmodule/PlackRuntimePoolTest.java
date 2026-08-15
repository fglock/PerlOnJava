package org.perlonjava.runtime.perlmodule;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.runtimetypes.*;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class PlackRuntimePoolTest {
    @Test
    void requestSlotsOwnIndependentSnapshotRuntimesAndAppRoots() throws Exception {
        PerlRuntime template = new PerlRuntime().initialize();
        RuntimeScalar app = new RuntimeScalar(new RuntimeCode(
                (args, context) -> new RuntimeScalar(200).getList(), null));
        PlackHandlerNetty.PsgiRuntimePool pool =
                new PlackHandlerNetty.PsgiRuntimePool(template, app, 2);

        PerlRuntime firstRuntime;
        PerlRuntime secondRuntime;
        try (PlackHandlerNetty.PsgiRuntimePool.RequestLease first = pool.checkout();
             PlackHandlerNetty.PsgiRuntimePool.RequestLease second = pool.checkout()) {
            firstRuntime = first.runtime();
            secondRuntime = second.runtime();
            assertNotSame(template, firstRuntime);
            assertNotSame(firstRuntime, secondRuntime);
            assertNotSame(first.app(), second.app());
            assertEquals(RuntimeScalarType.CODE, first.app().type);
        }

        assertTrue(firstRuntime.isClosed());
        assertTrue(secondRuntime.isClosed());
        try (PlackHandlerNetty.PsgiRuntimePool.RequestLease replacement = pool.checkout()) {
            assertNotSame(firstRuntime, replacement.runtime());
            assertNotSame(secondRuntime, replacement.runtime());
        } finally {
            pool.close();
            template.close();
        }
    }

    @Test
    void pooledHandlerAdvertisesMultithreadAndUsesDistinctRequestRuntimes() {
        PerlRuntime template = new PerlRuntime().initialize();
        Set<PerlRuntime> observed = ConcurrentHashMap.newKeySet();
        RuntimeCode callback = new RuntimeCode((args, context) -> {
            observed.add(PerlRuntime.current());
            RuntimeHash env = args.get(0).hashDeref();
            assertEquals(1, env.get("psgi.multithread").getInt());

            RuntimeArray headers = new RuntimeArray(
                    new RuntimeScalar("Content-Type"), new RuntimeScalar("text/plain"));
            RuntimeArray body = new RuntimeArray(new RuntimeScalar("ok"));
            RuntimeArray response = new RuntimeArray(
                    new RuntimeScalar(200), headers.createReference(), body.createReference());
            return response.createReference().getList();
        }, null);
        RuntimeScalar app = new RuntimeScalar(callback);
        PlackHandlerNetty.PsgiRuntimePool pool =
                new PlackHandlerNetty.PsgiRuntimePool(template, app, 2);
        try {
            for (int i = 0; i < 2; i++) {
                EmbeddedChannel channel = new EmbeddedChannel(
                        new PlackHandlerNetty.PSGIRequestHandler(
                                app, "localhost", 5000, false, template, pool));
                try {
                    channel.writeInbound(new DefaultFullHttpRequest(
                            HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
                    FullHttpResponse response = channel.readOutbound();
                    assertNotNull(response);
                    assertEquals(200, response.status().code());
                    assertEquals("ok", response.content().toString(StandardCharsets.ISO_8859_1));
                    response.release();
                } finally {
                    channel.finishAndReleaseAll();
                }
            }
            assertEquals(2, observed.size());
            assertFalse(observed.contains(template));
        } finally {
            pool.close();
            template.close();
        }
    }
}
