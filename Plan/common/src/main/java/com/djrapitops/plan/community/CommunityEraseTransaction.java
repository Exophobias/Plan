package com.djrapitops.plan.community;

import java.util.UUID;

/** Explicit erasure also suppresses pending snapshots; ordinary upkeep does not call this. */
public final class CommunityEraseTransaction extends CommunityTables.Tx {
    private final String player;
    public CommunityEraseTransaction(UUID player) { this.player = player.toString(); }
    @Override protected void performCommunityOperations() {
        if (!deleted(player)) sql("INSERT INTO " + CommunityTables.DELETED + " (uuid) VALUES (?)", player);
        sql("DELETE FROM " + CommunityTables.ACTIVITY + " WHERE uuid=?", player);
        sql("DELETE FROM " + CommunityTables.MEMBERS + " WHERE uuid=?", player);
    }
}
