# Spliit for Android — design

**Date:** 2026-09-14
**Status:** awaiting approval
**Reference:** [`spliit-app/spliit-ios`](https://github.com/spliit-app/spliit-ios) — the SwiftUI app this ports

---

## 1. What this is

A native Kotlin/Compose Android client for [Spliit](https://spliit.app), ported from the
SwiftUI iOS app. Same server, same groups, no account: a group is reachable by its ID and
nothing else, and the phone talks to `spliit.app` or to an instance the user hosts.

This spec covers **cycle 1 only**. The iOS app is 131 Swift files across several independent
subsystems, which is too much for one spec. The decomposition is in §9; everything not listed
in §2 is explicitly deferred.

## 2. Scope

**In:**

- `:api` — the tRPC/superjson client, models and endpoints
- `:core` — money, currency, split maths, form drafts, recent-groups store
- `:app` — groups list, add group by URL, group form, group detail (expenses + balances),
  expense create/edit, settings, currency picker, active-user picker
- The development harness cloned from the iOS repo (§8)

**Out, deferred to later cycles:** receipt scanning, QR join, deep links, App Shortcuts,
cloud backup, documents/S3 upload, the stats and activity tabs, French.

## 3. Architecture

Three Gradle modules, mirroring the seams the iOS app already found. `:api` and `:core` are
**pure Kotlin/JVM with no Android dependency**, which is what makes `make test` run the
protocol handling, money maths and split logic in seconds without an emulator — the property
that makes the iOS repo pleasant to work in.

```
app/     Compose M3 screens, ViewModels, DataStore, resources
api/     Kotlin/JVM — TrpcClient, SuperJson, Models, SpliitEndpoints, TrpcError
core/    Kotlin/JVM — MoneyFormatter, Currency, ExpenseFormDraft, GroupFormDraft,
         DefaultSplit, RecentGroupsStore (interface), date bucketing
```

Dependencies are kept to the minimum equivalents of what iOS gets from the platform:
`:api` takes **OkHttp** (for `URLSession`) and **kotlinx-serialization** (for `Codable`), and
nothing else. `:core` takes nothing. `minSdk 26` provides `java.time` natively, so no
date library is needed — the iOS repo's "no third-party dependencies without a good reason"
rule carries over intact.

`:app` uses ViewModels + `StateFlow`, a `LoadState` sealed type mirroring iOS's, Navigation
Compose, and DataStore for the recent-groups list and settings. The `RecentGroupsStore`
interface lives in `:core` so the split maths and merge rules stay testable on the JVM; its
DataStore implementation lives in `:app`.

### Rejected alternatives

- **Single `:app` module with packages.** Faster to start, but unit tests drag in Android and
  the API layer drifts into UI concerns — precisely where this app is subtle.
- **Kotlin Multiplatform sharing a core with iOS.** The Swift app exists and will not adopt a
  KMP core. Pure overhead.

## 4. The transport

tRPC with the superjson transformer at `{baseURL}api/trpc`. There is no REST layer. Queries go
as `GET …?input=<envelope>`, mutations as `POST` with the envelope as the body, both
**unbatched**, which the server accepts.

**Decoding ignores `meta.values`.** The models are statically typed, so a field the server
annotates as a date is already declared one. This is not a shortcut: `groups.list` sends
`createdAt` with no annotation at all, so trusting the metadata would break exactly one
endpoint.

**Encoding does emit annotations**, because the server rebuilds real `Date` instances before
its own validation runs. The algorithm is the one the Swift version uses, which ports cleanly:
serialize to a `JsonElement` tree with a contextual `Instant` serializer that writes each
timestamp behind a per-call random marker, then walk the tree stripping markers and recording
the dot-separated key path of each, and emit those paths under `meta.values`.

Query input is percent-encoded against the unreserved set only — `+` and `&` left alone inside
a query value would corrupt the envelope.

## 5. The sharp edges

Ported deliberately. Each of these cost the iOS app real debugging time, and every one of them
fails silently.

**Money is integer minor units, and minor units are not always hundredths.** `amount == 1234`
is 12.34 in a two-decimal currency and ¥1,234 in a group counted in yen. 34 of the 159
currencies have no minor unit and 6 have three. Never divide by 100 — ask
`Currency.getInstance(code).defaultFractionDigits`, handling its `-1` for unknown codes. A
group with only a symbol and no code is hundredths, because that is what it was stored as.

**`paidFor[].shares` changes meaning with the split mode** — the share value ×100 for
`EVENLY`, `BY_SHARES` and `BY_PERCENTAGE` whatever the currency, but a raw minor-unit amount
for `BY_AMOUNT`, which does scale with it.

**An expense paid in another currency carries two amounts on two scales.** `originalAmount` is
in `originalCurrency`'s minor units, `amount` is in the group's.

**Omitted is not cleared.** A field absent from the request is `undefined` to tRPC and Prisma
skips the column, so clearing something means sending an empty value, not null. What counts as
empty is per field: `""` for a dropped currency code; an explicit `null` for
`originalCurrency`, the only conversion field whose zod schema takes one; `originalAmount` and
`conversionRate` accept a number, a numeric string or `''` and reject null with a 400. So
`encodeDefaults = false` plus explicit nulls where the schema wants them.

**`totalParticipantShare` is the one amount in the API that is not an integer.** Instances
older than the web app's *Shares* change sum floating-point thirds and round to two decimals,
sending `1416.67`. It must be a floating type on the wire, rounded on the way to the display,
or the totals screen throws.

**An endpoint that may be renamed has to be asked for under every name it goes by.**
`groups.stats.overview` first, falling back to the removed `groups.stats.get` — asking only the
old name shipped a polite, wrong "this server has no totals" to everyone on `spliit.app`. An
instance answering neither genuinely has none, and that degrades to a message rather than a
retry that can never work.

**`groups.balances.list` does not tell you what anyone paid.** Its `paid` and `paidFor` are
derived from the suggested payments, not the expenses — one is always zero and the other is
`abs(total)`. Only `total` means anything.

**Who did it is something you have to tell the server.** `groups.update` and all three
`groups.expenses.*` mutations take an optional `participantId`, and it is the only thing the
activity log can name anybody with. Omit it and the write still succeeds and reads "Someone"
for good, since nothing backfills it.

**A saved split is the client's, not the server's.** `saveDefaultSplittingOptions` is sent,
validated and never read by any procedure; the web app keeps it in `localStorage`. Ours lives
on the recent-group row. Three rules travel with it: `BY_AMOUNT` keeps only its mode; a split
naming a departed participant is dropped whole rather than trimmed; an even split of the whole
group is stored as membership alone, so a member who joins later is not silently left out.

## 6. Design

Material 3, deliberately tuned to the iOS app's density, type scale and colour so screenshots
line up side by side.

The iOS `DESIGN.md` defines **only** the money axis, the monogram palette and a soft accent
tint, leaving backgrounds, separators and fills to the system because matching those by hand is
a debt that comes due every release. That discipline ports directly: define only those colours
and let M3's `ColorScheme` own every surface.

| Token | Light | Dark | For |
|---|---|---|---|
| Accent | `#059669` | `#10B981` | Applied globally |
| MoneyPositive | `#047857` | `#34D399` | Owed to you |
| MoneyNegative | `#C2334A` | `#FF8A9B` | You owe |
| BrandAccentSoft | `#ECFDF5` | accent @ 16% | Empty-state icon tile |
| Monogram 1–8 | emerald, cyan, indigo, pink, orange, amber, olive, violet | same | Participants |

`.green`/`.red` are not used; the money axis is the branded pair, tuned per theme to clear
WCAG AA on the surface it sits on.

Money gets its own treatment — tabular figures, a rounded face — at four sizes chosen by how
much the number matters, mapped onto the M3 type scale and expressed in `sp` so they scale with
the system font size:

| iOS | Android | For |
|---|---|---|
| `.hero` | `displaySmall` | A balance headline or total |
| `.lead` | `headlineSmall` | A suggested payment |
| `.row` | `bodyLarge` | A list row — the default |
| `.support` | `bodySmall` | An inline aside |

Sign is carried by colour **only where the amount has a direction**: an expense amount has
none and stays default; a balance is positive, negative or settled, and settled is secondary
rather than a third colour. Amounts are drawn unsigned wherever a caption above already says
which way they go.

SF Rounded has no exact Android equivalent; money uses a rounded Google Font with tabular
figures, falling back to Roboto with `tnum`.

## 7. Testing

Three layers, all runnable from the command line, matching the iOS split.

| Command | Covers | Needs |
|---|---|---|
| `make test` | superjson coding, response decoding, request building, money formatting, split maths, date bucketing | nothing |
| `make test-live` | the API client against a real server, including writes | `make e2e-up` |
| `make e2e` | the app itself, on an emulator, against a real server | Docker |

**Fixtures are recorded, not written.** `make fixtures` captures them from a real instance into
`api/src/test/resources/fixtures`. Hand-written fixtures only prove the decoder agrees with our
own assumptions; recorded ones prove it agrees with the server.

Compose `testTag`s are centralised in one object shared by the app and the instrumented tests,
and added in the same commit as the screen they belong to. UI tests must never contain an
unbounded scroll loop — on iOS that turned a missing element into a 40-minute CI job.

## 8. The development harness

Cloned from the iOS repo, because the workflow is as much a part of what was built as the app.

```
Makefile                 every task; Gradle underneath. `make` lists them.
e2e/compose.yaml         throwaway Spliit instance on :3009, Postgres in tmpfs,
                         MinIO beside it. Cloned near-verbatim.
e2e/seed.mjs             fixture groups and expenses
Scripts/                 what the Makefile reaches for that isn't one line of shell
.github/workflows/ci.yml build, unit tests, lint on every push
CLAUDE.md                how work happens here, and §5 rewritten for Kotlin
DESIGN.md                §6, expanded, read before touching a screen
README.md ROADMAP.md
```

The nightly `upstream-drift` job matters and is kept: CI pins the server to a commit so builds
are reproducible, which is exactly why it could not have caught the `groups.stats` rename. Only
a job running against the latest server can.

## 9. Later cycles

Each gets its own spec → plan → build:

1. **Documents** — `POST /api/s3-upload`, the derived address, the re-encode that caps the
   image at 2048px and strips EXIF, and the failure that means "this instance keeps none".
2. **Receipt scanning** — ML Kit text recognition plus an on-device model, with the
   rule-based parser as the unaided fallback and the re-check of every field the model answers.
   Nothing leaves the device.
3. **Stats and activity tabs.**
4. **Platform integration** — deep links, QR join, App Shortcuts, cloud backup of the
   recent-groups list with the union-merge and tombstones the iOS one needs.
5. **Localization** — French, with categories translated on the client from the web app's
   `messages/fr-FR.json`.

## 10. Build configuration

Verified against live sources on 2026-09-14, latest stable throughout.

| | Version | Note |
|---|---|---|
| Gradle | 9.7.1 | AGP 9.4 requires ≥ 9.6.0 |
| AGP | 9.4.0 | September 2026; max API level 37 |
| Kotlin | 2.4.20 | Compose compiler plugin ships with it |
| JDK | 21 | Android Studio's JBR; AGP needs ≥ 17 |
| compileSdk / targetSdk | 37 | android-37.1 stable; 37.2 in beta |
| Build Tools | 37.0.0 | AGP 9.4 requires ≥ 36.0.0 |
| minSdk | 26 | `java.time` natively; runs on the `Pixel_8_API_31` emulator |
| Compose BOM | 2026.09.00 | Material3 1.4.0 |
| OkHttp | 5.5.0 | |
| kotlinx-serialization-json | 1.11.0 | |
| kotlinx-coroutines | 1.11.0 | |
| JUnit | 6.1.3 | |

`applicationId` is `app.spliit.android`. The iOS bundle ID is not reused; there is no Play
Store listing to update in place.

**Requires installing** into the existing SDK: modern `cmdline-tools` (the bundled one predates
JDK 11 and cannot run), `platforms;android-37`, `build-tools;37.0.0`.

**Risk:** AGP 9.4.0 is days old, and AGP 9 is the release that makes the new Variant API
mandatory in AGP 10 and tightens flavor-dimension rules. It is genuinely the latest and least
trodden. Falling back to AGP 9.2/9.3 is the mitigation if the build proves rough.

## 11. Open questions

- **Git workflow.** The iOS `CLAUDE.md` mandates that nothing is committed to `main` and every
  change lands through a worktree and a PR. This repo has no remote yet, so cycle 1 lands on
  `main` directly and the rule is adopted once a remote exists — unless a GitHub repo should be
  created up front.
- **Rounded typeface** for money — which Google Font, decided during implementation.
