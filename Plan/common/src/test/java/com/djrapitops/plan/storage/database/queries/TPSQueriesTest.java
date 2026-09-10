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
import com.djrapitops.plan.delivery.rendering.json.datapoint.types.performance.MSPTAverageWithLowTPS;
import com.djrapitops.plan.delivery.rendering.json.datapoint.types.performance.CPUImpactPerPlayer;
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
    default void lowTpsMsptAverageDistinguishesMissingFromMeasuredZero() {
        config().set(DisplaySettings.GRAPH_TPS_THRESHOLD_MED, 10.0);
        MSPTAverageWithLowTPS metric = new MSPTAverageWithLowTPS(config(), dbSystem());
        GenericFilter window = new GenericFilter(new URIQuery("server=" + serverUUID() + "&after=0&before=5000"));
        assertEquals(-1.0, db().query(TPSQueries.averageMSPTWhenLowTps(0, 5000, List.of(serverUUID()), 10.0)));
        assertTrue(metric.getValue(window).isEmpty(), "An empty aggregate is unavailable");

        TPS normal = new TPS(1000, 15, 2, 25, 1000, 40, 20, 8000);
        normal.setMsptAverage(66.0);
        TPS threshold = new TPS(2000, 10, 2, 25, 1000, 40, 20, 8000);
        threshold.setMsptAverage(80.0);
        TPS unknown = new TPS(3000, 5, 2, 25, 1000, 40, 20, 8000);
        for (TPS sample : List.of(normal, threshold, unknown)) execute(DataStoreQueries.storeTPS(serverUUID(), sample));
        forcePersistenceCheck();
        assertTrue(metric.getValue(window).isEmpty(), "Normal/exact-threshold TPS and absent MSPT are not low-TPS measurements");

        TPS zero = new TPS(4000, 5, 2, 25, 1000, 40, 20, 8000);
        zero.setMsptAverage(0.0);
        execute(DataStoreQueries.storeTPS(serverUUID(), zero));
        forcePersistenceCheck();
        assertEquals(Optional.of(0.0), metric.getValue(window), "A measured zero is available");

        TPS measured = new TPS(4500, 5, 2, 25, 1000, 40, 20, 8000);
        measured.setMsptAverage(40.0);
        execute(DataStoreQueries.storeTPS(serverUUID(), measured));
        forcePersistenceCheck();
        assertEquals(Optional.of(20.0), metric.getValue(window), "Only valid low-TPS measurements contribute to the mean");
        assertEquals(Optional.of(20.0), metric.getValue(new GenericFilter(new URIQuery("after=0&before=5000"))));
        assertTrue(metric.getValue(new GenericFilter(new URIQuery("server=" + ServerUUID.randomUUID() + "&after=0&before=5000"))).isEmpty());
        assertTrue(metric.getValue(new GenericFilter(new URIQuery("server=" + serverUUID() + "&after=5000&before=6000"))).isEmpty());
    }

    @Test
    default void averageChunksPerPlayerPreservesFractionalSamples() {
        execute(DataStoreQueries.storeTPS(serverUUID(), new TPS(1000, 20, 2, 20, 1000, 40, 3, 8000)));
        execute(DataStoreQueries.storeTPS(serverUUID(), new TPS(2000, 20, 2, 20, 1000, 40, 5, 8000)));
        // Idle samples cannot contribute a ratio or cause division by zero.
        execute(DataStoreQueries.storeTPS(serverUUID(), new TPS(3000, 20, 0, 10, 1000, 40, 100, 8000)));
        forcePersistenceCheck();
        assertEquals(2L, db().query(TPSQueries.averageChunksPerPlayer(0, 4000, List.of(serverUUID()))));
        assertEquals(1L, db().query(TPSQueries.averageChunksPerPlayer(0, 1500, List.of(serverUUID()))));
        assertEquals(-1L, db().query(TPSQueries.averageChunksPerPlayer(2500, 4000, List.of(serverUUID()))));
        assertEquals(-1L, db().query(TPSQueries.averageChunksPerPlayer(0, 4000, List.of(ServerUUID.randomUUID()))));
    }

    @Test
    default void cpuImpactRequiresAnObservedIdleBaseline() {
        execute(DataStoreQueries.storeTPS(serverUUID(), new TPS(1000, 20, 2, 20, 1000, 40, 3, 8000)));
        CPUImpactPerPlayer metric = new CPUImpactPerPlayer(dbSystem());
        GenericFilter both = new GenericFilter(new URIQuery("server=" + serverUUID() + "&after=0&before=2500"));
        assertTrue(metric.getValue(both).isEmpty(), "Active samples alone cannot establish idle CPU usage");

        execute(DataStoreQueries.storeTPS(serverUUID(), new TPS(2000, 20, 0, 10, 1000, 40, 3, 8000)));
        execute(DataStoreQueries.storeTPS(serverUUID(), new TPS(2200, 20, 0, -1, 1000, 40, 3, 8000)));
        forcePersistenceCheck();
        assertEquals(0.05, metric.getValue(both).orElseThrow(), 0.000001);
        assertTrue(metric.getValue(new GenericFilter(new URIQuery("server=" + serverUUID() + "&after=0&before=1500"))).isEmpty(),
                "An idle sample outside the requested window is not a baseline");

        execute(DataStoreQueries.storeTPS(serverUUID(), new TPS(3000, 20, 0, 0, 1000, 40, 3, 8000)));
        execute(DataStoreQueries.storeTPS(serverUUID(), new TPS(4000, 20, 2, 20, 1000, 40, 3, 8000)));
        GenericFilter zeroBaseline = new GenericFilter(new URIQuery("server=" + serverUUID() + "&after=2500&before=4500"));
        assertEquals(0.1, metric.getValue(zeroBaseline).orElseThrow(), 0.000001, "Measured zero CPU remains a valid baseline");
        assertTrue(metric.getValue(new GenericFilter(new URIQuery("server=" + serverUUID() + "&after=1500&before=2500"))).isEmpty(),
                "An idle baseline alone cannot establish player impact");
    }

    @Test
    default void resolutionBucketsPreserveSlowMsptSamples() {
        TPS fast = new TPS(1000, 20, 2, 10, 1000, 40, 20, 8000);
        fast.setMsptAverage(5.0);
        fast.setMsptJitterAverage(1.0);
        TPS slow = new TPS(2000, 12, 2, 80, 1000, 40, 20, 8000);
        slow.setMsptAverage(80.0);
        slow.setMsptJitterAverage(3.0);
        TPS nextBucket = new TPS(20000, 20, 2, 15, 1000, 40, 20, 8000);
        nextBucket.setMsptAverage(12.0);
        for (TPS sample : List.of(fast, slow, new TPS(3000, 18, 2, 20, 1000, 40, 20, 8000), nextBucket)) {
            execute(DataStoreQueries.storeTPS(serverUUID(), sample));
        }
        forcePersistenceCheck();
        List<TPS> buckets = db().query(TPSQueries.fetchTPSDataOfServerInResolution(0, 30000, 10000, serverUUID()));
        assertEquals(2, buckets.size());
        assertEquals(80.0, buckets.getFirst().getMsptAverage());
        assertEquals(12.0, buckets.getFirst().getTicksPerSecond());
        assertEquals(80.0, buckets.getFirst().getCPUUsage());
        assertEquals(2.0, buckets.getFirst().getMsptJitterAverage(), "Jitter's average aggregation remains unchanged");
        assertEquals(12.0, buckets.getLast().getMsptAverage());
        assertEquals(20000, buckets.getLast().getDate());
    }

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
