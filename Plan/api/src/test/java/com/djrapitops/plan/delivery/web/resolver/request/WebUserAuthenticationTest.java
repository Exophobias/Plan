/*
 *  This file is part of Player Analytics (Plan).
 */
package com.djrapitops.plan.delivery.web.resolver.request;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WebUserAuthenticationTest {

    @Test
    void existingConstructorsRetainLocalAuthenticationRegardlessOfName() {
        WebUser local = new WebUser("name", UUID.randomUUID(), "forum:issuer:42", List.of("access.player.self"));
        assertTrue(local.getAuthenticationProvider().isEmpty());
        assertTrue(local.getAuthenticationSubject().isEmpty());
        assertTrue(new WebUser("forum:issuer:42").getAuthenticationProvider().isEmpty());
    }

    @Test
    void externalMetadataDoesNotAddPermissions() {
        UUID uuid = UUID.randomUUID();
        WebUser external = new WebUser("name", uuid, "username", List.of("access.player.self"), "forum", "issuer:42");
        assertEquals("forum", external.getAuthenticationProvider().orElseThrow());
        assertEquals("issuer:42", external.getAuthenticationSubject().orElseThrow());
        assertEquals(uuid, external.getUUID().orElseThrow());
        assertTrue(external.hasPermission("access.player.self"));
        assertFalse(external.hasPermission("access.player"));
        assertFalse(external.hasPermission("manage.users"));
    }

    @Test
    void externalProviderAndSubjectMustBeCompleteAndUnambiguous() {
        assertThrows(IllegalArgumentException.class, () -> new WebUser("n", null, "u", List.of(), "forum", null));
        assertThrows(IllegalArgumentException.class, () -> new WebUser("n", null, "u", List.of(), null, "42"));
        assertThrows(IllegalArgumentException.class, () -> new WebUser("n", null, "u", List.of(), "forum", "issuer\n42"));
        assertThrows(IllegalArgumentException.class, () -> new WebUser("n", null, "u", List.of(), "forum\n42", "subject"));
    }
}
