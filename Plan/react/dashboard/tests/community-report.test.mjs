import test from 'node:test';
import assert from 'node:assert/strict';
import {availableReportSections, canViewReports, createPublicReport, DAY, formatReportNumber, parseReportDate,
    publicReportText, REPORT_PERMISSION, reportPreset, reportRange, validateCommunityReport} from '../src/util/communityReport.js';

// Synthetic aggregate values only. This fixture contains no server or account records.
function fixture() {
    return {schema_version: 1, status: 'ready',
        period: {start_date: '2026-08-01', end_date: '2026-08-04', timezone: 'UTC',
            recorded_from: Date.parse('2026-08-01T00:00:00Z'), as_of: Date.parse('2026-08-04T00:00:00Z'), complete: true},
        summary: {participants: 30, newcomers: 12, active_hours: 250.5, peak_online: 18},
        daily: [15, 0, 25].map((participants, index) => ({date: `2026-08-0${index + 1}`, participants,
            active_hours: participants * 2, covered: true})),
        retention: {eligible: 12, returned: 6, rate: .5, observed_through: Date.parse('2026-08-12T00:00:00Z')},
        omissions: []};
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

test('UTC presets and inclusive requests survive leap days, month/year rollover and DST dates', () => {
    assert.deepEqual(reportPreset('month', Date.parse('2024-03-31T23:00:00-07:00')),
        {start: '2024-03-01', end: '2024-03-31'});
    assert.deepEqual(reportPreset('month', Date.parse('2024-03-01T00:00:00Z')),
        {start: '2024-02-01', end: '2024-02-29'});
    assert.deepEqual(reportPreset('yesterday', Date.parse('2026-01-01T00:00:00Z')),
        {start: '2025-12-31', end: '2025-12-31'});
    assert.deepEqual(reportRange('2026-03-08', '2026-03-08', Date.parse('2026-03-09T00:00:00Z')),
        {start: '2026-03-08', end: '2026-03-09'});
    assert.deepEqual(reportRange('2024-01-01', '2024-12-31', Date.parse('2025-01-01T00:00:00Z')),
        {start: '2024-01-01', end: '2025-01-01'});
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
    for (const mutate of [d => d.schema_version = 2, d => d.period.timezone = 'local', d => d.period.as_of = NaN,
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
    assert.deepEqual(availableReportSections(data), {summary: true, daily: false, retention: false});
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

test('disabled sections are absent from both rendered spec and companion exports', () => {
    const report = createPublicReport(fixture(), {summary: false, retention: false});
    assert.equal(report.pages.length, 1);
    assert.deepEqual(report.pages[0].cards, []);
    assert.doesNotMatch(JSON.stringify(report), /newcomers|retention|eligible|returned/);
    assert.doesNotMatch(publicReportText(report), /Newly observed|first week/);
    assert.deepEqual(createPublicReport(fixture(), {summary: false, daily: false, retention: false}).pages, []);
});
