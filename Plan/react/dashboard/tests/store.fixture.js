// Synthetic fixtures only; never imported by production source.
const DAY = 86400000;
export function storeFixture(selectedCurrency = 'USD', days = 30) {
    const until = Date.UTC(2026, 8, 14), from = until - days * DAY;
    const summary = {saved_orders: 90, known_payers: 70, repeat_payers: 15, baseline_orders: 12,
        unknown_currency_orders: 0, order_amount_minor: selectedCurrency ? 67500 : null};
    return {
        schema_version: 1, server_uuid: '00000000-0000-0000-0000-000000000001', generated_at: until + DAY / 2,
        period: {days, from, until, time_zone: 'UTC', currency: selectedCurrency, complete: true, previous_complete: true},
        coverage: {status: 'current', source_as_of: until + DAY / 2, received_at: until + DAY / 2,
            capture_started_at: until - 365 * DAY, legacy_snapshot_at: until - 365 * DAY, legacy_records: 20, adjustments_complete: false},
        available_currencies: ['CAD', 'USD'], summary,
        previous_summary: {...summary, saved_orders: 60, known_payers: 40, repeat_payers: 8, order_amount_minor: selectedCurrency ? 45000 : null},
        daily: Array.from({length: days}, (_, i) => ({date: new Date(from + i * DAY).toISOString().slice(0, 10),
            covered: true, saved_orders: 3, completed_payments: 2, order_amount_minor: selectedCurrency ? 2250 : null,
            completed_amount_minor: selectedCurrency ? 1500 : null, refund_minor: selectedCurrency ? 0 : null,
            reversal_minor: selectedCurrency ? 0 : null, credit_awarded_minor: selectedCurrency ? 50 : null,
            credit_captured_minor: selectedCurrency ? 100 : null})),
        currencies: (selectedCurrency ? [selectedCurrency] : ['CAD', 'USD', null]).map(currency => ({currency,
            saved_orders: 110, order_amount_minor: currency ? 82500 : null,
            payments: [
                {status: 'completed', origin: 'paypal', count: 70, amount_minor: 50000, unknown_amount_count: 0},
                {status: 'pending', origin: 'stripe', count: 10, amount_minor: 7500, unknown_amount_count: 0},
                {status: 'completed', origin: 'credit_only', count: 4, amount_minor: 0, unknown_amount_count: 0},
                {status: 'completed', origin: 'manual', count: 3, amount_minor: 2000, unknown_amount_count: 0},
                {status: 'reversed', origin: 'unknown', count: 1, amount_minor: null, unknown_amount_count: 1}
            ], adjustments: {count: 2, refund_minor: 1000, reversal_minor: 500},
            credits: {awarded_minor: 3000, reserved_minor: 500, captured_minor: 1000, released_minor: 500, refunded_minor: 500}})),
        products: [{name: 'Patriam supporter rank', currency: selectedCurrency || 'USD', quantity: 50, order_count: 40, amount_minor: 50000},
            {name: 'A long product name <script>alert("text only")</script> & gift bundle', currency: selectedCurrency || 'CAD', quantity: 40, order_count: 35, amount_minor: 17500}]
    };
}
