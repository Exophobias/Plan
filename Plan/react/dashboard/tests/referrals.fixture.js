// Synthetic UI/test data only; this file is outside the production source bundle.
export function referralsFixture() {
    const generated = Date.UTC(2026, 8, 14, 12);
    const groups = ['recorded_referral', 'no_recorded_referral', 'late_referral', 'unknown'];
    const comparison = groups.map((group, index) => ({
        group, players: [12, 24, 4, 2][index],
        retention: ['D1', 'D7', 'D30', 'W1', 'W2', 'W4'].map(window => ({
            window, eligible: index === 3 || window === 'W4' || window === 'D30' ? 0 : [10, 16, 2][index],
            retained: index === 3 || window === 'W4' || window === 'D30' ? 0 : [6, 4, 0][index],
            incomplete: window === 'W4' || window === 'D30' || index === 3 ? [12, 24, 4, 2][index] : [2, 8, 2, 2][index],
            rate: index === 3 || window === 'W4' || window === 'D30' ? null : [.6, .25, 0][index],
            lower: index === 3 || window === 'W4' || window === 'D30' ? null : [.31, .10, 0][index],
            upper: index === 3 || window === 'W4' || window === 'D30' ? null : [.83, .50, .66][index]
        })),
        activity: [7, 30].map(days => ({days, eligible: days === 30 ? 0 : [10, 16, 2, 0][index],
            incomplete: days === 30 ? [12, 24, 4, 2][index] : [2, 8, 2, 2][index],
            median_minutes: days === 30 || index === 3 ? null : [82, 25, 0][index],
            median_active_days: days === 30 || index === 3 ? null : [3, 1, 0][index]}))
    }));
    return {
        schema_version: 1, server_uuid: '00000000-0000-0000-0000-000000000001', generated_at: generated,
        coverage: {status: 'collecting', started_at: generated - 21 * 86400000, activity_through: generated,
            referral_through: generated, unknown_gaps: 2, attribution_hours: 24, return_minimum_minutes: 5},
        summary: {players: 38, recorded_referral: 12, no_recorded_referral: 24, late_referral: 4, unknown: 2, late_referral_is_subset: true},
        comparison, cohorts: [{week: '2026-08-24', groups: structuredClone(comparison)},
            {week: '2026-08-31', groups: structuredClone(comparison)}],
        funnel: {scope: 'all_recorded_claims', requested: 16, accepted: 12, qualified: 8, rewarded: 7, verifying: 2, pending: 4, rejected: 1, expired: 1},
        credit: [{currency: 'USD', window: 'W1', granted_minor: 350, delivered_minor: 300,
            cohort_granted_minor: 250, cohort_delivered_minor: 200,
            denominator_scope: 'all_recorded_referral_players',
            retained_players: 6, eligible: 10, incomplete: 2, granted_minor_per_retained: 250 / 6, delivered_minor_per_retained: 200 / 6,
            post_reward: {window: 'days1_to8_after_issue', eligible: 4, retained: 2, incomplete: 2, unknown_timing: 1, rate: .5}},
            {currency: 'CAD', window: 'W1', granted_minor: 100, delivered_minor: 100,
                cohort_granted_minor: 100, cohort_delivered_minor: 100,
                denominator_scope: 'all_recorded_referral_players',
                retained_players: 6, eligible: 10, incomplete: 2, granted_minor_per_retained: 100 / 6, delivered_minor_per_retained: 100 / 6,
                post_reward: {window: 'days1_to8_after_issue', eligible: 0, retained: 0, incomplete: 1, unknown_timing: 0, rate: null}}],
        raffle: {granted_entries: 40, delivered_entries: 35}
    };
}
