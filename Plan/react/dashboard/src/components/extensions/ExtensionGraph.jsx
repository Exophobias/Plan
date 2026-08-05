import React, {useEffect, useMemo, useState} from "react";
import LineGraph from "../graphs/LineGraph";
import {usePreferences} from "../../hooks/preferencesHook.jsx";
import {useTimeAmountFormatter} from "../../util/format/useTimeAmountFormatter.js";
import {useDateFormatter} from "../../util/format/useDateFormatter.js";
import {useDecimalFormatter} from "../../util/format/useDecimalFormatter.js";
import {ChartLoader} from "../navigation/Loader.tsx";

/**
 * The kept history of one graphed extension provider.
 *
 * Delegates to LineGraph rather than mounting Highcharts itself, so it inherits the theming, locale,
 * timezone and stock chrome that every other time series on the page already has.
 */
const ExtensionGraph = ({graph}) => {
    // formatTime throws outright when preferences have not loaded, and an axis formatter runs after
    // render, so the failure would surface inside Highcharts with a stack that names none of this.
    const {preferencesLoaded} = usePreferences();
    const {formatTime} = useTimeAmountFormatter();
    const {formatDecimals} = useDecimalFormatter();
    // Only ever applied to y values, which Highcharts does not shift. LineGraph's time.timezoneOffset
    // moves the x axis alone, so the server offset must be left in place here rather than suppressed.
    const {formatDate} = useDateFormatter(graph.type === 'DATE_SECOND');

    // Highcharts finds its container by DOM id, and an extension card is repeated per plugin and per
    // tab. A fixed id would make the second graph on a page render into the first one's div.
    const [id] = useState("extension-graph-" + Date.now() + "-" + (Math.floor(Math.random() * 100000)));
    const [series, setSeries] = useState([]);

    useEffect(() => {
        if (!preferencesLoaded) return;

        const format = value => {
            switch (graph.type) {
                case 'PERCENTAGE':
                    // Already scaled to 0-100 below, so this is only the suffix.
                    return formatDecimals(value) + '%';
                case 'TIME_MILLISECONDS':
                    return formatTime(value);
                case 'DATE_YEAR':
                case 'DATE_SECOND':
                    return formatDate(value);
                default:
                    return formatDecimals(value);
            }
        };

        // A share is stored as 0 to 1 and read as 0 to 100. Scaling here rather than in the axis
        // formatter keeps the tooltip, the axis and the crosshair quoting the same number.
        const points = graph.type === 'PERCENTAGE'
            ? graph.points.map(([timestamp, value]) => [timestamp, value * 100])
            : graph.points;

        setSeries([{
            name: graph.description.text,
            type: 'spline',
            data: points,
            tooltip: {
                // Highcharts binds `this` to the point, so this cannot be an arrow function.
                pointFormatter: function () {
                    return '<span style="color:' + this.color + '">●</span> ' +
                        this.series.name + ': <b>' + format(this.y) + '</b><br/>';
                }
            }
        }]);
    }, [graph, preferencesLoaded, formatTime, formatDate, formatDecimals]);

    // Memoised because LineGraph lists yAxis in the dependency array of the effect that builds the
    // chart. A fresh object literal each render would tear down and remount Highcharts every time.
    const yAxis = useMemo(() => {
        const dateValued = graph.type === 'DATE_YEAR' || graph.type === 'DATE_SECOND';
        return {
            labels: {
                // Same binding rule as the tooltip above: `this.value` is the tick.
                formatter: function () {
                    switch (graph.type) {
                        case 'PERCENTAGE':
                            return formatDecimals(this.value) + '%';
                        case 'TIME_MILLISECONDS':
                            return formatTime(this.value);
                        case 'DATE_YEAR':
                        case 'DATE_SECOND':
                            return formatDate(this.value);
                        default:
                            return this.value;
                    }
                }
            },
            title: {text: ''},
            // A counter starts at zero, and an axis cropped to the data makes a flat line look like
            // a cliff. Never for a date-valued series though: those y values are epoch milliseconds,
            // so anchoring at zero would stretch the axis from 1970 to now and flatten the line into
            // the top pixel.
            softMin: dateValued ? undefined : 0
        };
    }, [graph.type, formatTime, formatDate, formatDecimals]);

    if (!preferencesLoaded) return <ChartLoader/>;

    // selectedRange 4 is the 'all' button. LineGraph defaults to 2, the 7 day one, so a provider
    // whose history has only just started being kept would open on an empty chart.
    return <LineGraph id={id} series={series} yAxis={yAxis} selectedRange={4}/>;
}

export default ExtensionGraph
