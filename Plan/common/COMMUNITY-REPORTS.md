# Community Chronicle reports

The private server Reports page generates dated public PNGs, text and matching aggregate JSON.
It requires authentication, explicit native `page.server.reports`, and `access.server`. Parent
page permissions, forum self access, anonymous mode and static dashboard exports do not grant it.
Generation does not publish to Discord or the website.

## Measurement contract (schema 2)

Requests to `/v1/community-report` take `server`, `start` and exclusive `end`, as ISO Vancouver calendar
dates. The UI includes both selected dates and converts the end. Ranges are 1–366 days. Today is
partial through confirmed observations. `period.recorded_from` and `as_of` identify the actual
included times; a pre-collection start is clipped and labelled partial. Internal coverage gaps
withhold totals rather than silently treating unknown activity as zero.

Dates use `America/Vancouver`, labelled **Pacific Time (Vancouver)**. This applies to selection,
midnight boundaries, daily activity and return cohorts; stored instants remain UTC epoch milliseconds.
Schema 2 replaces schema 1's UTC calendar interpretation, so older clients must refresh. A current
month is complete only after its final Vancouver day ends; earlier requests are month-to-date.
Following [B.C.'s March 2026 decision](https://news.gov.bc.ca/releases/2026AG0013-000209), Vancouver
stays at UTC-7 from 2026-03-08T10:00:00Z, including November. Both Java and browser helpers pin this
rule for runtimes with older timezone data. Earlier dates use historical IANA Vancouver rules,
including 23/25-hour transition days; no fixed 24-hour arithmetic splits measured intervals.

- Participants: distinct account UUIDs with at least five measured active minutes in the period.
  Accounts are not guaranteed distinct humans. Character changes do not create new participants.
- Newly observed players: participating accounts whose first community observation lies within
  the period. This collector does not import Bukkit's first-played date. Installing it or resetting
  its database begins a new observed population; do not present that as all-time human acquisition.
- Active hours: union of Plan-classified non-AFK intervals per account, clipped to the period.
  Recent unresolved time is withheld. This is Plan's AFK classification, not proof of attention.
- Peak online: peak distinct overlapping recorded sessions, with simultaneous departures and
  arrivals combined at their timestamp. It is a connected count, not the five-minute cohort size.
- Daily participants: the five-minute rule applied independently each Vancouver day. Daily unique counts
  are not added to obtain period uniques. A real observed zero day stays on the chart.
- First-week return: accounts first observed in the requested period whose next seven Vancouver calendar
  days have elapsed with complete coverage. A return requires five active minutes on at least one
  day numbered 1–7 after the first-observation date. This is not exact D7 retention. The distinct
  return observation cutoff is included in the exported page and text.

The generation response contains aggregate metrics and private omission explanations; it never
contains account identities, characters, locations, money, faction membership or other private
plugin data. Public export uses an explicit allowlist and excludes omission diagnostics. One
response drives the preview PNG, downloaded PNG, public text and companion data.

## Selection and disclosure

The report provides activity, weekly busy-times and return pages. Unavailable gameplay/event histories and
editorial integrations are not placeholders. Missing or irrelevant zero summary cards disappear;
operators can hide eligible sections and the canvas reflows. An empty result produces no image.

Overview requires ten qualifying accounts. New/established subdivisions require at least five in
both cells. Active hours are withheld when one account contributes more than half. The daily chart
requires a complete multi-day period and no positive daily cell smaller than five; otherwise the
whole chart is omitted. Return publication requires at least ten eligible accounts and five in
both returned and not-returned cells. These are conservative product defaults, not a mathematical
anonymity guarantee. Operators must review combined figures and previously shared overlapping
ranges before publication; public arbitrary-range queries are not exposed.

The busy-times heatmap requires a complete period of at least seven local days and ten qualifying
period participants. Its 168 Monday-to-Sunday hourly cells show **average simultaneous active
accounts**: unioned non-AFK account milliseconds divided by the actual wall milliseconds of all
matching weekday/hour occurrences in the selected range. Repeated historical autumn hours count
twice in both numerator and denominator; a skipped spring hour has no exposure. A positive cell
needs five accounts with at least five active minutes in that cell across the period and no account
contributing more than half the cell's activity. Small/concentrated/unobserved cells are null,
recorded quiet cells are zero, and a heatmap with no publishable positive cells disappears entirely.
Public `busy_times` contains only `metric: average_active_players` and cells with `day` (Monday=1),
`hour` (0–23) and nullable `average_active_players`; identities and cell population counts stay private.
Renderer 3 uses a dark-green-to-bright-mint scale for increasing activity. Recorded quiet cells
remain navy and withheld/unavailable cells remain hatched. The measurement schema remains 2.

## Collection and persistence

Community activity independently receives the existing AFK classifier signal. It records every
observed account without the referral collector's first-join eligibility or 35-day limit. Tables
`plan_community_members`, `plan_community_activity`, `plan_community_coverage`,
`plan_community_deleted` and `plan_community_stops` form the durable history. Existing session
rows cannot backfill exact historical interval placement. No gameplay/event counter history is
invented from current plugin snapshots.

Collection flushes every 30 seconds. Bridge API .5 integration forwards the raw publication/test
pause even when referral/website features are disabled. Pauses, failures and uncertain restarts
leave coverage gaps. A final flush at a platform-confirmed real shutdown can certify the offline
interval only when a newer distinct process resumes. A native database restore must clear
`plan_community_stops` before startup; a restored stop receipt cannot prove subsequent downtime.
Built-in database transfers omit and clear those process-local proofs automatically.

Routine inactive-player cleanup preserves this history. Explicit player erasure deletes the
member/session rows and adds a tombstone so queued work cannot recreate them. Full database
reset clears community history and tombstones. Built-in copy preserves the dataset coherently and
rejects unsafe merging. Recomputed reports can change after explicit erasure; retain published
aggregate JSON/PNG when historical publication reproducibility is required.

Reports run on a separate single worker with no queued requests and a 20-second caller deadline.
Source reads use a consistent database transaction with row/JSON bounds and reject oversize
requests rather than truncate. Raw history has no automatic expiry; monitor its growth and plan
archival before capacity limits are approached. No new operator config schema is introduced.

## Release scope

This release implements the first activity/return slice. Comparison periods, custom gameplay
event feeds, editorial screenshot pages, website publication and scheduled month-end drafts
remain separate additions. It changes neither referral eligibility nor rewards. Start accurate
recording before the intended pre-release database reset/launch. Do not reset a live database
as part of installing these code changes.
