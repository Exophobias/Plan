import test from 'node:test';
import assert from 'node:assert/strict';
import {availableReportSections, canViewReports, createPublicReport, DAY, formatReportNumber, parseReportDate,
    publicReportText, REPORT_PERMISSION, REPORT_TIMEZONE, REPORT_TIMEZONE_LABEL, reportDate, reportPeriodLabel,
    reportPreset, reportRange, reportTimestamp, validateCommunityReport} from '../src/util/communityReport.js';

// Synthetic aggregate values only. This fixture contains no server or account records.
function fixture() {
    return {schema_version: 2, status: 'ready',
        period: {start_date: '2026-08-01', end_date: '2026-08-04', timezone: 'America/Vancouver',
            recorded_from: Date.parse('2026-08-01T07:00:00Z'), as_of: Date.parse('2026-08-04T07:00:00Z'), complete: true},
        summary: {participants: 30, newcomers: 12, active_hours: 250.5, peak_online: 18},
        daily: [15, 0, 25].map((participants, index) => ({date: `2026-08-0${index + 1}`, participants,
            active_hours: participants * 2, covered: true})),
        retention: {eligible: 12, returned: 6, rate: .5, observed_through: Date.parse('2026-08-12T07:00:00Z')},
        busy_times: {metric: 'average_active_players', cells: []},
        omissions: []};
}

function heatmapFixture() {
    const data = fixture();
    data.busy_times.cells = Array.from({length: 168}, (_, index) => ({day: Math.floor(index / 24) + 1,
        hour: index % 24, average_active_players: index === 0 ? null : index === 1 ? 0 : index / 10}));
    return data;
}

test('Reports requires exact permission, current authentication, and excludes static exports', () => {
    const staff = {authLoaded: true, loggedIn: true, user: {permissions: [REPORT_PERMISSION]}};
    assert.equal(canViewReports(staff), true);
    for (const auth of [undefined, {}, {...staff, authLoaded: false}, {...staff, loggedIn: false},
        {...staff, user: {permissions: ['*', 'page', 'page.server', 'page.server.store', REPORT_PERMISSION + '.extra']}}]) {
        assert.equal(canViewReports(auth), false);
    }
    assert.equal(canViewReports(staff, true), false);
});

test('Vancouver presets and inclusive requests survive leap days, month/year rollover and DST dates', () => {
    assert.deepEqual(reportPreset('month', Date.parse('2024-03-31T23:00:00-07:00')),
        {start: '2024-02-01', end: '2024-02-29'});
    assert.deepEqual(reportPreset('month', Date.parse('2024-04-01T07:00:00Z')),
        {start: '2024-03-01', end: '2024-03-31'});
    assert.deepEqual(reportPreset('month', Date.parse('2024-03-01T08:00:00Z')),
        {start: '2024-02-01', end: '2024-02-29'});
    assert.deepEqual(reportPreset('yesterday', Date.parse('2026-01-01T08:00:00Z')),
        {start: '2025-12-31', end: '2025-12-31'});
    assert.deepEqual(reportRange('2026-03-08', '2026-03-08', Date.parse('2026-03-09T00:00:00Z')),
        {start: '2026-03-08', end: '2026-03-09'});
    assert.deepEqual(reportRange('2024-01-01', '2024-12-31', Date.parse('2025-01-01T00:00:00Z')),
        {start: '2024-01-01', end: '2025-01-01'});
});

test('report dates switch at Vancouver midnight, independent of the browser calendar', () => {
    assert.equal(reportDate(Date.parse('2026-09-15T06:59:59.999Z')), '2026-09-14');
    assert.equal(reportDate(Date.parse('2026-09-15T07:00:00Z')), '2026-09-15');
    assert.deepEqual(reportPreset('yesterday', Date.parse('2026-09-15T00:30:00Z')),
        {start: '2026-09-13', end: '2026-09-13'});
    assert.throws(() => reportRange('2026-09-15', '2026-09-15', Date.parse('2026-09-15T06:59:59Z')), /future/);
    assert.deepEqual(reportRange('2026-09-15', '2026-09-15', Date.parse('2026-09-15T07:00:00Z')),
        {start: '2026-09-15', end: '2026-09-16'});
});

test('timestamps preserve the last spring jump and historical fall overlap with explicit offsets', () => {
    assert.equal(reportTimestamp(Date.parse('2026-03-08T09:59:59.999Z')), '2026-03-08 01:59 UTC−08:00');
    assert.equal(reportTimestamp(Date.parse('2026-03-08T10:00:00Z')), '2026-03-08 03:00 UTC−07:00');
    assert.equal(reportTimestamp(Date.parse('2025-11-02T08:30:00Z')), '2025-11-02 01:30 UTC−07:00');
    assert.equal(reportTimestamp(Date.parse('2025-11-02T09:30:00Z')), '2025-11-02 01:30 UTC−08:00');
    assert.equal(reportDate(Date.parse('2025-12-01T07:30:00Z')), '2025-11-30');
    assert.equal(reportDate(Date.parse('2026-12-01T07:30:00Z')), '2026-12-01');
    assert.deepEqual(reportPreset('month', Date.parse('2026-12-01T07:00:00Z')),
        {start: '2026-11-01', end: '2026-11-30'});
});

test('stale browser tzdata cannot restore November fallback after the permanent UTC−7 transition', () => {
    const original = Intl.DateTimeFormat.prototype.formatToParts;
    let calls = 0;
    // Simulate the old November UTC−8 rule. Neither date selection nor exports should consult it.
    Intl.DateTimeFormat.prototype.formatToParts = () => {
        calls++;
        return Object.entries({year: '2026', month: '11', day: '30', hour: '23', minute: '30', second: '00'})
            .map(([type, value]) => ({type, value}));
    };
    try {
        assert.equal(reportDate(Date.parse('2026-12-01T07:30:00Z')), '2026-12-01');
        assert.equal(reportTimestamp(Date.parse('2026-11-01T08:30:00Z')), '2026-11-01 01:30 UTC−07:00');
        assert.equal(reportTimestamp(Date.parse('2026-11-01T09:30:00Z')), '2026-11-01 02:30 UTC−07:00');
        assert.equal(reportTimestamp(Date.parse('2027-01-01T07:00:00Z')), '2027-01-01 00:00 UTC−07:00');
        assert.deepEqual(reportPreset('month', Date.parse('2026-12-01T07:30:00Z')),
            {start: '2026-11-01', end: '2026-11-30'});
        assert.equal(calls, 0);
    } finally {
        Intl.DateTimeFormat.prototype.formatToParts = original;
    }
});

test('invalid, reversed, future and overlong ranges cannot become requests', () => {
    const now = Date.parse('2026-09-14T12:00:00Z');
    for (const [start, end] of [['2026-02-30', '2026-03-01'], ['2026-9-01', '2026-09-02'],
        ['2026-09-14', '2026-09-13'], ['2026-09-14', '2026-09-15'], ['2024-01-01', '2025-01-01']]) {
        assert.throws(() => reportRange(start, end, now));
    }
    assert.ok(Number.isNaN(parseReportDate(null)));
    assert.equal(parseReportDate('2024-03-01') - parseReportDate('2024-02-29'), DAY);
});

test('strict response validation rejects malformed measures, timelines and mismatched filters', () => {
    const data = fixture();
    assert.equal(validateCommunityReport(data, {start: '2026-08-01', end: '2026-08-04'}), data);
    for (const mutate of [d => d.schema_version = 1, d => d.period.timezone = 'UTC', d => d.period.as_of = NaN,
        d => d.summary.participants = undefined, d => d.summary.active_hours = '250', d => d.summary.newcomers = 31,
        d => d.summary.peak_online = -1, d => d.daily.reverse(), d => d.daily.pop(),
        d => d.daily[1].date = d.daily[0].date, d => d.daily[0].covered = false,
        d => d.retention.returned = 13, d => d.retention.rate = .4, d => d.retention.observed_through = null,
        d => d.retention.eligible = null, d => d.omissions = [{section: 'daily', reason: 42}]]) {
        const changed = fixture(); mutate(changed); assert.throws(() => validateCommunityReport(changed));
    }
    assert.throws(() => validateCommunityReport(data, {start: '2026-08-01', end: '2026-08-05'}));
});

test('suppression removes cards and whole pages but preserves genuine zero graph days', () => {
    const data = fixture(); data.summary.newcomers = null; data.summary.peak_online = 0;
    const report = createPublicReport(data);
    assert.deepEqual(report.pages[0].cards.map(card => card.key), ['participants', 'active_hours']);
    assert.equal(report.pages[0].daily.points[1].value, 0);
    data.daily = []; data.summary = {participants: null, newcomers: null, active_hours: null, peak_online: null};
    assert.deepEqual(createPublicReport(data).pages.map(page => page.id), ['return']);
    data.retention = {eligible: null, returned: null, rate: null, observed_through: null};
    assert.deepEqual(createPublicReport(data).pages, []);
    assert.equal(publicReportText(createPublicReport(data)), '');
    for (const status of ['empty', 'unavailable']) {
        const unavailable = fixture(); unavailable.status = status;
        assert.deepEqual(createPublicReport(unavailable).pages, []);
    }
});

test('uncovered and suppressed observations stay gaps; zero-only graphs and zero-rate pages vanish', () => {
    const data = fixture(); data.period.complete = false;
    data.daily[0] = {...data.daily[0], covered: false, participants: null, active_hours: null};
    assert.equal(createPublicReport(data).pages[0].daily.points[0].value, null);
    data.daily.forEach(row => { row.covered = true; row.participants = 0; row.active_hours = 0; });
    data.retention.returned = 0; data.retention.rate = 0;
    assert.deepEqual(availableReportSections(data), {summary: true, daily: false, busy_times: false, retention: false});
    assert.equal(createPublicReport(data).pages.length, 1);
    assert.equal(createPublicReport(data).pages[0].daily, null);
    assert.equal(formatReportNumber(.004, 1), '< 0.1');
});

test('public export allowlist removes operator notes and unknown fields at every level', () => {
    const data = fixture();
    data.omissions = [{section: 'secret-section', reason: 'secret-reason'}];
    data.private_accounts = ['secret-account']; data.period.private_source = 'secret-source';
    data.summary.internal = 'secret-counter'; data.daily[0].internal = 'secret-daily';
    data.retention.internal = 'secret-cohort';
    const report = createPublicReport(data);
    const encoded = JSON.stringify(report);
    assert.doesNotMatch(encoded, /secret|omissions|internal|private/);
    assert.doesNotMatch(publicReportText(report), /secret|omissions|internal|private/);
    assert.deepEqual(Object.keys(report), ['schema_version', 'renderer_version', 'period', 'pages']);
    assert.equal(report.pages[1].retention.observed_through, data.retention.observed_through);
    assert.match(publicReportText(report), /6 of 12 eligible accounts/);
});

test('image spec, companion text and JSON expose the Vancouver calendar and revised schema', () => {
    const data = fixture();
    const report = createPublicReport(data);
    assert.equal(report.schema_version, 2);
    assert.equal(report.renderer_version, 2);
    assert.equal(report.period.timezone, REPORT_TIMEZONE);
    assert.equal(report.period.recorded_from, data.period.recorded_from);
    assert.equal(reportPeriodLabel(report.period), '2026-08-01 – 2026-08-03 · ' + REPORT_TIMEZONE_LABEL);
    assert.match(publicReportText(report), /Pacific Time \(Vancouver\)/);
    assert.match(publicReportText(report), /2026-08-01 00:00 UTC−07:00 through 2026-08-04 00:00 UTC−07:00/);
    assert.match(publicReportText(report), /follow-up Pacific calendar days/);
    assert.match(publicReportText(report), /per Pacific calendar day/);
    assert.doesNotMatch(publicReportText(report), /UTC day|PST|PDT|MDT/);
});

test('heatmap validates all 168 ordered weekday/hour cells and nullable averages', () => {
    assert.equal(validateCommunityReport(heatmapFixture()).busy_times.cells.length, 168);
    for (const mutate of [d => delete d.busy_times, d => d.busy_times.metric = 'sessions',
        d => d.busy_times.cells.pop(), d => d.busy_times.cells.reverse(), d => d.busy_times.cells[0].day = 0,
        d => d.busy_times.cells[23].hour = 24, d => d.busy_times.cells[0].average_active_players = -1,
        d => d.busy_times.cells[0].average_active_players = '2',
        d => d.busy_times.cells[0].average_active_players = Infinity,
        d => delete d.busy_times.cells[0].average_active_players]) {
        const data = heatmapFixture(); mutate(data); assert.throws(() => validateCommunityReport(data));
    }
});

test('heatmap public exports retain quiet and withheld cells distinctly and exclude private extras', () => {
    const data = heatmapFixture();
    data.busy_times.internal = 'secret-raw'; data.busy_times.cells[0].accounts = ['secret-account'];
    const report = createPublicReport(data);
    assert.deepEqual(report.pages.map(page => page.id), ['activity', 'busy-times', 'return']);
    const heatmap = report.pages[1];
    assert.equal(heatmap.busy_times.metric, 'average_active_players');
    assert.equal(heatmap.busy_times.cells.length, 168);
    assert.equal(heatmap.busy_times.cells[0].average_active_players, null);
    assert.equal(heatmap.busy_times.cells[1].average_active_players, 0);
    assert.equal(heatmap.busy_times.cells[167].average_active_players, 16.7);
    assert.doesNotMatch(JSON.stringify(report), /secret|internal/);
    assert.match(publicReportText(report), /Average simultaneous non-AFK accounts/);
    assert.match(publicReportText(report), /Hatched cells are withheld or unavailable/);
    assert.match(publicReportText(report), /hours 00 through 23 in Pacific Time \(Vancouver\)/);
});

test('heatmap pages reflow when disabled or without publishable positive activity', () => {
    const data = heatmapFixture();
    const onlyHeatmap = createPublicReport(data, {summary: false, daily: false, retention: false});
    assert.deepEqual(onlyHeatmap.pages.map(page => page.id), ['busy-times']);
    const disabled = createPublicReport(data, {busy_times: false});
    assert.deepEqual(disabled.pages.map(page => page.id), ['activity', 'return']);
    assert.doesNotMatch(JSON.stringify(disabled), /busy_times|average_active_players/);
    assert.doesNotMatch(publicReportText(disabled), /busiest|Hatched/);
    data.busy_times.cells.forEach((cell, index) => { cell.average_active_players = index % 2 ? 0 : null; });
    assert.equal(availableReportSections(data).busy_times, false);
    assert.deepEqual(createPublicReport(data).pages.map(page => page.id), ['activity', 'return']);
});

test('disabled sections are absent from both rendered spec and companion exports', () => {
    const report = createPublicReport(fixture(), {summary: false, retention: false});
    assert.equal(report.pages.length, 1);
    assert.deepEqual(report.pages[0].cards, []);
    assert.doesNotMatch(JSON.stringify(report), /newcomers|retention|eligible|returned/);
    assert.doesNotMatch(publicReportText(report), /Newly observed|first week/);
    assert.deepEqual(createPublicReport(fixture(), {summary: false, daily: false, retention: false}).pages, []);
});
