package com.djrapitops.plan.delivery.webserver.auth.forum;

import com.djrapitops.plan.settings.forumauth.ForumAuthConfig;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/** No redirects, browser credentials, dynamic discovery or reusable browser assertions. */
public final class HttpForumBroker implements ForumBroker {
    private final ForumAuthConfig config;
    private final HttpClient client;

    public HttpForumBroker(ForumAuthConfig config) {
        this.config = config;
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public ForumIdentity redeem(String code, String verifier, String state) throws IOException {
        return parse(post(config.getTokenUrl(), Map.of("client_id", config.getClientId(), "code", code,
                "redirect_uri", config.getCallbackUrl(), "code_verifier", verifier, "state", state)), config.getForumUrl());
    }

    @Override
    public ForumIdentity check(ForumIdentity identity) throws IOException {
        String response = post(config.getCheckUrl(), Map.of("client_id", config.getClientId(), "subject", identity.subject(),
                "minecraft_uuid", identity.minecraftUUID().toString(), "link_revision", identity.revision()));
        return parseCheck(response, config.getForumUrl(), identity);
    }

    private String post(URI endpoint, Map<String, String> body) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .header("Authorization", "Bearer " + config.getClientSecret())
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(new Gson().toJson(body))).build();
        CompletableFuture<HttpResponse<byte[]>> operation = client.sendAsync(request, ignored -> new LimitedBodySubscriber());
        try {
            // Bound the entire exchange, including a server that sends headers then stalls.
            HttpResponse<byte[]> response = operation.get(config.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (response.statusCode() == 401 || response.statusCode() == 403 || response.statusCode() == 409) {
                throw new ForumVerificationRefusedException();
            }
            if (response.statusCode() != 200 || !response.headers().firstValue("Content-Type")
                    .orElse("").split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
                throw new IOException("Forum identity verification was refused");
            }
            return new String(response.body(), StandardCharsets.UTF_8);
        } catch (InterruptedException interrupted) {
            operation.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("Forum identity verification interrupted");
        } catch (ExecutionException | TimeoutException failure) {
            operation.cancel(true);
            throw new IOException("Forum identity verification unavailable");
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Invalid forum identity response");
        }
    }

    static ForumIdentity parseCheck(String json, String issuer, ForumIdentity original) throws IOException {
        try {
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            // Rechecks never extend the original authentication or session expiry.
            object.addProperty("auth_time", original.authTime());
            object.addProperty("auth_method", "forum");
            object.addProperty("expires_in", original.expiresIn());
            return parse(object.toString(), issuer);
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid forum identity response");
        }
    }

    static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private int bytes;
        private boolean ended;

        @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }
        @Override public void onNext(List<ByteBuffer> item) {
            if (ended) return;
            for (ByteBuffer buffer : item) {
                if (buffer.remaining() > 8192 - bytes) {
                    ended = true;
                    subscription.cancel();
                    delegate.onError(new IOException("Forum identity response exceeds limit"));
                    return;
                }
                bytes += buffer.remaining();
            }
            delegate.onNext(item);
        }
        @Override public void onError(Throwable error) {
            if (!ended) { ended = true; delegate.onError(error); }
        }
        @Override public void onComplete() {
            if (!ended) { ended = true; delegate.onComplete(); }
        }
    }

    static ForumIdentity parse(String json, String issuer) throws IOException {
        try {
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            String actualIssuer = string(object, "issuer");
            String subject = string(object, "subject");
            String uuid = string(object, "minecraft_uuid");
            String revision = string(object, "link_revision");
            long authTime = number(object, "auth_time");
            long expires = number(object, "expires_in");
            long recheck = number(object, "check_after");
            if (!issuer.equals(actualIssuer) || !subject.matches("[1-9][0-9]{0,18}")
                    || !uuid.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
                    || !revision.matches("[A-Za-z0-9_-]{1,128}")
                    || !"forum".equals(string(object, "auth_method"))
                    || authTime <= 0 || expires < 1 || expires > 900 || recheck < 1 || recheck > 60) {
                throw new IllegalArgumentException();
            }
            return new ForumIdentity(issuer, subject, UUID.fromString(uuid), revision, authTime, (int) expires, (int) recheck);
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid forum identity response");
        }
    }

    private static String string(JsonObject object, String field) {
        if (!object.get(field).isJsonPrimitive() || !object.getAsJsonPrimitive(field).isString()) throw new IllegalArgumentException();
        return object.get(field).getAsString();
    }

    private static long number(JsonObject object, String field) {
        if (!object.get(field).isJsonPrimitive() || !object.getAsJsonPrimitive(field).isNumber()
                || !object.get(field).getAsString().matches("[0-9]{1,15}")) throw new IllegalArgumentException();
        return object.get(field).getAsLong();
    }
}
