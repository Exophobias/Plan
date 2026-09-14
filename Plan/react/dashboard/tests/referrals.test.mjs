import test from 'node:test';
import assert from 'node:assert/strict';
import {amount, canViewReferrals, REFERRALS_PERMISSION, retentionCell, retentionSeries, validateReferrals} from '../src/util/referralAnalytics.js';
import {referralsFixture} from './referrals.fixture.js';
import {redirectLoadingState} from '../src/util/redirectState.js';

const staff = {authLoaded: true, loggedIn: true, user: {permissions: [REFERRALS_PERMISSION]}};

test('redirect help waits ten seconds and reported loading errors are immediately retryable', () => {
    for (const elapsed of [0, 50, 500, 9999]) assert.equal(redirectLoadingState(elapsed), 'loading');
    for (const elapsed of [10000, 30000]) assert.equal(redirectLoadingState(elapsed), 'delayed');
    for (const elapsed of [0, 500, 30000]) assert.equal(redirectLoadingState(elapsed, true), 'error');
});

test('aggregate permission requires authentication and the exact dedicated opt-in', () => {
    assert.equal(canViewReferrals(staff), true);
    for (const auth of [undefined, {}, {...staff, authLoaded: false}, {...staff, loggedIn: false},
        {...staff, user: undefined}, {...staff, user: {permissions: ['page.server', 'page', 'page.server.retention']}},
        {...staff, user: {permissions: ['page.player', 'access.player.self']}},
        {...staff, user: {permissions: ['page.server.referrals.extra']}}]) {
        assert.equal(canViewReferrals(auth), false);
    }
    assert.equal(canViewReferrals(staff, true), false, 'static exports never show this private aggregate');
    assert.equal(canViewReferrals({...staff, authRequired: false}), true, 'authenticated explicit opt-in is independent of public overview mode');
});

test('real zero retention remains distinct from incomplete observations', () => {
    const data = validateReferrals(referralsFixture());
    assert.equal(retentionCell(data.comparison[2], 'W1').rate, 0);
    assert.equal(retentionCell(data.comparison[0], 'W4'), null);
    assert.equal(retentionCell(data.comparison[3], 'W1'), null);
    assert.equal(retentionCell(undefined, 'W1'), null);
    const series = retentionSeries(data.comparison, ['W1', 'W2', 'W4']);
    assert.equal(series[0].data[0].y, 60);
    assert.equal(series[0].data[2], null);
    assert.equal(series[0].connectNulls, false);
    assert.equal(series[4].data[0].y, 0);
    assert.deepEqual(series[1].data[0], [31, 83]);
    assert.deepEqual(series[6].data, [null, null, null]);
});

test('historical currency amounts stay separate and missing denominator stays absent', () => {
    const data = validateReferrals(referralsFixture());
    assert.equal(data.credit[0].granted_minor, 350);
    assert.equal(data.credit[1].granted_minor, 100);
    assert.match(amount(data.credit[0].granted_minor, 'USD'), /USD.*3\.50/);
    assert.match(amount(data.credit[1].granted_minor, 'CAD'), /CAD.*1\.00/);
    assert.match(amount(50, 'JPY'), /JPY.*0\.50/);
    assert.equal(amount(null, 'USD'), '—');
    assert.equal(data.credit[0].granted_minor_per_retained, 250 / 6);
    assert.notEqual(data.credit[0].granted_minor_per_retained, data.credit[0].granted_minor / 6);
    assert.equal(data.credit[0].eligible, data.credit[1].eligible);
    assert.equal(data.credit[0].retained_players, data.credit[1].retained_players);
});

test('return after actual posting keeps unknown timing and incomplete windows outside rates', () => {
    const data = validateReferrals(referralsFixture());
    assert.equal(data.credit[0].post_reward.rate, .5);
    assert.equal(data.credit[0].post_reward.unknown_timing, 1);
    assert.equal(data.credit[1].post_reward.rate, null);
    assert.equal(data.credit[1].post_reward.incomplete, 1);
});

test('all collection states are explicit and preserve the response without inventing data', () => {
    for (const status of ['ready', 'collecting', 'stale', 'unavailable', 'disabled']) {
        const data = referralsFixture(); data.coverage.status = status;
        assert.equal(validateReferrals(data), data);
    }
});

test('later claims remain an overlapping subset of the frozen first-day baseline', () => {
    const data = validateReferrals(referralsFixture());
    assert.equal(data.summary.players, data.summary.recorded_referral + data.summary.no_recorded_referral + data.summary.unknown);
    assert.ok(data.summary.late_referral > 0);
    assert.ok(data.summary.no_recorded_referral >= data.summary.late_referral);
    assert.equal(retentionSeries(data.comparison, ['W1'])[2].name, 'No referral in first 24h');
    assert.equal(retentionSeries(data.comparison, ['W1'])[4].name, 'Later claim (subset)');
});

const corruptions = {
    'future schema': data => data.schema_version++,
    'missing coverage': data => delete data.coverage,
    'missing count': data => delete data.summary.unknown,
    'outdated disjoint attribution': data => delete data.summary.late_referral_is_subset,
    'missing collection status': data => delete data.coverage.status,
    'invented group': data => data.comparison[0].group = 'organic',
    'duplicate group': data => data.comparison.push(data.comparison[0]),
    'duplicate return window': data => data.comparison[0].retention.push(data.comparison[0].retention[0]),
    'negative sample': data => data.comparison[0].retention[0].eligible = -1,
    'retained greater than eligible': data => data.comparison[0].retention[0].retained = 99,
    'incomplete zero presented as observed rate': data => data.comparison[0].retention[2].rate = 0,
    'invalid interval': data => data.comparison[0].retention[0].upper = .1,
    'infinite activity': data => data.comparison[0].activity[0].median_minutes = Infinity,
    'missing activity presented as zero': data => data.comparison[0].activity[1].median_minutes = 0,
    'too many active days': data => data.comparison[0].activity[0].median_active_days = 8,
    'invalid currency': data => data.credit[0].currency = '<script>',
    'unsafe money integer': data => data.credit[0].granted_minor = Number.MAX_SAFE_INTEGER + 1,
    'wrong credit cohort window': data => data.credit[0].window = 'D7',
    'zero credit denominator with rate': data => data.credit[0].retained_players = 0,
    'missing credit sample count': data => delete data.credit[0].eligible,
    'wrong credit denominator': data => data.credit[0].denominator_scope = 'qualified_only',
    'missing posting time presented as zero return': data => data.credit[1].post_reward.rate = 0
};
for (const [name, corrupt] of Object.entries(corruptions)) {
    test(`unsupported response fails closed: ${name}`, () => {
        const data = referralsFixture(); corrupt(data);
        assert.throws(() => validateReferrals(data), /unsupported or incomplete/);
    });
}
