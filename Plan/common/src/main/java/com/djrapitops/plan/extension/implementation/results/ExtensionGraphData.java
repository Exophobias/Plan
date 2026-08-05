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
package com.djrapitops.plan.extension.implementation.results;

import com.djrapitops.plan.extension.FormatType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Represents the kept history of one graphed provider, ready to be drawn as a series.
 *
 * @author AuroraLS3
 */
public class ExtensionGraphData implements Comparable<ExtensionGraphData> {

    private final ExtensionDescription description;
    private final FormatType formatType;
    private final boolean percentage;
    private final List<Point> points;

    public ExtensionGraphData(ExtensionDescription description, FormatType formatType, boolean percentage) {
        this.description = description;
        this.formatType = formatType;
        this.percentage = percentage;
        this.points = new ArrayList<>();
    }

    public void addPoint(long timestamp, double value) {
        points.add(new Point(timestamp, value));
    }

    public ExtensionDescription getDescription() {
        return description;
    }

    /**
     * How the y axis should be labelled.
     *
     * <p>The same {@link FormatType} the single value carries, so a duration graphs in hours and a plain count
     * graphs as a count. Sending the raw number with no format would make a millisecond series unreadable.
     *
     * @return the format, never null.
     */
    public FormatType getFormatType() {
        return formatType;
    }

    /**
     * Whether the series came from a percentage provider.
     *
     * <p>Carried separately because the extension {@link FormatType} has no percentage constant: it holds
     * only NONE, TIME_MILLISECONDS, DATE_YEAR and DATE_SECOND. Percentage-ness lives in which column the
     * value was stored in, exactly as it does for a single value, so without this flag a percentage series
     * arrives as {@code NONE} and draws as a raw figure between 0 and 1.
     *
     * @return true if the values are shares between 0 and 1.
     */
    public boolean isPercentage() {
        return percentage;
    }

    /**
     * The series, oldest first.
     *
     * <p>Ordered by the query rather than sorted here: the database can return it in order off the timestamp,
     * and re-sorting a list that is already sorted is work done on every page load for nothing.
     *
     * @return the points.
     */
    public List<Point> getPoints() {
        return points;
    }

    @Override
    public int compareTo(ExtensionGraphData other) {
        return String.CASE_INSENSITIVE_ORDER.compare(description.getName(), other.description.getName());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ExtensionGraphData that = (ExtensionGraphData) o;
        return Objects.equals(description, that.description)
                && formatType == that.formatType
                && percentage == that.percentage
                && Objects.equals(points, that.points);
    }

    @Override
    public int hashCode() {
        return Objects.hash(description, formatType, percentage, points);
    }

    @Override
    public String toString() {
        return "ExtensionGraphData{" +
                "description=" + description +
                ", formatType=" + formatType +
                ", percentage=" + percentage +
                ", points=" + points.size() +
                '}';
    }

    /**
     * One sample.
     *
     * <p>The value is a double even for a number provider. The two are stored apart because they are stored
     * apart everywhere else in the extension tables, but a graph does not care: it plots a y, and widening a
     * long to a double is lossless below 2^53, which no counter a plugin reports will reach.
     */
    public static class Point {

        private final long timestamp;
        private final double value;

        public Point(long timestamp, double value) {
            this.timestamp = timestamp;
            this.value = value;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public double getValue() {
            return value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Point point = (Point) o;
            return timestamp == point.timestamp && Double.compare(value, point.value) == 0;
        }

        @Override
        public int hashCode() {
            return Objects.hash(timestamp, value);
        }

        @Override
        public String toString() {
            return "Point{" + timestamp + "=" + value + '}';
        }
    }
}
