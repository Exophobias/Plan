/*
 *  This file is part of Player Analytics (Plan).
 *
 *  Plan is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Lesser General Public License v3 as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  Plan is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with Plan. If not, see <https://www.gnu.org/licenses/>.
 */
package com.djrapitops.plan.delivery.webserver.auth.forum;

import com.djrapitops.plan.settings.forumauth.ForumAuthConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HttpForumBrokerTest {
    private static final String ISSUER = "https://forums.example.test";
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000042");

    private static JsonObject validJson() {
        return JsonParser.parseString("""
                {"issuer":"https://forums.example.test","subject":"42",
                 "minecraft_uuid":"00000000-0000-0000-0000-000000000042","link_revision":"verified-link-1",
                 "auth_method":"forum","auth_time":1800000000,"expires_in":900,"check_after":60}
                """).getAsJsonObject();
    }

    static Stream<Arguments> invalidFields() {
        return Stream.of(
                Arguments.of("issuer", "\"https://other.example.test\""),
                Arguments.of("subject", "42"), Arguments.of("subject", "\"name\""),
                Arguments.of("subject", "\"0\""), Arguments.of("subject", "\"01\""),
                Arguments.of("minecraft_uuid", "\"0-0-0-0-42\""), Arguments.of("minecraft_uuid", "null"),
                Arguments.of("link_revision", "\"\""), Arguments.of("link_revision", "\"revision with spaces\""),
                Arguments.of("auth_method", "\"discord\""), Arguments.of("auth_time", "0"),
                Arguments.of("auth_time", "\"1800000000\""), Arguments.of("auth_time", "1.1"),
                Arguments.of("auth_time", "1e9"), Arguments.of("expires_in", "901"),
                Arguments.of("expires_in", "-1"), Arguments.of("check_after", "61"),
                Arguments.of("check_after", "0"), Arguments.of("check_after", "true"));
    }

    @ParameterizedTest
    @MethodSource("invalidFields")
    void invalidIdentityClaimsAreRejectedWithoutEchoingSource(String field, String invalidJson) {
        JsonObject json = validJson();
        json.add(field, JsonParser.parseString(invalidJson));
        IOException error = assertThrows(IOException.class, () -> HttpForumBroker.parse(json.toString(), ISSUER));
        assertEquals("Invalid forum identity response", error.getMessage());
        assertNull(error.getCause());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "{}", "not-json", "{\"secret\":\"must-not-be-echoed\"}"})
    void malformedResponseIsSanitized(String json) {
        IOException error = assertThrows(IOException.class, () -> HttpForumBroker.parse(json, ISSUER));
        assertEquals("Invalid forum identity response", error.getMessage());
    }

    @Test
    void recheckPreservesOriginalAuthenticationTimeAndExpiry() throws IOException {
        ForumIdentity original = HttpForumBroker.parse(validJson().toString(), ISSUER);
        JsonObject response = validJson();
        response.remove("auth_time");
        response.remove("auth_method");
        response.remove("expires_in");
        response.addProperty("check_after", 30);
        ForumIdentity checked = HttpForumBroker.parseCheck(response.toString(), ISSUER, original);
        assertTrue(checked.sameAccount(original));
        assertEquals(original.authTime(), checked.authTime());
        assertEquals(original.expiresIn(), checked.expiresIn());
        assertEquals(30, checked.checkAfter());
    }

    @Test
    void boundedSubscriberAcceptsExactLimitAndCancelsOversizedChunks() throws Exception {
        HttpForumBroker.LimitedBodySubscriber exact = new HttpForumBroker.LimitedBodySubscriber();
        Flow.Subscription exactSubscription = mock(Flow.Subscription.class);
        exact.onSubscribe(exactSubscription);
        exact.onNext(List.of(ByteBuffer.allocate(4096), ByteBuffer.allocate(4096)));
        exact.onComplete();
        assertEquals(8192, exact.getBody().toCompletableFuture().get().length);
        verify(exactSubscription, never()).cancel();

        HttpForumBroker.LimitedBodySubscriber excessive = new HttpForumBroker.LimitedBodySubscriber();
        Flow.Subscription excessiveSubscription = mock(Flow.Subscription.class);
        excessive.onSubscribe(excessiveSubscription);
        excessive.onNext(List.of(ByteBuffer.allocate(4096)));
        excessive.onNext(List.of(ByteBuffer.allocate(4097)));
        excessive.onComplete();
        assertThrows(ExecutionException.class, () -> excessive.getBody().toCompletableFuture().get());
        verify(excessiveSubscription).cancel();
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 409})
    void brokerRefusalIsDistinctFromRetryableTransportFailure(int status) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        try {
            HttpForumBroker broker = localBroker(server);
            assertThrows(ForumVerificationRefusedException.class, () -> broker.redeem("code", "verifier", "state"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void tokenExchangeUsesBackchannelCredentialsAndBoundCallback() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/token", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] json = validJson().toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, json.length);
            exchange.getResponseBody().write(json);
            exchange.close();
        });
        server.start();
        try {
            ForumIdentity identity = localBroker(server).redeem("code", "verifier", "state");
            assertEquals(PLAYER, identity.minecraftUUID());
            assertEquals("Bearer test-client-secret", authorization.get());
            JsonObject body = JsonParser.parseString(requestBody.get()).getAsJsonObject();
            assertEquals("code", body.get("code").getAsString());
            assertEquals("verifier", body.get("code_verifier").getAsString());
            assertEquals("state", body.get("state").getAsString());
            assertEquals("https://plan.example.test/auth/forum/callback", body.get("redirect_uri").getAsString());
            assertFalse(requestBody.get().contains("test-client-secret"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void serverStallingAfterHeadersCannotExceedWholeExchangeDeadline() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newCachedThreadPool();
        CountDownLatch release = new CountDownLatch(1);
        server.setExecutor(executor);
        server.createContext("/token", exchange -> {
            try {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 100);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            HttpForumBroker broker = localBroker(server);
            CompletableFuture<Boolean> completed = CompletableFuture.supplyAsync(() -> {
                try { broker.redeem("code", "verifier", "state"); return false; }
                catch (IOException expected) { return true; }
            });
            assertTrue(completed.get(3, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private static HttpForumBroker localBroker(HttpServer server) {
        ForumAuthConfig config = mock(ForumAuthConfig.class);
        when(config.getTimeoutSeconds()).thenReturn(1);
        when(config.getTokenUrl()).thenReturn(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/token"));
        when(config.getClientId()).thenReturn("plan-test");
        when(config.getClientSecret()).thenReturn("test-client-secret");
        when(config.getCallbackUrl()).thenReturn("https://plan.example.test/auth/forum/callback");
        when(config.getForumUrl()).thenReturn(ISSUER);
        return new HttpForumBroker(config);
    }
}
