# Working on Spliit for Android

A Kotlin/Compose port of [Spliit for iOS](https://github.com/spliit-app/spliit-ios), talking to
the same servers. See [DESIGN.md](DESIGN.md) before touching a screen, [README.md](README.md)
for the full picture, and `docs/superpowers/specs/` for the spec each cycle was built from.

This is **cycle 1**. The modules exist and the build is green; most of the app does not exist
yet. Adding something the spec for this cycle does not list is not being helpful.

## How work happens here

**Work on `development`; `master` is what ships.** `master` is protected, changes arrive by
pull request, and the `build` check has to be green before one can merge. It is also the only
branch CI builds, and the only branch a release tag is cut from.

```
development  →  pull request  →  CI  →  master  →  tag  →  release
```

Pushing to `development` deliberately spends no CI run. What matters about a commit there is
whether it survives the pull request, which is where the suites run.

Two things about that protection are worth knowing rather than discovering. **Admins bypass it.**
`enforce_admins` is off, so a direct push to `master` from the repository owner still goes
through, git prints `Bypassed rule violations` and does it anyway. The rule is a guard rail for
the normal path, not a lock. And **`build` is the only required check**: `upstream-drift` is
skipped on a push, and requiring a check that never runs would make every pull request
unmergeable.

Commits in this repo need `-c commit.gpgsign=false --no-verify`; signing and hooks hang here.

### Commit messages

**[Conventional Commits](https://www.conventionalcommits.org).** The same format the docs
vault uses, so one habit covers both:

```
type(scope): summary in the imperative, lower case, no full stop
```

`type` is one of **feat, fix, docs, test, build, ci, refactor, perf, chore, revert**. `scope` is
the part of the repo it lands in, **api, core, app, build, ci, design, e2e**, and is left off
when a change spans everything. Keep the subject under about 72 characters.

```
fix(build): treat blank signing variables as unset
docs: rewrite the README for people who just want the app
feat(core): rate-limit refreshing
```

**A body is for *why*, and only when why is not obvious.** A one-line change does not need one;
a decision, a trap, or a bug whose cause is not visible in the diff does. That is the same rule
the code comments follow, see *Style* at the bottom of this file. What the change does is
already in the diff.

`git config commit.template .gitmessage` puts the format in front of you while you write.

Nothing enforces this in a hook, deliberately: hooks hang in this repo, which is why every
commit here passes `--no-verify` anyway. It is a convention people keep, not a gate.

Commits made before this was written do not follow it, and are left alone, they are pushed, and
rewriting shared history to tidy up message formatting is a bad trade.

## Commands

Android Studio is never required, everything runs from the Makefile, and `make` on its own
lists every target.

```sh
make test      # :api and :core on the JVM. Seconds. No emulator.
make build     # assemble the debug APK
make lint      # Android Lint
make e2e-up    # a throwaway Spliit on :3009 (Docker)
make e2e-seed  # fixture groups and expenses
make run       # install and launch on the running emulator
make shot      # screenshot it into build/screenshot.png
```

`JAVA_HOME` is exported by the Makefile to Android Studio's JBR. Calling `./gradlew` directly
without it picks up the system `java`, which is 11 on this machine and too old for AGP 9.4 -
that failure reads as a cryptic class-version error, not as "wrong JDK".

## Layout

```
api/     Kotlin/JVM, TrpcClient, SuperJson, Models, SpliitEndpoints, TrpcError
core/    Kotlin/JVM, MoneyFormatter, Currency, form drafts, DefaultSplit,
         RecentGroupsStore (interface), date bucketing
app/     Compose M3 screens, ViewModels, DataStore, resources
e2e/     compose.yaml, seed.mjs, the disposable server the suites run against
gradle/libs.versions.toml   every version number in the project
```

**`:api` and `:core` are pure Kotlin/JVM and must stay that way.** Neither applies an Android
plugin, and the day one does, `make test` stops being a thing you run without thinking about
it. That is the property that makes this repo pleasant to work in, and it is load-bearing, not
decorative. If something in `:api` or `:core` seems to need a `Context`, the part that needs it
belongs in `:app` and the part that does not stays where it is.

**`:core` does not depend on `:api`, deliberately.** The maths is about amounts and
participants, not about wire formats. Letting a server model become the shape the arithmetic is
written against is how the money bugs below get harder to see. `:app` is where the two meet.

## The build

Versions live in `gradle/libs.versions.toml` and nowhere else.

| | Version | Why it is pinned there |
|---|---|---|
| Gradle | 9.7.1 | AGP 9.4 requires ≥ 9.6.0 |
| AGP | 9.4.0 | max API level 37 |
| Kotlin | 2.4.20 | the Compose compiler plugin is versioned with it |
| JDK | 21 | the toolchain every module compiles against; AGP needs ≥ 17 |
| compileSdk / targetSdk | 37 (minor 1) | see below |
| minSdk | 26 | `java.time` natively, so `:core` needs no date library |

`:api` and `:core` run in **explicit API mode**. A public declaration needs a visibility
modifier and a declared return type, so every piece of their surface is a decision rather than
a default. It applies to main sources only, the Kotlin Gradle plugin exempts test source sets,
so tests need nothing.

A test-only base URL is passed as the Gradle property **`spliit.baseUrl`**, dotted and
namespaced, like `android.useAndroidX`, and spelled that way in both places the Makefile passes
it. Nothing reads it yet; Parts 1-8 should read that name and not invent a second one.

**AGP 9 compiles Kotlin itself, and applying `org.jetbrains.kotlin.android` alongside it is a
hard error.** Not a deprecation warning, the build fails outright with "the plugin is no
longer required for Kotlin support since AGP 9.0". So `:app` applies
`com.android.application` and the Compose compiler plugin, and nothing else. `:api` and `:core`
still need `org.jetbrains.kotlin.jvm`, because they have no AGP to bring Kotlin with it. There
is deliberately no `kotlin-android` alias in the version catalogue, so nobody reaches for one.

**Android platforms now have minor versions, and AGP 9 wants them as two properties.** The
installed platform is `android-37.1`, there is no bare `android-37` directory, and its
`source.properties` says `AndroidVersion.ApiLevel=37.1`. The DSL that matches it is:

```kotlin
compileSdk = 37
compileSdkMinor = 1
```

`compileSdkMinor` is a real AGP 9 property and this is the combination that builds. The
fallback, installing `platforms/android-37.0` and using a bare `compileSdk = 37`, was not
needed and was not used. `targetSdk` takes no minor: it stays a plain `37`. If a future
platform install leaves you with a different minor, that one line is what changes.

## Things that will bite you

These are the iOS app's hard-won list, rewritten for Kotlin. Every one of them fails *silently* -
no crash, no error, just a wrong number on a screen. They are roughly in order of how quietly
they go wrong.

**The JDK that runs Gradle is not the JDK the build compiles with, and Android Studio moves
the first one out from under you.** The Makefile points `JAVA_HOME` at Studio's JBR so Gradle
itself has something modern to run on; the modules separately ask for a **21** toolchain. Those
were the same JDK until a Studio update took the JBR to 25, at which point the build stopped
with "Cannot find a Java installation … matching {languageVersion=21}", on a machine whose only
other JDK is the system 11. The failure names the toolchain, not the JBR, so it reads as a
project misconfiguration rather than as an IDE upgrade. `settings.gradle.kts` applies the foojay
resolver, which lets Gradle fetch the JDK the build asked for instead of taking whatever happens
to be installed. Do not "fix" a repeat of this by bumping the toolchain to match the JBR: that
silently changes the bytecode target for everyone, CI included.

*Running `updateDaemonJvm` is not the fix either.* It writes
`gradle/gradle-daemon-jvm.properties` pinning the **daemon**, which was never the thing that was
wrong, and leaves the toolchain error exactly as it was.

**The bundled emulator segfaults; update it before believing a boot failure.** The SDK shipped
`emulator` 35.5.10 (2024), which dies with `Segmentation fault: 11` in `qemu-system-aarch64`
partway through booting `Pixel_8_API_31` on Apple silicon, including with
`-no-snapshot -gpu swiftshader_indirect`. It looks exactly like a broken AVD and is not one.
`android sdk install emulator` brings 37.1.11, which boots the same AVD in about 20 seconds.
This cost a part's visual verification before anyone thought to check the emulator's own version.

**Money is integer minor units, and minor units are not always hundredths.** `amount == 1234`
is 12.34 in a two-decimal currency and ¥1,234 in a group counted in yen. Of the 155 currencies
this app's picker offers, **16 have no minor unit at all and 7 have three**, measured on the
JDK we build against (21), not inherited: the iOS app reads CLDR's `commonISOCurrencyCodes` and
counts 34 of 159, so its figures do not describe this codebase. `CurrenciesTest` is what keeps
these honest. **Never divide by 100.** Ask
`Currency.getInstance(code).defaultFractionDigits`, and handle its `-1` for a code Java does
not know. A group carrying only a symbol and no ISO code is hundredths, because that is what it
was stored as.

Two JDK traps sit under that, both verified rather than assumed. **`DecimalFormat.setCurrency`
does not change the fraction digits**, it is documented not to, so setting USD on a `ja-JP`
formatter keeps Japan's zero digits and draws `$12` for 1234 minor units. Set the digits
explicitly afterwards. And **uppercase currency codes with `Locale.ROOT`**: under a Turkish
default locale `"iqd".uppercase()` is `İQD`, which is not a currency any more.

**`paidFor[].shares` changes meaning with the split mode.** It is the share value ×100 for
`EVENLY`, `BY_SHARES` and `BY_PERCENTAGE`, whatever the currency, and a raw minor-unit amount
for `BY_AMOUNT`, which does scale with the currency. One field, two units, decided by a sibling
field.

**An expense paid in another currency carries two amounts on two different scales.**
`originalAmount` is in `originalCurrency`'s minor units; `amount` is in the group's. Formatting
either with the other's currency is a bug that looks plausible.

**`notes` and `information` reject an explicit null; the conversion fields reject it too.**
Verified against a live instance: `groups.expenses.create` with `"notes": null` answers 400
`invalid_type, expected string, received null`, and `groupFormValues.information` behaves the
same. Our DTOs type both as `String?` with `explicitNulls = false`, so a Kotlin null is
*omitted* rather than sent, which is why the client never trips this, and why nobody should
"fix" those fields into something that encodes a literal null.

**Omitted is not cleared.** A field absent from the request is `undefined` to tRPC, and Prisma
skips the column, so clearing something means sending an empty value, not leaving it out, and
not always null. What counts as empty is per field: `""` for a dropped currency code; an
explicit `null` for `originalCurrency`, the only conversion field whose zod schema accepts one;
`originalAmount` and `conversionRate` take a number, a numeric string or `''` and answer 400 to
null. So: `encodeDefaults = false`, plus explicit nulls exactly where the schema wants them.

**`totalParticipantShare` is the one amount in the API that is not an integer.** Instances older
than the web app's *Shares* change sum floating-point thirds and round to two decimals, sending
`1416.67`. Declare it as a floating type on the wire and round on the way to the display. Typing
it as `Int` throws on the totals screen, against real servers, for a subset of users.

**An endpoint that may have been renamed has to be asked for under every name it goes by.** Try
`groups.stats.overview`, fall back to the removed `groups.stats.get`. Asking only the old name
is what shipped a polite, wrong "this server has no totals" to everyone on spliit.app. An
instance that answers to neither genuinely has none, degrade to a message, not to a retry that
can never succeed.

**`groups.balances.list` does not tell you what anyone paid.** Its `paid` and `paidFor` are
derived from the suggested payments rather than from the expenses: one is always zero and the
other is `abs(total)`. Only `total` means anything. Reading the other two gives you numbers that
are real, stable, and about something else.

**Who did it is something you have to tell the server.** `groups.update` and all three
`groups.expenses.*` mutations take an optional `participantId`, and it is the only thing the
activity log can name anybody with. Omit it and the write still succeeds, and reads "Someone"
for good, because nothing backfills it.

**A saved split is the client's, not the server's.** `saveDefaultSplittingOptions` is sent,
validated, and never read by any procedure; the web app keeps it in `localStorage`. Ours lives
on the recent-group row. Three rules travel with it: `BY_AMOUNT` keeps only its mode; a split
naming a participant who has left is dropped whole rather than trimmed; an even split of the
whole group is stored as membership alone, so a member who joins later is not silently excluded.

**Query input is percent-encoded against the unreserved set only.** A `+` or `&` left alone
inside a query value corrupts the envelope, and the server's complaint will be about the
envelope rather than about the character.

**Decoding ignores `meta.values`; encoding still emits it.** The models are statically typed, so
a field the server annotates as a date is already declared one, and `groups.list` sends
`createdAt` with no annotation at all, so trusting the metadata breaks exactly one endpoint.
Encoding does emit annotations, because the server rebuilds real `Date` instances before its own
validation runs.

**`localhost` inside an emulator is the emulator.** The host is `10.0.2.2`. `E2E_URL` in the
Makefile is the app's address and uses it; seeding runs on the host and uses `localhost`.
Swapping them produces a connection refused indistinguishable from the stack being down.

**CI needs cmdline-tools >= 20.0, and the runner image does not have it.** Minor-versioned
platforms like `android-37.1` are parsed correctly only from cmdline-tools 20.0 onward. The
`ubuntu-24.04` image ships 12.0, which does not reject the coordinate, it mis-parses the
version and carries on, which is how you get an AVD targeting `android-0` while every command
reports success. `.github/workflows/ci.yml` therefore pins the tools version explicitly rather
than taking a default that could drift back under the threshold.

**The SDK installer exits 0 when it installs nothing.** `Package … not found` goes to stdout
and the process still returns success, so a typo'd or unavailable coordinate is a green step
that installs nothing, and the failure surfaces much later as an AGP complaint about
`compileSdk`. CI asserts the platform directory exists afterwards; do the same for anything
else it installs.

**Refreshing is rate-limited, and the limit is on the work rather than the launcher.**
`RefreshLimiter` allows one refresh per five seconds; pull-to-refresh and the retry buttons go
through it, and first loads, lazily-opened tabs and write-triggered reloads do not, those happen
once and must not be dropped. The check sits inside `refreshInPlace`/`applyExpenseChange`, not
inside `pullToRefresh`, for a reason worth keeping: `viewModelScope` dispatches on Main, which the
JVM suites deliberately do not install, so anything guarded inside the launcher is guarded where
no unit test can reach it. Two things travel with this. A refused refresh must still clear
`isRefreshing`, because `PullToRefreshBox` keeps its indicator up until the callback returns, drop
the work without clearing it and the spinner turns over nothing. And a write **resets** the
limiter: the screen is known to be stale, so the "you just asked" reasoning does not apply.

**A fixture that still decodes is not a contract that still holds.** `ApiContractDriftTest`
(`@Tag("live")`) compares the *shape* of every live response against its recorded fixture and
names each field that appeared, vanished or changed type. The decode tests cannot do this: a model
that declares a field optional accepts its disappearance silently, which is exactly how the
`groups.stats` rename stayed green. Empty lists, nulls and superjson's `meta` are ignored on
purpose, they describe how much data exists, not the contract, and `balances` is compared by its
values because its keys are server-generated participant IDs. When a change is real, `make
fixtures` re-records and the diff in that commit is the changelog.

**Participant rows on the group form are sorted by name, so a row moves as you type into it.**
`GroupFormDraft.sortedParticipants` re-sorts on every keystroke, which means a UI test that
addresses `group_form_participant_field_1` because it was the second row added will type into
whichever row is second *now*. The symptom is not a wrong name: it is the create sheet never
closing, because the row left blank fails validation. The instrumented suite addresses the row by
what is in it instead, see `blankParticipantFieldTag`.

**Never write an unbounded scroll loop in a UI test.** On iOS that turned a missing element into
a CI job that swiped for forty minutes. Bound the loop and assert.

**Do not `make e2e-down` while something else is testing.** The server is shared and its database
lives in tmpfs, so stopping it discards the data of every run in flight. `make e2e` leaves it up
on purpose.

## Testing

| Command | Covers | Needs |
|---|---|---|
| `make test` | superjson coding, response decoding, request building, money formatting, split maths, date bucketing | nothing |
| `make test-live` | the API client against a real server, including writes | `make e2e-up` |
| `make e2e` | the app itself, on an emulator, against a real server | Docker + emulator |

**Fixtures are recorded, not written.** `make fixtures` captures them from a real instance into
`api/src/test/resources/fixtures`. A hand-written fixture only proves the decoder agrees with
our own assumptions; a recorded one proves it agrees with the server, which is the only party
whose opinion counts. Assertions avoid the server-generated IDs, they change on every
re-record.

Compose `testTag`s belong in one shared object, used by both the app and the instrumented
tests, and added in the same commit as the screen they belong to.

## CI

`.github/workflows/ci.yml` runs `make test`, `make build` and `make lint` on every push and
pull request, on JDK 21, with no device involved.

The nightly `upstream-drift` job is not redundant with it. CI pins the server image so builds
are reproducible, which is precisely why it could not have caught the `groups.stats` rename -
only a job pointed at the live server can. It is allowed to fail: spliit.app being briefly down
is not a defect here, and a job that cries wolf gets muted.

## Style

Explain *why* in comments, not *what*, the code already says what. A comment that survives is
one that records a decision or a trap, not one that narrates the line below it.

No third-party dependency without a good reason. `:api` has OkHttp and
kotlinx-serialization because it needs an HTTP client and a codec; `:core` has nothing at all,
and `minSdk 26` is what keeps it that way.
