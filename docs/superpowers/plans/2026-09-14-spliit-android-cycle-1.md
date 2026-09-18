# Spliit for Android — Cycle 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A native Kotlin/Compose Android client for Spliit — groups, expenses and balances against `spliit.app` or a self-hosted instance — ported from the SwiftUI app.

**Architecture:** Three Gradle modules. `:api` and `:core` are pure Kotlin/JVM with no Android dependency, so the protocol handling, money maths and split logic test on the JVM in seconds. `:app` is Compose M3 with ViewModels and `StateFlow`.

**Tech Stack:** Gradle 9.7.1, AGP 9.4.0, Kotlin 2.4.20, JDK 21, compileSdk 37, minSdk 26, Compose BOM 2026.09.00, OkHttp 5.5.0, kotlinx-serialization 1.11.0, JUnit 6.1.3.

**Spec:** [`docs/superpowers/specs/2026-09-14-spliit-android-design.md`](../specs/2026-09-14-spliit-android-design.md)

---

## How this is split

Fifteen parts, each a self-contained branch and PR. Every part ends green and reviewable on its
own; no part depends on a later one. The three that carry the most risk — superjson, money, and
the split maths — are deliberately alone in their own parts, because they are where a silent
error costs the most and where review attention is worth the most.

| Part | What lands | Review weight |
|---|---|---|
| 0 | Build skeleton, Makefile, CI, e2e server | Mechanical |
| 1 | superjson codec | **Heavy — the trickiest algorithm** |
| 2 | tRPC client and error model | Medium |
| 3 | Models and recorded fixtures | Medium |
| 4 | Endpoints and the stats fallback | Medium |
| 5 | Money and currency | **Heavy — minor units** |
| 6 | Expense form draft and split maths | **Heavy — the shares rule** |
| 7 | Group form draft and default split | Medium |
| 8 | Recent-groups store | Medium |
| 9 | App shell, theme, design system | Light, visual |
| 10 | Groups list, add by URL, group form | Medium |
| 11 | Group detail — expenses and balances | Medium |
| 12 | Expense create and edit | **Heavy — the biggest screen** |
| 13 | Settings and currency picker | Light |
| 14 | Instrumented tests and CI wiring | Medium |

The app is first runnable at the end of Part 10. Parts 0–9 are library and scaffolding work
verified by unit tests alone.

---

## File structure

```
settings.gradle.kts                     three modules
gradle/libs.versions.toml               one version catalogue, all versions pinned here
Makefile                                every task; Gradle underneath

api/src/main/kotlin/app/spliit/api/
  SuperJson.kt                          envelope encode/decode           (Part 1)
  TrpcProcedure.kt                      query/mutation descriptor        (Part 2)
  TrpcClient.kt                         request building, calling        (Part 2)
  TrpcError.kt                          server + client errors           (Part 2)
  Models.kt                             wire DTOs                        (Part 3)
  FormValues.kt                         write-side DTOs and their nulls  (Part 3)
  SpliitEndpoints.kt                    the procedures                   (Part 4)

core/src/main/kotlin/app/spliit/core/
  MoneyFormatter.kt                     minor units, formatting          (Part 5)
  Currencies.kt                         the picker's list                (Part 5)
  ExpenseFormDraft.kt                   split maths, validation          (Part 6)
  GroupFormDraft.kt                     group validation                 (Part 7)
  DefaultSplit.kt                       the three saved-split rules      (Part 7)
  RecentGroups.kt                       model, snapshot, merge           (Part 8)
  RecentGroupsStore.kt                  interface                        (Part 8)
  DateBuckets.kt                        expense/activity grouping        (Part 8)
  LoadState.kt                          loading/loaded/failed            (Part 8)

app/src/main/kotlin/app/spliit/android/
  MainActivity.kt  SpliitApp.kt  Navigation.kt                           (Part 9)
  ui/theme/{Color,Type,Theme}.kt                                         (Part 9)
  ui/design/{Money,Monogram,CategoryIcon,EmptyState,DateHeader}.kt       (Part 9)
  data/DataStoreRecentGroupsStore.kt                                     (Part 8)
  feature/groups/{GroupsListScreen,GroupsListViewModel}.kt               (Part 10)
  feature/groups/{AddGroupByUrlScreen,GroupFormScreen,GroupFormViewModel}.kt (Part 10)
  feature/group/{GroupDetailScreen,GroupDetailViewModel}.kt              (Part 11)
  feature/group/{ExpenseListTab,BalancesTab,ExpenseRow}.kt               (Part 11)
  feature/expense/{ExpenseFormScreen,ExpenseFormViewModel}.kt            (Part 12)
  feature/settings/{SettingsScreen,CurrencyPickerScreen}.kt              (Part 13)
  TestTags.kt                           shared with androidTest          (Part 9)
```

---

## Part 0 — Build skeleton, harness, CI

**Goal:** `make test` and `make build` both succeed against three empty modules. Nothing else.

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`
- Create: `gradle/wrapper/gradle-wrapper.properties`, `gradlew`, `gradlew.bat`
- Create: `api/build.gradle.kts`, `core/build.gradle.kts`, `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`, `local.properties`
- Create: `Makefile`, `.github/workflows/ci.yml`
- Create: `e2e/compose.yaml`, `e2e/seed.mjs`, `e2e/README.md`
- Create: `CLAUDE.md`, `DESIGN.md`, `README.md`

- [ ] **Step 1: Resolve how AGP 9.4 names a minor platform**

The installed platform is `android-37.1`, not `android-37`. AGP 9 expresses this as
`compileSdk` plus `compileSdkMinor`. Confirm before writing the rest:

```bash
ls ~/Library/Android/sdk/platforms
```

Set `compileSdk = 37` and `compileSdkMinor = 1` in `app/build.gradle.kts`. If AGP rejects
`compileSdkMinor`, fall back to `compileSdk = 37` alone and install `platforms/android-37.0`
with `android sdk install "platforms/android-37.0"`. Record whichever worked in `CLAUDE.md`.

- [ ] **Step 2: Generate the Gradle wrapper at 9.7.1**

There is no `gradle` on the PATH, and the cached distributions are 8.4 and 8.11.1 — too old for
AGP 9.4, which requires ≥ 9.6.0. Write `gradle/wrapper/gradle-wrapper.properties` by hand:

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-9.7.1-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

Fetch `gradlew`, `gradlew.bat` and `gradle-wrapper.jar` from the Gradle 9.7.1 distribution,
then `chmod +x gradlew`.

- [ ] **Step 3: Write the version catalogue**

`gradle/libs.versions.toml` — every version lives here and nowhere else:

```toml
[versions]
agp = "9.4.0"
kotlin = "2.4.20"
composeBom = "2026.09.00"
okhttp = "5.5.0"
serialization = "1.11.0"
coroutines = "1.11.0"
junit = "6.1.3"
activityCompose = "1.13.0"
navigationCompose = "2.10.1"
lifecycle = "2.11.0"
datastore = "1.2.1"
coreKtx = "1.19.0"

[libraries]
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
okhttp-mockwebserver = { module = "com.squareup.okhttp3:mockwebserver3-junit5", version.ref = "okhttp" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit" }
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
compose-material3 = { module = "androidx.compose.material3:material3" }
compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
compose-ui-test-junit4 = { module = "androidx.compose.ui:ui-test-junit4" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
androidx-navigation-compose = { module = "androidx.navigation:navigation-compose", version.ref = "navigationCompose" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

- [ ] **Step 4: Write the three module build files**

`api/build.gradle.kts` and `core/build.gradle.kts` apply `kotlin-jvm` only — **no Android
plugin**. That is the boundary the whole test story rests on; a reviewer should check it.
Both set `kotlin { jvmToolchain(21) }` and `tasks.test { useJUnitPlatform() }`.

`app/build.gradle.kts` applies `android-application`, `kotlin-android` and `compose-compiler`,
with `namespace = "app.spliit.android"`, `applicationId = "app.spliit.android"`, `minSdk = 26`,
`targetSdk = 37`, and depends on `project(":api")` and `project(":core")`.

- [ ] **Step 5: Point Gradle at the SDK and the JDK**

`local.properties` (gitignored):

```properties
sdk.dir=/Users/armend/Library/Android/sdk
```

`gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g
org.gradle.caching=true
org.gradle.parallel=true
android.useAndroidX=true
kotlin.code.style=official
```

- [ ] **Step 6: Verify the skeleton builds**

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :api:test :core:test :app:assembleDebug
```

Expected: BUILD SUCCESSFUL. The first run downloads Gradle 9.7.1 and the AGP/Kotlin artifacts,
so allow several minutes. If AGP 9.4.0 proves rough here, this is the moment to fall back to
AGP 9.2/9.3 per the spec's risk note — not later.

- [ ] **Step 7: Write the Makefile**

Wraps Gradle so nobody needs to remember the task names, mirroring the iOS repo's front door.
`make` on its own lists every target.

```make
.DEFAULT_GOAL := help
export JAVA_HOME := /Applications/Android Studio.app/Contents/jbr/Contents/Home
GRADLE := ./gradlew
E2E_URL ?= http://10.0.2.2:3009/

help:              ## List every task
	@grep -hE '^[a-zA-Z0-9_-]+:.*?## ' $(MAKEFILE_LIST) | awk -F':.*?## ' '{printf "  %-14s %s\n", $$1, $$2}'

test:              ## Unit suites on the JVM — no emulator
	$(GRADLE) :api:test :core:test

build:             ## Build the debug APK
	$(GRADLE) :app:assembleDebug

lint:              ## Android lint
	$(GRADLE) :app:lintDebug

test-live:         ## API suites against the local instance (needs `make e2e-up`)
	SPLIIT_LIVE_URL=http://localhost:3009/ $(GRADLE) :api:test -Dlive=true

e2e-up:            ## Start the throwaway Spliit instance on :3009
	docker compose -f e2e/compose.yaml up -d --wait
	docker compose -f e2e/compose.yaml --profile setup run --rm s3-policy

e2e-down:          ## Stop it and discard its data
	docker compose -f e2e/compose.yaml down -v

e2e-seed:          ## Load fixture groups and expenses
	node e2e/seed.mjs http://localhost:3009/

e2e:               ## Instrumented tests against the running instance
	$(GRADLE) :app:connectedDebugAndroidTest

fixtures:          ## Re-record the API fixtures the unit tests decode
	node Scripts/record-fixtures.mjs http://localhost:3009/

emulator:          ## Boot the emulator this project uses
	$(ANDROID_HOME)/emulator/emulator -avd Pixel_8_API_31 -no-snapshot-load &

run: build         ## Install and launch on the running emulator
	adb install -r app/build/outputs/apk/debug/app-debug.apk
	adb shell am start -n app.spliit.android/.MainActivity

shot:              ## Screenshot the emulator to build/screenshot.png
	@mkdir -p build && adb exec-out screencap -p > build/screenshot.png

clean:             ## Remove build output
	$(GRADLE) clean
```

Note `E2E_URL` defaults to `10.0.2.2`, which is how the emulator reaches the host's `localhost`.

- [ ] **Step 8: Clone the e2e server**

Copy `e2e/compose.yaml` and `e2e/seed.mjs` from the iOS repo essentially verbatim — the server,
the tmpfs Postgres and the MinIO bucket are identical needs. Change only the compose project
name to `spliit-android-e2e` so both repos' stacks can run side by side.

- [ ] **Step 9: Write CI**

`.github/workflows/ci.yml`: JDK 21, `actions/setup-java`, Gradle cache, then `make test`,
`make build`, `make lint` on every push. Add the nightly `upstream-drift` job that runs
`make test-live` against `https://spliit.app/` — the spec explains why a pinned-server CI could
never have caught the `groups.stats` rename.

- [ ] **Step 10: Write CLAUDE.md, DESIGN.md, README.md**

Port §5 of the spec into `CLAUDE.md`'s "Things that will bite you", rewritten for Kotlin.
Port §6 into `DESIGN.md`. `README.md` gets the getting-started and layout sections.

- [ ] **Step 11: Commit**

```bash
git add -A && git commit -m "Build skeleton, Makefile, CI and the e2e server"
```

---

## Part 1 — The superjson codec

**Goal:** Encode and decode Spliit's envelope, proven by tests, with no network anywhere near it.

This is the part to review hardest. It is pure, it is fiddly, and every mistake in it is silent.

**Files:**
- Create: `api/src/main/kotlin/app/spliit/api/SuperJson.kt`
- Test: `api/src/test/kotlin/app/spliit/api/SuperJsonTest.kt`

- [ ] **Step 1: Write the failing decode tests**

The three facts worth pinning: the envelope unwraps, a procedure returning nothing decodes, and
`meta.values` is ignored rather than consulted.

```kotlin
@Test fun `unwraps the result envelope`() {
    val body = """{"result":{"data":{"json":{"groupId":"abc"}}}}"""
    val decoded = SuperJson.decodeResponse<CreateGroupResponse>(body)
    assertEquals("abc", decoded.groupId)
}

@Test fun `decodes a procedure that returns nothing`() {
    val body = """{"result":{"data":{"json":null}}}"""
    assertEquals(TrpcVoid, SuperJson.decodeResponse<TrpcVoid>(body))
}

@Test fun `decodes a date the server did not annotate`() {
    // groups.list sends createdAt with no meta.values entry at all. Trusting the
    // metadata would break exactly this endpoint.
    val body = """{"result":{"data":{"json":{"at":"2026-01-02T03:04:05.678Z"}}}}"""
    val decoded = SuperJson.decodeResponse<HasInstant>(body)
    assertEquals(Instant.parse("2026-01-02T03:04:05.678Z"), decoded.at)
}

@Test fun `accepts a timestamp without milliseconds`() {
    // expenseDate is a bare Postgres date; createdAt is a full timestamp.
    val body = """{"result":{"data":{"json":{"at":"2026-01-02T03:04:05Z"}}}}"""
    assertEquals(Instant.parse("2026-01-02T03:04:05Z"), SuperJson.decodeResponse<HasInstant>(body).at)
}

@Test fun `reads a trpc error body`() {
    val body = """{"error":{"json":{"message":"No procedure found","data":{"code":"NOT_FOUND","httpStatus":404,"path":"groups.stats.get"}}}}"""
    val error = SuperJson.decodeError(body)!!
    assertEquals("NOT_FOUND", error.code)
    assertTrue(error.isUnknownProcedure)
}
```

- [ ] **Step 2: Write the failing encode tests**

The encoder's whole job is the `meta.values` annotation, so test that directly.

```kotlin
@Test fun `annotates every date it encodes`() {
    val envelope = SuperJson.encodeEnvelope(HasInstant(Instant.parse("2026-01-02T03:04:05Z")))
    val meta = Json.parseToJsonElement(envelope).jsonObject["meta"]!!.jsonObject
    assertEquals(
        JsonArray(listOf(JsonPrimitive("Date"))),
        meta["values"]!!.jsonObject["at"]
    )
}

@Test fun `annotates a date nested in an array by its index path`() {
    val envelope = SuperJson.encodeEnvelope(HasList(listOf(HasInstant(EPOCH), HasInstant(EPOCH))))
    val values = Json.parseToJsonElement(envelope).jsonObject["meta"]!!.jsonObject["values"]!!.jsonObject
    assertTrue(values.containsKey("items.0.at"))
    assertTrue(values.containsKey("items.1.at"))
}

@Test fun `writes no meta when there are no dates`() {
    val envelope = SuperJson.encodeEnvelope(GroupIdInput("abc"))
    assertNull(Json.parseToJsonElement(envelope).jsonObject["meta"])
}

@Test fun `leaves the marker out of the encoded value`() {
    val envelope = SuperJson.encodeEnvelope(HasInstant(Instant.parse("2026-01-02T03:04:05Z")))
    assertEquals(
        "2026-01-02T03:04:05.000Z",
        Json.parseToJsonElement(envelope).jsonObject["json"]!!.jsonObject["at"]!!.jsonPrimitive.content
    )
}
```

- [ ] **Step 3: Run them and watch them fail**

```bash
./gradlew :api:test --tests '*SuperJsonTest*'
```

Expected: FAIL — `SuperJson` unresolved.

- [ ] **Step 4: Implement**

The algorithm, ported from Swift. kotlinx-serialization gives no way to learn which strings came
from `Instant`s, so the serializer writes each behind a per-call random marker and a second pass
over the `JsonElement` tree strips the markers and records the key path of each one.

```kotlin
object SuperJson {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun encodeEnvelope(value: Any, serializer: SerializationStrategy<Any>): String {
        val marker = "superjson-date:${UUID.randomUUID()}:"
        val tree = Json { encodeDefaults = false }
            .encodeToJsonElement(MarkedInstantSerializer.install(serializer, marker), value)

        val paths = mutableListOf<String>()
        val cleaned = strip(tree, marker, emptyList(), paths)

        return buildJsonObject {
            put("json", cleaned)
            when {
                paths == listOf("") -> put("meta", buildJsonObject {
                    put("values", JsonArray(listOf(JsonPrimitive("Date"))))
                })
                paths.isNotEmpty() -> put("meta", buildJsonObject {
                    put("values", buildJsonObject {
                        paths.forEach { put(it, JsonArray(listOf(JsonPrimitive("Date")))) }
                    })
                })
            }
        }.toString()
    }

    private fun strip(
        element: JsonElement, marker: String, path: List<String>, paths: MutableList<String>
    ): JsonElement = when (element) {
        is JsonPrimitive ->
            if (element.isString && element.content.startsWith(marker)) {
                paths += path.joinToString(".")
                JsonPrimitive(element.content.removePrefix(marker))
            } else element
        is JsonObject -> buildJsonObject {
            element.forEach { (key, nested) -> put(key, strip(nested, marker, path + key, paths)) }
        }
        is JsonArray -> JsonArray(
            element.mapIndexed { i, nested -> strip(nested, marker, path + i.toString(), paths) }
        )
    }
}
```

Decoding unwraps `result.data.json`, treating an absent or null `json` as `TrpcVoid`. The
`Instant` deserializer accepts ISO-8601 with **and** without fractional seconds.

- [ ] **Step 5: Run the tests**

```bash
./gradlew :api:test --tests '*SuperJsonTest*'
```

Expected: PASS, all of them.

- [ ] **Step 6: Commit**

```bash
git add api/ && git commit -m "superjson envelope encoding and decoding"
```

---

## Part 2 — tRPC client and error model

**Goal:** Build correct requests and turn responses into values or typed errors.

**Files:**
- Create: `api/src/main/kotlin/app/spliit/api/TrpcProcedure.kt`, `TrpcClient.kt`, `TrpcError.kt`
- Test: `api/src/test/kotlin/app/spliit/api/TrpcClientTest.kt`

- [ ] **Step 1: Write the failing request-building tests**

These pin the wire format, which no later test would catch if it drifted.

```kotlin
@Test fun `a query goes as GET with the envelope in the input parameter`()
@Test fun `a mutation goes as POST with the envelope as the body`()
@Test fun `percent-encodes everything outside the unreserved set`()
    // URLComponents-equivalent laziness would leave + and & alone and corrupt the JSON.
@Test fun `appends api trpc to a base URL with no trailing slash`()
@Test fun `appends api trpc to a base URL that already has one`()
    // A group stores its instance as "https://spliit.app/" — both must work.
@Test fun `a query with no input sends no input parameter`()
```

- [ ] **Step 2: Write the failing response tests**

```kotlin
@Test fun `a 404 with a trpc error body throws TrpcServerError`()
@Test fun `isUnknownProcedure is true for a missing procedure`()
@Test fun `a 500 with an empty body throws unexpectedResponse`()
    // This exact shape is what a Spliit instance with no S3 bucket returns. Part 0 of a
    // later cycle depends on telling it apart from a real failure.
@Test fun `a cancelled call rethrows cancellation rather than reporting a network error`()
    // Typing another character in a search field must not say the server is unreachable.
```

- [ ] **Step 3: Run and watch them fail**

```bash
./gradlew :api:test --tests '*TrpcClientTest*'
```

- [ ] **Step 4: Implement**

`TrpcProcedure<I, O>` carries a path, a kind (`Query`/`Mutation`) and an input. `TrpcClient`
takes a base URL and an `OkHttpClient` — shared by default so switching instances does not leak
a connection pool per call, with a 20s call timeout, because `URLSession`'s 60s default reads
to a person as the app having hung. Cache is disabled: a cached GET would quietly serve stale
balances.

Use MockWebServer for the tests; no live server in this part.

- [ ] **Step 5: Run the tests** — expected PASS.

- [ ] **Step 6: Commit**

```bash
git add api/ && git commit -m "tRPC client, procedures and the error model"
```

---

## Part 3 — Models and recorded fixtures

**Goal:** Every DTO the app reads and writes, proven against JSON captured from a real server.

**Files:**
- Create: `api/src/main/kotlin/app/spliit/api/Models.kt`, `FormValues.kt`
- Create: `Scripts/record-fixtures.mjs`
- Test: `api/src/test/kotlin/app/spliit/api/ModelsTest.kt`, `FormValuesTest.kt`
- Test fixtures: `api/src/test/resources/fixtures/*.json`

- [ ] **Step 1: Record the fixtures, do not write them**

Hand-written fixtures only prove the decoder agrees with our own assumptions. Recorded ones
prove it agrees with the server.

```bash
make e2e-up && make e2e-seed && make fixtures
```

Produces `groups.list.json`, `groups.get.json`, `groups.getDetails.json`,
`groups.expenses.list.json`, `groups.expenses.get.json`, `groups.balances.list.json`,
`categories.list.json`, `groups.stats.overview.json`.

- [ ] **Step 2: Write the failing decode tests**

One per fixture, plus these, which are the ones that bite:

```kotlin
@Test fun `decodes a group summary's participant count from _count`()
@Test fun `totalParticipantShare decodes a non-integer`() {
    // An instance older than the Shares change sends 1416.67. An Int field throws and
    // takes the whole totals screen with it.
    val decoded = decodeFixture<GroupStatsResponse>("""{"totalGroupSpendings":4250,"totalParticipantShare":1416.67}""")
    assertEquals(1416.67, decoded.totalParticipantShare)
}
@Test fun `an unrecognised activity type decodes as unknown rather than throwing`()
@Test fun `a conversion rate decodes from a Prisma decimal sent as a string`()
```

- [ ] **Step 3: Write the failing form-values tests**

The write side is where "omitted is not cleared" lives, and each field differs.

```kotlin
@Test fun `a cleared currency code is sent as an empty string, not omitted`()
@Test fun `originalCurrency is sent as an explicit null when conversion is dropped`()
@Test fun `originalAmount and conversionRate are omitted rather than nulled`() {
    // Their zod schemas accept a number, a numeric string or '' and reject null with a 400.
}
@Test fun `expenseDate is annotated as a Date in the envelope`()
```

- [ ] **Step 4: Run and watch them fail.**

- [ ] **Step 5: Implement the DTOs**

`ExpenseCategory`, `Participant`, `Group`, `GroupSummary`, `ExpenseListItem`,
`ExpenseDetails`, `Balance`, `Reimbursement`, `Activity`, `ActivityType` (with an `Unknown`
arm), `ExpenseDocument`, `SplitMode`, `RecurrenceRule`, `LenientDecimal`, plus
`GroupFormValues` and `ExpenseFormValues`.

Name the category type `ExpenseCategory`, never `Category`.

**Two constraints Part 1 imposes here. Both fail silently — unit tests stay green and only a
live write rejects.**

**Every timestamp must be `@Contextual val x: Instant`.** Kotlin binds serializers at compile
time, so the per-call marker that produces `meta.values` needs a per-call `SerializersModule`,
and only `@Contextual` reaches it. Naming `InstantSerializer` directly on a field — which
compiles, and which the same module can see — encodes the date with **no annotation at all**.
It decodes perfectly. The server rejects the write.

**An explicit JSON null cannot be a Kotlin `null`.** The encoder runs `explicitNulls = false`
to get Swift's omit-nil behaviour, which is what makes "omitted is not cleared" work. So a
field that must send a literal `null` — `originalCurrency`, and only it — has to be typed
`JsonElement?` and given `JsonNull`. A Kotlin `null` there is omitted, and the server leaves
the column alone instead of clearing it.

- [ ] **Step 6: Run the tests** — expected PASS.

- [ ] **Step 7: Commit**

```bash
git add api/ Scripts/ && git commit -m "Wire models and form values, against recorded fixtures"
```

---

## Part 4 — Endpoints and the stats fallback

**Goal:** Every procedure cycle 1 calls, including the one that has two names.

**Files:**
- Create: `api/src/main/kotlin/app/spliit/api/SpliitEndpoints.kt`
- Test: `api/src/test/kotlin/app/spliit/api/EndpointsTest.kt`, `LiveApiTest.kt`

- [ ] **Step 1: Write the failing fallback test**

The single most valuable test in this part.

```kotlin
@Test fun `groupStats falls back to the old procedure name`() {
    // Asking only groups.stats.get shipped a polite, wrong "no totals" to everyone on
    // spliit.app after upstream renamed it.
    server.enqueue(notFound("groups.stats.overview"))
    server.enqueue(success(statsJson))
    val stats = client.groupStats(groupId = "abc")
    assertEquals(4250, stats.totalGroupSpendings)
    assertEquals("groups.stats.overview", server.takeRequest().path.procedure())
    assertEquals("groups.stats.get", server.takeRequest().path.procedure())
}

@Test fun `groupStats rethrows when the instance answers neither name`()
```

- [ ] **Step 2: Write the remaining endpoint tests** — one per procedure, asserting path and
  input shape: `groups.list`, `groups.get`, `groups.getDetails`, `groups.create`,
  `groups.update`, `groups.expenses.{list,get,create,update,delete}`, `groups.balances.list`,
  `groups.stats.overview`, `categories.list`.

- [ ] **Step 3: Write the live tests, tagged so they do not run by default**

```kotlin
@Tag("live")
class LiveApiTest {
    @Test fun `creates a group, adds an expense, reads the balances back`()
    @Test fun `the server accepts our date annotations`()
        // Only a live server proves the envelopes we send pass its validation.
}
```

Wire `make test-live` to run only this tag, and `make test` to exclude it.

- [ ] **Step 4: Run, implement, run again** — expected PASS.

- [ ] **Step 5: Commit**

```bash
git add api/ && git commit -m "Endpoints, and asking stats under both its names"
```

---

## Part 5 — Money and currency

**Goal:** Format an amount correctly in every currency Spliit supports.

**Files:**
- Create: `core/src/main/kotlin/app/spliit/core/MoneyFormatter.kt`, `Currencies.kt`
- Test: `core/src/test/kotlin/app/spliit/core/MoneyFormatterTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `1234 is 12,34 in a two-decimal currency`()
@Test fun `1234 is 1234 yen, not 12,34`() {
    // 34 of the 159 currencies have no minor unit at all.
    assertEquals(0, MoneyFormatter.minorUnitDigits("JPY"))
}
@Test fun `a dinar has three minor digits`() {
    assertEquals(3, MoneyFormatter.minorUnitDigits("KWD"))
}
@Test fun `a group with only a symbol and no code is hundredths`() {
    // That is what it was stored as.
    assertEquals(2, MoneyFormatter.minorUnitDigits(null))
}
@Test fun `an unknown code falls back to hundredths rather than to -1`() {
    // Currency.getDefaultFractionDigits returns -1 for codes it does not know.
}
@Test fun `parses a typed amount into minor units at the currency's precision`()
@Test fun `rounds a non-integer share half up on the way to the display`()
```

- [ ] **Step 2: Run and watch them fail.**

- [ ] **Step 3: Implement**

`minorUnitDigits` reads `java.util.Currency.getInstance(code).defaultFractionDigits`, mapping
its `-1` and any unknown code to 2. Formatting goes through `NumberFormat.getCurrencyInstance`
so symbols, placement and grouping are the locale's business, not ours.

**Never divide by 100 anywhere in the codebase.** A reviewer should grep for `/ 100` and
`100.0` in this part and find nothing.

- [ ] **Step 4: Run the tests** — expected PASS.

- [ ] **Step 5: Commit**

```bash
git add core/ && git commit -m "Money formatting, in currencies that are not all hundredths"
```

---

## Part 6 — Expense form draft and the split maths

**Goal:** Turn what someone typed into an `ExpenseFormValues` the server accepts, and back.

**Files:**
- Create: `core/src/main/kotlin/app/spliit/core/ExpenseFormDraft.kt`
- Test: `core/src/test/kotlin/app/spliit/core/ExpenseFormDraftTest.kt`

- [ ] **Step 1: Write the failing shares tests**

The rule that will be got wrong if it is not tested first.

```kotlin
@Test fun `an evenly split expense sends shares of 100 per participant`()
@Test fun `by-shares sends the share value times 100`()
@Test fun `by-percentage sends the percentage times 100`()
@Test fun `by-amount sends raw minor units, not times 100`() {
    // The one mode whose shares scale with the currency.
}
@Test fun `by-amount in a yen group sends whole yen`()
@Test fun `an even split apportions the remainder in whole minor units`() {
    // A third of 10.00 is 334/333/333, not 333.33 three times.
}
```

- [ ] **Step 2: Write the failing conversion tests**

```kotlin
@Test fun `a converted expense keeps two amounts on two different scales`() {
    // A EUR 40.00 dinner in a yen group: originalAmount 4000, amount 6540.
}
@Test fun `dropping the conversion sends originalCurrency as null and omits the other two`()
@Test fun `editing an expense with no originalCurrency ignores the other conversion fields`()
```

- [ ] **Step 3: Write the failing validation tests** — a title is required, the amount must be
  non-zero, at least one participant must be paid for, percentages must total 100, by-amount
  shares must total the expense amount.

- [ ] **Step 4: Run, implement, run again** — expected PASS.

- [ ] **Step 5: Commit**

```bash
git add core/ && git commit -m "Expense drafts, and shares that mean four different things"
```

---

## Part 7 — Group form draft and the default split

**Goal:** Group validation, and the saved split with its three rules.

**Files:**
- Create: `core/src/main/kotlin/app/spliit/core/GroupFormDraft.kt`, `DefaultSplit.kt`
- Test: `core/src/test/kotlin/app/spliit/core/GroupFormDraftTest.kt`, `DefaultSplitTest.kt`

- [ ] **Step 1: Write the failing default-split tests**

```kotlin
@Test fun `by-amount keeps only its mode`() {
    // Its shares are one receipt's amounts; they would never suit the next expense.
}
@Test fun `a split naming a departed participant is dropped whole, not trimmed`() {
    // 70/30 with the 30 removed is a percentage split nobody chose and that cannot be saved.
}
@Test fun `an even split of the whole group is stored as membership alone`() {
    // Stored as names instead, a flatmate who moves in next month is silently left out of
    // every expense from then on.
}
@Test fun `a partial even split keeps its names`() {
    // Nobody joins 50/30/20 without breaking it, so those modes keep their names.
}
```

- [ ] **Step 2: Write the failing group tests** — a name is required, participant names must be
  non-empty and unique, a dropped currency code is sent as `""`, and a participant who appears
  on an expense cannot be removed.

- [ ] **Step 3: Run, implement, run again** — expected PASS.

- [ ] **Step 4: Commit**

```bash
git add core/ && git commit -m "Group drafts and the saved split's three rules"
```

---

## Part 8 — The recent-groups store

**Goal:** Remember which groups this phone knows about, who you are in each, and your saved split.

**Files:**
- Create: `core/src/main/kotlin/app/spliit/core/RecentGroups.kt`, `RecentGroupsStore.kt`,
  `DateBuckets.kt`, `LoadState.kt`
- Create: `app/src/main/kotlin/app/spliit/android/data/DataStoreRecentGroupsStore.kt`
- Test: `core/src/test/kotlin/app/spliit/core/RecentGroupsTest.kt`, `DateBucketsTest.kt`

- [ ] **Step 1: Write the failing store tests**

```kotlin
@Test fun `every mutation stamps updatedAt`() {
    // Including the ones that do not change the order — a later cycle's cloud merge
    // settles conflicts by it, and an unstamped row loses silently.
}
@Test fun `only opening a group touches lastOpenedAt`()
@Test fun `actorId resolves who this phone is in a group`()
@Test fun `actorId is null when the participant has since been removed`() {
    // A write with a stale participantId must not claim to be someone who left.
}
@Test fun `forgetting a group leaves a tombstone`()
```

- [ ] **Step 2: Write the failing date-bucket tests** — today, yesterday, earlier this week,
  by month, then by year; and that bucket boundaries follow the device's time zone.

- [ ] **Step 3: Run, implement, run again**

`RecentGroupsStore` is an interface in `:core` so the merge rules stay JVM-testable; the
DataStore implementation in `:app` is a thin adapter with no logic of its own.

- [ ] **Step 4: Commit**

```bash
git add core/ app/ && git commit -m "The recent-groups store, and date bucketing"
```

---

## Part 9 — App shell, theme and design system

**Goal:** A running app with the right colours and typography, and nothing to do in it yet.

**Files:**
- Create: `app/src/main/kotlin/app/spliit/android/MainActivity.kt`, `SpliitApp.kt`,
  `Navigation.kt`, `TestTags.kt`
- Create: `app/src/main/kotlin/app/spliit/android/ui/theme/{Color,Type,Theme}.kt`
- Create: `app/src/main/kotlin/app/spliit/android/ui/design/{Money,Monogram,CategoryIcon,EmptyState,DateHeader}.kt`

- [ ] **Step 1: Define only the colours DESIGN.md defines**

The money axis, the eight monogram colours, the accent and the soft accent tint — and nothing
else. Every surface, separator and fill comes from M3's `ColorScheme`. A reviewer should check
that no background or divider colour is hardcoded anywhere in this part.

- [ ] **Step 2: Write `Money`**

Four sizes mapped to the M3 type scale (`displaySmall`, `headlineSmall`, `bodyLarge`,
`bodySmall`), tabular figures, and a `Sign` that takes its tint from the signed total. Settled
is `onSurfaceVariant`, not a third colour. An expense amount has no direction and takes no tint.

- [ ] **Step 3: Write `Monogram`** — initials on one of the eight palette colours, chosen by a
  stable hash of the participant ID so a person keeps their colour between screens.

- [ ] **Step 4: Add the navigation graph** with routes for every cycle-1 screen, each a
  placeholder for now.

- [ ] **Step 5: Verify it runs**

```bash
make emulator && make run && make shot
```

Expected: the app launches to an empty groups list in the right accent colour. Check `make shot`
in both light and dark.

- [ ] **Step 6: Commit**

```bash
git add app/ && git commit -m "App shell, theme and the design system pieces"
```

---

## Part 10 — Groups list, add by URL, group form

**Goal:** The first part where the app does something. Create a group, or add one you already have.

**Files:**
- Create: `app/src/main/kotlin/app/spliit/android/feature/groups/{GroupsListScreen,GroupsListViewModel,AddGroupByUrlScreen,GroupFormScreen,GroupFormViewModel}.kt`
- Test: `app/src/test/kotlin/app/spliit/android/feature/groups/GroupsListViewModelTest.kt`

- [ ] **Step 1: Write the failing ViewModel tests**

```kotlin
@Test fun `loads the stored group IDs and fetches their summaries`()
@Test fun `a group the server no longer has drops out of the list silently`() {
    // groups.list omits unknown IDs; that is how a deleted group shows up.
}
@Test fun `a failed load offers a retry rather than an empty list`()
@Test fun `parses a group ID out of a pasted spliit.app URL`()
@Test fun `parses a group ID out of a self-hosted instance URL and remembers the instance`()
@Test fun `rejects a URL that is not a group link`()
```

- [ ] **Step 2: Run, implement, run again.**

- [ ] **Step 3: Build the screens** — list with monogram, name and participant count; an empty
  state; a FAB to create; a menu entry to add by URL. Add `testTag`s from `TestTags.kt` on
  leaves only.

- [ ] **Step 4: Verify against a real server**

```bash
make e2e-up && make e2e-seed && make run
```

Expected: add a seeded group by its URL and see it in the list.

- [ ] **Step 5: Commit**

```bash
git add app/ && git commit -m "Groups list, adding by URL, and the group form"
```

---

## Part 11 — Group detail: expenses and balances

**Goal:** Open a group and see what it contains.

**Files:**
- Create: `app/src/main/kotlin/app/spliit/android/feature/group/{GroupDetailScreen,GroupDetailViewModel,ExpenseListTab,BalancesTab,ExpenseRow,ActiveUserPicker}.kt`
- Test: `app/src/test/kotlin/app/spliit/android/feature/group/GroupDetailViewModelTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `pages the expense list by the offset cursor the server offers`()
@Test fun `groups expenses under date headers`()
@Test fun `the balances tab reads only total, never paid or paidFor`() {
    // Those two are derived from the suggested payments, not the expenses. One is always
    // zero and the other is abs(total).
}
@Test fun `your own balance leads the tab once you have said who you are`()
@Test fun `reimbursements list the fewest payments that settle the group`()
```

- [ ] **Step 2: Run, implement, run again.**

- [ ] **Step 3: Build the screens** — tabs for Expenses and Balances, date-bucketed rows,
  search, and the active-user picker that records who this phone is.

Amounts under a caption that already says the direction are drawn unsigned.

- [ ] **Step 4: Verify against the seeded server, then commit**

```bash
git add app/ && git commit -m "Group detail: the expense list and the balances tab"
```

---

## Part 12 — Expense create and edit

**Goal:** The biggest screen in the app. Add what you spent and split it the way it happened.

**Files:**
- Create: `app/src/main/kotlin/app/spliit/android/feature/expense/{ExpenseFormScreen,ExpenseFormViewModel,SplitModeSection,PaidForSection}.kt`
- Test: `app/src/test/kotlin/app/spliit/android/feature/expense/ExpenseFormViewModelTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `saving passes the actor's participantId`() {
    // Omit it and every line in the activity log reads "Someone", for good.
}
@Test fun `switching split mode keeps the participants and recomputes the shares`()
@Test fun `the saved default split is applied to a new expense`()
@Test fun `editing an existing expense round-trips every field unchanged`()
@Test fun `a validation failure names the field rather than failing the whole save`()
```

- [ ] **Step 2: Run, implement, run again.**

- [ ] **Step 3: Build the screen** — title, amount, payer, split mode with its four editors,
  date, category, notes, reimbursement toggle, and the optional currency conversion.

Keep one composable per row whose state changes, rather than swapping which composable is
there — the iOS app's receipt scanner shipped a bug where a row that became a progress
indicator rebuilt its presenter and dismissed the next thing shown.

- [ ] **Step 4: Verify the round trip against a real server**

Create an expense, reopen it, confirm every field survived, and check the balances moved.

- [ ] **Step 5: Commit**

```bash
git add app/ && git commit -m "Creating and editing expenses"
```

---

## Part 13 — Settings and the currency picker

**Goal:** The small screens.

**Files:**
- Create: `app/src/main/kotlin/app/spliit/android/feature/settings/{SettingsScreen,SettingsViewModel,CurrencyPickerScreen}.kt`
- Test: `app/src/test/kotlin/app/spliit/android/feature/settings/CurrencyPickerTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `the picker lists every currency in the user's own language`() {
    // Names and symbols come from java.util.Currency, so there is no table in this repo.
}
@Test fun `sorts with a collator, so accented names land in the right place`() {
    // "Épicerie" sorts after "Vêtements" on the strength of its accent with a naive sort.
}
@Test fun `remembers the default instance URL`()
```

- [ ] **Step 2: Run, implement, run again, commit**

```bash
git add app/ && git commit -m "Settings and the currency picker"
```

---

## Part 14 — Instrumented tests and CI wiring

**Goal:** The app itself, tested on an emulator against a real server, in CI.

**Files:**
- Create: `app/src/androidTest/kotlin/app/spliit/android/{GroupFlowTest,ExpenseFlowTest}.kt`
- Modify: `.github/workflows/ci.yml`

- [ ] **Step 1: Write the flows**

Add a group by URL, open it, create an expense, confirm the balance moved, edit it, delete it.

**No unbounded loops.** `while (!node.isDisplayed()) scrollDown()` turned a missing element into
a 40-minute CI job on iOS. Bound every scroll and assert afterwards.

- [ ] **Step 2: Run them**

```bash
make e2e-up && make e2e-seed && make e2e
```

- [ ] **Step 3: Wire CI** — add the emulator job with `reactivecircus/android-emulator-runner`,
  bringing the e2e stack up as a service.

- [ ] **Step 4: Commit**

```bash
git add app/ .github/ && git commit -m "Instrumented flows, and the emulator job in CI"
```

---

## Done when

- `make test` is green and runs in seconds without an emulator
- `make test-live` is green against a local instance
- `make e2e` is green on `Pixel_8_API_31`
- `make run` installs an app that creates a group, adds an expense and shows correct balances
  against `spliit.app`
- `CLAUDE.md` and `DESIGN.md` describe what was actually built
