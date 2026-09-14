# Phase 4: Statistics and insights — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The remaining domain ports and their screens: the Statistics screen (spend over time, by object tree, by type, by category, year picker, purchase-price switch), the insights block on an object's Info tab (spend per month / year / category, cost per counter, usage, fuel consumption and per-fill trend, total cost of ownership), and reminders' usage-based estimated due date — all computed from the mirror, offline.

**Architecture:** `src/domain/insights.rs` and `src/domain/stats.rs` become pure Kotlin objects (`core/domain/Insights.kt`, `core/domain/SpendStats.kt`) with every Rust test transliterated. The SQL in `src/api/stats.rs` and `src/api/insights.rs` becomes DAO queries keyed by uuid; two small model classes (`StatsModel`, `InsightsModel`) run the queries and hand the domain functions their inputs, exactly as the two Rust handlers do. Screens draw bars with plain Compose (`Box` with a fractional width), no chart library.

**Tech Stack:** As phase 3. No new dependencies. DataStore for the two per-device switches (purchase prices, include contents).

Spec: `docs/superpowers/specs/2026-09-14-logb-android-design.md`, *Statistics* under *Interface*, the *Domain ports* table, phase 4.

## Global Constraints

- Every function ported from Rust keeps its name (camel-cased), its integer arithmetic (milli / cents scaling, integer division) and its edge-case answers; every `#[test]` in the two modules has a Kotlin twin with the same name and figures.
- Object identity is the uuid, not the server id: `ObjectRow.id`, `Spend.objectId` and `ObjectNode.id` are `String`.
- Readings dated past tomorrow are ignored as the server's `reading_horizon` does; the rate query reads `2 × RATE_WINDOW_DAYS` back from today, as `usage_by_object` does.
- The Statistics screen and the insights block read only Room; nothing calls the server's `/stats`, `/insights` or `/usage`.
- The bottom bar keeps its three destinations; Statistics is a drill-down from Objects (a top-bar action) and belongs to `Destination.Objects`.
- The two switches (*Include purchase prices*, *Include contents*) are per device, not synced, as on the web.
- Lint clean, unit tests green, EN and DE strings (`StringsParityTest`) before every commit.

## File map

```
core/domain/Insights.kt            Reading, latestReading, dailyRateMilli, estimatedDate, MonthUsage/monthlyUsage,
                                   Fill/consumptionPer100Milli/fuelCostPerCounterMilli, costPerCounterMilli,
                                   defaultFuelUnit, DatedFill/FillRate/consumptionPerFill, Usage/usage
core/domain/SpendStats.kt          PURCHASE_PRICE, ObjectRow, Spend, Amount, ObjectNode, Stats, purchaseSpend,
                                   summarize, rollUp, Ownership, dayOf, ownership, monthsEnding
core/db/dao/ActivityDao.kt         spend grouping, buckets by year/category, month totals, counter span, fills, purchased
core/db/model/Projections.kt       SpendRow, Bucket, MonthTotal, CounterSpan, FillRow
feature/stats/StatsModel.kt        Stats from the mirror for (year, purchases)  — port of api/stats.rs::read
feature/stats/InsightsModel.kt     ObjectInsights from the mirror for (uuid, contents) — port of api/insights.rs::read
feature/stats/StatsPrefs.kt        includePurchases, includeContents (DataStore)
feature/stats/BarList.kt           the bar list composable (label · bar · figure; depth, expand, note)
feature/stats/StatsScreen.kt       screen + view model; year picker, purchases switch, four sections
feature/stats/InsightsSection.kt   the Info-tab block
feature/stats/Labels.kt            periodLabel, monthLabel, sinceLabel, fillLabel
core/format/Money.kt               formatCentsWhole, formatPerCounter; core/format/Quantity.kt formatQuantity
core/domain/ReminderPresenter.kt   usage: Usage? → estimatedDueDate, soonestDays
feature/objects/ObjectDetailViewModel.kt   usage for the reminders tab; insights flow for the Info tab
feature/reminders/DueListScreen.kt         lookahead on soonestDays
feature/objects/ObjectsScreen.kt           top-bar chart action → Stats
navigation/Routes.kt, AppNavHost.kt        Stats route
res/values*/strings.xml                    stats_*, insights_*, reminder_estimated
```

---

### Task 1: Insights port
**Files:** create `core/domain/Insights.kt`; test `core/domain/InsightsTest.kt`.
**Produces:**
```kotlin
object Insights {
    const val RATE_WINDOW_DAYS = 180L; const val RATE_MIN_SPAN_DAYS = 14L; const val FILL_BARS = 12
    data class Reading(val date: LocalDate, val counter: Long)
    data class MonthUsage(val month: String, val amount: Long?)
    data class Fill(val counter: Long, val quantityMilli: Long, val costCents: Long?)
    data class DatedFill(val date: String, val counter: Long, val quantityMilli: Long)
    data class FillRate(val date: String, val per100Milli: Long)
    data class Usage(val last: Reading, val rateMilli: Long)
    fun latestReading(readings: List<Reading>): Reading?
    fun dailyRateMilli(readings: List<Reading>): Long?
    fun estimatedDate(last: Reading, rateMilli: Long, target: Long): LocalDate?
    fun monthlyUsage(readings: List<Reading>, today: LocalDate, months: Int): List<MonthUsage>
    fun consumptionPer100Milli(fills: List<Fill>): Long?
    fun fuelCostPerCounterMilli(fills: List<Fill>): Long?
    fun costPerCounterMilli(totalCostCents: Long, span: Long): Long?
    fun defaultFuelUnit(counterUnit: String?): String
    fun consumptionPerFill(fills: List<DatedFill>): List<FillRate>
    /** `api::insights::usage_by_object` for one object: readings within the horizon and 2×window back. */
    fun usage(readings: List<Reading>, today: LocalDate): Usage?
}
```
- [ ] Tests: the 25 Rust tests transliterated (`monthlyUsage measures each month that has a reading`, `… does not invent negative months`, `… crosses a year`, `the rate runs from the earliest reading in the window to the latest`, `a short window falls back to the earliest reading`, `no rate without a real span or a rising counter`, `the estimate projects from the latest reading`, `no estimate once reached or without a rate or absurdly far`, `consumption excludes the first fill`, `a single fill cannot produce consumption`, `a zero span produces nothing rather than dividing by zero`, `fills out of order are sorted before measuring`, `cost per counter needs a span`, `fuel cost matches the worked consumption case`, `the first fills cost is excluded like its quantity`, `a fill with no recorded cost contributes zero but still counts`, `unmeasurable consumption means unmeasurable cost too`, `default fuel unit uses gallons for miles and litres otherwise`, `each fill is measured from the one before it`, `fills are put in date order first`, `a distance that is not positive gives no bar but starts the next interval`, `only the newest twelve are kept`, `fewer than two fills give nothing`) plus `usage ignores readings past tomorrow and older than twice the window`.
- [ ] Rounding: Rust integer division truncates toward zero; Kotlin `/` on `Long` does the same. `estimatedDate` uses `(remaining * 1000 + rateMilli - 1) / rateMilli` and `LocalDate.plusDays`.
- [ ] Commit `feat: port the insights domain module`.

### Task 2: SpendStats port
**Files:** create `core/domain/SpendStats.kt`; test `core/domain/SpendStatsTest.kt`.
**Produces:**
```kotlin
object SpendStats {
    const val PURCHASE_PRICE = "purchase_price"; const val MIN_DAYS_FOR_PER_YEAR = 90L
    data class ObjectRow(val id: String, val parentId: String?, val name: String, val kind: String, val archived: Boolean, val purchaseDate: String?, val purchasePriceCents: Long?, val createdAt: String)
    data class Spend(val objectId: String, val month: String, val category: String, val costCents: Long)
    data class Amount(val bucket: String, val costCents: Long)
    data class ObjectNode(val id: String, val name: String, val kind: String, val archived: Boolean, val costCents: Long, val children: List<ObjectNode>)
    data class Stats(val totalCents: Long, val years: List<String>, val overTime: List<Amount>, val byObject: List<ObjectNode>, val byType: List<Amount>, val byCategory: List<Amount>)
    data class Ownership(val totalCents: Long, val purchaseCents: Long, val since: String, val perYearCents: Long?)
    fun purchaseSpend(objects: List<ObjectRow>, purchased: Set<String>): List<Spend>
    fun summarize(objects: List<ObjectRow>, spend: List<Spend>, year: Int?): Stats
    fun dayOf(s: String): LocalDate?
    fun ownership(runningCents: Long, purchaseCents: Long, since: LocalDate, until: LocalDate): Ownership
    fun monthsEnding(today: LocalDate, months: Int, totals: List<Pair<String, Long>>): List<Amount>
}
```
- [ ] Tests: the 20 Rust tests transliterated (`a parent carries every descendants spend`, `a subtree that spent nothing is left out`, `an object whose parent is not in the list is a root`, `type is each objects own not its roots`, `all years draws one bar per year oldest first and lists years newest first`, `one year draws all twelve months and filters every block`, `categories are sorted largest first and zero rows dropped`, `archived is carried through`, `nothing spent is an empty but complete answer`, `a purchase price is dated by its purchase date`, `… without a date falls back to when the object was created`, `… is skipped when a costed purchase entry already records it`, `no price or a zero price adds nothing`, `a typo year is still well formed and stays offered and selectable`, `a malformed month is ignored everywhere`, `the month window ends with this month and crosses a year`, `ownership adds the purchase price and measures up to until`, `no per year figure under ninety days owned`, `day of reads dates and timestamps`). The tree fixture uses uuids "1".."4".
- [ ] `year` formats as `%04d-`; `sorted` orders by cost desc then bucket asc; `rollUp` by cost desc then name asc (plain `String` comparison, as Rust's).
- [ ] Commit `feat: port the spend statistics domain module`.

### Task 3: DAO queries and the two models
**Files:** modify `core/db/dao/ActivityDao.kt`, `core/db/model/Projections.kt`; create `feature/stats/StatsModel.kt`, `feature/stats/InsightsModel.kt`; tests `core/db/StatsDaoTest.kt`, `feature/stats/ModelsTest.kt` (Robolectric).
**Produces (DAO, all `deleted_at IS NULL` on activities and on the joined object):**
```kotlin
data class SpendRow(val objectUuid: String, val month: String, val category: String, val costCents: Long)
data class Bucket(val bucket: String, val costCents: Long, val count: Int)
data class MonthTotal(val month: String, val costCents: Long)
data class CounterSpan(val minCounter: Long?, val maxCounter: Long?, val totalCostCents: Long)
data class FillRow(val date: String, val counterValue: Long, val quantityMilli: Long, val costCents: Long?)

suspend fun spendByObjectMonthCategory(): List<SpendRow>        // cost NOT NULL, category <> 'reading'
suspend fun purchasedObjectUuids(): List<String>                // category = 'purchase' AND cost > 0
suspend fun byYear(uuids: List<String>): List<Bucket>           // substr(date,1,4), COALESCE(SUM), COUNT, desc
suspend fun byCategory(uuids: List<String>): List<Bucket>       // category <> 'reading', cost desc
suspend fun monthTotals(uuids: List<String>): List<MonthTotal>  // cost NOT NULL, substr(date,1,7)
suspend fun runningCents(uuids: List<String>): Long             // COALESCE(SUM(cost_cents),0)
suspend fun counterSpan(uuid: String): CounterSpan
suspend fun fills(uuid: String): List<FillRow>                  // category='fuel', counter & quantity NOT NULL, ORDER BY counter_value
suspend fun readingRows(uuid: String, upTo: String): List<ReadingRow>  // date, counter_value where date <= upTo
```
**Produces (models):**
```kotlin
class StatsModel(db: LogbDatabase) { suspend fun stats(year: Int?, purchases: Boolean): SpendStats.Stats }
data class ObjectInsights(val byYear: List<Bucket>, val byCategory: List<Bucket>, val counterSpan: Pair<Long, Long>?, val costPerCounterMilli: Long?, val fuel: FuelInsights?, val counterPerDayMilli: Long?, val usageByMonth: List<Insights.MonthUsage>, val hasContents: Boolean, val ownership: SpendStats.Ownership, val byMonth: List<SpendStats.Amount>)
data class FuelInsights(val unit: String, val quantityMilli: Long, val per100Milli: Long?, val costPerCounterMilli: Long?, val fills: List<Insights.FillRate>)
class InsightsModel(db: LogbDatabase, today: () -> LocalDate = { LocalDate.now() }) {
    suspend fun insights(uuid: String, contents: Boolean): ObjectInsights?   // null when the object is gone
    suspend fun usage(uuid: String): Insights.Usage?
}
```
- [ ] Tests (DAO): spend rows group by object/month/category and skip readings, null costs, deleted activities and deleted objects; `byCategory` leaves readings out; `counterSpan` gives min/max/total; `fills` need both counter and quantity.
- [ ] Tests (models): House > Garage > Bulb + Car fixture: `stats(null, false)` matches the domain test's totals; `stats(null, true)` adds the car's purchase price unless a costed purchase entry exists; `insights(car, false)` gives by-year/category buckets, the counter span and cost per counter, fuel per 100, usage by month over 12 months, ownership since the purchase date with `perYearCents`; `insights(house, true)` folds the garage's spend in and `hasContents` is true; `usage(car)` matches `Insights.usage` over the readings.
- [ ] Commit `feat: statistics and insights computed from the mirror`.

### Task 4: Estimated due date from usage
**Files:** modify `core/domain/ReminderPresenter.kt` (`present(reminder, currentCounter, lastReadingDate, today, usage: Insights.Usage? = null)`; `ReminderView.estimatedDueDate` set when not due, `dueCounter` set and usage known; `ReminderView.soonestDays: Long?` = min of daysUntil and days until the estimate), `feature/objects/ObjectDetailViewModel.kt` (usage from `InsightsModel.usage`), `feature/reminders/DueListScreen.kt` (usage per object; lookahead on `soonestDays`), `feature/objects/tabs/RemindersTab.kt` (`reminderSubtitle` appends "≈ {date} at recent usage" when there is an estimate), strings `reminder_estimated`; test additions in `core/domain/PresenterAndFoldTest.kt`.
- [ ] Tests: `usage estimates a counter target but never makes it due` (59 000 at 25 km/day, target 60 000, today 2026-09-13 → estimate 2026-10-11, not due); a due reminder has no estimate; `soonestDays` picks the nearer of the two.
- [ ] Commit `feat: reminders estimate when the counter will reach its target`.

### Task 5: Bar list and the Info-tab insights
**Files:** create `feature/stats/BarList.kt`, `feature/stats/InsightsSection.kt`, `feature/stats/Labels.kt`, `feature/stats/StatsPrefs.kt`; `core/format/Money.kt` (+`formatCentsWhole`, `formatPerCounter`), `core/format/Quantity.kt` (`formatQuantity(milli, unit, locale)` → "41.3 l"); modify `feature/objects/tabs/InfoTab.kt`, `feature/objects/ObjectDetailViewModel.kt` (`insights: StateFlow<ObjectInsights?>` recomputed from the detail flow and the contents switch), strings; test `feature/stats/LabelsAndFormatTest.kt`.
**Produces:**
```kotlin
data class Bar(val key: String, val label: String, val value: Long, val display: String, val depth: Int = 0, val note: String? = null, val expanded: Boolean? = null, val onToggle: (() -> Unit)? = null, val onLabel: (() -> Unit)? = null)
@Composable fun BarList(items: List<Bar>, modifier: Modifier = Modifier)   // widest bar fills the track; zero-max draws empty tracks
object Labels { fun periodLabel(bucket: String, locale: Locale): String; fun monthLabel(month: String, locale): String; fun sinceLabel(date: String, locale): String; fun fillLabel(date: String, locale): String }
```
- [ ] Insights block on the Info tab, between the fields and *Contents*, in the web's order: *Include contents* switch (only with children); ownership line (total, "≈ X a year since May 2024" or "since …"); "Not enough data yet." when no year has spend, else *Spend per month* (12 bars), *Per year*, *Per category* (category labels via `categoryLabel`), *Cost per {unit}*; fuel: *Fuel logged*, *Consumption* (per 100 unit), *Fuel cost per {unit}*; *Usage* "≈ N unit a month" (rate × 30.44 / 1000 rounded); *Usage per month* (dash for an unknown month); *Consumption per fill* with the hint.
- [ ] Tests: `periodLabel("2026")=="2026"`, `periodLabel("2026-03", GERMAN)=="März"`, `monthLabel("2026-09", ENGLISH)=="Sep 26"`, `sinceLabel("2024-05-01", ENGLISH)=="May 2024"`, `fillLabel("2026-01-10", ENGLISH)=="Jan 10"`, `formatPerCounter(2_496, "EUR", GERMAN)=="0,02 €"`, `formatQuantity(41_300, "l", ENGLISH)=="41.3 l"`, `formatCentsWhole(123_456, "EUR", ENGLISH)=="€1,235"`.
- [ ] Commit `feat: insights on the Info tab`.

### Task 6: Statistics screen
**Files:** create `feature/stats/StatsScreen.kt` (+ `StatsViewModel`, `StatsUiState(year: String?, years, purchases, stats, loaded)`); modify `navigation/Routes.kt` (`@Serializable object Stats`, under `Destination.Objects`), `navigation/AppNavHost.kt`, `feature/objects/ObjectsScreen.kt` (top-bar `BarChart` icon action, content description *Statistics*), strings.
- [ ] Screen: top bar with back; year picker as an `ExposedDropdownMenuBox` (*All years* + `years`, a selected year with no spend stays offered); *Include purchase prices* switch; empty state "No costs recorded for this period." at zero; else *Total* figure, sections *Spend over time* (months show the amount alone; years show amount · share), *By object* (tree, expand/collapse chevrons, "archived" note, tap a name → object detail), *By type*, *By category* (`purchase_price` bucket labelled *Purchase price*). Recomputes when the mirror changes (observe `activityDao.timelineVersion`-style flow: combine `objectDao.all()` and a `SELECT COUNT(*), MAX(updated_at) FROM activities` flow).
- [ ] Route from the Objects top bar; `activeDestination("Stats") == Objects`; a unit test for that in `navigation/RoutesTest.kt`.
- [ ] Commit `feat: statistics screen`.

### Task 7: Device check, docs, memory
- [ ] Install on the phone, airplane mode on: open Statistics from Objects, switch years and purchases, expand House, tap Garage → detail; Info tab on the seeded car shows spend per month, consumption, usage; a service reminder with a counter target shows the "≈ date" line. Compare the figures against the browser's Statistics page and the car's insights on the same server.
- [ ] `docs/smoke-checklist.md` gains a Statistics section; `README.md` status "phases 1–4"; spec status header "phases 0–4 built; phase 5 open"; this plan's status header notes deviations; memory file updated.
- [ ] Commit `docs: phase 4 status, statistics smoke checks`.

## Self-review
Spec *Statistics* ✔ T6; *Info shows … insights* ✔ T5; domain ports table (`Insights.kt`, `SpendStats.kt`) ✔ T1/T2; `estimatedDueDate` gap left by phase 2 ✔ T4; Compose-drawn bars, no library ✔ T5. Type consistency: `Insights.Usage` is what `ReminderPresenter.present` and `InsightsModel.usage` share; `SpendStats.Amount` is what `monthsEnding`, `Stats.overTime` and the bar sections consume; `Bucket` lives in `core/db/model` and is used by `ObjectInsights` directly.
