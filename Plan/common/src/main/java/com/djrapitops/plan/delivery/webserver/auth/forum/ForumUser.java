/*
 *  This file is part of Player Analytics (Plan).
 */
package com.djrapitops.plan.delivery.webserver.auth.forum;

import com.djrapitops.plan.delivery.domain.auth.User;
import com.djrapitops.plan.delivery.web.resolver.request.WebUser;

/** Preserves authenticated provenance without creating a local password account. */
public final class ForumUser extends User {

    private final String preferenceSubject;

    public ForumUser(String username, String playerName, ForumIdentity identity) {
        super(username, playerName, identity.minecraftUUID(), "", "forum-self", ForumAuthService.SELF_PERMISSIONS);
        preferenceSubject = ForumAuthService.hash(identity.issuer()) + ':' + identity.subject();
    }

    @Override
    public WebUser toWebUser() {
        return new WebUser(getLinkedTo(), getLinkedToUUID(), getUsername(), getPermissions(), "forum", preferenceSubject);
    }

    @Override
    public boolean doesPasswordMatch(String password) {
        return false;
    }
}
