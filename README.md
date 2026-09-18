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

You need a JDK 21 and an Android SDK with platform 37 and build-tools 37.0.0. Android Studio
ships both and its bundled JBR is the JDK the Makefile uses, so installing Studio is the short
path even though nothing here requires you to open it.

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

## Testing

Three layers, all runnable from the command line.

| Command | Covers | Needs |
|---|---|---|
| `make test` | superjson coding, response decoding, request building, money formatting, split maths, date bucketing | nothing |
| `make test-live` | the API client against a real server, including writes | `make e2e-up` |
| `make e2e` | the app itself, on an emulator, against a real server | Docker + emulator |
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
