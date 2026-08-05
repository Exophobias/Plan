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
package com.djrapitops.plan.extension.implementation.storage.queries;

import com.djrapitops.plan.extension.ElementOrder;
import com.djrapitops.plan.extension.FormatType;
import com.djrapitops.plan.extension.icon.Color;
import com.djrapitops.plan.extension.icon.Family;
import com.djrapitops.plan.extension.icon.Icon;
import com.djrapitops.plan.extension.implementation.TabInformation;
import com.djrapitops.plan.extension.implementation.results.ExtensionData;
import com.djrapitops.plan.extension.implementation.results.ExtensionDescription;
import com.djrapitops.plan.extension.implementation.results.ExtensionGraphData;
import com.djrapitops.plan.extension.implementation.results.ExtensionTabData;
import com.djrapitops.plan.identification.ServerUUID;
import com.djrapitops.plan.storage.database.SQLDB;
import com.djrapitops.plan.storage.database.queries.Query;
import com.djrapitops.plan.storage.database.queries.QueryStatement;
import com.djrapitops.plan.storage.database.sql.tables.extension.*;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.djrapitops.plan.storage.database.sql.building.Sql.*;

/**
 * Query for the kept history of every graphed provider on a server.
 * <p>
 * Returns Map: PluginID - {@link ExtensionData.Builder}.
 * <p>
 * How it is done:
 * 1. Query every history point, joined to its provider, tab and icons
 * 2. Ordered by provider then timestamp, so the rows for one series arrive together and in order
 * 3. Fold consecutive rows of the same provider into one {@link ExtensionGraphData}
 *
 * @author AuroraLS3
 */
public class ExtensionServerGraphsQuery implements Query<Map<Integer, ExtensionData.Builder>> {

    private final ServerUUID serverUUID;

    public ExtensionServerGraphsQuery(ServerUUID serverUUID) {
        this.serverUUID = serverUUID;
    }

    @Override
    public Map<Integer, ExtensionData.Builder> executeQuery(SQLDB db) {
        String sql = SELECT +
                "h1." + ExtensionServerValueHistoryTable.TIMESTAMP + " as point_timestamp," +
                "h1." + ExtensionServerValueHistoryTable.LONG_VALUE + " as point_long," +
                "h1." + ExtensionServerValueHistoryTable.DOUBLE_VALUE + " as point_double," +
                "p1." + ExtensionProviderTable.ID + " as provider_id," +
                "p1." + ExtensionProviderTable.PLUGIN_ID + " as plugin_id," +
                "p1." + ExtensionProviderTable.PROVIDER_NAME + " as provider_name," +
                "p1." + ExtensionProviderTable.TEXT + " as text," +
                "p1." + ExtensionProviderTable.DESCRIPTION + " as description," +
                "p1." + ExtensionProviderTable.PRIORITY + " as provider_priority," +
                "p1." + ExtensionProviderTable.FORMAT_TYPE + " as format_type," +
                "v1." + ExtensionServerValueTable.PERCENTAGE_VALUE + " as percentage_marker," +
                "t1." + ExtensionTabTable.TAB_NAME + " as tab_name," +
                "t1." + ExtensionTabTable.TAB_PRIORITY + " as tab_priority," +
                "t1." + ExtensionTabTable.ELEMENT_ORDER + " as element_order," +
                "i1." + ExtensionIconTable.ICON_NAME + " as provider_icon_name," +
                "i1." + ExtensionIconTable.FAMILY + " as provider_icon_family," +
                "i1." + ExtensionIconTable.COLOR + " as provider_icon_color," +
                "i2." + ExtensionIconTable.ICON_NAME + " as tab_icon_name," +
                "i2." + ExtensionIconTable.FAMILY + " as tab_icon_family," +
                "i2." + ExtensionIconTable.COLOR + " as tab_icon_color" +
                FROM + ExtensionServerValueHistoryTable.TABLE_NAME + " h1" +
                INNER_JOIN + ExtensionProviderTable.TABLE_NAME + " p1 on p1." + ExtensionProviderTable.ID + "=h1." + ExtensionServerValueHistoryTable.PROVIDER_ID +
                INNER_JOIN + ExtensionPluginTable.TABLE_NAME + " e1 on p1." + ExtensionProviderTable.PLUGIN_ID + "=e1." + ExtensionPluginTable.ID +
                // The provider's current value, solely to find out whether it is a percentage provider.
                // Nothing on plan_extension_providers records that: percentage-ness is which column the value
                // was written to, and the value query infers it the same way. Exactly one row per provider,
                // so the join cannot multiply points.
                LEFT_JOIN + ExtensionServerValueTable.TABLE_NAME + " v1 on v1." + ExtensionServerValueTable.PROVIDER_ID + "=p1." + ExtensionProviderTable.ID +
                LEFT_JOIN + ExtensionTabTable.TABLE_NAME + " t1 on t1." + ExtensionTabTable.ID + "=p1." + ExtensionProviderTable.TAB_ID +
                LEFT_JOIN + ExtensionIconTable.TABLE_NAME + " i1 on i1." + ExtensionIconTable.ID + "=p1." + ExtensionProviderTable.ICON_ID +
                LEFT_JOIN + ExtensionIconTable.TABLE_NAME + " i2 on i2." + ExtensionIconTable.ID + "=t1." + ExtensionTabTable.ICON_ID +
                WHERE + ExtensionPluginTable.SERVER_UUID + "=?" +
                AND + "p1." + ExtensionProviderTable.HIDDEN + "=?" +
                // Provider first so one series arrives unbroken, timestamp second so it arrives sorted.
                // Sorting in Java instead would mean holding every point of every series before drawing any.
                ORDER_BY + "provider_id ASC, point_timestamp ASC";

        // No time bound and no LIMIT here on purpose. The series is bounded by retention alone, and the
        // setting that does it is Time.Thresholds.Remove_time_series_data_after -- named for time series
        // generally rather than for TPS, which is why the prune reuses it. Extension server data refreshes
        // hourly, so a provider gathers 24 points a day and the 3650 day default settles around 87 000
        // points: large, but years away, and an operator who cares has one setting to turn. A cap here
        // instead would silently disagree with what that setting promises.

        return db.query(new QueryStatement<>(sql, 5000) {
            @Override
            public void prepare(PreparedStatement statement) throws SQLException {
                statement.setString(1, serverUUID.toString());
                statement.setBoolean(2, false); // Don't select hidden values
            }

            @Override
            public Map<Integer, ExtensionData.Builder> processResults(ResultSet set) throws SQLException {
                return extractTabDataByPluginID(set).toExtensionDataByPluginID();
            }
        });
    }

    private QueriedTabData extractTabDataByPluginID(ResultSet set) throws SQLException {
        QueriedTabData tabData = new QueriedTabData();
        // Provider id to the series being accumulated, so consecutive rows append rather than starting over.
        Map<Integer, ExtensionGraphData> graphsByProviderID = new HashMap<>();

        while (set.next()) {
            int pluginID = set.getInt("plugin_id");
            int providerID = set.getInt("provider_id");
            String tabName = Optional.ofNullable(set.getString("tab_name")).orElse("");

            ExtensionGraphData graph = graphsByProviderID.get(providerID);
            if (graph == null) {
                FormatType formatType = FormatType.getByName(set.getString("format_type")).orElse(FormatType.NONE);
                // wasNull() reports on the last column read, so this pair has to stay adjacent.
                set.getDouble("percentage_marker");
                boolean percentage = !set.wasNull();

                graph = new ExtensionGraphData(extractDescription(set), formatType, percentage);
                graphsByProviderID.put(providerID, graph);

                ExtensionTabData.Builder extensionTab = tabData.getTab(pluginID, tabName, () -> extractTabInformation(tabName, set));
                extensionTab.putGraphData(graph);
            }

            // Whichever column the value landed in. Which one it is says how it was provided, and the graph
            // does not care: see ExtensionGraphData.Point.
            double value = set.getDouble("point_double");
            if (set.wasNull()) {
                value = set.getLong("point_long");
            }
            graph.addPoint(set.getLong("point_timestamp"), value);
        }
        return tabData;
    }

    private TabInformation extractTabInformation(String tabName, ResultSet set) throws SQLException {
        Optional<Integer> tabPriority = Optional.of(set.getInt("tab_priority"));
        if (set.wasNull()) {
            tabPriority = Optional.empty();
        }
        Optional<ElementOrder[]> elementOrder = Optional.ofNullable(set.getString("element_order")).map(ElementOrder::deserialize);

        return new TabInformation(
                tabName,
                extractTabIcon(set),
                elementOrder.orElse(ElementOrder.values()),
                tabPriority.orElse(100)
        );
    }

    private ExtensionDescription extractDescription(ResultSet set) throws SQLException {
        String name = set.getString("provider_name");
        String text = set.getString("text");
        String description = set.getString("description");
        int priority = set.getInt("provider_priority");

        String iconName = set.getString("provider_icon_name");
        Family family = Family.getByName(set.getString("provider_icon_family")).orElse(Family.SOLID);
        Color color = Color.getByName(set.getString("provider_icon_color")).orElse(Color.NONE);
        Icon icon = new Icon(family, iconName, color);

        return new ExtensionDescription(name, text, description, icon, priority);
    }

    private Icon extractTabIcon(ResultSet set) throws SQLException {
        Optional<String> iconName = Optional.ofNullable(set.getString("tab_icon_name"));
        if (iconName.isPresent()) {
            Family iconFamily = Family.getByName(set.getString("tab_icon_family")).orElse(Family.SOLID);
            Color iconColor = Color.getByName(set.getString("tab_icon_color")).orElse(Color.NONE);
            return new Icon(iconFamily, iconName.get(), iconColor);
        } else {
            return TabInformation.defaultIcon();
        }
    }
}
