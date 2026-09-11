package com.djrapitops.plan.delivery.webserver.auth.forum;

import java.util.UUID;

/** Verified identity returned only by the configured forum's authenticated backchannel. */
public record ForumIdentity(String issuer, String subject, UUID minecraftUUID, String revision,
                            long authTime, int expiresIn, int checkAfter) {
    public boolean sameAccount(ForumIdentity other) {
        return issuer.equals(other.issuer) && subject.equals(other.subject)
                && minecraftUUID.equals(other.minecraftUUID) && revision.equals(other.revision);
    }
}
