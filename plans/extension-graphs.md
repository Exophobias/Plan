# Extension graphs

Added 2026-08-04 on the Patriam fork. Read this before touching the extension storage or the extension
card again.

## What it is

Upstream Plan's extension API draws values and tables and nothing else. `ElementOrder.GRAPH` existed in the
enum and was documented as "Represents graphs", but the renderer in `ExtensionCard.jsx` switched on `VALUES`
and `TABLE` only and returned `''` for anything else, so declaring it rendered nothing. It was a reserved slot
that had never been filled.

This fills it. A server-level provider sets `graphed = true`:

```java
@NumberProvider(text = "Gold in circulation", graphed = true, ...)
public long goldSupply() { ... }
```

Plan then keeps that value's history and the tab draws it as a line.

## Why this design and not the obvious one

The obvious design is a `@GraphProvider` that returns a series, stored as a snapshot the way a `Table` is. It
mirrors an existing pattern exactly, and it was rejected: it only works for a plugin that already keeps a
history, and most do not. PatriamFlora's counters are static `AtomicLong`s that zero on restart; Powderkeg
persists cumulative totals only. Every plugin would have needed new persistence before it could draw anything.

Plan, meanwhile, already gathers every server value on a schedule and throws the previous one away. Keeping
them instead makes every existing number plottable with no plugin persistence at all, which is why the first
three graphs cost one word each.

**Opt-in, not automatic.** PatriamReligion alone has around thirty number providers. Recording and drawing all
of them would mean thirty stored series and thirty graphs nobody asked for.

**Server-level only.** A per-player series is one series per value per player, unbounded. There is no player
history table and no player store transaction writes one.

## How it flows

1. `@NumberProvider(graphed = true)` -> `DataValueGatherer` -> `ValueBuilder.graphed(boolean)` ->
   `ExtValueBuilder` -> `ProviderInformation.isGraphed()`.
2. `StoreServerNumberResultTransaction` / `StoreServerDoubleResultTransaction` do their usual overwrite of the
   single current value, and **additionally** append a point through `ExtensionValueHistory.append(...)` into
   `plan_extension_server_value_history`. Appended as well as, never instead of, so every existing reader still
   finds the latest value where it expects it.
3. `RemoveOldSampledDataTransaction` prunes points older than the **TPS retention window**, scoped to this
   server. Reusing that setting rather than adding one: both are sampled series kept only so a page can draw
   them, and a plugin graph outliving the TPS graph beside it helps nobody.
4. `ExtensionServerGraphsQuery` reads points joined to provider, tab and icons, `ORDER BY provider_id,
   timestamp`, folding consecutive rows of one provider into one `ExtensionGraphData`. Combined into
   `ExtensionServerDataQuery` like every other aggregate.
5. `ExtensionGraphDataDto` carries it as `{description, type, points}` where points are `[timestamp, value]`
   pairs, and `ExtensionTabDataDto` exposes them as `graphs`.
6. `ExtensionGraph.jsx` draws it via the shared `LineGraph`, so it inherits theming, locale, timezone and stock
   chrome from every other time series on the page.

## Things that bit, and will bite again

- **The foreign key is the dangerous part.** `plan_extension_server_value_history.provider_id` references
  `plan_extension_providers(id)`. **Four** transactions delete provider rows, and every one of them must clear
  history first or MySQL throws a constraint violation: `RemoveServerTransaction.deleteExtensionTables`,
  `RemoveOldExtensionsTransaction.removeValues`, `RemoveInvalidResultsTransaction` (via
  `deleteInvalidServerMethodHistory`, which must stay ordered before `deleteInvalidMethodProvider`), and
  `RemoveEverythingTransaction`. Missing these was caught in review, not in testing, because SQLite does not
  enforce the constraint the way InnoDB does and the local tests are SQLite.
- **The prune must stay scoped to the server.** Several servers can share one database, each with its own
  retention setting. An unscoped delete lets the most aggressive node destroy everyone else's history. The
  sibling TPS and ping cleans are both scoped; match them.
- **Do NOT add the table to `CreateTablesTransaction.tableNames()`.** All eleven existing `plan_extension_*`
  tables are deliberately absent from it. Adding one there pulls it into `DatabaseBackupTest.databaseMerge`,
  `RemoveEverythingTransaction`'s assertions and `DatabaseCopyProcessor`, none of which handle extension data
  at all today (`DatabaseCopyProcessor` still carries `// TODO plan how to copy extension data`).
- **A brand new table needs no Patch.** `CreateTablesTransaction` runs on every startup and every table's DDL
  is `CREATE TABLE IF NOT EXISTS`. Adding a *column* to an existing table does still need one.
- **Percentages are invisible to `FormatType`.** The extension enum holds only NONE, TIME_MILLISECONDS,
  DATE_YEAR and DATE_SECOND. Percentage-ness is which column the value was written to, so the query infers it
  by left-joining `plan_extension_server_values` and testing whether `percentage_value` is null, exactly as the
  value path does. Without it a share renders as a raw figure between 0 and 1.
- **Gson reflects over fields, unconfigured.** Field name is the JSON key and getters are irrelevant. Points
  are `Object[]` rather than `double[]` on purpose: Gson writes a double through `Double.toString`, which
  renders an epoch millisecond as `1.7543E12`.
- **`LineGraph` rebuilds the chart whenever `series` or `yAxis` changes identity.** Both must be memoised or
  Highcharts is torn down and remounted every render.
- **`softMin: 0` is wrong for a date-valued series**, whose y values are epoch milliseconds: it would stretch
  the axis from 1970 to now and flatten the line into the top pixel.
- **Shipping the API to plugins is a manual step.** Plugins compile against `com.djrapitops:Plan:5.8-local`,
  the server's own jar hand-installed into `~/.m2`. Nothing installs it automatically and `build-all.sh` does
  not, because Plan's `publishes` field is empty. After `./build-all.sh Plan`:

  ```
  mvn install:install-file -Dfile="<jars-26.2>/Plan.jar" \
      -DgroupId=com.djrapitops -DartifactId=Plan -Dversion=5.8-local -Dpackaging=jar
  ```

  Until that runs, `graphed = true` fails to compile with "cannot find symbol", one layer from the cause.

## What is graphed today

- **PatriamEconomy** `goldSupply` and `burnedThisWeek`. That class's own notes say a commodity economy "drains
  slowly" and that without a supply figure over time there is no telling hoarding from the money being gone.
  That sentence describes a graph and was written when the page could not draw one.
- **PatriamUtils** `awayNow`. A month rollup is too coarse to show a week everybody was away in.

## Not yet proven

None of this has been seen rendering. A graph cannot appear until the server has run long enough to gather a
second point, so the first real check is on a live server after a few gather cycles.
