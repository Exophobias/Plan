package com.djrapitops.plan.delivery.webserver.auth.forum;

import java.io.IOException;

public interface ForumBroker {
    ForumIdentity redeem(String code, String verifier, String state) throws IOException;
    ForumIdentity check(ForumIdentity identity) throws IOException;
}
