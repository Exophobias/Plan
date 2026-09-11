package com.djrapitops.plan.delivery.webserver.auth.forum;

import java.io.IOException;

/** An explicit broker revocation, distinct from temporarily unavailable verification. */
public final class ForumVerificationRefusedException extends IOException {
    public ForumVerificationRefusedException() {
        super("Forum identity verification was refused");
    }
}
