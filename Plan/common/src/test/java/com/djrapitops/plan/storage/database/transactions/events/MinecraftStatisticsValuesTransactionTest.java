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
package com.djrapitops.plan.storage.database.transactions.events;

import com.djrapitops.plan.gathering.domain.MinecraftStatistics;
import com.djrapitops.plan.storage.database.DatabaseTestPreparer;
import com.djrapitops.plan.storage.database.queries.objects.StatisticsQueries;
import org.junit.jupiter.api.Test;
import utilities.RandomData;
import utilities.TestConstants;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

public interface MinecraftStatisticsValuesTransactionTest extends DatabaseTestPreparer {

    @Test
    default void storesCurrentSnapshotWhenAPreviouslyStoredStatisticIsAbsent() {
        String updated = "minecraft.custom:minecraft.time_played";
        String omitted = "minecraft.custom:minecraft.jump";
        String inserted = "minecraft.custom:minecraft.walk_one_cm";

        executeTransactions(
                new StoreServerPlayerTransaction(playerUUID, RandomData::randomTime,
                        TestConstants.PLAYER_ONE_NAME, serverUUID(), TestConstants.GET_PLAYER_HOSTNAME),
                new StoreMinecraftStatisticsTransaction(Set.of(updated, omitted, inserted))
        );
        Map<String, Integer> ids = db().query(StatisticsQueries.fetchStatisticNameToId());

        executeTransactions(new StoreMinecraftStatisticsValuesTransaction(new MinecraftStatistics(
                playerUUID, serverUUID(), Map.of(ids.get(updated), 5, ids.get(omitted), 7)
        )));

        MinecraftStatistics current = new MinecraftStatistics(playerUUID, serverUUID(),
                Map.of(ids.get(updated), 9, ids.get(inserted), 3));
        executeTransactions(new StoreMinecraftStatisticsValuesTransaction(current));
        assertEquals(Map.of(updated, 9, omitted, 0, inserted, 3),
                db().query(StatisticsQueries.fetchStatistics(playerUUID)).get(0).getStatistics());

        executeTransactions(new StoreMinecraftStatisticsValuesTransaction(current));
        assertEquals(Map.of(updated, 9, omitted, 0, inserted, 3),
                db().query(StatisticsQueries.fetchStatistics(playerUUID)).get(0).getStatistics());
    }
}
