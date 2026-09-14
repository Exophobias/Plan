export const STORE_PERMISSION = 'page.server.store';
export const DAY = 86400000;
export const DAILY_AMOUNTS = ['order_amount_minor', 'completed_amount_minor', 'refund_minor',
    'reversal_minor', 'credit_awarded_minor', 'credit_captured_minor'];

export function canViewStore(auth, exported = false) {
    return !exported && auth?.authLoaded === true && auth?.loggedIn === true
        && Array.isArray(auth.user?.permissions) && auth.user.permissions.includes(STORE_PERMISSION);
}

const count = value => Number.isSafeInteger(value) && value >= 0;
const currency = value => typeof value === 'string' && /^[A-Z]{3}$/.test(value);
const nullableCurrency = value => value === null || currency(value);
const optionalCount = value => value === null || count(value);
const text = (value, max) => typeof value === 'string' && value.length > 0 && value.length <= max;
const unique = (rows, key) => new Set(rows.map(key)).size === rows.length;

export function validateStore(data) {
    const invalid = () => { throw new Error('Store analytics returned an unsupported or incomplete response.'); };
    if (!data || data.schema_version !== 1 || !count(data.generated_at)
        || !text(data.server_uuid, 36) || !data.period || !data.coverage) invalid();
    const p = data.period, c = data.coverage;
    if (![30, 90].includes(p.days) || !count(p.from) || !count(p.until)
        || p.until - p.from !== p.days * DAY || p.from % DAY !== 0 || p.until % DAY !== 0
        || p.time_zone !== 'UTC' || !nullableCurrency(p.currency)
        || typeof p.complete !== 'boolean' || typeof p.previous_complete !== 'boolean') invalid();
    if (!['unavailable', 'collecting', 'current', 'stale', 'paused'].includes(c.status)
        || !['source_as_of', 'received_at', 'capture_started_at', 'legacy_snapshot_at'].every(key => optionalCount(c[key]))
        || !count(c.legacy_records) || typeof c.adjustments_complete !== 'boolean'
        || !Array.isArray(data.available_currencies) || data.available_currencies.length > 200
        || !data.available_currencies.every(currency) || !unique(data.available_currencies, value => value)) invalid();
    const checkSummary = summary => {
        if (!summary || !['saved_orders', 'known_payers', 'repeat_payers', 'baseline_orders', 'unknown_currency_orders'].every(key => count(summary[key]))
            || !optionalCount(summary.order_amount_minor) || summary.known_payers > summary.saved_orders
            || summary.repeat_payers > summary.known_payers || summary.unknown_currency_orders > summary.saved_orders
            || (p.currency === null && summary.order_amount_minor !== null)) invalid();
    };
    checkSummary(data.summary); checkSummary(data.previous_summary);
    if (!Array.isArray(data.currencies) || data.currencies.length > 201 || !unique(data.currencies, row => row.currency)
        || !Array.isArray(data.daily) || data.daily.length !== p.days
        || !Array.isArray(data.products) || data.products.length > 50) invalid();
    data.currencies.forEach(row => {
        if (!nullableCurrency(row.currency) || !count(row.saved_orders) || !optionalCount(row.order_amount_minor)
            || (p.currency !== null && row.currency !== p.currency)
            || !Array.isArray(row.payments) || row.payments.length > 100 || !row.adjustments || !row.credits
            || !['refund_minor', 'reversal_minor', 'count'].every(key => count(row.adjustments[key]))
            || !['awarded_minor', 'reserved_minor', 'captured_minor', 'released_minor', 'refunded_minor'].every(key => count(row.credits[key]))) invalid();
        row.payments.forEach(payment => {
            if (!text(payment.status, 40) || !text(payment.origin, 80) || !count(payment.count)
                || !optionalCount(payment.amount_minor) || !count(payment.unknown_amount_count)
                || payment.unknown_amount_count > payment.count) invalid();
        });
        if (!unique(row.payments, payment => `${payment.status}:${payment.origin}`)) invalid();
    });
    data.daily.forEach((row, index) => {
        if (row.date !== new Date(p.from + index * DAY).toISOString().slice(0, 10)
            || typeof row.covered !== 'boolean' || !count(row.saved_orders) || !count(row.completed_payments)
            || !DAILY_AMOUNTS.every(key => optionalCount(row[key]))
            || (p.currency === null && DAILY_AMOUNTS.some(key => row[key] !== null))) invalid();
    });
    if (p.complete && data.daily.some(row => !row.covered)) invalid();
    data.products.forEach(row => {
        if (!text(row.name, 255) || !nullableCurrency(row.currency) || !count(row.quantity)
            || !count(row.order_count) || !optionalCount(row.amount_minor)
            || (p.currency !== null && row.currency !== p.currency)) invalid();
    });
    return data;
}

// Store's source contract explicitly defines two decimal minor units for every exported currency.
export const storeMoney = (minor, code) => minor === null || minor === undefined || !code ? '—'
    : new Intl.NumberFormat(undefined, {style: 'currency', currency: code, currencyDisplay: 'code',
        minimumFractionDigits: 2, maximumFractionDigits: 2}).format(minor / 100);

export function periodChange(current, previous, complete, previousComplete) {
    if (!complete || !previousComplete || !Number.isFinite(current) || !Number.isFinite(previous) || previous <= 0) return null;
    return (current - previous) / previous;
}

export function storeSeries(daily, monetary = false) {
    return (monetary ? [['order_amount_minor', 'Saved order value', '#b8974d'],
        ['completed_amount_minor', 'Completion transitions', '#559fc7']]
        : [['saved_orders', 'Saved orders', '#b8974d'], ['completed_payments', 'Completion transitions', '#559fc7']])
        .map(([key, name, color]) => ({name, color, type: 'line', connectNulls: false,
            data: daily.map(row => row.covered && row[key] !== null ? row[key] / (monetary ? 100 : 1) : null)}));
}

export const statusLabel = value => ({pending: 'Pending', completed: 'Completed', refunded: 'Refunded',
    reversed: 'Reversed', denied: 'Denied', unknown: 'Unknown'}[String(value).toLowerCase()] || value);
export const originLabel = value => ({paypal: 'PayPal', stripe: 'Stripe', manual: 'Staff recorded',
    api: 'API recorded', credit_only: 'Credit only', free: 'No payment required', zero_total: 'Zero total',
    credits_legacy: 'Legacy credit payment', unknown: 'Unknown origin', legacy_unknown: 'Historical origin unknown', manual_or_free: 'Manual / free / credit'}[value] || value.replaceAll('_', ' '));
