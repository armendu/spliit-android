# Spliit for Android

Split expenses with friends, without anyone needing an account.

A native Kotlin/Compose client for [Spliit](https://spliit.app), ported from the
[iOS app](https://github.com/spliit-app/spliit-ios) and talking to the same servers, either
`spliit.app` or an instance you host yourself.

There are no logins. A group is reachable by the ID the server gave it and nothing else; your
phone just keeps a list of the groups you have opened.

---

## Get the app

**[Download the latest release →](../../releases/latest)**

Grab the `.apk`, open it on your phone, and allow installing from that source when Android asks.

Every release ships a checksum, and a signed attestation saying which workflow run built it:

```sh
sha256sum -c spliit-0.1.0.apk.sha256
gh attestation verify spliit-0.1.0.apk --repo armendu/spliit-android
```

One APK, not one per ABI. Per-ABI splits pay off when an app bundles large native libraries;
here the only `.so` files come from AndroidX and total 73 KB across all four ABIs of a 10 MB
APK, so splitting would mean choosing between five downloads to save half a percent.

Want a build of something that isn't released yet? Every push to `master` and every pull
request produces one: open the run under [Actions](../../actions/workflows/ci.yml) and the APK
is attached at the bottom of the page.

## Build it yourself

You need Android Studio (for the SDK and a JDK) and Docker if you want to run the tests against
a real server. Then:

```sh
git clone git@github.com:armendu/spliit-android.git
cd spliit-android
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
make test
```

`make test` runs every unit suite in a few seconds with no emulator, and is the quickest way to
find out your toolchain is set up correctly. Run `make` on its own to see everything else.

| | |
|---|---|
| `make test` | all unit tests, no device |
| `make build` | the debug APK |
| `make run` | install and launch it on a running emulator |
| `make lint` | Android Lint |
| `make e2e-up` | start a throwaway Spliit on `:3009` (Docker) |
| `make e2e` | drive the real app on an emulator against that server |
| `make test-live` | the API client against a real server |

Android Studio is never required, but if you open the project in it, everything works there too.

## Branches

`master` is the released branch. It is the only thing that builds, and a tag on it is what
publishes a release, so it stays protected and takes changes through pull requests rather than
direct pushes.

`development` is where work happens. Pushing to it does not spend a CI run; opening the pull
request into `master` is what asks for one.

```
work on development  →  pull request  →  CI runs  →  merge to master  →  tag  →  release
```

## Releasing

Version numbers live in `gradle/libs.versions.toml`:

```toml
appVersionName = "0.1.0"   # what people see; the tag is this with a leading v
appVersionCode = "1"       # what Android compares; only ever goes up
```

Bump both, commit, then tag and push:

```sh
git tag -a v0.1.0 -m "Spliit for Android 0.1.0"
git push origin v0.1.0
```

That runs [`release.yml`](.github/workflows/release.yml), which checks the tag matches
`appVersionName`, runs the tests and the linter, builds the APK, attests where it came from and
publishes it to [Releases](../../releases). Nothing needs a machine of yours.

Write what changed in `fastlane/metadata/android/en-US/changelogs/<appVersionCode>.txt` first:
the release notes lead with it. That path is F-Droid's convention, so the same file would serve
a listing there without being written twice.

### Signing

Without a signing key, releases are signed with Android's **debug** key. They install and run
fine, but Android identifies an app by its signature, so a debug-signed build can't update one
signed with a different key, and you'd have to uninstall first. The release notes say so on
every build that is in that state.

To sign properly, make a key and add it to the repository's secrets:

```sh
keytool -genkeypair -v -keystore release.jks -alias spliit \
        -keyalg RSA -keysize 2048 -validity 10000
base64 -i release.jks | pbcopy       # paste as SPLIIT_KEYSTORE_BASE64
```

| Secret | What it is |
|---|---|
| `SPLIIT_KEYSTORE_BASE64` | the keystore file, base64-encoded |
| `SPLIIT_KEYSTORE_PASSWORD` | its store password |
| `SPLIIT_KEY_ALIAS` | the alias inside it (`spliit` above) |
| `SPLIIT_KEY_PASSWORD` | that key's password |

**Back `release.jks` up and don't lose it.** A new key is a new app as far as every device is
concerned, and nobody could update their install again. It is never committed, CI writes it to
a temporary file and throws it away.

## How the code is laid out

```
api/     the tRPC client, models and endpoints
core/    money, currencies, split maths, form drafts
app/     the Compose UI, ViewModels and storage
e2e/     a disposable Spliit instance for tests
```

`api/` and `core/` are plain Kotlin with **no Android dependency at all**, which is what keeps
the tests fast, and `core/` doesn't depend on `api/` either, the arithmetic is about amounts and
people, not about wire formats. The UI is where the two meet.

## Testing

Three layers, each with a `make` target above: unit tests that need nothing, API tests against a
real server, and the full app driven on an emulator.

One is worth calling out. **`ApiContractDriftTest` tells you what changed when Spliit's own API
moves.** For each recorded response it asks a live server the same question and reports every
field that appeared, vanished or changed type:

```
The live server's `groups.get` no longer matches the recorded fixture:

  result.data.json.group.currency: was NUMBER, is now STRING
```

Ordinary tests can't catch that, a field declared optional can vanish without anything
complaining, which is exactly how an upstream rename slipped through once before. A nightly CI
job runs this against spliit.app; it is allowed to fail, because a red mark there means "go
look", not "the build is broken".

## Contributing

[CLAUDE.md](CLAUDE.md) is the one to read first, especially **Things that will bite you**, a
list of the ways this codebase fails *silently*, most of them about money. (Amounts are integer
minor units, and minor units are not always hundredths. Never divide by 100.)

[DESIGN.md](DESIGN.md) covers the interface: Material 3 throughout, with only the money colours
and participant palette defined by us, and colour carrying meaning only where an amount actually
has a direction.

## Licence

MIT, matching [Spliit](https://github.com/spliit-app/spliit) itself and the iOS app. See
[LICENSE](LICENSE).
