package com.djrapitops.plan.delivery.webserver;

import com.djrapitops.plan.gathering.domain.TPS;
import org.openqa.selenium.logging.LogEntry;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Exact absent samples for JSErrorRegressionTest's single recent, active TPS row. */
final class SparsePerformanceFixture {
    private static final long DAY = TimeUnit.DAYS.toMillis(1);
    private static final String RESOURCE_404 = " - Failed to load resource: the server responded with a status of 404 (Not Found)";
    private static final Set<Map<String, String>> EMPTY_QUERIES = emptyQueries();

    private SparsePerformanceFixture() {
    }

    static TPS recentSample(long now) {
        TPS sample = new TPS(now - 5000, 15, 2, 25, 1024, 40, 20, 8000);
        sample.setMsptAverage(66.0);
        sample.setMspt95thPercentile(80.0);
        sample.setMsptJitterAverage(1.0);
        sample.setMsptJitterMax(4.0);
        return sample;
    }

    private static Set<Map<String, String>> emptyQueries() {
        Set<Map<String, String>> queries = new HashSet<>();
        // These are optional performance aggregates; the fresh database contains
        // no samples in any of these historical windows. Do not allow other types
        // or recent windows: a 404 for the populated recent sample is a failure.
        String[] historical = {
                "CHUNKS_AVERAGE:ACTIVE", "CHUNKS_PER_PLAYER", "CPU_AVERAGE", "CPU_AVERAGE:ACTIVE",
                "CPU_AVERAGE:IDLE", "CPU_IMPACT_PER_PLAYER", "DISK_MAX", "DISK_MIN",
                "ENTITIES_AVERAGE:ACTIVE", "ENTITIES_PER_CHUNK", "MSPT_AVERAGE:ACTIVE", "MSPT_AVERAGE:IDLE",
                "MSPT_AVERAGE_LOW_TPS", "MSPT_IMPACT_PER_CHUNK", "MSPT_IMPACT_PER_PLAYER",
                "MSPT_JITTER_AVERAGE:ACTIVE", "MSPT_JITTER_MAX:ACTIVE", "MSPT_MAX_95TH",
                "MSPT_MAX_95TH_LOW_TPS", "PLAYERS_ONLINE_AVERAGE", "PLAYERS_ONLINE_AVERAGE:ACTIVE",
                "RAM_AVERAGE", "TPS_AVERAGE"
        };
        for (String metric : historical) {
            for (int week = 1; week <= 3; week++) {
                Map<String, String> query = query(metric, DAY * 7 * (week + 1));
                query.put("beforeMillisAgo", Long.toString(DAY * 7 * week));
                queries.add(Map.copyOf(query));
            }
        }
        // The one sample has two players and TPS 15 above the fixture's explicit
        // threshold 10, so idle and low-TPS-only aggregates have no samples either.
        for (String metric : new String[]{"CPU_AVERAGE:IDLE", "MSPT_AVERAGE:IDLE",
                "MSPT_AVERAGE_LOW_TPS", "MSPT_MAX_95TH_LOW_TPS"}) {
            for (int days : new int[]{1, 7, 30}) {
                queries.add(Map.copyOf(query(metric, DAY * days)));
            }
        }
        return Set.copyOf(queries);
    }

    private static Map<String, String> query(String metric, long after) {
        String[] parts = metric.split(":");
        Map<String, String> query = new HashMap<>();
        query.put("type", parts[0]);
        query.put("server", "Server 1");
        query.put("afterMillisAgo", Long.toString(after));
        if (parts.length == 2) query.put("activityType", parts[1]);
        return query;
    }

    static boolean isExpectedMissingMetric(LogEntry entry) {
        String message = entry.getMessage();
        if (!message.endsWith(RESOURCE_404)) return false;
        try {
            URI uri = URI.create(message.substring(0, message.length() - RESOURCE_404.length()));
            if (!"http".equals(uri.getScheme()) || !"localhost".equals(uri.getHost()) || uri.getPort() != 9091
                    || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || !"/v1/datapoint".equals(uri.getPath()) || uri.getRawQuery() == null) return false;
            Map<String, String> query = new HashMap<>();
            for (String parameter : uri.getRawQuery().split("&", -1)) {
                String[] parts = parameter.split("=", 2);
                if (parts.length != 2) return false;
                String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                String value = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                if (query.putIfAbsent(key, value) != null) return false;
            }
            return EMPTY_QUERIES.contains(query);
        } catch (IllegalArgumentException invalidUri) {
            return false;
        }
    }
}
