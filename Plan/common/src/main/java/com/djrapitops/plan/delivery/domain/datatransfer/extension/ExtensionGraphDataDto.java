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
package com.djrapitops.plan.delivery.domain.datatransfer.extension;

import com.djrapitops.plan.extension.FormatType;
import com.djrapitops.plan.extension.implementation.results.ExtensionGraphData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * One graphed provider's series, on its way to the page.
 * <p>
 * The points are sent as two-element {@code [timestamp, value]} arrays rather than objects, because that is
 * the shape Highcharts takes for a time series without any mapping, and because a series of a few thousand
 * points roughly halves in size when the two key names stop being repeated on every one of them.
 *
 * @author AuroraLS3
 */
public class ExtensionGraphDataDto {

    private final ExtensionDescriptionDto description;
    private final String type;
    private final List<Object[]> points;

    public ExtensionGraphDataDto(ExtensionGraphData graphData) {
        this.description = new ExtensionDescriptionDto(graphData.getDescription());
        this.type = typeOf(graphData);

        this.points = new ArrayList<>(graphData.getPoints().size());
        for (ExtensionGraphData.Point point : graphData.getPoints()) {
            // Object[] rather than double[] so the timestamp stays a Long. Gson writes a double through
            // Double.toString, which renders an epoch millisecond as "1.7543E12" -- valid JSON that every
            // parser accepts, and unreadable to anybody who opens the endpoint to see what is in it.
            this.points.add(new Object[]{point.getTimestamp(), point.getValue()});
        }
    }

    /**
     * The same vocabulary a single value uses, so the page has one thing to switch on.
     *
     * <p>{@code ExtensionTabDataDto.mapToValue} labels values PERCENTAGE, NUMBER, or the format's own name,
     * and the front end already knows those words. Inventing a second set for graphs would mean teaching it
     * the same distinctions twice.
     *
     * @param graphData the series
     * @return PERCENTAGE, NUMBER, TIME_MILLISECONDS, DATE_YEAR or DATE_SECOND.
     */
    private static String typeOf(ExtensionGraphData graphData) {
        if (graphData.isPercentage()) return "PERCENTAGE";
        FormatType formatType = graphData.getFormatType();
        return formatType == FormatType.NONE ? "NUMBER" : formatType.name();
    }

    public ExtensionDescriptionDto getDescription() {
        return description;
    }

    public String getType() {
        return type;
    }

    public List<Object[]> getPoints() {
        return points;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ExtensionGraphDataDto that = (ExtensionGraphDataDto) o;
        return Objects.equals(description, that.description)
                && Objects.equals(type, that.type)
                && pointsEqual(points, that.points);
    }

    @Override
    public int hashCode() {
        return Objects.hash(description, type, pointsHash(points));
    }

    /**
     * List equality would compare the pairs by array identity.
     *
     * <p>{@code Object[].equals} is reference equality, so a plain {@code Objects.equals} on the two lists
     * reports two DTOs built from the same rows as different, and the mismatched hash codes travel up into
     * {@link ExtensionTabDataDto}. Comparing element by element is what the pairs being arrays actually meant.
     */
    private static boolean pointsEqual(List<Object[]> one, List<Object[]> other) {
        if (one.size() != other.size()) return false;
        for (int i = 0; i < one.size(); i++) {
            if (!Arrays.equals(one.get(i), other.get(i))) return false;
        }
        return true;
    }

    private static int pointsHash(List<Object[]> points) {
        int hash = 1;
        for (Object[] point : points) {
            hash = 31 * hash + Arrays.hashCode(point);
        }
        return hash;
    }

    @Override
    public String toString() {
        return "ExtensionGraphDataDto{" +
                "description=" + description +
                ", type='" + type + '\'' +
                ", points=" + points.size() +
                '}';
    }
}
