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
package com.djrapitops.plan.storage.database.queries;

import com.djrapitops.plan.delivery.domain.DateObj;
import com.djrapitops.plan.delivery.domain.datatransfer.GenericFilter;
import com.djrapitops.plan.delivery.rendering.json.datapoint.types.performance.MSPTMax95th;
import com.djrapitops.plan.delivery.rendering.json.datapoint.types.performance.MSPTMax95thWithLowTPS;
import com.djrapitops.plan.delivery.web.resolver.request.URIQuery;
import com.djrapitops.plan.settings.config.paths.DisplaySettings;
import com.djrapitops.plan.delivery.domain.mutators.TPSMutator;
import com.djrapitops.plan.gathering.domain.TPS;
import com.djrapitops.plan.gathering.domain.builders.TPSBuilder;
import com.djrapitops.plan.identification.ServerUUID;
import com.djrapitops.plan.storage.database.DatabaseTestPreparer;
import com.djrapitops.plan.storage.database.queries.objects.TPSQueries;
import com.djrapitops.plan.storage.database.transactions.commands.RemoveEverythingTransaction;
import com.djrapitops.plan.storage.database.transactions.events.TPSStoreTransaction;
import com.djrapitops.plan.utilities.comparators.TPSComparator;
import com.djrapitops.plan.utilities.java.Lists;
import net.playeranalytics.plugin.server.PluginLogger;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import utilities.RandomData;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public interface TPSQueriesTest extends DatabaseTestPreparer {

    @Test
    default void generalMsptMaximumIncludesNormalTpsSamples() {
        config().set(DisplaySettings.GRAPH_TPS_THRESHOLD_MED, 10.0);
        TPS normal = new TPS(1000, 20, 2, 25, 1000, 40, 20, 8000);
        normal.setMspt95thPercentile(80.0);
        TPS low = new TPS(2000, 5, 2, 25, 1000, 40, 20, 8000);
        low.setMspt95thPercentile(40.0);
        execute(DataStoreQueries.storeTPS(serverUUID(), normal));
        execute(DataStoreQueries.storeTPS(serverUUID(), low));
        forcePersistenceCheck();

        MSPTMax95th general = new MSPTMax95th(dbSystem());
        MSPTMax95thWithLowTPS onlyLow = new MSPTMax95thWithLowTPS(config(), dbSystem());
        GenericFilter both = new GenericFilter(new URIQuery("server=" + serverUUID() + "&after=0&before=2500"));
        assertEquals(Optional.of(80.0), general.getValue(both));
        assertEquals(Optional.of(40.0), onlyLow.getValue(both));

        GenericFilter normalOnly = new GenericFilter(new URIQuery("server=" + serverUUID() + "&after=0&before=1500"));
        assertEquals(Optional.of(80.0), general.getValue(normalOnly));
        assertTrue(onlyLow.getValue(normalOnly).isEmpty());
        GenericFilter empty = new GenericFilter(new URIQuery("server=" + serverUUID() + "&after=3000&before=4000"));
        assertTrue(general.getValue(empty).isEmpty());
    }

    @Test
    default void tpsIsStored() {
        List<TPS> expected = RandomData.randomTPS();
        for (TPS tps : expected) {
            execute(DataStoreQueries.storeTPS(serverUUID(), tps));
        }

        forcePersistenceCheck();

        expected.sort(new TPSComparator());
        assertEquals(expected, db().query(TPSQueries.fetchTPSDataOfServer(Long.MIN_VALUE, Long.MAX_VALUE, serverUUID())));
    }


    @Test
    default void tpsFetchedInResolution() {
        execute(LargeStoreQueries.storeAllTPSData(Map.of(serverUUID(), RandomData.randomTPS())));

        assertFalse(db().query(TPSQueries.fetchTPSDataOfServerInResolution(Long.MIN_VALUE, Long.MAX_VALUE, TimeUnit.MINUTES.toMillis(5), serverUUID()))
                .isEmpty());
    }

    @Test
    default void previewGraphData() {
        List<TPS> tps = RandomData.randomTPS();
        execute(LargeStoreQueries.storeAllTPSData(Map.of(serverUUID(), tps)));

        tps.sort(new TPSComparator());
        var expected = tps.stream().map(t -> new DateObj<>(t.getDate(), t.getPlayers())).toList();
        var result = db().query(TPSQueries.fetchViewPreviewGraphData(serverUUID()));
        assertEquals(expected, result);
    }

    @Test
    default void playersOnlineOfServer() {
        List<TPS> tps = RandomData.randomTPS();
        execute(LargeStoreQueries.storeAllTPSData(Map.of(serverUUID(), tps)));

        tps.sort(new TPSComparator());
        var expected = tps.stream().map(t -> new DateObj<>(t.getDate(), t.getPlayers())).toList();
        var result = db().query(TPSQueries.fetchPlayersOnlineOfServer(Long.MIN_VALUE, Long.MAX_VALUE, serverUUID()));
        assertEquals(expected, result);
    }

    @Test
    default void latestTpsOfServer() {
        List<TPS> tps = RandomData.randomTPS();
        execute(LargeStoreQueries.storeAllTPSData(Map.of(serverUUID(), tps)));

        tps.sort(new TPSComparator());
        var expected = tps.getLast();
        var result = db().query(TPSQueries.fetchLatestTPSEntryForServer(serverUUID()))
                .orElseThrow();
        assertEquals(expected, result);

        var expectedDate = expected.getDate();
        var resultDate = db().query(TPSQueries.fetchLastStoredTpsDate(serverUUID()))
                .orElseThrow();
        assertEquals(expectedDate, resultDate);
    }

    @Test
    default void tpsAveragesOfServer() {
        List<TPS> tps = RandomData.randomTPS();
        execute(LargeStoreQueries.storeAllTPSData(Map.of(serverUUID(), tps)));

        tps.sort(new TPSComparator());
        TPSMutator mutator = new TPSMutator(tps);

        assertEquals(mutator.averageTPS(), db().query(TPSQueries.averageTPS(Long.MIN_VALUE, Long.MAX_VALUE, serverUUID())), 0.01);
        assertEquals(mutator.averageCPU(), db().query(TPSQueries.averageCPU(Long.MIN_VALUE, Long.MAX_VALUE, serverUUID())), 0.01);
        assertEquals((Long) (long) mutator.averageRAM(), db().query(TPSQueries.averageRAM(Long.MIN_VALUE, Long.MAX_VALUE, serverUUID())));
        assertEquals((Long) (long) mutator.averageChunks(), db().query(TPSQueries.averageChunks(Long.MIN_VALUE, Long.MAX_VALUE, serverUUID())));
        assertEquals((Long) (long) mutator.averageEntities(), db().query(TPSQueries.averageEntities(Long.MIN_VALUE, Long.MAX_VALUE, serverUUID())));
        assertEquals(mutator.maxFreeDisk(), db().query(TPSQueries.maxFreeDisk(Long.MIN_VALUE, Long.MAX_VALUE, serverUUID())), 0.01);
        assertEquals(mutator.minFreeDisk(), db().query(TPSQueries.minFreeDisk(Long.MIN_VALUE, Long.MAX_VALUE, serverUUID())), 0.01);
        assertEquals((Long) (long) mutator.averageFreeDisk(), db().query(TPSQueries.averageFreeDisk(Long.MIN_VALUE, Long.MAX_VALUE, serverUUID())));
    }

    @RepeatedTest(5)
    default void occupiedCalculationMatches() {
        List<TPS> data = RandomData.randomDateOrderedTPS();
        for (TPS tps : data) {
            execute(DataStoreQueries.storeTPS(serverUUID(), tps));
        }

        data.sort(new TPSComparator());
        Long expected = new TPSMutator(data).serverOccupiedTime();
        Long result = db().query(TPSQueries.occupiedTime(Long.MIN_VALUE, Long.MAX_VALUE, List.of(serverUUID())));
        assertEquals(expected, result, () -> "Mismatch (" + expected + ", " + result + ") with data " + data);
    }

    @RepeatedTest(5)
    default void uptimeCalculationMatches() {
        List<TPS> data = RandomData.randomDateOrderedTPS();
        for (TPS tps : data) {
            execute(DataStoreQueries.storeTPS(serverUUID(), tps));
        }

        data.sort(new TPSComparator());
        Long expected = new TPSMutator(data).serverUptime();
        Long result = db().query(TPSQueries.uptime(Long.MIN_VALUE, Long.MAX_VALUE, List.of(serverUUID())));
        assertEquals(expected, result, () -> "Mismatch (" + expected + ", " + result + ") with data " + data);
    }

    @RepeatedTest(5)
    default void downtimeCalculationMatches() {
        List<TPS> data = RandomData.randomDateOrderedTPS();
        for (TPS tps : data) {
            execute(DataStoreQueries.storeTPS(serverUUID(), tps));
        }

        data.sort(new TPSComparator());
        Long expected = new TPSMutator(data).serverDownTime();
        Long result = db().query(TPSQueries.downtime(Long.MIN_VALUE, Long.MAX_VALUE, List.of(serverUUID())));
        assertEquals(expected, result, () -> "Mismatch (" + expected + ", " + result + ") with data " + data);
    }

    @RepeatedTest(5)
    default void lowTpsSpikeCalculationMatches() {
        List<TPS> data = RandomData.randomDateOrderedTPS();
        for (TPS tps : data) {
            execute(DataStoreQueries.storeTPS(serverUUID(), tps));
        }

        data.sort(new TPSComparator());
        double threshold = RandomData.randomInt(3, 18);
        Integer expected = new TPSMutator(data).lowTpsSpikeCount(threshold);
        Integer result = db().query(TPSQueries.lowTpsSpikes(threshold, Long.MIN_VALUE, Long.MAX_VALUE, List.of(serverUUID())));
        assertEquals(expected, result, () -> "Mismatch (" + expected + ", " + result + ") with data " + data);
    }

    @Test
    default void removeEverythingRemovesTPS() {
        tpsIsStored();
        db().executeTransaction(new RemoveEverythingTransaction());
        assertTrue(db().query(TPSQueries.fetchTPSDataOfAllServersBut(0, System.currentTimeMillis(), ServerUUID.randomUUID())).isEmpty());
    }

    @Test
    default void playerMaxPeakIsCorrect() {
        List<TPS> tpsData = RandomData.randomTPS();

        for (TPS tps : tpsData) {
            db().executeTransaction(new TPSStoreTransaction(serverUUID(), tps));
        }

        tpsData.sort(Comparator.comparingInt(TPS::getPlayers));
        int expected = tpsData.getLast().getPlayers();
        int actual = db().query(TPSQueries.fetchPeakPlayerCount(serverUUID(), 0, Long.MAX_VALUE)).map(DateObj::getValue).orElse(-1);
        assertEquals(expected, actual, () -> "Wrong return value. " + Lists.map(tpsData, TPS::getPlayers).toString());
    }

    @Test
    default void maxDateIsFetched() {
        List<TPS> tpsData = RandomData.randomTPS();

        for (TPS tps : tpsData) {
            db().executeTransaction(new TPSStoreTransaction(serverUUID(), tps));
        }

        long expected = tpsData.stream()
                .mapToLong(TPS::getDate)
                .max()
                .orElseThrow(AssertionError::new);
        long result = db().query(TPSQueries.fetchLastStoredTpsDate(serverUUID()))
                .orElseThrow(AssertionError::new);
        assertEquals(expected, result);
    }

    @Test
    default void sameServerIsDetected() {
        int value = ThreadLocalRandom.current().nextInt();
        long time = System.currentTimeMillis() - 50;
        TPS tps = new TPS(time, time, value, time, time, value, value, time);
        PluginLogger logger = Mockito.mock(PluginLogger.class);
        db().executeTransaction(new TPSStoreTransaction(logger, serverUUID(), tps));

        TPSStoreTransaction.setLastStorageCheck(0L);

        db().executeTransaction(new TPSStoreTransaction(logger, serverUUID(), tps));
        db().executeTransaction(new TPSStoreTransaction(logger, serverUUID(), tps));

        verify(logger, times(1)).warn(anyString());
    }

    @Test
    default void serverStartDateIsFetched() {
        List<TPS> tpsData = RandomData.randomTPS();
        TPS stored = tpsData.get(0);
        TPS stored2 = TPSBuilder.get().date(stored.getDate() + TimeUnit.MINUTES.toMillis(1L)).toTPS();
        db().executeTransaction(new TPSStoreTransaction(serverUUID(), stored));
        db().executeTransaction(new TPSStoreTransaction(serverUUID(), stored2));

        Optional<Long> result = db().query(TPSQueries.fetchLatestServerStartTime(serverUUID(), TimeUnit.MINUTES.toMillis(3)));
        assertTrue(result.isPresent());
        assertEquals(stored.getDate(), result.get());
    }

    @Test
    default void serverStartDateIsCorrect() {
        List<TPS> tpsData = RandomData.randomTPS();
        TPS stored = tpsData.get(0);
        TPS stored2 = TPSBuilder.get().date(stored.getDate() + TimeUnit.MINUTES.toMillis(4L)).toTPS();
        TPS stored3 = TPSBuilder.get().date(stored.getDate() + TimeUnit.MINUTES.toMillis(5L)).toTPS();
        db().executeTransaction(new TPSStoreTransaction(serverUUID(), stored));
        db().executeTransaction(new TPSStoreTransaction(serverUUID(), stored2));
        db().executeTransaction(new TPSStoreTransaction(serverUUID(), stored3));

        Optional<Long> result = db().query(TPSQueries.fetchLatestServerStartTime(serverUUID(), TimeUnit.MINUTES.toMillis(3)));
        assertTrue(result.isPresent());
        assertEquals(stored2.getDate(), result.get());
    }
}
