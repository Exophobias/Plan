import test from 'node:test';
import assert from 'node:assert/strict';
import {canViewStore, DAILY_AMOUNTS, periodChange, STORE_PERMISSION, storeMoney, storeSeries, validateStore} from '../src/util/storeAnalytics.js';
import {storeFixture} from './store.fixture.js';

const staff = {authLoaded: true, loggedIn: true, user: {permissions: [STORE_PERMISSION]}};
test('financial aggregate requires exact grant, current authentication and excludes static exports', () => {
    assert.equal(canViewStore(staff), true);
    for (const auth of [undefined, {}, {...staff, authLoaded: false}, {...staff, loggedIn: false},
        {...staff, user: {permissions: ['page.server', 'page', 'page.server.referrals', 'page.server.store.extra', '*']}}]) {
        assert.equal(canViewStore(auth), false);
    }
    assert.equal(canViewStore(staff, true), false);
});
test('single and multi-currency reports retain source amounts without adding currencies', () => {
    for (const currency of ['USD', 'CAD', null]) for (const days of [30, 90]) {
        const data = storeFixture(currency, days);
        assert.equal(validateStore(data), data);
        if (currency === null) {
            assert.equal(data.summary.order_amount_minor, null);
            assert.ok(data.daily.every(row => DAILY_AMOUNTS.every(key => row[key] === null)));
        }
    }
    assert.match(storeMoney(50, 'USD'), /USD.*0\.50/);
    assert.match(storeMoney(50, 'JPY'), /JPY.*0\.50/, 'source contract is two decimal minor units');
    assert.equal(storeMoney(500, null), '—');
    assert.equal(storeMoney(null, 'USD'), '—');
});
test('empty or malformed money cannot become a plausible zero', () => {
    for (const amount of [undefined, '50', -1, .5, Infinity, Number.MAX_SAFE_INTEGER + 1]) {
        const data = storeFixture(); data.daily[0].refund_minor = amount;
        assert.throws(() => validateStore(data));
    }
});
test('incomplete days produce real gaps rather than zero-value activity', () => {
    const data = storeFixture(); data.period.complete = false; data.daily[0].covered = false;
    data.daily[1].saved_orders = 0;
    assert.equal(validateStore(data), data);
    const series = storeSeries(data.daily);
    assert.equal(series[0].data[0], null);
    assert.equal(series[0].data[1], 0);
    assert.equal(series[0].connectNulls, false);
    data.period.complete = true;
    assert.throws(() => validateStore(data));
});
test('trend amounts use cents and do not net orders against payment transitions', () => {
    const data = storeFixture(); const series = storeSeries(data.daily, true);
    assert.equal(series[0].data[0], 22.5); assert.equal(series[1].data[0], 15);
    assert.equal(series[1].name, 'Completion transitions');
});
test('comparisons require complete equally measured periods and nonzero denominator', () => {
    assert.equal(periodChange(150, 100, true, true), .5);
    assert.equal(periodChange(0, 100, true, true), -1);
    for (const [current, prior, complete, previousComplete] of [[1, 0, true, true], [1, 1, false, true],
        [1, 1, true, false], [null, 1, true, true], [1, null, true, true], [Infinity, 1, true, true]]) {
        assert.equal(periodChange(current, prior, complete, previousComplete), null);
    }
});
test('UTC windows reject duplicate, missing, reordered and out-of-period daily rows', () => {
    for (const mutate of [data => data.daily.pop(), data => data.daily.reverse(),
        data => data.daily[1].date = data.daily[0].date, data => data.period.from++,
        data => data.period.days = 31, data => data.period.time_zone = 'local']) {
        const data = storeFixture(); mutate(data); assert.throws(() => validateStore(data));
    }
});
test('response cannot mix a selected currency with other product or snapshot rows', () => {
    for (const mutate of [data => data.currencies[0].currency = 'CAD', data => data.products[0].currency = 'CAD',
        data => data.available_currencies.push('USD'), data => data.currencies.push(data.currencies[0]),
        data => data.currencies[0].payments.push(data.currencies[0].payments[0])]) {
        const data = storeFixture(); mutate(data); assert.throws(() => validateStore(data));
    }
});
test('all-currency period cannot carry a combined amount', () => {
    const data = storeFixture(null); data.daily[0].order_amount_minor = 100;
    assert.throws(() => validateStore(data));
    data.daily[0].order_amount_minor = null; data.summary.order_amount_minor = 100;
    assert.throws(() => validateStore(data));
});
test('invalid denominators and uncertain payment counts fail validation', () => {
    for (const mutate of [data => data.summary.repeat_payers = 71, data => data.summary.known_payers = 91,
        data => data.summary.unknown_currency_orders = 91, data => data.currencies[0].payments[0].unknown_amount_count = 71]) {
        const data = storeFixture(); mutate(data); assert.throws(() => validateStore(data));
    }
});
test('all collection states can retain explicitly incomplete observations', () => {
    for (const status of ['unavailable', 'collecting', 'current', 'stale', 'paused']) {
        const data = storeFixture(); data.coverage.status = status; data.period.complete = false;
        assert.equal(validateStore(data), data);
    }
});
