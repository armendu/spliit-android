#!/usr/bin/env node
// Records the API fixtures the :api unit tests decode, straight from a running Spliit instance.
//
//   node Scripts/record-fixtures.mjs --base-url http://localhost:3009/ \
//        --out api/src/test/resources/fixtures
//
// Fixtures are recorded, never written. A hand-written fixture only proves the decoder agrees
// with the assumptions of whoever wrote it; a recorded one proves it agrees with the server,
// which is the only party whose opinion counts. Everything saved here is the response body
// byte for byte — no reformatting, no trimming — because the parts that bite are exactly the
// ones nobody would think to reproduce by hand: `_count` wrappers, a `Prisma.Decimal` arriving
// as a string, and `groups.list` sending `createdAt` with no superjson annotation.
//
// Seeding is `e2e/seed.mjs`'s job and this script shells out to it, so the two never drift into
// two different ideas of what the fixture data is. That also means re-recording always starts
// from a group this run created: the tests assert on titles and amounts, never on the
// server-generated IDs, which change with every re-record.
//
// Every capture below is addressed by that fresh group's ID, threaded through explicitly. That is
// deliberate and worth keeping: it is the property that makes `make fixtures` re-runnable against
// an instance somebody else has been poking at, and the shared e2e server accumulates exactly
// that kind of debris — probe groups from live checks, leftovers from earlier runs. "Simplifying"
// this to list whatever the instance happens to hold would make the fixtures depend on the
// server's history instead of on this run, and the first symptom would be assertions failing on a
// machine where nothing is wrong.

import { spawn } from 'node:child_process'
import { mkdir, writeFile } from 'node:fs/promises'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const args = process.argv.slice(2)
const option = (name, fallback) => {
  const index = args.indexOf(`--${name}`)
  return index === -1 ? fallback : args[index + 1]
}

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const baseUrl = (option('base-url', 'http://localhost:3009/') ?? '').replace(/\/?$/, '/')
const outDir = resolve(repoRoot, option('out', 'api/src/test/resources/fixtures'))

const endpoint = (path) => `${baseUrl}api/trpc/${path}`

/**
 * Every capture keeps the raw body, including the failures: an error envelope is a response the
 * client has to decode too, and `TrpcServerError` is tested against real ones.
 */
async function query(path, input) {
  // `input` is omitted entirely rather than sent as null when a procedure takes none — the two
  // are different requests to tRPC. Note this is not the same as "has no arguments":
  // `groups.list` answers BAD_REQUEST when called without an input object, because it takes
  // `groupIds`.
  const url =
    input === undefined
      ? endpoint(path)
      : `${endpoint(path)}?input=${encodeURIComponent(JSON.stringify({ json: input }))}`
  const response = await fetch(url, { headers: { accept: 'application/json' } })
  return { status: response.status, body: await response.text() }
}

async function mutate(path, input, meta) {
  const response = await fetch(endpoint(path), {
    method: 'POST',
    headers: { 'content-type': 'application/json', accept: 'application/json' },
    body: JSON.stringify(meta ? { json: input, meta } : { json: input }),
  })
  const text = await response.text()
  const body = JSON.parse(text)
  if (body.error) throw new Error(`${path}: ${body.error.json?.message ?? text.slice(0, 300)}`)
  return body.result.data.json
}

/**
 * A group of its own, holding the one expense the seed data deliberately does not: one paid in
 * another currency.
 *
 * It lives outside the seeded groups because every other fixture — and the e2e suite's
 * assertions — depend on those staying exactly as `e2e/seed.mjs` describes them. What this adds
 * is the only recorded proof that `conversionRate` arrives as a *string*: a `Prisma.Decimal`
 * crosses superjson that way, and a model that reads it as a number decodes every fixture here
 * except this one.
 */
async function recordConvertedExpense() {
  const { groupId } = await mutate('groups.create', {
    groupFormValues: {
      name: 'Conversion fixture',
      currency: '€',
      currencyCode: 'EUR',
      participants: [{ name: 'Ana' }, { name: 'Bruno' }],
    },
  })
  const { group } = await query('groups.get', { groupId }).then((r) => JSON.parse(r.body).result.data.json)
  const [ana, bruno] = group.participants.map((participant) => participant.id)

  const { expenseId } = await mutate(
    'groups.expenses.create',
    {
      groupId,
      participantId: ana,
      expenseFormValues: {
        title: 'Hotel, paid in dollars',
        expenseDate: '2026-09-01T00:00:00.000Z',
        amount: 18482,
        category: 0,
        paidBy: ana,
        paidFor: [
          { participant: ana, shares: 100 },
          { participant: bruno, shares: 100 },
        ],
        splitMode: 'EVENLY',
        saveDefaultSplittingOptions: false,
        isReimbursement: false,
        documents: [],
        recurrenceRule: 'NONE',
        // 200 USD at 0.9241, in the group's own minor units above.
        originalAmount: 20000,
        originalCurrency: 'USD',
        conversionRate: '0.9241',
      },
    },
    { values: { 'expenseFormValues.expenseDate': ['Date'] } },
  )

  return () => query('groups.expenses.get', { groupId, expenseId })
}

/** Runs the seeder and returns the map of fixture keys to the IDs the server assigned. */
async function seed() {
  const child = spawn(process.execPath, [join(repoRoot, 'e2e/seed.mjs'), '--base-url', baseUrl], {
    stdio: ['ignore', 'pipe', 'inherit'],
  })
  let stdout = ''
  child.stdout.setEncoding('utf8')
  child.stdout.on('data', (chunk) => (stdout += chunk))

  const code = await new Promise((done) => child.on('close', done))
  if (code !== 0) throw new Error(`e2e/seed.mjs exited ${code}`)
  return JSON.parse(stdout)
}

async function record() {
  const seeded = await seed()
  const lisbon = seeded.groups.lisbon.id
  const ana = seeded.groups.lisbon.participants['Ana']

  const listed = JSON.parse(
    (await query('groups.expenses.list', { groupId: lisbon, limit: 20, cursor: 0 })).body,
  )
  const anExpense = listed.result.data.json.expenses[0].id

  const captures = [
    ['categories.list', () => query('categories.list', undefined)],
    ['groups.list', () => query('groups.list', { groupIds: Object.values(seeded.groups).map((g) => g.id) })],
    ['groups.get', () => query('groups.get', { groupId: lisbon })],
    ['groups.getDetails', () => query('groups.getDetails', { groupId: lisbon })],
    ['groups.expenses.list', () => query('groups.expenses.list', { groupId: lisbon, limit: 20, cursor: 0 })],
    ['groups.expenses.get', () => query('groups.expenses.get', { groupId: lisbon, expenseId: anExpense })],
    ['groups.expenses.get.converted', await recordConvertedExpense()],
    ['groups.balances.list', () => query('groups.balances.list', { groupId: lisbon })],
    ['groups.activities.list', () => query('groups.activities.list', { groupId: lisbon, limit: 20, cursor: 0 })],
    // Both the named and the anonymous shape: with a participant the totals carry Ana's
    // figures, without one they come back null, and the decoder has to survive both.
    ['groups.stats.overview', () => query('groups.stats.overview', { groupId: lisbon, participantId: ana })],
    ['groups.stats.overview.anonymous', () => query('groups.stats.overview', { groupId: lisbon })],
    // And both procedure names, because an instance has one or the other and never both:
    // upstream replaced `groups.stats.get` with the overview. Whichever this instance does not
    // have records its 404 body, which is exactly the fixture the fallback in Part 4 needs.
    ['groups.stats.get', () => query('groups.stats.get', { groupId: lisbon, participantId: ana })],
    // A resource that is not there: NOT_FOUND, but about a group rather than about a route.
    ['error.not-found', () => query('groups.getDetails', { groupId: 'does-not-exist' })],
    // A route that is not there, which is what an instance older than this client answers for a
    // procedure it has never heard of. Told apart from the one above by its message alone.
    ['error.unknown-procedure', () => query('groups.thisProcedureDoesNotExist', { groupId: lisbon })],
  ]

  await mkdir(outDir, { recursive: true })
  for (const [name, run] of captures) {
    const { status, body } = await run()
    await writeFile(join(outDir, `${name}.json`), body.endsWith('\n') ? body : body + '\n')
    console.error(`recorded ${name}.json (HTTP ${status})`)
  }
}

record().catch((error) => {
  console.error(error.message)
  process.exit(1)
})
