# Store analytics

The authenticated server Store tab reads `/v1/store?server=<native-id>&days=30|90&currency=USD`.
The currency parameter is optional: all-currency mode shows counts and no combined monetary
amounts. `page.server.store` must appear explicitly in the authenticated native user's permission
list, alongside `access.server`. The new permission is added only to existing groups with native
`manage.groups` authority. Public mode, anonymous/self-only users and static exports cannot use
the endpoint. Responses are private and not cacheable by browsers/proxies.

Plan API `5.9-R0.1-patriam.4` exposes the independent `StoreAnalyticsService`. Its batch/event times
are UTC epoch **seconds**; dashboard response times are milliseconds. Financial values are Store
integer cents (two decimal places) per frozen currency, never converted at a current exchange rate.
Credentials stay in Bridge's independent schema-7 Store configuration. The API does not execute
orders, acknowledge rewards or alter balances.

The Store owns frozen orders, observed payment state transitions, PayPal adjustment records,
credit awards and credit funding. Plan validates exact schema-1 fields and sequential journal
positions, removes payer/recipient UUIDs, then transactionally persists records, projections,
deletion tombstones and the cursor. Opaque payer keys remain to count distinct/repeat ordering
customers; there are no per-customer outputs, lookups or purchase/activity correlations. Explicit
source deletion retires the entire order and its payment/funding/adjustment facts and prevents
replay from resurrecting them. Standalone credit-award records retain their own source identity.

Period panels count prospective saved orders by their frozen creation time. They are not a visitor
conversion funnel or proof of payment. Repeat ordering customers have at least two such orders
within the selected period; the previous period has equal length. Periods contain complete UTC
calendar days ending at the current UTC midnight. Comparisons require source capture before the
period begins and a caught-up watermark through its end; unavailable days remain gaps.

Daily completion counts represent recorded transitions into `completed`, including a later
recompletion after another state; identical repeated observations have no additional effect.
These use immutable transition timestamps, so a subsequent refund does not erase a prior
completion or move it to the order date. Unknown historical timestamps are not backfilled.
Payment/funding creation can stay unknown even when a new transition has a known recorded time;
that transition is prospective and does not acquire an invented historical creation date.
Current-state currency/payment tables are **all-time current observations**, independently of
the selected period. Historical baseline orders are a separate non-additive count. Product rows
use frozen product identity and label and are limited to the 50 greatest quantities in the period.

Manual/API states, credit-only orders and gateway-origin observations remain separately labelled.
They are not verified processor settlements. Recorded refund/reversal coverage is explicitly
incomplete (including unavailable Stripe partial adjustments), so no net-cash/revenue figure is
inferred. Legacy currency is used only when the source can prove it; baseline product labels may
come from the catalogue at adoption and are excluded from prospective product/timeline panels.

Imports are bounded to 100 events and 1 MiB per page; each order has at most 100 lines. Signed source
snapshots pin pages. Exact cursor/stream checks reject gaps, rebasing and changed source continuity.
Completed transaction futures are checked against actual commit success. Incomplete pages durably
set a pending flag. Normal pagination retains the last complete in-memory report; after a restart
mid-import, the UI waits in collecting/unavailable state until the same durable cursor catches up.
It never loads a partial projection as a complete financial snapshot.

Reports are prepared in a background executor; HTTP requests never scan financial history.
Only indexed effective event history for the last 180 complete UTC days is loaded, plus current
projections. Heartbeats with an unchanged cursor refresh metadata only. One report generation is
limited to 200,000 combined current/history records and 64 MiB of decoded JSON, and at most 200
currencies. Exceeding a bound produces unavailable coverage, not a truncated total. Journal records
remain durable for replay/audit. Extending those limits requires an explicitly reviewed aggregation
strategy. Data older than the UI periods is not silently included in a period result.

The four `plan_store_*` tables are initialized by Plan's native table/index lifecycle. Routine
inactive-player cleanup does not affect their independent aggregate denominators. Full Plan data
removal clears them. Plan database backup/copy preserves exact IDs, events, cursors and tombstones;
merging two populated histories or rebasing server UUIDs is refused before destination writes.
The bounded copy mechanism refuses more than 200,000 rows/64 MiB and requires a native database
backup beyond that limit. Financial completeness follows Store source watermarks; game restart or
Testing pause never proves that no financial events occurred.
