package com.djrapitops.plan.referrals;

import java.util.UUID;

/** Only joins observed prospectively while collection is admitted can enter the cohort roster. */
public final class ReferralMemberTransaction extends ReferralTables.Tx {
    private final String server, player;
    private final long firstJoin;
    private final ReferralCapture.State gate;
    public ReferralMemberTransaction(UUID server, UUID player, long firstJoin) {
        this.server = server.toString(); this.player = player.toString(); this.firstJoin = firstJoin;
        this.gate = ReferralCapture.state();
    }
    @Override protected void performReferralOperations() {
        if (gate.paused() || firstJoin < gate.changedAt() || ReferralCapture.state().generation() != gate.generation()
                || deleted(player)) return;
        Long existing = one("SELECT first_join FROM " + ReferralTables.MEMBERS + " WHERE server_uuid=? AND uuid=?",
                r -> r.getLong(1), null, server, player);
        if (existing == null) sql("INSERT INTO " + ReferralTables.MEMBERS + " (server_uuid,uuid,first_join) VALUES (?,?,?)", server, player, firstJoin);
        else if (firstJoin < existing) sql("UPDATE " + ReferralTables.MEMBERS + " SET first_join=? WHERE server_uuid=? AND uuid=?", firstJoin, server, player);
    }
}
