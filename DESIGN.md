# Emerald Ledger — what Spliit for Android looks like, and why

Read this before touching a screen.

The aesthetic is **modern minimalist with Material 3 craftsmanship**: the airy, intentional
precision of a high-end consumer finance tool, on native Android patterns. Money between friends
carries friction, so the interface reduces it — crisp typography, generous white space,
disciplined colour, and layered surfaces separated by hairlines rather than shadow clutter.

Dense data — split breakdowns, balances, ledger tallies — is made approachable by typographic
hierarchy and clean containers, not by hiding it.

---

## 1. Colour

### Core

| Token | Value | Used for |
|---|---|---|
| `primary` | `#006948` | The scheme's primary: buttons, FAB, selected states, active nav. |
| `on-primary` | `#FFFFFF` | Text and icons on primary. |
| `primary-container` | `#00855D` | Emphasis surfaces that still read as brand. |
| `secondary` | `#565E74` | Slate, for supporting chrome. |
| `surface` / `background` | `#FBF8FF` | The canvas. |
| `surface-container-lowest` | `#FFFFFF` | Cards and modules sit here. |
| `surface-container-low` | `#F4F2FD` | Nested blocks inside a card. |
| `surface-container` | `#F4F4F5` | Chips, inert tiles. |
| `surface-container-high` | `#E8E7F1` | Pressed and selected chrome. |
| `on-surface` | `#1A1B22` | Primary text. |
| `on-surface-variant` | `#3D4A42` | Secondary text. |
| `outline-variant` | `#BCCAC0` | Hairline dividers. |
| `border-subtle` | `#E4E4E7` | Card outlines. |
| `error` | `#BA1A1A` | Something has gone wrong. **Never** a balance. |

**The brand green and the UI primary are two different colours, and that is deliberate.** The
logo is `#059669`; the scheme's `primary` is `#006948`. The brief names both. White text on
`#059669` measures **3.77:1** — below AA — and primary carries white label text on every button
and the FAB. `#006948` measures **6.74:1**. So the logo keeps its own green, and the interface
uses the darker one. Do not "correct" one to the other.

### The ledger axis

Financial state is the one place colour carries meaning.

| Token | Value | Used for |
|---|---|---|
| `balance-positive` | `#16A34A` | Owed to you — **large text and non-text only** (≥24px, dots, bars, icons). |
| `balance-positive-text` | `#15803D` | Owed to you, at body and label sizes. |
| `balance-positive-bg` | `#DCFCE7` | The pill or tint behind a positive figure. |
| `balance-negative` | `#E11D48` | You owe — large text and non-text. |
| `balance-negative-text` | `#BE123C` | You owe, at body and label sizes. |
| `balance-negative-bg` | `#FFE4E6` | The pill behind a negative figure. |

**Why two tokens per side.** Measured against `#FFFFFF`: `#16A34A` is **3.30:1** and on its own
`#DCFCE7` tint **3.00:1**. That clears AA for large text (3:1) and **fails** the 4.5:1 body
threshold. A row amount at 14–16px in `#16A34A` is not readable to everyone, and this is an app
whose entire purpose is telling people what they owe. The darker pair measures 5.02:1 and
4.57:1. The hue is unchanged; only the value moves, and only where the text is small.

`#E11D48` clears AA on white (4.70:1) but not on `#FFE4E6` (3.91:1) — so inside a tinted pill,
use `balance-negative-text`.

**Settled is neutral, not a third colour.** A zero balance takes `on-surface-variant`. Zero is
not an outcome worth tinting.

**A balance is never `error`.** Owing money has not gone wrong. Reusing `error` would make a
normal balance look like a failure and drag it around with any change to the error colour.

### Dark

The brief supplies a light palette only, so dark is **derived** rather than invented: surfaces
come from M3's own dark scheme seeded by `primary`, and only the roles the brief actually names
are overridden. Hand-tuning eleven surface tones with no brief to check them against would be
inventing a palette nobody asked for.

| Role | Dark value | Where it comes from |
|---|---|---|
| `primary` | `#68DBA9` | the brief's `inverse-primary` |
| `on-primary` | `#002114` | the brief's `on-primary-fixed` |
| `primary-container` | `#00855D` | the brief's `primary-container` |
| `balance-positive` | `#4ADE80` | lightened to clear AA on a dark surface |
| `balance-negative` | `#FDA4AF` | the same |

**The dark primary is not a guess, and it is not the light one reused.** Material 3's
`inverse-primary` *means* primary as it appears in the opposite theme, so the supplied light
palette already names what dark's primary should be, and `on-primary-fixed` names the label that
belongs on it.

That pairing replaced one that failed twice over. `#00855D` on `#003D29` measures **2.66:1** — a
button label well under the 4.5:1 it needs — and the same `#00855D` as accent *text* on the dark
canvas measures **3.68:1**, so every green label failed, not only the ones inside a filled
button. The replacements measure **10.01:1** and **10.03:1**.

The comment that sat above those colours claimed ">8:1". It was wrong, and nobody noticed
because nobody measured. **State no contrast figure in this repo that you have not computed.**

---

## 2. Typography

**Inter**, bundled — not a downloadable font, because a ledger that reflows when a font arrives
late is worse than one that never had it.

| Style | Size / line | Weight | Tracking | For |
|---|---|---|---|---|
| `display-lg` | 36 / 44 | 700 | −0.02em | The amount field on the expense form. |
| `display-amount` | 32 / 40 | 700 | −0.02em | A hero balance. |
| `headline-lg` | 24 / 32 | 600 | −0.015em | Screen titles. |
| `headline-md` | 20 / 28 | 600 | −0.01em | Section and group names. |
| `title-md` | 16 / 24 | 600 | −0.005em | Row titles, row amounts. |
| `body-lg` | 16 / 24 | 400 | — | Reading text. |
| `body-md` | 14 / 20 | 400 | — | Descriptions, split detail. |
| `label-lg` | 14 / 20 | 600 | 0.01em | Buttons. |
| `label-md` | 12 / 16 | 500 | 0.02em | Metadata. |
| `label-sm` | 11 / 14 | 500 | 0.03em | Captions, bucket headers. |

Sizes are **sp**, so they scale with the system font size. Anything that would clip at the
largest accessibility size must wrap or scroll — never truncate an amount.

**Numbers are the wayfinding.** Tabular figures (`tnum`) are required on every amount — totals,
balances, split breakdowns, row figures. A column that jitters as digits change is the defect
this prevents.

---

## 3. Layout, shape, elevation

**8dp grid**, 4dp for micro-alignment. **16dp** horizontal margins on phones.

Related rows inside a cluster sit `4–8dp` apart; distinct modules `24dp` apart. More space above
a heading than below it — a heading belongs to what follows.

| Shape | Radius |
|---|---|
| Cards and surfaces | 16dp |
| Text fields, dropdowns | 12dp |
| FAB, chips, balance pills | full |
| Bottom sheets | 24dp top corners |

**Depth is tonal plus hairline, not shadow clutter.** Level 0 is the canvas. Level 1 is a card:
`surface-container-lowest` with a 1dp `border-subtle` outline and at most a feather shadow.
Level 2 (FAB, bottom sheets) and level 3 (dialogs) carry a soft ambient shadow and, for level 3,
a dimmed scrim.

**Adaptive.** Below 600dp, one column. At 600dp and above, list-detail: groups or the expense
feed on the left, detail on the right.

---

## 4. Components

**Reach for the Material component before building one.** Our tabs and our split picker were
both hand-drawn pills for a while, which is exactly why they read as "just buttons" — the real
components bring the selection indicator, the ripple, the touch targets, `selectableGroup()`
semantics and keyboard traversal that a hand-rolled row has to reinvent and usually doesn't.

Two components that look alike do different jobs, and the distinction decides which to use:
**tabs navigate between views; segmented buttons choose an option.**

| Job | Component | Where |
|---|---|---|
| Move between a group's views | `PrimaryTabRow` + `Tab` | Expenses / Balances / Totals / Information |
| Pick one of a small set | `SingleChoiceSegmentedButtonRow` | the split picker: Equally / By shares / By % / Exact |
| A short task over the current screen | `ModalBottomSheet` | editing an expense, creating a group, adding by link |
| The screen's one main action | `FloatingActionButton` | create group, add expense |

- **Top app bar** — up arrow in the `navigationIcon` slot, never a text "Back". The groups screen
  leads with the wordmark instead of a title. Search lives here as an action, not as a tab.
- **FAB** — **circular, icon-only**, `primary` on `on-primary`, bottom-right, 16dp margins, clear
  of the navigation bar. The colours are defined **once** and shared: two FABs drifted to two
  different greens because one passed explicit colours and the other took M3's
  `primaryContainer` default. An icon-only FAB **must** carry a `contentDescription` and a
  long-press tooltip — dropping the label drops the only thing a screen reader had, and leaves a
  first-time user guessing.
- **Rows and cards** — an expense row is at least 56dp: category glyph, title, payer and split
  detail in `body-md`, amount right-aligned in `title-md` with tabular figures. A group row
  carries its monogram, name, then icon-led metadata — participants and created date — which
  **wraps rather than truncates** at large font sizes.
- **Chips** — 32dp, full radius, `surface-container` idle, `primary` with `on-primary` selected.
- **Bottom sheets** — 32×4dp drag handle, 24dp top corners. A sheet that edits something opens
  **partially**, showing the fields most edits touch, and drags up to the rest; its confirm action
  must be reachable **without** dragging, or the common case costs a gesture. The sheet owns its
  window insets: `ModalBottomSheet` consumes the navigation-bar inset by default, which leaves a
  `navigationBarsPadding()` inside it with nothing to apply and the last row under the gesture bar.
- **Destructive actions** — full width, in the `error-container` tone with `on-error-container`
  text, not a solid `error` fill. Loud enough to find, quiet enough not to be what the eye lands
  on first, and separated from the confirm action. Measured: 7.24:1 light, 8.96:1 dark.
- **Amount entry** — oversized, centred, currency symbol beside the figure.
- **Progressive disclosure** — a setting most people never touch is collapsed behind *Advanced*,
  prefilled from its default. The server address on the create-group sheet is the case in point:
  it moved there once Settings could hold a default, but it did **not** disappear, because
  self-hosting is first-class and a group's server is what its link resolves against.

Every interactive component ships **default, pressed, focused, disabled, loading and error**.
Loading is a **skeleton in the shape of the content**, never a spinner over it — and the skeleton
must match what replaces it, or the list jumps when it resolves. Empty states teach what the
screen is for rather than announcing that it is empty, and they **scroll** rather than centring
at large font sizes: an empty state whose only action has fallen off the bottom is worse than none.

**One action, one place.** The empty state offered "Create group" as a button *and* a FAB; later
the toolbar menu offered it beside the FAB again. Both read as the same instruction competing
with itself. The FAB creates, a link button joins, the overflow holds what is not a daily action.

**Motion** is 150–250ms, conveying state only. Nothing bounces — a spring on a row that is
leaving reads as a toy, and this is an app people open at a restaurant table one-handed. There is
no orchestrated load sequence.

---

## 5. Icons

**The icons are drawn here, not imported.** They live in `app/src/main/res/drawable` as
`ic_*.xml` vectors and cover everything the app points at: the seven category groupings, the
navigation and chrome glyphs, the tab icons, and the Spliit mark itself.

`material-icons-extended` was the obvious alternative and was deliberately not taken. Seven
category groupings do not justify a dependency the house rule would need an exception for, and a
set drawn to one stroke weight reads as one family in a way a library's assorted metrics do not.

The rules that keep it a family:

- **Stroked, not filled**, at a consistent weight (1.8–2.2 depending on size). Stroked shapes
  take `Icon`'s tint, so a glyph follows the colour of the row it sits in; a filled or coloured
  glyph would not, and an emoji actively ignores it.
- **24dp viewport**, sized at the use site to sit with the text beside it — around 14–16dp for
  metadata, baseline-aligned rather than centred on the row, or they float.
- **Never an emoji, and never a letter abbreviation.** The category slot used to render "FD" and
  "TR"; it was honest about having no icon set, and it looked like a placeholder because it was.
- **`autoMirrored` on anything directional.** `ic_arrow_back` flips for right-to-left layouts; a
  hand-rolled arrow would not.
- **A glyph beside its own label is decorative** — `contentDescription = null`, and let the text
  carry the meaning. An icon *without* a label needs a real description and a tooltip.

### Category glyphs carry colour

Each category grouping has its own hue. The **glyph** is tinted; the **tile behind it stays
neutral** and nothing is filled with a solid block of colour — a stroked glyph in colour reads at
a glance without becoming a second saturated object competing with the amount.

| Grouping | Light | Dark |
|---|---|---|
| Utilities | `#B45309` | `#FBBF24` |
| Uncategorized | `#15803D` | `#4ADE80` |
| Food and Drink | `#C2410C` | `#FB923C` |
| Transportation | `#1D4ED8` | `#7DB3FF` |
| Entertainment | `#7E22CE` | `#C4A0F5` |
| Home | `#0F766E` | `#5EEAD4` |
| Life | `#BE123C` | `#FDA4AF` |

All fourteen clear **3:1** against their tile — the WCAG bar for a meaningful non-text graphic —
measured, at 4.56:1 or better in light and 6.32:1 or better in dark.

**The bolt is yellow only where yellow is legible.** A true yellow (`#FBBF24`) on the light tile
measures **1.52:1**; even `#D97706` is 2.90:1, still under the bar. So light gets a deep amber
and dark gets the yellow. This is the general rule for any hue asked for here: it reads as the
colour it was asked to be wherever that colour can actually be seen, and darkens where it cannot.

This replaces an earlier rule that the category slot was always neutral, on the reasoning that
the amount should be the only saturated thing in a row. Tinting the glyph while leaving the tile
and the fill neutral keeps that reasoning intact — the amount is still the only *block* of colour
— while making a list of expenses scannable by category.

The mark and the wordmark appear on the groups screen and in the empty state, and nowhere else.
An error is not an occasion for branding.

---

## 6. System bars, and choosing a theme

**The app is edge-to-edge.** `enableEdgeToEdge()`, transparent system bars, and the app's own
surface drawing behind them. This is not polish: `targetSdk 37` means the platform enforces it,
so the only choice is whether we handle it deliberately or discover it.

Before it was handled, the status bar drew an opaque `#757575` scrim over content at `#141218` —
the app looked like it started an inch down the phone.

Insets are handled where they land, not guessed at:

- the top app bar consumes the status-bar inset, while its background extends behind it
- scrolling lists add the navigation-bar inset to their content padding, **on top of** the FAB
  clearance, not instead of it — the last row must clear both
- bottom sheets own their own insets, per §4

**Status-bar icon colour follows the resolved theme, not the system's.** Someone reading Light
while their phone is in Dark needs dark icons; getting this backwards trades a grey band for an
invisible clock.

**The app offers Follow system / Light / Dark**, in Settings. This is a deliberate divergence
from iOS, which has no such picker because iOS apps follow the system by convention. Android does
not share that convention. The choice is read **synchronously before the first composition
paints**, or the app shows the system theme for a frame and then snaps.

---

## 7. What this app does not claim

The mockups this design came from show several things Spliit does not do. They are not built,
and the copy that asserts them is not shipped.

**Spliit is not peer-to-peer and does not sync via "encrypted hash".** Groups live in the
instance's Postgres database — `spliit.app`, or one you host. The honest claims, which are
strong enough on their own, are: **no account, no ads, open source**, and a group reachable only
by its link. Telling someone their data is peer-to-peer when it is on a server is not a design
flourish; it is false, and this app will not say it.

Also absent from the API, and so absent from the UI: group cover images, group categories,
participant photos, "last active" timestamps, an organiser role, cross-group balance totals
(groups can be in different currencies, so the sum means nothing), payment-provider integration,
reminders and nudges, CSV export, and a settlement-progress percentage. Marking a debt settled
is recorded the way Spliit records it — as a reimbursement expense.

Receipt scanning, the activity log and the totals tab are **real** Spliit features that are
deferred to a later cycle. They are not in cycle 1, so their chrome — scan badges, match
percentages, an Activity tab — is not drawn yet.

Participants are identified by **monograms** on the eight-colour palette, keyed by a stable hash
of the participant id, because Spliit has no avatars.
