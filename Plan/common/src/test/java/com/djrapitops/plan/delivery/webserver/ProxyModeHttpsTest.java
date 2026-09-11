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
package com.djrapitops.plan.delivery.webserver;

import com.djrapitops.plan.PlanSystem;
import com.djrapitops.plan.delivery.web.resolver.request.Request;
import com.djrapitops.plan.delivery.webserver.resolver.StaticResourceResolver;
import com.djrapitops.plan.settings.config.PlanConfig;
import com.djrapitops.plan.settings.config.paths.WebserverSettings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import utilities.HTTPConnector;
import utilities.RandomData;
import utilities.TestResources;
import utilities.mocks.PluginMockComponent;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ProxyModeHttpsTest {
    private static final int TEST_PORT_NUMBER = RandomData.randomInt(9005, 9500);

    private static PlanSystem system;

    @BeforeAll
    static void setUpClass(@TempDir Path tempDir) throws Exception {
        PluginMockComponent component = new PluginMockComponent(tempDir);
        system = component.getPlanSystem();

        PlanConfig config = system.getConfigSystem().getConfig();

        config.set(WebserverSettings.CERTIFICATE_PATH, "proxy");

        config.set(WebserverSettings.PORT, TEST_PORT_NUMBER);

        system.enable();
    }

    @AfterAll
    static void tearDownClass() {
        if (system != null) {
            system.disable();
        }
    }

    @Test
    @DisplayName("Webserver with 'proxy' keystore path assumes proxy server is handling https")
    void proxyModeAddressIsHttps() {
        assertEquals("https", system.getWebServerSystem().getWebServer().getProtocol());
    }

    @ParameterizedTest(name = "Bundled {0}.{1} is available before login")
    @CsvSource({
            "crest, webp, image/webp",
            "eot-misty-valley, webp, image/webp",
            "Flaticon_circle, png, image/gif",
            "jost-latin-ext, woff2, application/font-woff2",
            "instrument-sans-latin-ext, woff2, application/font-woff2"
    })
    void bundledStaticAssetsRemainAvailableWithoutLogin(String name, String extension, String mimeType) throws Exception {
        String target = "/static/" + findBundledAsset(name, extension);
        byte[] expected = TestResources.getJarResourceAsBytes("/assets/plan/web" + target);
        assertTrue(expected.length > 0, "Bundled asset must not be empty");

        String etag;
        HttpURLConnection connection = connect(target);
        try {
            assertEquals(200, connection.getResponseCode());
            assertEquals(mimeType, connection.getContentType());
            try (InputStream input = connection.getInputStream()) {
                assertArrayEquals(expected, input.readAllBytes());
            }
            assertEquals(CacheStrategy.CACHE_IN_BROWSER, connection.getHeaderField("Cache-Control"));
            assertNotNull(connection.getHeaderField("Last-Modified"));
            etag = connection.getHeaderField("ETag");
            assertNotNull(etag);
            assertFalse(etag.isBlank());
        } finally {
            connection.disconnect();
        }

        connection = connect(target);
        try {
            connection.setRequestProperty("If-None-Match", "stale-asset-version");
            assertEquals(200, connection.getResponseCode());
            assertEquals(mimeType, connection.getContentType());
            try (InputStream input = connection.getInputStream()) {
                assertArrayEquals(expected, input.readAllBytes());
            }
        } finally {
            connection.disconnect();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/static/missing-asset.webp", "/static/missing-asset.png"})
    void missingStaticImagesReturnNotFound(String target) throws Exception {
        HttpURLConnection connection = connect(target);
        try {
            assertEquals(404, connection.getResponseCode());
        } finally {
            connection.disconnect();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/static/image.webp.exe", "/static/image.webp.html", "/static/image.svg", "/static/archive.zip"})
    void staticResolverDoesNotAcceptUnsupportedExtensions(String target) {
        ResponseFactory responses = mock(ResponseFactory.class);
        StaticResourceResolver resolver = new StaticResourceResolver(responses);
        Request request = new Request("GET", target, null, Map.of(), "127.0.0.1");

        assertTrue(resolver.resolve(request).isEmpty());
        verifyNoInteractions(responses);
    }

    private String findBundledAsset(String name, String extension) throws Exception {
        String prefix = "assets/plan/web/static/";
        URL directory = getClass().getResource("/" + prefix);
        assertNotNull(directory, "Frontend bundle must be included on the test classpath");
        Pattern fileName = Pattern.compile(Pattern.quote(name) + "-[A-Za-z0-9_-]+\\." + Pattern.quote(extension));
        List<String> matches;
        if ("jar".equals(directory.getProtocol())) {
            JarURLConnection connection = (JarURLConnection) directory.openConnection();
            connection.setUseCaches(false);
            try (JarFile jar = connection.getJarFile()) {
                matches = jar.stream().map(entry -> entry.getName())
                        .filter(path -> path.startsWith(prefix))
                        .map(path -> path.substring(prefix.length()))
                        .filter(path -> fileName.matcher(path).matches()).toList();
            }
        } else {
            try (Stream<Path> files = Files.list(Path.of(directory.toURI()))) {
                matches = files.map(path -> path.getFileName().toString())
                        .filter(path -> fileName.matcher(path).matches()).toList();
            }
        }
        assertEquals(1, matches.size(), "Expected exactly one bundled " + name + "." + extension);
        return matches.get(0);
    }

    private HttpURLConnection connect(String target) throws Exception {
        HttpURLConnection connection = new HTTPConnector().getConnection("GET", "http://localhost:" + TEST_PORT_NUMBER + target);
        connection.setReadTimeout(10000);
        return connection;
    }
}
