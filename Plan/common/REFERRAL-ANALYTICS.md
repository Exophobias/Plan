# Referral analytics backend

The in-process `ReferralAnalyticsService` API accepts the trusted website journal through the
Nameless Bridge. Plan has no additional credentials or operator settings for this integration.
Capture starts paused. The Bridge supplies a synchronous pause observer and only admits capture
when its referral integration is enabled. Fixture pauses remain unknown observation periods.

The authenticated `/v1/referrals?server=...` endpoint requires the exact
`page.server.referrals` grant and native `access.server` authority. Public mode is always denied.
Existing groups with the exact `manage.groups` permission receive the explicit new grant
additively. The endpoint returns aggregates, never player UUIDs, referral codes, provider data or
moderation reasons. Responses are private and cannot be statically exported.

## Measurement contract

- Cohorts use Plan's first recorded server join, grouped by Monday-start UTC weeks.
- The primary comparator freezes at 24 hours after first join. A known claim before that deadline,
  including a pre-join claim, is recorded referral exposure regardless of later claim outcome.
  Later claims remain in the no-referral-in-first-24-hours comparator. The late-claim group is an
  overlapping descriptive subset; its count must not be added to the primary groups.
- No-claim attribution requires a caught-up website watermark covering the entire 24-hour window.
  Legacy attribution and first joins before journal coverage stay unknown.
- Return activity requires at least five minutes of Plan non-AFK time. D1/D7/D30 use elapsed
  24-hour windows `[1,2)`, `[7,8)`, `[30,31)` days after join. W1/W2/W4 use `[7,14)`, `[14,21)`,
  `[28,35)`. Only fully elapsed, fully covered windows enter denominators. Wilson 95% intervals
  accompany rates. Zero eligible samples serialize rates and medians as explicit JSON null.
- Seven- and thirty-day medians include covered inactive players. Active days are join-anchored
  24-hour periods containing at least five minutes of non-AFK activity.
- The funnel counts all recorded claims, including historical records, independently from the
  prospective activity cohort. Acceptance and qualification timestamps mean website processing.
- Credit obligation amounts and currencies are immutable; delivered amounts require website
  delivery proof. Totals are kept separately by currency. Per-retained-player numerators use the
  same W1-eligible acquired cohort, with an explicitly shared all-acquired denominator for each
  currency. These amounts are granted credit, not cash expenditure or causal return on investment.
- Post-reward return measures newcomer activity during `[issued_at+1day, issued_at+8days)` after
  the referrer's earliest known actual Store credit posting for that claim/currency. Legacy
  delivered credit without a posting timestamp has unknown timing. Website acknowledgement time
  is never substituted for posting time. Windows beyond recorded activity coverage are incomplete.

## Persistence and observation coverage

`plan_referral_*` tables belong to Plan. The member roster and activity have no foreign keys to
expiring raw user/session tables, so routine inactive-player cleanup preserves cohort denominators.
Explicit player removal erases their analytics history and keeps only suppression tombstones to
prevent the trusted feed reintroducing it; full database removal also clears those tombstones.

Exact active intervals are recorded prospectively from Plan's AFK decisions. The manual AFK command
first resolves existing idle time so it cannot retrospectively convert an old idle gap into active
time. Historical session AFK totals are never distributed across invented day boundaries.
Active and pending-logout snapshots are captured coherently and committed with collection coverage
every 30 seconds. Coverage normally lags by the AFK threshold while recent idle time is unresolved.
Only the first 35 days after each cohort member's join are retained as activity history.

A trusted Paper adapter may call `prepareServerShutdown()` only while the actual server is stopping,
before pausing publication. The adapter awaits completion and cancels on timeout. A final activity
flush, coverage endpoint and clean-stop proof commit together. The proof expires after 14 seconds
while queued and checks capture generation/cancellation. Only a genuinely new JVM process may
consume it to classify the intervening offline period as known zero. Hot reloads, crashes, unknown
gaps and fixture pauses are not inferred as downtime.

The journal uses a persistent stream identity and exact contiguous cursor compare-and-set. The
latest projection validates only touched entities; empty heartbeats do not scan history. Immutable
claim identity, first acceptance/qualification, terminal states and award facts cannot be rewritten.
Every new persistence boundary checks `Transaction.wasSuccessful()` because Plan's database futures
can otherwise complete normally after logging a transaction failure.

Reports are precomputed on a dedicated background executor. Requests only read a cached generation
and perform a small server-existence check. Feed freshness allows ten minutes for the five-minute
poll and scheduler jitter; activity flush freshness allows five minutes. Empty/unavailable states
remain explicit. Bounded limits refuse an oversized report rather than silently truncate it:
50,000 members, 200,000 sessions/latest entities, 100,000 coverage segments, 16,384 intervals per
session, and a 32 MiB serialized interval budget per report. Reaching a bound requires a deliberate
aggregate archival migration; it does not grant permission to delete cohort history.

## Native database verification

The normal upstream MySQL test fixture mutates global database settings and recreates its database.
Run it only against a dedicated disposable MariaDB/MySQL instance with no shared volumes or data.
Set `MYSQL_DB=codex_plan_referrals_<suffix>`, `MYSQL_USER`, `MYSQL_PASS`, and `MYSQL_PORT` (the
local forwarded port). The database must already exist before the fixture connects and recreates
it. Run `:common:test --tests '*MySQLTest.referral*'`. The test operator owns provisioning,
credential handling, tunnel lifetime and cleanup; these tests must never target the live Plan DB.
