# Spliit for Android

A native Kotlin/Compose client for [Spliit](https://spliit.app), the shared-expense app — ported
from the [SwiftUI iOS app](https://github.com/spliit-app/spliit-ios) and talking to the same
servers.

There are no accounts. A group is reachable by the ID the server gave it and by nothing else,
which is the whole of Spliit's privacy model: the phone holds a list of group IDs you have
visited, and the server is either `spliit.app` or an instance you host yourself.

**Status: cycle 1, in progress.** The build, the module boundaries and the development harness
are in place. The app itself is being built out; see
`docs/superpowers/specs/` for what each cycle covers, and [CLAUDE.md](CLAUDE.md) for how work
happens here.

## Getting started

You need an Android SDK with platform 37 and build-tools 37.0.0, and a JDK 17 or newer for
Gradle itself to run on — Android Studio ships both, and its bundled JBR is what the Makefile
points `JAVA_HOME` at, so installing Studio is the short path even though nothing here requires
you to open it. The **JDK 21** the modules compile against is a separate thing: Gradle fetches
it if it is not installed, so you do not have to go and find one. CLAUDE.md explains why those
are two different JDKs and what goes wrong when they are confused.

```sh
git clone <this repo> && cd spliit
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
make test
```

`make test` is the one to run first. It compiles `:api` and `:core` and runs their suites on the
JVM in a few seconds, with no emulator and no device, which is also the fastest way to find out
that your toolchain is wired up correctly.

`make` on its own lists every target.

## Running the app

```sh
make emulator   # boot the AVD and wait until it can take an install
make run        # assemble, install, launch
make shot       # screenshot it into build/screenshot.png
```

`make run` installs `app/build/outputs/apk/debug/app-debug.apk` and starts
`app.spliit.android/.MainActivity`.

## Getting the app

Every push builds an APK. Open the run under
[Actions](../../actions/workflows/ci.yml), and `spliit-debug-<sha>.zip` is at the bottom of the
page — that is the latest build of any branch, kept for 30 days.

Tagged versions are on the [releases page](../../releases) and are the ones worth keeping: an
APK named for its version, with a SHA-256 to check it against. Either way Android will ask you
to allow installing from that source the first time.

### Cutting a release

The version lives in `gradle/libs.versions.toml` and nowhere else:

```toml
appVersionName = "0.1.0"   # the human one; the tag is this with a leading v
appVersionCode = "1"       # the integer Android compares; only ever goes up
```

Bump both, commit, then tag and push:

```sh
git tag -a v0.1.0 -m "Spliit for Android 0.1.0"
git push origin v0.1.0
```

`.github/workflows/release.yml` checks the tag against `appVersionName` and **fails if they
disagree**, runs the tests and the linter, builds the release APK and opens a GitHub release
with it attached. Nothing about that needs a machine of yours.

### Signing

Without a signing key the release APK is signed with the **debug** key. It installs and runs,
and the release is marked a pre-release so nobody mistakes it for otherwise — but Android
identifies an app by its signature, so a build signed with one key cannot upgrade a copy
installed from another. Consecutive debug-signed releases have to be uninstalled and
reinstalled.

To publish properly signed builds, make a key and put it in the repository's secrets:

```sh
keytool -genkeypair -v -keystore release.jks -alias spliit \
        -keyalg RSA -keysize 2048 -validity 10000
base64 -i release.jks | pbcopy       # the value for SPLIIT_KEYSTORE_BASE64
```

| Secret | What it is |
|---|---|
| `SPLIIT_KEYSTORE_BASE64` | the keystore file, base64-encoded |
| `SPLIIT_KEYSTORE_PASSWORD` | its store password |
| `SPLIIT_KEY_ALIAS` | the alias inside it (`spliit` above) |
| `SPLIIT_KEY_PASSWORD` | that key's password |

**Keep `release.jks` and back it up.** Losing it means never being able to upgrade an installed
app again — a new key is a new app as far as every device is concerned. It is never committed;
the build reads it from a path, and the workflow writes it to a temporary file it throws away.

Locally, `make apk-release` builds the same APK, picking up a key from the same four names as
environment variables (`SPLIIT_KEYSTORE_PATH` rather than the base64 blob) and falling back to
the debug key when they are absent.

## Testing

Three layers, all runnable from the command line.

| Command | Covers | Needs |
|---|---|---|
| `make test` | superjson coding, response decoding, request building, money formatting, split maths, date bucketing | nothing |
| `make test-live` | the API client against a real server, including writes | `make e2e-up` |
| `make e2e` | the app itself, on an emulator, against a real server | Docker + emulator |

### Knowing what changed when upstream moves

`make test-live` carries **`ApiContractDriftTest`**, which is the answer to "the server changed —
what changed?". For every recorded fixture it fetches the same procedure from a live instance and
compares the two **shapes**, then names each field that appeared, vanished or changed type:

```
The live server's `groups.get` no longer matches the recorded fixture:

  result.data.json.group.currency: was NUMBER, is now STRING
  result.data.json.group.somethingUpstreamRemoved: gone — the fixture has it, the server no longer sends it
```

The decode tests next door cannot do this. They answer yes or no, and they stay green through the
most dangerous change there is — an optional field quietly disappearing — because a model that
declares something nullable accepts its absence without complaint. That is the exact shape of the
`groups.stats` rename that already shipped once.

Three kinds of difference are deliberately ignored, because they are about how much data exists
rather than about the contract: empty lists, nulls, and superjson's `meta` (which the decoder
never reads and which is keyed by array index). `balances` is compared by its values rather than
its keys, since those keys are server-generated participant IDs. `JsonShapeTest` pins all of it.

The nightly `upstream-drift` CI job runs this against spliit.app. It is allowed to fail — a red
mark there means go and look, not that this repo is broken. When a change is expected, `make
fixtures` re-records, and the diff in that commit is the changelog.
| `make lint` | Android Lint | nothing |

The split is the point. Almost everything subtle in this app — the transport, the money
arithmetic, the split rules — lives in modules that have no Android dependency, so the tests
that cover it run in seconds. Only the tests that genuinely need a device use one.

The end-to-end server is a throwaway Spliit instance in Docker: Postgres in tmpfs, MinIO beside
it for expense documents, everything discarded on the way down. See [e2e/README.md](e2e/README.md).

**Fixtures are recorded, not written.** `make fixtures` captures them from a real instance.
A hand-written fixture proves the decoder agrees with our assumptions; a recorded one proves it
agrees with the server.

CI runs the first and last of these on every push, and a nightly job runs `make test-live`
against the live `spliit.app`. That job exists because CI pins the server image for
reproducibility, and a pinned server cannot tell you that upstream has renamed an endpoint — a
failure this project's iOS sibling shipped to users once already.

## Layout

```
api/     Kotlin/JVM — the tRPC/superjson client, models, endpoints
core/    Kotlin/JVM — money, currency, split maths, form drafts, date bucketing
app/     Compose M3 screens, ViewModels, DataStore, resources
e2e/     the disposable Spliit instance the suites run against
gradle/libs.versions.toml   every version number in the project
```

`:api` and `:core` are **pure Kotlin/JVM with no Android dependency**, and that is a rule rather
than a coincidence. It is what makes `make test` fast enough to run without thinking, and it is
what keeps transport and arithmetic from quietly acquiring UI concerns. `:core` does not depend
on `:api` either: the maths is about amounts and participants, not about wire formats. `:app` is
where the two meet.

## How it talks to Spliit

tRPC with the superjson transformer, at `{baseURL}api/trpc`. There is no REST layer. Queries go
as `GET …?input=<envelope>`, mutations as `POST` with the envelope in the body — both
**unbatched**, which the server accepts.

Decoding ignores superjson's `meta.values`. The models are statically typed, so a field the
server annotates as a date is already declared one, and `groups.list` sends `createdAt` with no
annotation at all — trusting the metadata would break exactly that one endpoint. Encoding does
emit annotations, because the server rebuilds real `Date` instances before its own validation
runs.

The rest of what is sharp about this protocol — and there is a lot of it, most of it about
money — is in the *Things that will bite you* section of [CLAUDE.md](CLAUDE.md). Read it before
writing anything that handles an amount.

## Design

[DESIGN.md](DESIGN.md), before touching a screen. The short version: Material 3, with only the
money colours and the participant palette defined by us and every surface left to M3's own
`ColorScheme`; money in tabular figures at four sizes; and colour carrying sign only where an
amount actually has a direction.

## Licence

MIT, matching [Spliit](https://github.com/spliit-app/spliit) itself and the iOS app. The
`LICENSE` file lands when this repo gets a remote.
