export const REFERRALS_PERMISSION = 'page.server.referrals';
export const REFERRAL_GROUPS = ['recorded_referral', 'no_recorded_referral', 'late_referral', 'unknown'];
export const REFERRAL_WINDOWS = {weekly: ['W1', 'W2', 'W4'], daily: ['D1', 'D7', 'D30']};
export const GROUP_LABELS = {
    recorded_referral: 'Recorded referral', no_recorded_referral: 'No referral in first 24h',
    late_referral: 'Later claim (subset)', unknown: 'Unknown attribution'
};
export const GROUP_COLORS = ['#b8974d', '#559fc7', '#ba79ac', '#8b9299'];
export const WINDOW_LABELS = {W1: 'Days 7–13', W2: 'Days 14–20', W4: 'Days 28–34', D1: 'Day 1', D7: 'Day 7', D30: 'Day 30'};

// This aggregate is explicitly opt-in. Generic Plan permissions permit public views and parent grants.
export function canViewReferrals(auth, exported = false) {
    return !exported && auth?.authLoaded === true && auth?.loggedIn === true
        && Array.isArray(auth.user?.permissions) && auth.user.permissions.includes(REFERRALS_PERMISSION);
}

const count = value => Number.isSafeInteger(value) && value >= 0;
const fraction = value => value === null || (Number.isFinite(value) && value >= 0 && value <= 1);
const optionalNumber = value => value === null || (Number.isFinite(value) && value >= 0);

export function validateReferrals(data) {
    const invalid = () => { throw new Error('Referral analytics returned an unsupported or incomplete response.'); };
    if (!data || data.schema_version !== 1 || !count(data.generated_at) || !data.coverage
        || !['ready', 'collecting', 'unavailable', 'disabled', 'stale'].includes(data.coverage.status)
        || !data.summary || !Array.isArray(data.comparison) || !Array.isArray(data.cohorts)
        || !Array.isArray(data.credit) || !data.funnel) invalid();
    if (!['players', ...REFERRAL_GROUPS].every(key => count(data.summary[key]))) invalid();
    if (data.summary.late_referral_is_subset !== true) invalid();
    if (!['requested', 'accepted', 'qualified', 'rewarded', 'verifying', 'pending', 'rejected', 'expired'].every(key => count(data.funnel[key]))) invalid();
    if (!count(data.funnel.qualification_sample) || data.funnel.qualification_sample > data.funnel.qualified
        || !optionalNumber(data.funnel.median_qualification_hours)
        || (data.funnel.qualification_sample === 0) !== (data.funnel.median_qualification_hours === null)) invalid();
    const checkGroups = groups => {
        if (!Array.isArray(groups) || groups.length > 4 || new Set(groups.map(group => group.group)).size !== groups.length) invalid();
        groups.forEach(group => {
            if (!REFERRAL_GROUPS.includes(group.group) || !count(group.players)
                || !Array.isArray(group.retention) || !Array.isArray(group.activity)) invalid();
            if (new Set(group.retention.map(row => row.window)).size !== group.retention.length) invalid();
            group.retention.forEach(row => {
                if (!Object.hasOwn(WINDOW_LABELS, row.window) || !count(row.eligible) || !count(row.retained)
                    || row.retained > row.eligible || !count(row.incomplete)
                    || ![row.rate, row.lower, row.upper].every(fraction)
                    || (row.eligible === 0 && [row.rate, row.lower, row.upper].some(value => value !== null))
                    || (row.lower !== null && row.upper !== null && row.lower > row.upper)) invalid();
            });
            group.activity.forEach(row => {
                if (![7, 30].includes(row.days) || !count(row.eligible) || !count(row.incomplete)
                    || ![row.median_minutes, row.median_active_days].every(optionalNumber)
                    || (row.median_active_days !== null && row.median_active_days > row.days)
                    || (row.eligible === 0 && [row.median_minutes, row.median_active_days].some(value => value !== null))) invalid();
            });
        });
    };
    checkGroups(data.comparison);
    data.cohorts.forEach(cohort => {
        if (!/^\d{4}-\d{2}-\d{2}$/.test(cohort.week)) invalid();
        checkGroups(cohort.groups);
    });
    data.credit.forEach(row => {
        if (!/^[A-Z]{3}$/.test(row.currency) || !count(row.granted_minor) || !count(row.delivered_minor)
            || !count(row.cohort_granted_minor) || !count(row.cohort_delivered_minor)
            || !count(row.retained_players) || !optionalNumber(row.granted_minor_per_retained)
            || !optionalNumber(row.delivered_minor_per_retained)
            || row.window !== 'W1' || !count(row.eligible) || !count(row.incomplete) || row.retained_players > row.eligible
            || row.denominator_scope !== 'all_recorded_referral_players'
            || (row.retained_players === 0 && row.granted_minor_per_retained !== null)) invalid();
        if (row.retained_players === 0 && row.delivered_minor_per_retained !== null) invalid();
        const post = row.post_reward;
        if (!post || post.window !== 'days1_to8_after_issue' || !count(post.eligible) || !count(post.retained)
            || post.retained > post.eligible || !count(post.incomplete) || !count(post.unknown_timing)
            || !fraction(post.rate) || (post.eligible === 0 && post.rate !== null)) invalid();
    });
    return data;
}

export function retentionCell(group, window) {
    const row = group?.retention.find(value => value.window === window);
    return row && row.eligible > 0 && row.rate !== null ? row : null;
}

export function retentionSeries(comparison, windows) {
    return REFERRAL_GROUPS.flatMap((name, index) => {
        const group = comparison.find(value => value.group === name);
        if (!group) return [];
        const data = windows.map(window => {
            const row = retentionCell(group, window);
            return row ? {y: row.rate * 100, custom: row} : null;
        });
        return [{name: GROUP_LABELS[name], type: 'line', color: GROUP_COLORS[index], data, connectNulls: false}, {
            name: `${GROUP_LABELS[name]} · 95% interval`, type: 'errorbar', color: GROUP_COLORS[index],
            linkedTo: ':previous', showInLegend: false,
            data: windows.map(window => {
                const row = retentionCell(group, window);
                return row && row.lower !== null && row.upper !== null ? [row.lower * 100, row.upper * 100] : null;
            })
        }];
    });
}

export const percent = value => value === null || value === undefined ? '—' : `${(value * 100).toFixed(1)}%`;
export const amount = (minor, currency) => minor === null || minor === undefined ? '—'
    : new Intl.NumberFormat(undefined, {style: 'currency', currency, currencyDisplay: 'code', minimumFractionDigits: 2, maximumFractionDigits: 2}).format(minor / 100);
