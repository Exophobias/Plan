import {reportDate, REPORT_TIMEZONE, REPORT_TIMEZONE_LABEL, reportTimestamp} from './communityReportTimezone.js';
export {reportDate, REPORT_TIMEZONE, REPORT_TIMEZONE_LABEL, reportTimestamp};

export const REPORT_PERMISSION = 'page.server.reports';
export const DAY = 86400000;
export const MAX_REPORT_DAYS = 366;
export const RENDERER_VERSION = 3;
export const REPORT_SECTIONS = {summary: 'Community totals', daily: 'Daily activity graph',
    busy_times: 'Busiest times heatmap', retention: 'First-week return'};

export function canViewReports(auth, exported = false) {
    return !exported && auth?.authLoaded === true && auth?.loggedIn === true
        && Array.isArray(auth.user?.permissions) && auth.user.permissions.includes(REPORT_PERMISSION);
}

// UTC is only a stable representation for arithmetic on date labels, never a report day boundary.
export function utcDate(value) { return new Date(value).toISOString().slice(0, 10); }
export function parseReportDate(value) {
    if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return NaN;
    const time = Date.parse(`${value}T00:00:00.000Z`);
    return Number.isFinite(time) && utcDate(time) === value ? time : NaN;
}

// Inputs are inclusive calendar dates. Only the request's end boundary is exclusive.
export function reportRange(start, end, now = Date.now()) {
    const from = parseReportDate(start), until = parseReportDate(end) + DAY;
    if (!Number.isFinite(from) || !Number.isFinite(until) || until <= from) {
        throw new Error('Choose valid dates with the end on or after the start.');
    }
    if (until - from > MAX_REPORT_DAYS * DAY) throw new Error('Choose a period of 366 days or fewer.');
    if (until > parseReportDate(reportDate(now)) + DAY) throw new Error('The end date cannot be in the future.');
    return {start: utcDate(from), end: utcDate(until)};
}

export function reportPreset(preset, now = Date.now()) {
    const today = parseReportDate(reportDate(now));
    if (preset === 'yesterday') return {start: utcDate(today - DAY), end: utcDate(today - DAY)};
    const date = new Date(today);
    const monthStart = Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), 1);
    return {start: utcDate(Date.UTC(date.getUTCFullYear(), date.getUTCMonth() - 1, 1)), end: utcDate(monthStart - DAY)};
}

const count = value => Number.isSafeInteger(value) && value >= 0;
const number = value => Number.isFinite(value) && value >= 0 && value <= Number.MAX_SAFE_INTEGER;
const optional = (value, check = count) => value === null || check(value);
const record = value => value !== null && typeof value === 'object' && !Array.isArray(value);

export function validateCommunityReport(data, requested) {
    const invalid = () => { throw new Error('The report returned an unsupported or incomplete response. Generate it again.'); };
    if (!record(data) || data.schema_version !== 2 || !['ready', 'empty', 'unavailable'].includes(data.status)
        || !record(data.period) || !record(data.summary) || !record(data.retention) || !record(data.busy_times)) invalid();
    const p = data.period, s = data.summary, r = data.retention;
    const from = parseReportDate(p.start_date), until = parseReportDate(p.end_date);
    const days = (until - from) / DAY;
    if (!Number.isInteger(days) || days < 1 || days > MAX_REPORT_DAYS || p.timezone !== REPORT_TIMEZONE
        || !count(p.as_of) || p.as_of > 8640000000000000 || !count(p.recorded_from)
        || p.recorded_from > 8640000000000000 || typeof p.complete !== 'boolean'
        || (requested && (p.start_date !== requested.start || p.end_date !== requested.end))) invalid();
    if (!['participants', 'newcomers', 'peak_online'].every(key => optional(s[key]))
        || !optional(s.active_hours, number)
        || (s.participants !== null && s.newcomers !== null && s.newcomers > s.participants)) invalid();
    if (!Array.isArray(data.daily) || (data.daily.length !== 0 && data.daily.length !== days)) invalid();
    data.daily.forEach((row, index) => {
        if (!record(row) || row.date !== utcDate(from + index * DAY) || typeof row.covered !== 'boolean'
            || !optional(row.participants) || !optional(row.active_hours, number)
            || (!row.covered && (row.participants !== null || row.active_hours !== null))) invalid();
    });
    const busy = data.busy_times;
    if (busy.metric !== 'average_active_players' || !Array.isArray(busy.cells)
        || (busy.cells.length !== 0 && busy.cells.length !== 168)) invalid();
    busy.cells.forEach((cell, index) => {
        if (!record(cell) || cell.day !== Math.floor(index / 24) + 1 || cell.hour !== index % 24
            || !optional(cell.average_active_players, number)) invalid();
    });
    if (!optional(r.eligible) || !optional(r.returned) || !optional(r.rate, value => number(value) && value <= 1)
        || !optional(r.observed_through, value => count(value) && value <= 8640000000000000)) invalid();
    const present = [r.eligible, r.returned, r.rate].filter(value => value !== null).length;
    if ((present !== 0 && present !== 3) || (present === 3 && (r.eligible <= 0 || r.returned > r.eligible
        || r.observed_through === null || Math.abs(r.rate - r.returned / r.eligible) > 0.000001))) invalid();
    if (!Array.isArray(data.omissions) || data.omissions.length > 100 || !data.omissions.every(row => record(row)
        && typeof row.section === 'string' && row.section.length <= 100
        && typeof row.reason === 'string' && row.reason.length <= 2000)) invalid();
    return data;
}

const METRICS = [
    ['participants', 'Participating accounts', 'At least 5 minutes of measured active time in this period.'],
    ['newcomers', 'Newly observed players', 'Participating accounts first seen in this period, since community tracking began.'],
    ['active_hours', 'Active hours together', 'Measured non-AFK time across recorded accounts.'],
    ['peak_online', 'Peak simultaneous accounts', 'Highest overlap of recorded sessions; no individual schedules are published.']
];
export const formatReportNumber = (value, decimals = 0) => decimals > 0 && value > 0 && value < 10 ** -decimals
    ? `< ${(10 ** -decimals).toFixed(decimals)}` : new Intl.NumberFormat('en-US', {maximumFractionDigits: decimals}).format(value);
export const reportPeriodLabel = period => `${period.start_date} – ${utcDate(parseReportDate(period.end_date) - DAY)} · ${REPORT_TIMEZONE_LABEL}`;

export function availableReportSections(data) {
    if (data.status !== 'ready') return {summary: false, daily: false, busy_times: false, retention: false};
    return {
        summary: METRICS.some(([key]) => data.summary[key] > 0),
        daily: data.daily.some(row => row.covered && (row.participants > 0 || row.active_hours > 0)),
        busy_times: data.busy_times.cells.some(cell => cell.average_active_players > 0),
        retention: data.retention.eligible > 0 && data.retention.returned > 0 && data.retention.rate > 0
    };
}

// Build public output from a field allowlist. Never spread the server response into an export.
// The same spec drives the PNG, public text, and JSON; disabled sections leave no hidden data behind.
export function createPublicReport(data, enabled = {}) {
    validateCommunityReport(data);
    const available = availableReportSections(data);
    const selected = key => available[key] && enabled[key] !== false;
    const p = data.period;
    const report = {schema_version: 2, renderer_version: RENDERER_VERSION,
        period: {start_date: p.start_date, end_date: p.end_date, timezone: REPORT_TIMEZONE, recorded_from: p.recorded_from,
            as_of: p.as_of, complete: p.complete}, pages: []};
    const cards = selected('summary') ? METRICS.filter(([key]) => data.summary[key] > 0)
        .map(([key, label, definition]) => ({key, label, value: data.summary[key], definition})) : [];
    let daily = null;
    if (selected('daily')) {
        const key = data.daily.some(row => row.covered && row.participants > 0) ? 'participants' : 'active_hours';
        daily = {key, label: key === 'participants' ? 'Participating accounts each day' : 'Active hours each day',
            definition: key === 'participants' ? 'At least 5 active minutes per account per Pacific calendar day. Daily counts are not added to make period totals.'
                : 'Measured non-AFK time per Pacific calendar day.',
            points: data.daily.map(row => ({date: row.date, value: row.covered ? row[key] : null}))};
    }
    if (cards.length || daily) report.pages.push({id: 'activity', title: 'Community activity', cards, daily});
    if (selected('busy_times')) {
        report.pages.push({id: 'busy-times', title: 'When Patriam is busiest',
            busy_times: {metric: 'average_active_players', cells: data.busy_times.cells.map(cell => ({
                day: cell.day, hour: cell.hour, average_active_players: cell.average_active_players}))},
            definition: 'Average simultaneous non-AFK accounts for each weekday and hour across this period. Pacific calendar days and the actual duration of each hour are used.',
            legend: 'Darker cells show less activity; brighter cells show more. Quiet cells mean recorded zero activity. Hatched cells are withheld or unavailable.'});
    }
    if (selected('retention')) {
        const r = data.retention;
        report.pages.push({id: 'return', title: 'Coming back to Patriam',
            retention: {eligible: r.eligible, returned: r.returned, rate: r.rate, observed_through: r.observed_through},
            definition: 'First-observed accounts in this period with seven complete follow-up Pacific calendar days. A return means at least 5 active minutes on a day from day 1 through day 7 after first observation.'});
    }
    return report;
}

export function publicReportText(report) {
    if (!report.pages.length) return '';
    const lines = ['PATRIAM · COMMUNITY CHRONICLE', reportPeriodLabel(report.period),
        `${report.period.complete ? 'Recorded' : 'Partial period · recorded'} ${reportTimestamp(report.period.recorded_from)} through ${reportTimestamp(report.period.as_of)}`];
    for (const page of report.pages) {
        lines.push('', page.title);
        if (page.cards) for (const card of page.cards) lines.push(`${card.label}: ${formatReportNumber(card.value, card.key === 'active_hours' ? 1 : 0)}. ${card.definition}`);
        if (page.daily) lines.push(page.daily.label + '. ' + page.daily.definition + ' Gaps mean withheld or unavailable data. Exact plotted values are in the companion JSON.');
        if (page.busy_times) lines.push(page.definition, page.legend,
            'Rows run Monday through Sunday; columns show hours 00 through 23 in Pacific Time (Vancouver). Exact plotted values are in the companion JSON.');
        if (page.retention) {
            const r = page.retention;
            lines.push(`${formatReportNumber(r.rate * 100, 1)}% returned in their first week (${formatReportNumber(r.returned)} of ${formatReportNumber(r.eligible)} eligible accounts).`,
                page.definition, `Return observations through ${reportTimestamp(r.observed_through)}.`);
        }
    }
    return lines.join('\n');
}
