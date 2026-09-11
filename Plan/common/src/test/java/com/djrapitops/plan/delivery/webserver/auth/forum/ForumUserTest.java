/*
 *  This file is part of Player Analytics (Plan).
 */
package com.djrapitops.plan.delivery.webserver.auth.forum;

import com.djrapitops.plan.delivery.web.resolver.request.WebUser;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ForumUserTest {

    private final ForumIdentity identity = new ForumIdentity("https://forums.example.test", "42", UUID.randomUUID(), "r1", 100, 900, 60);

    @Test
    void selfOnlyForumUserRetainsExternalProvenanceWithoutPassword() {
        ForumUser forum = new ForumUser("gerber11", "RenamableName", identity);
        WebUser user = forum.toWebUser();
        assertEquals("forum", user.getAuthenticationProvider().orElseThrow());
        assertEquals(ForumAuthService.hash(identity.issuer()) + ":42", user.getAuthenticationSubject().orElseThrow());
        assertTrue(user.hasPermission("access.player.self"));
        assertFalse(user.hasPermission("access.player"));
        assertFalse(user.hasPermission("manage.users"));
        assertFalse(forum.doesPasswordMatch(""));
    }

}
