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
package com.djrapitops.plan.settings.forumauth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ForumAuthConfigTest {

    @TempDir
    Path directory;

    private Path file() { return directory.resolve("forum-auth.yml"); }

    private String template() throws IOException {
        try (InputStream resource = getClass().getResourceAsStream("/assets/plan/forum-auth.yml")) {
            assertNotNull(resource);
            return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private List<Path> backups() throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().contains(".bak-")).toList();
        }
    }

    private void assertOwnerOnly(Path path) throws IOException {
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class) != null) {
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(path));
        } else {
            AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
            assertNotNull(acl);
            assertEquals(1, acl.getAcl().size());
            assertEquals(Files.getOwner(path), acl.getAcl().get(0).principal());
            assertEquals(AclEntryType.ALLOW, acl.getAcl().get(0).type());
        }
    }

    @Test
    void freshFileUsesExactBundledDisabledDefaultsAndPrivatePermissions() throws IOException {
        ForumAuthConfig config = ForumAuthConfig.load(file());
        assertFalse(config.isEnabled());
        assertEquals("", config.getClientSecret());
        assertEquals("plan", config.getClientId());
        assertEquals("https://forums.patriam.cc", config.getForumUrl());
        assertEquals("https://plan.patriam.cc/auth/forum/callback", config.getCallbackUrl());
        assertEquals("https://forums.patriam.cc/plan-auth/authorize", config.getAuthorizeUrl().toString());
        assertEquals("https://forums.patriam.cc/plan-auth/token", config.getTokenUrl().toString());
        assertEquals("https://forums.patriam.cc/plan-auth/check", config.getCheckUrl().toString());
        assertEquals(900, config.getSessionSeconds());
        assertEquals(60, config.getRecheckSeconds());
        assertEquals(5, config.getTimeoutSeconds());
        assertEquals(1, config.getInstalledVersion());
        assertEquals("created", config.getState());
        assertEquals(template(), Files.readString(file()));
        assertTrue(backups().isEmpty());
        assertOwnerOnly(file());
    }

    @Test
    void currentFileDoesNotRewriteAdministratorFormatting() throws IOException {
        String current = "# Administrator comment\r\nconfig-version: 1\r\nsession-seconds: 123\r\n";
        Files.writeString(file(), current);
        ForumAuthConfig config = ForumAuthConfig.load(file());
        assertEquals(123, config.getSessionSeconds());
        assertEquals("current", config.getState());
        assertEquals(current, Files.readString(file()));
        assertTrue(backups().isEmpty());
    }

    @Test
    void emptyMappingIsAnExplicitLegacyConfiguration() throws IOException {
        Files.writeString(file(), "{}\n");
        assertEquals("migrated", ForumAuthConfig.load(file()).getState());
        assertEquals("{}\n", Files.readString(backups().get(0)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "config-version: 0\n"})
    void schemaZeroPreservesExplicitSecretAndUnknownValuesInCanonicalOrder(String marker) throws IOException {
        String secret = "example_secret_with_at_least_43_characters_and_base64url-only";
        String original = "# Old administrator comment\n" + marker + """
                extension:
                  nested: {chosen: false, count: 42}
                  list: [one, two, 3, false]
                  dotted.key: 'one # two: three'
                recheck-seconds: 12
                enabled: true
                client-secret: '""" + secret + "'\n";
        Files.writeString(file(), original);
        ForumAuthConfig config = ForumAuthConfig.load(file());
        assertTrue(config.isEnabled());
        assertEquals(secret, config.getClientSecret());
        assertEquals(12, config.getRecheckSeconds());
        assertEquals("migrated", config.getState());
        assertEquals(1, backups().size());
        assertArrayEquals(original.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(backups().get(0)));
        assertOwnerOnly(backups().get(0));
        assertOwnerOnly(file());

        String migrated = Files.readString(file());
        Map<String, Object> installed = new Yaml().load(migrated);
        Map<String, Object> defaults = new Yaml().load(template());
        List<String> expectedKeys = new ArrayList<>(defaults.keySet());
        expectedKeys.add("extension");
        assertEquals(expectedKeys, new ArrayList<>(installed.keySet()));
        Map<String, Object> before = new Yaml().load(original);
        assertEquals(before.get("extension"), installed.get("extension"));
        assertTrue(migrated.contains("Maximum interval between eligibility checks is 60 seconds."));
        assertFalse(migrated.contains("Old administrator comment"));
        assertEquals("current", ForumAuthConfig.load(file()).getState());
        assertEquals(migrated, Files.readString(file()));
        assertEquals(1, backups().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "config-version:", "config-version: null", "config-version: ~", "config-version: ''",
            "config-version: '1'", "config-version: \"1\"", "'config-version': 1", "config-version: -1",
            "config-version: 1.0", "config-version: false", "config-version: 01", "config-version: +1",
            "config-version: 0x1", "config-version: 1_0", "config-version: 2", "config-version: 2147483648",
            "config-version: !!int 1", "!!str config-version: 1", "config-version: &version 1",
            "config-version: 0\nconfig-version: 1", "config-version: 0\n'config-version': 1",
            "config-version: [1]", "config-version: {x: 1}", "secret: null", "secret:",
            "config-version: 0\n---\nconfig-version: 1", "config-version: 0\n---",
            "extension: [one, null]", "extension:\n  key: first\n  key: second",
            "extension: {key: first, 'key': second}", "extension: {1: integer-key}",
            "extension: {? [one, two]: invalid-key}", "extension: {<<: {one: two}}",
            "extension: &shared {a: b}\nother: *shared", "extension: &shared [*shared]",
            "extension: !!java/object:java.lang.Runtime {}", "extension: [unterminated", "[]", "", "# empty"
    })
    void invalidPhysicalYamlIsRejectedWithoutWritesOrSecretDisclosure(String yaml) throws IOException {
        String source = yaml + "\n";
        Files.writeString(file(), source);
        IOException error = assertThrows(IOException.class, () -> ForumAuthConfig.load(file()));
        assertTrue(error.getMessage().startsWith("forum-auth.yml blocked"));
        assertNull(error.getCause());
        assertEquals(source, Files.readString(file()));
        assertTrue(backups().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "enabled: true", "enabled: yes", "enabled: 'false'", "session-seconds: 901",
            "session-seconds: 0", "session-seconds: '900'", "session-seconds: 9.5", "recheck-seconds: 61",
            "recheck-seconds: 0", "timeout-seconds: 11", "timeout-seconds: 0", "client-id: ''",
            "client-secret: \"secret\\nwith-newline\"", "forum-url: 'http://forums.patriam.cc'",
            "forum-url: 'https://user:password@forums.patriam.cc'", "forum-url: 'https://forums.patriam.cc/'",
            "forum-url: 'https://forums.patriam.cc?query=value'", "forum-url: 'https://forums.patriam.cc#fragment'",
            "forum-url: 'https://forums.patriam.cc:0'", "forum-url: 'https://forums.patriam.cc:65536'",
            "callback-url: 'https://plan.patriam.cc/different'", "callback-url: 'https://plan.patriam.cc/auth/forum/callback?x=1'",
            "callback-url: 'https://plan.patriam.cc/auth/../auth/forum/callback'"
    })
    void invalidSettingsAreRejectedBeforeLegacyMigrationWrites(String settings) throws IOException {
        Files.writeString(file(), settings + "\n");
        byte[] source = Files.readAllBytes(file());
        assertThrows(IOException.class, () -> ForumAuthConfig.load(file()));
        assertArrayEquals(source, Files.readAllBytes(file()));
        assertTrue(backups().isEmpty());
    }

    @Test
    void malformedUtf8IsRejectedWithoutNormalization() throws IOException {
        byte[] invalid = {(byte) 0xC3, (byte) 0x28};
        Files.write(file(), invalid);
        assertThrows(IOException.class, () -> ForumAuthConfig.load(file()));
        assertArrayEquals(invalid, Files.readAllBytes(file()));
        assertTrue(backups().isEmpty());
    }

    @Test
    void atomicWriteFailureKeepsOriginalAndExactBackup() throws IOException {
        byte[] original = "enabled: false\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file(), original);
        assertThrows(IOException.class, () -> ForumAuthConfigLoader.load(file(), (target, candidate, expected) -> {
            throw new AtomicMoveNotSupportedException("", "", "synthetic failure");
        }));
        assertArrayEquals(original, Files.readAllBytes(file()));
        assertArrayEquals(original, Files.readAllBytes(backups().get(0)));
    }

    @Test
    void unsupportedAtomicFreshInstallationIsSanitizedAndLeavesNoFile() {
        IOException failure = assertThrows(IOException.class,
                () -> ForumAuthConfigLoader.load(file(), (target, candidate, expected) -> {
                    throw new UnsupportedOperationException("synthetic filesystem detail");
                }));
        assertFalse(failure.getMessage().contains("synthetic filesystem detail"));
        assertFalse(Files.exists(file()));
        assertNull(failure.getCause());
    }

    @Test
    void concurrentAdministratorEditIsNotOverwritten() throws IOException {
        Files.writeString(file(), "enabled: false\n");
        String edit = "enabled: false\nsession-seconds: 123\n";
        assertThrows(IOException.class, () -> ForumAuthConfigLoader.load(file(), (target, candidate, expected) -> {
            Files.writeString(target, edit);
            ForumAuthFiles.replace(target, candidate, expected);
        }));
        assertEquals(edit, Files.readString(file()));
        try (Stream<Path> files = Files.list(directory)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().contains(".tmp-")));
        }
    }

    @Test
    void concurrentFreshCreationIsNotOverwritten() throws IOException {
        String edit = "config-version: 1\n";
        assertThrows(IOException.class, () -> ForumAuthConfigLoader.load(file(), (target, candidate, expected) -> {
            Files.writeString(target, edit);
            ForumAuthFiles.replace(target, candidate, expected);
        }));
        assertEquals(edit, Files.readString(file()));
    }

    @Test
    void postReplacementEditCannotBeActivated() throws IOException {
        Files.writeString(file(), "enabled: false\n");
        assertThrows(IOException.class, () -> ForumAuthConfigLoader.load(file(), (target, candidate, expected) -> {
            ForumAuthFiles.replace(target, candidate, expected);
            Files.writeString(target, "config-version: 1\nsession-seconds: 123\n");
        }));
        assertEquals("config-version: 1\nsession-seconds: 123\n", Files.readString(file()));
    }

    @Test
    void failureRetainsLastKnownGoodSnapshotWhenCallerReloads() throws IOException {
        Files.writeString(file(), "config-version: 1\nsession-seconds: 123\n");
        AtomicReference<ForumAuthConfig> active = new AtomicReference<>(ForumAuthConfig.load(file()));
        ForumAuthConfig previous = active.get();
        Files.writeString(file(), "config-version: 999\n");
        assertThrows(IOException.class, () -> active.set(ForumAuthConfig.load(file())));
        assertSame(previous, active.get());
        assertEquals(123, active.get().getSessionSeconds());
    }

    @ParameterizedTest
    @ValueSource(ints = {43, 128})
    void enabledSecretAcceptsTheBrokerProtocolBoundaries(int length) throws IOException {
        String secret = "s".repeat(length);
        Files.writeString(file(), "enabled: true\nclient-secret: '" + secret + "'\n");
        ForumAuthConfig config = ForumAuthConfig.load(file());
        assertTrue(config.isEnabled());
        assertEquals(secret, config.getClientSecret());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 32, 42, 129})
    void configuredSecretOutsideBrokerProtocolBoundsIsRejectedWithoutWrites(int length) throws IOException {
        String source = "client-secret: '" + "s".repeat(length) + "'\n";
        Files.writeString(file(), source);
        assertThrows(IOException.class, () -> ForumAuthConfig.load(file()));
        assertEquals(source, Files.readString(file()));
        assertTrue(backups().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"plan.client", "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklm",
            "plan/client", "client:plan"})
    void clientIdentifiersUnsupportedByTheBrokerAreRejected(String identifier) throws IOException {
        String source = "client-id: '" + identifier + "'\n";
        Files.writeString(file(), source);
        assertThrows(IOException.class, () -> ForumAuthConfig.load(file()));
        assertEquals(source, Files.readString(file()));
        assertTrue(backups().isEmpty());
    }

    @Test
    void nonBase64urlSecretIsRejectedDespiteHavingSufficientLength() throws IOException {
        Files.writeString(file(), "client-secret: '" + "s".repeat(43) + "#'\n");
        assertThrows(IOException.class, () -> ForumAuthConfig.load(file()));
        assertTrue(backups().isEmpty());
    }

}
