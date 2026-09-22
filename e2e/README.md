# End-to-end harness

A disposable Spliit instance for the UI tests, and the script that fills it with known data.

```sh
make e2e-up      # start it on :3009, if it isn't already up
make e2e-seed    # add fixture groups and expenses
make e2e-down    # stop it and discard everything
```

One instance serves every worktree, and test runs do not own its lifecycle: `make e2e` brings
it up if it is down and leaves it running, because tearing it down would discard the data of
any run happening at the same time. `make e2e-down` is the deliberate way to stop it.

Sharing it is safe because a group is only ever reached by the ID the server assigned -
`groups.list` takes those IDs as input rather than returning everything, so one run cannot
see, or disturb, another's data.

The compose project is named `spliit-android-e2e`, so this stack and the iOS repo's can be up
at the same time without either one adopting the other's containers. They do still contend for
ports 3009 and 9009; only one of the two can be up on a given machine.

## What's here

**`compose.yaml`** runs the published `ghcr.io/spliit-app/spliit` image against Postgres. The
database lives in tmpfs, so every `up` starts from an empty schema and nothing survives a
`down`. Port 3009 keeps it clear of a Spliit dev server on the usual 3000.

**Object storage** runs alongside it, because expense documents are not kept in Spliit's
database: the instance signs an upload and the app sends the picture straight to a bucket. MinIO
stands in for that bucket on `:9009`, with its data in tmpfs like the database's, and the
`s3-policy` service opens it for anonymous reads, which is what makes a stored document's URL one
anybody can open, exactly as a real deployment does. It sits behind a compose profile and is run
by `make e2e-up`, because it exits when it is done and `up --wait` counts that as a failure.

The address the server signs is `http://localhost:9009`, which is not one that container can
reach and does not need to be: signing is arithmetic over the request, never a round trip, so the
server signs a URL it never visits and the device, which reaches the host through `10.0.2.2` -
is what has to reach it.

**`seed.mjs`** creates groups, participants and expenses through the public tRPC API rather
than through SQL. That keeps the harness decoupled from Prisma migrations, and means it works
against any instance, including a self-hosted one you want to smoke-test.

It prints a JSON map of fixture keys to the IDs the server assigned, which the UI tests read
to know what to look for:

```sh
node seed.mjs --base-url http://localhost:3009/
```

The fixture set covers the cases the app has to get right: a group with all four split modes
including `BY_AMOUNT` and `BY_PERCENTAGE`, expenses spread across the date buckets (this week,
last month, last year), a two-person group, and a group with no expenses at all.

## Reaching the harness from an emulator

`localhost` inside an Android emulator is the emulator, not your machine. The host is
`10.0.2.2`, which is why the Makefile's `E2E_URL` is `http://10.0.2.2:3009/` and not
`http://localhost:3009/`. Seeding runs on the host, so it uses `localhost`; only the app's own
base URL needs the translation. Getting this backwards produces a connection refused that looks
exactly like the stack being down.

Cleartext HTTP to `10.0.2.2` also needs a debug-only network security config. That arrives with
the cycle that first makes the app talk to a server.

## Recording API fixtures

The unit tests decode real recorded responses rather than hand-written JSON:

```sh
make e2e-up
make fixtures
```

That rewrites `api/src/test/resources/fixtures/*.json` with raw, unmodified responses.
Assertions in those tests avoid the server-generated IDs, which change on every re-record.

Hand-written fixtures only prove the decoder agrees with our own assumptions. Recorded ones
prove it agrees with the server, which is the only party whose opinion counts.
