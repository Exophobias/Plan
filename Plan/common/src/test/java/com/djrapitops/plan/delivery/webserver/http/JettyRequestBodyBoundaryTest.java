package com.djrapitops.plan.delivery.webserver.http;

import com.djrapitops.plan.delivery.web.resolver.Response;
import com.djrapitops.plan.delivery.webserver.Addresses;
import com.djrapitops.plan.delivery.webserver.auth.AuthenticationExtractor;
import com.djrapitops.plan.delivery.webserver.configuration.WebserverConfiguration;
import com.djrapitops.plan.processing.Processing;
import com.djrapitops.plan.settings.config.PlanConfig;
import com.djrapitops.plan.utilities.logging.ErrorLogger;
import net.playeranalytics.plugin.server.PluginLogger;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises the real Jetty reader and HTTP handler, before any resolver can inspect the body. */
class JettyRequestBodyBoundaryTest {
    private static final String INSIGHTS = "/patriam-bridge/player-insights/00000000-0000-0000-0000-000000000000";
    private final WebserverConfiguration config = mock(WebserverConfiguration.class);
    private final AuthenticationExtractor auth = mock(AuthenticationExtractor.class);

    private Request request(String method, String path, HttpFields headers) {
        Request request = mock(Request.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getHttpURI()).thenReturn(HttpURI.from(path));
        when(request.getHeaders()).thenReturn(headers);
        when(request.getLength()).thenReturn(-1L);
        return request;
    }

    @ParameterizedTest
    @ValueSource(strings = {"/auth/forum", "/auth/forum/start", "/auth/forum/callback", "/auth/forum/status",
            "/auth/forum/unknown", "/auth/%66orum/callback", "/%61uth/forum/start",
            "/auth/forum/../forum/status", "/patriam-bridge/player-insights",
            INSIGHTS, "/patriam-bridge/%70layer-insights/unknown"})
    void declaredHugeBodiesAreRejectedWithoutReadingOrAuthenticating(String path) {
        Request nativeRequest = request("GET", path, HttpFields.build().put("Content-Length", "2147483647"));
        Response rejection = new JettyInternalRequest(nativeRequest, config, auth).preflight().join().orElseThrow();
        assertEquals(413, rejection.getCode());
        assertSafe(rejection);
        verify(nativeRequest, never()).read();
        verifyNoInteractions(config, auth);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "DELETE", "HEAD", "OPTIONS", "PATCH"})
    void methodsAreRejectedBeforeReadingEvenAnUnfinishedBody(String method) {
        Request nativeRequest = request(method, INSIGHTS, HttpFields.build().put("Transfer-Encoding", "chunked"));
        Response rejection = new JettyInternalRequest(nativeRequest, config, auth).preflight().join().orElseThrow();
        assertEquals(405, rejection.getCode());
        assertSafe(rejection);
        verify(nativeRequest, never()).read();
        verifyNoInteractions(config, auth);
    }

    @ParameterizedTest
    @ValueSource(strings = {"chunked", "gzip, chunked", "identity"})
    void transferEncodingIsRejectedBeforeRequestingTheFirstChunk(String encoding) {
        Request nativeRequest = request("GET", "/auth/forum/callback", HttpFields.build().put("tRaNsFeR-EnCoDiNg", encoding));
        assertEquals(413, new JettyInternalRequest(nativeRequest, config, auth).preflight().join().orElseThrow().getCode());
        verify(nativeRequest, never()).read();
    }

    @Test
    void unknownLengthBodyStopsAtFirstByteInsteadOfAggregatingFurtherChunks() {
        Request nativeRequest = request("GET", INSIGHTS, HttpFields.EMPTY);
        Content.Chunk chunk = spy(Content.Chunk.from(ByteBuffer.wrap(new byte[] {1}), false));
        when(nativeRequest.read()).thenReturn(chunk).thenThrow(new AssertionError("must not read more content"));
        Response rejection = new JettyInternalRequest(nativeRequest, config, auth).preflight().join().orElseThrow();
        assertEquals(400, rejection.getCode());
        assertSafe(rejection);
        verify(chunk).release();
        verify(nativeRequest, times(1)).read();
        verify(nativeRequest).fail(any(Throwable.class));
        verifyNoInteractions(auth);
    }

    @Test
    void bodyReadFailureCannotMasqueradeAsEmptyContent() {
        Request nativeRequest = request("GET", "/auth/forum/callback?code=secret-code", HttpFields.EMPTY);
        when(nativeRequest.read()).thenReturn(Content.Chunk.from(new IOException("private-parser-detail")));
        JettyInternalRequest internal = new JettyInternalRequest(nativeRequest, config, auth);
        Response rejection = internal.preflight().join().orElseThrow();
        assertEquals(400, rejection.getCode());
        assertFalse(rejection.getAsString().contains("private-parser-detail"));
        assertFalse(rejection.getAsString().contains("secret-code"));
        verifyNoInteractions(auth);
    }

    @Test
    void unknownLengthEmptyBodyWaitsForEofThenReusesTheValidatedRead() {
        Request nativeRequest = request("GET", INSIGHTS, HttpFields.EMPTY);
        AtomicReference<Runnable> demand = new AtomicReference<>();
        doAnswer(invocation -> { demand.set(invocation.getArgument(0)); return null; }).when(nativeRequest).demand(any());
        when(nativeRequest.read()).thenReturn(null, Content.Chunk.EOF);
        JettyInternalRequest internal = new JettyInternalRequest(nativeRequest, config, auth);
        CompletableFuture<Optional<Response>> result = internal.preflight();
        assertFalse(result.isDone());
        verifyNoInteractions(auth);
        demand.get().run();
        assertTrue(result.join().isEmpty());
        when(config.isAuthenticationDisabled()).thenReturn(true);
        assertArrayEquals(new byte[0], internal.toRequest("127.0.0.1").getRequestBody());
        verify(nativeRequest, times(2)).read();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/auth/login", "/auth/register", "/v1/web/theme", "/other/auth/forum/start",
            "/patriam-bridge/player-insights-other"})
    void unrelatedPostBodiesKeepTheirExistingBehavior(String path) {
        Request nativeRequest = request("POST", path, HttpFields.EMPTY);
        byte[] bytes = "existing-form=value".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(nativeRequest.read()).thenReturn(Content.Chunk.from(ByteBuffer.wrap(bytes), true));
        when(config.isAuthenticationDisabled()).thenReturn(true);
        JettyInternalRequest internal = new JettyInternalRequest(nativeRequest, config, auth);
        assertTrue(internal.preflight().join().isEmpty());
        verify(nativeRequest, never()).read();
        assertArrayEquals(bytes, internal.toRequest("127.0.0.1").getRequestBody());
    }

    @Test
    void realHttpHandlerRejectsBeforeConversionAndPreservesValidCallbackAndStats() throws Exception {
        RequestHandler handler = mock(RequestHandler.class);
        when(config.isAuthenticationDisabled()).thenReturn(true);
        when(handler.getResponse(any())).thenAnswer(invocation -> {
            InternalRequest internal = invocation.getArgument(0);
            var converted = internal.toRequest("127.0.0.1");
            assertEquals(0, converted.getRequestBody().length);
            return CompletableFuture.completedFuture(Response.builder().setStatus(204).build());
        });
        Processing processing = mock(Processing.class);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        when(processing.getNonCriticalExecutor()).thenReturn(executor);
        Server server = new Server();
        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);
        server.setHandler(new JettyRequestHandler(config, auth, mock(Addresses.class), handler, processing,
                mock(PlanConfig.class), mock(PluginLogger.class), mock(ErrorLogger.class)));
        try {
            server.start();
            assertStatus(413, connector.getResponse("GET /auth/%66orum/callback HTTP/1.1\r\nHost: localhost\r\nContent-Length: 2147483647\r\nConnection: close\r\n\r\n", 3, TimeUnit.SECONDS));
            assertStatus(413, connector.getResponse("GET " + INSIGHTS + " HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n", 3, TimeUnit.SECONDS));
            assertStatus(405, connector.getResponse("POST /auth/forum/start HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1000000\r\nConnection: close\r\n\r\n", 3, TimeUnit.SECONDS));
            verifyNoInteractions(handler, auth);
            assertStatus(400, connector.getResponse("GET /auth/forum/status HTTP/1.1\r\nHost: localhost\r\nContent-Length: broken\r\nConnection: close\r\n\r\n", 3, TimeUnit.SECONDS));
            assertStatus(400, connector.getResponse("GET /auth%2fforum/callback HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1\r\nConnection: close\r\n\r\nx", 3, TimeUnit.SECONDS));
            assertStatus(400, connector.getResponse("GET /auth/forum/%xx HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n", 3, TimeUnit.SECONDS));
            assertStatus(400, connector.getResponse("GET /auth/forum/% HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n", 3, TimeUnit.SECONDS));
            verifyNoInteractions(handler, auth);
            assertStatus(204, connector.getResponse("GET /auth/forum/callback?code=code&state=state HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n"));
            assertStatus(204, connector.getResponse("GET " + INSIGHTS + " HTTP/1.1\r\nHost: localhost\r\n\r\n"));
            verify(handler, times(2)).getResponse(any());
        } finally {
            server.stop();
            executor.shutdownNow();
        }
    }

    private static void assertStatus(int expected, String response) {
        assertNotNull(response);
        assertTrue(response.startsWith("HTTP/1.1 " + expected + " "), response);
        if (expected == 405 || expected == 413) {
            assertTrue(response.contains("Cache-Control: no-store"), response);
            assertTrue(response.contains("Allow: GET"), response);
        }
    }

    private static void assertSafe(Response response) {
        assertEquals("GET", response.getHeaders().get("Allow"));
        assertEquals("no-store", response.getHeaders().get("Cache-Control"));
        assertEquals("no-referrer", response.getHeaders().get("Referrer-Policy"));
        assertFalse(response.getHeaders().containsKey("Set-Cookie"));
    }
}
