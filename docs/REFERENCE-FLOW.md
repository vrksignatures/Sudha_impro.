# Reference flow — how SudhaAutoBot is supposed to work

This document is the specification the bot's code must match. It was built from the
operator's screen recording `20261118_000330_2 (compressed by happyscribe.com).mp4`
(3 m 51 s, 1080 × 1920, with audio) plus the operator's written instructions.

Everything in the tables marked **verified** was read directly off the recording's frames.
Anything that could not be confirmed is marked **to confirm**.

The user requirements for IDs, row eligibility, exact-anchor resume and Fast mode below
supersede any older SMR-only assumptions in the historical recording notes.

> **Note on the audio track.** The recording has a Hindi/Hinglish narration track, but the
> narration was **not transcribed** when this document was written — no transcript was
> supplied and the audio could not be listened to in this environment. The flow below is
> therefore derived from the frames and the operator's written description. If the narrator
> described anything that contradicts a step here, the narration wins and this file must be
> corrected.

---

## 1. The run, end to end

Numbers in the first column are the bot's steps. "Verified" means the screen and its text
were seen in the recording.

| # | Screen | Verified strings | What the bot must do |
| --- | --- | --- | --- |
| 1 | Home — `Daily Orders` | `Welcome!`, tiles `Customers`, `Instant Delivery`, `Reports`, `Feedback`, `Bills`, `Manual Work`, `Market Summary`, `Ledger`, `Complaints` | Tap **Customers** |
| 2 | Customer list | `Customers`, `Total Customers: 125`, `All Distributors`, `Show Retailers` | Ensure the list is expanded — see §2 |
| 3 | Customer list | `Delivery Point (Retailer)`, `Customer Id: SMR2255`, `Customer Id: SM65`, `S O(s)` | Read the exact ID and visible type/name; choose the next eligible unhandled row in displayed order |
| 4 | Customer Details | `Customer Details`, `Name`, `Phone`, `Distributor Name`, `Delivery Point Name`, `View Orders`, tabs `Milk` / `PROD`, bottom tabs `Transactions` / `Pay` | Verify the header shows the **same exact** ID, then tap **Milk** |
| 5 | Order history (titled `New Order Milk` / `Orders`) | `Order Id: 368538`, `Status: Order placed`, `Shift: Morning`, `No of Products: 6`, `Total Amount: ₹1321.40`, `Ordered qty`, `Delivery qty` | Tap the floating orange **+** at the bottom-right |
| 6 | Order form — `New Order Milk` | `Date`, `Item Group`, `Zone`, `Party`, `Item · Rate · MQty · EQty · Total`, `Copy Order`, `View Order Summary`, `PLACE ORDER` | Tap **Copy Order** immediately. Do **not** inspect the table. |
| 7 | Calendar dialog | `2026`, `Wed, Nov 18`, `< November 2026`, `S M T W T F S`, `CANCEL`, `OK` | Tap the **T-1** day cell, then **OK** |
| 8 | Order form (after OK) | `Date: 18-November-2026`, item rows | Decide by the dialog — see §3 |
| 9 | Payment | `Payment`, `Paid Amount: 1321.40`, `Payment Mode: CASH`, `Remarks: Enter Remarks`, orange **Pay** | Tap **Pay** |
| 10 | Success | green ✓, `Successfully Payment Created`, green **Done** | Record `SUCCESS`, tap **Done** |
| 11 | Back on the customer list | `Customers`, `Total Customers: 125` | Continue with the next `SMR`; re-expand if it collapsed |

Between steps 5 and 6 the app writes the order first and the payment is completed afterwards.
Only after step 10 is the order complete.

---

## 2. The customer list has two important traps

*(both verified)*

1. **The first row is a distributor, not a retailer.** The top row carries
   `Customer Id: SM65` whereas an example retailer carries `Customer Id: SMR2196`.
   These are examples only: the ID shape does not decide eligibility. Any nonblank exact
   ID is accepted when its row is visibly marked `(Retailer)`.
2. **The list collapses to two rows.** `Total Customers: 2` instead of `125`. The
   `Show Retailers` checkbox exposes no readable checked state, so the bot judges by
   counting visible customer ID rows and taps `Show Retailers` until the list looks expanded.

### Eligibility and adjacency

Never click a row visibly marked `(DIST)`. Also skip a `(Retailer)` row only when it is
immediately after a `(DIST)` row with the same name. A later retailer with that name stays
eligible. A distributor's ID format never changes its type. The bot does not open customer
details to classify rows. It keeps verified predecessor context across overlapping screen
scrolls; if row type, name or adjacency cannot be verified, it stops rather than guessing.

IDs are exact nonblank strings. The bot does not strip internal punctuation/spaces, add a
prefix, sort numerically or derive the next ID. The displayed row order is the only order.

Long lists are scrolled by the bot itself; see §8.6 for how, and for how it decides the
list has ended.

---

## 3. The date rule — the part that decides everything

*(verified)*

The app copies a **previous** day's order onto the form. It only has data for days that
actually had an order, so a wrong date produces an error dialog:

```
┌──────────────────────────────────────────┐
│  ✕  Error                                │
│                                          │
│  No order to copy from selected date Nov │
│  16, 2026                                │
│                                          │
│                                     OK   │
└──────────────────────────────────────────┘
```

Verified text: title `Error`, body `No order to copy from selected date <date>`,
single button `OK`.

### The loop

```
        ┌──────────────────────────────────────────────┐
        │  tap Copy Order                              │
        │  tap the next older day  →  OK               │
        └───────────────────┬──────────────────────────┘
                            │
              wait ~2–3 s for a dialog
                            │
        ┌───────────────────┴───────────────────┐
        │                                       │
   dialog appeared                       no dialog appeared
   = that date has no data              = the order loaded
        │                                       │
   tap OK on the dialog                  tap PLACE ORDER
        │                                       │
   next older day (T-2, T-3 …)           → Payment → Pay → Done
        │
   after T-5 as well?
        │
   tap OK, press BACK, mark this SMR
   SKIPPED_NO_DATA, next SMR
```

### What "T" is

**T is the date the calendar has highlighted in orange when it opens** for the retailer.
The bot reads it from the dialog (the selected day; failing that the headline, `2026` above
`Wed, 18 Nov`; failing that the orange disc on the screen) and counts back from it:
T-1, then T-2 … T-5. If the calendar opens on the 20th it tries 19, 18, 17, 16, 15.

The phone's own date is **not** used for this — not the day, the month or the year. T is
read once, at the first opening, and kept for that retailer, so it does not matter whether
the dialog reopens on T or on the date chosen last time. If T cannot be read at all the
dialog is closed with `CANCEL` and reopened; after three rounds the retailer is skipped.
The bot does not fall back on the phone's date.

### Concrete dates from the recording

The calendar opened on **18 November 2026**, so the ladder is:

| Attempt | Date | Cell to tap |
| --- | --- | --- |
| T-1 | 17 Nov 2026 | `17` |
| T-2 | 16 Nov 2026 | `16` |
| T-3 | 15 Nov 2026 | `15` |
| T-4 | 14 Nov 2026 | `14` |
| T-5 | 13 Nov 2026 | `13` |

**Maximum 5 attempts.** Never today, never more than 5 days back, never a week jump.

---

## 4. The calendar dialog: what it allows

*(verified from the frame)*

```
        ┌─────────────────────────────┐
        │ 2026                        │   ← year of the highlighted date
        │ Wed, Nov 18                 │   ← the date currently selected
        ├─────────────────────────────┤
        │ <      November 2026        │   ← month header + previous-month arrow
        │                             │
        │  S   M   T   W   T   F   S  │
        │  1   2   3   4   5   6   7  │   ← 1–18 dark  = selectable
        │  8   9  10  11  12  13  14  │
        │ 15  16  17 (18) 19  20  21  │   ← 18 = today, shown filled/orange
        │ 22  23  24  25  26  27  28  │   ← 19+ faded  = future, not selectable
        └─────────────────────────────┘
              CANCEL            OK
```

Facts that constrain the code:

* **The dialog opens with today already selected** (`Wed, Nov 18` in the orange header and
  the `18` cell filled). A bot that trusts "a day is highlighted" would silently order for
  today. The highlighted cell must never be taken as "the target is already chosen".
* **Past days are enabled, future days are faded.** So T-1 … T-5 are all tappable, and a
  tap on a faded future cell should be treated as a failure, not as success.
* **`OK` is a separate button** in the dialog's footer next to `CANCEL`. The day tap alone
  does not commit anything.
* The month header carries the **year** (`November 2026`), and there is a `<` arrow. A date
  ladder that crosses a month boundary needs the arrow; it must never jump more than one
  month at a time.
* After `OK`, the form's `Date` field shows the chosen date (e.g. `18-November-2026`).

### The dialog's date can disagree with the form

In the verified frame the form's `Date` reads `18-November-2026` while the dialog says
`No order to copy from selected date Nov 16, 2026` — the app reports the date of a previous
attempt. **So the bot must not parse a date out of the message.** Only "dialog or no dialog"
carries meaning.

---

## 5. Why quantity must never be read

*(verified from the frame)*

The item table contains these rows:

```
DTM 180 ML      ₹ 49.56    0   0   ₹ 0.0
TM 500 ML       ₹ 53.6     0   0   ₹ 0.0
TM 1000 ML      ₹ 52.6     0   0   ₹ 0.0
COW 1000 ML     ₹ 54.7     0   0   ₹ 0.0
SM 1000 ML      ₹ 57.7     0   0   ₹ 0.0
FCM 1000 ML     ₹ 65.7     0   0   ₹ 0.0
FCM 6000 ML     ₹ 67.0     0   0   ₹ 0.0
SUDHA DELIGHT   ₹ 62.6     0   0   ₹ 0.0
1000 ML
```

Item names contain numbers like **`1000`** and **`6000`**. An earlier version of the bot
summed numbers that appeared near the words "total"/"qty", read the `1000` out of
`FCM 1000 ML` as a real quantity, and therefore declared the **empty** form "ready" — so it
skipped `Copy Order` entirely and waited forever for a payment screen.

**Rule: the item table is never parsed.** Success is decided only by the absence of the
error dialog. The `Date` field is the only form value worth reading, and only to confirm
which date was selected.

---

## 6. Payment and success

*(verified)*

After `PLACE ORDER` the app shows the **Payment** screen:

| Field | Value in the recording |
| --- | --- |
| `Paid Amount` | prefilled, `1321.40` |
| `Payment Mode` | dropdown defaulting to **`CASH`** |
| `Remarks` | placeholder `Enter Remarks` (left empty) |
| Button | orange **`Pay`** |

Tapping `Pay` shows a centred card with a green ✓, the text
**`Successfully Payment Created`**, and a green **`Done`** button. Tapping `Done` returns to
the customer list, where the next `SMR` is picked up.

---

## 7. Rules the bot must never break

1. Never select **T** (the date the calendar opens on) or anything after it, and never
   look back more than **5 days** from it. T comes from the calendar, never from the
   phone's clock.
2. Never tap `PLACE ORDER` unless the app returned **no error dialog**.
3. Never place an order twice for the same retailer. A pre-submit marker is written to the
   daily ledger before `PLACE ORDER` is tapped.
4. Never infer eligibility from the ID format. Skip visible `(DIST)` rows and only an
   immediately following same-name `(Retailer)` shadow.
5. Never infer success from quantities, totals or item names.
6. Never treat `Status: Order placed` as a success signal — that is the status of a
   **previous** order printed on every history card (verified: `Order Id: 368538`,
   `Status: Order placed`). Reading it as success marks a retailer done before anything is
   copied or placed.
7. After the 5th error, `OK` → `BACK` → mark the customer `SKIPPED_NO_DATA` → next eligible customer.
   The ledger write happens **before** the `BACK`, so an interrupted exit cannot cause a
   retry that orders twice.
8. Progress survives a restart: a retailer already recorded for today is never reprocessed.
9. The next customer is always the one **after the exact last-handled ID**, in the order
   the list shows them. A replayed scroll position is only a hint: verify the exact anchor
   before continuing. If it is missed, perform a checked scan; never choose the nearest row.
   A manual exact-ID anchor is available in setup. With no anchor, the app cannot prove that
   the list viewport is at the true top on every phone.
10. `Today Order already created` means the retailer is done. Do not wait for a payment
    screen: record it, press `BACK`, next `SMR`.

---

## 8. What the second recording proves (bot defect log)

`WhatsApp Video 2026-11-18 at 2.05.53 AM.mp4` (1 m 15 s, 576 × 1280, with audio) is a
real run of the bot on a Galaxy A-series device. It is far more diagnostic than the first
recording because the phone screen is captured directly, so the floating panel's text is
legible frame by frame.

**What went right:** Steps 1–6 all work on a real run — home → `Customers` → expand to
`Total Customers: 125` → pick `SMR2255` → `Customer Details` → `Milk` → tap the floating
`+` → the `New Order Milk` form opens → **`Copy Order` is tapped** → the calendar opens.

**What goes wrong — three defects, in the order they appear:**

### 8.1 The `OK` button taps the wrong control (`CANCEL`)

Reading the panel bottom-up, one retailer (`SMR2255`) produces:

```
Copy Order tapped for T-1 (SMR2255)      ← calendar opens, T-1 committed
No data for T-1; trying T-2 (SMR2255)
Closing the popup for T-1                ← dismissing the dialog
No data for T-2; trying T-3
Closing the popup for T-2
No data for T-3; trying T-4
Closing the popup for T-3
No data for T-4; trying T-5
Closing the popup for T-4
Copy Order tapped for T-5 (SMR2255)
RETURNING_TO_LIST  SKIPPED 1  Processed 1/125
ERROR  Could not get back to the retailer list after 6 Back presses
```

But the **dialog itself never closes**: in every frame its body still reads
`No order to copy from selected date Nov 18, 2026`. Only the *side* of the screen shifts
frame to frame — the form behind it moving — which means `OK` was never pressed.

The cause is `AccessibilityTree.findExact()`: it matches the **first label equal to `OK`**,
and it does not filter by visibility. The calendar's footer sits next to `CANCEL`, so a
dialog button is found that is not the one on screen, and the tap lands on the wrong
control. The ladder then advances the date counter while the dialog stays put — that is why
`No data for T-...` keeps climbing next to an unchanged dialog.

**Wrong exit too:** a 5th `Copy Order` tap is logged *after* T-5 (`Copy Order tapped for
T-5`), instead of dismissing the final dialog and leaving. The expected sequence is
`No data for T-5` → one `OK` → `BACK`.

### 8.2 Back cannot work while a modal dialog is on screen

`performGlobalAction(GLOBAL_ACTION_BACK)` is swallowed by the modal. The bot shows
`Returning to the retailer list (1/6) … (4/6)` while the screen behind is unchanged,
because the Back lands on the dialog, and a Back that is *not consumed* does not necessarily
leave the app either. Six attempts later the run dies with
`Could not get back to the retailer list after 6 Back presses`.

So an un-dismissed dialog does not merely delay the bot — it **guarantees** a fatal stop.
Any Back-based escape must first confirm that no dialog is present.

### 8.3 A fatal stop destroys the session instead of isolating one retailer

`fail()` clears the running flag, the state machine is left in `ERROR`, and the counters are
frozen at `SKIPPED 1`. Nothing else is processed: 124 of 125 retailers are abandoned because
one dialog would not close.

The panel stays on `ERROR` with the **`START`** button, which by design *resumes* rather than
restarts (`Processed 1 / 125` is kept). So the operator's natural next action does not clear
the fault either. A retailer-level failure needs to skip that retailer and continue; only a
ledger failure or an unverified order deserves a stop.

### 8.4 Smaller observation

Only one retailer has been processed (`Processed 1/125`) and `SUCCESS 0`, so this recording
does **not** yet show a successful `PLACE ORDER` → `Payment` → `Successfully Payment Created`
run by the bot. The success path is still unproven on this device.

---

### 8.5 The highlight never left the present day (third recording, 18 Nov 2026)

Reported from the device: after `Copy Order` the orange circle stays on today; the plain
black past dates are never pressed.

**Cause found (reproduced off-device, see `tools/selftest`).** The stock date dialog keeps
the neighbouring month attached off-screen, and that page comes *first* in the
accessibility tree. The selector read the month from the first month label it met, so with
November on screen it believed it was looking at October, concluded the day it wanted was
"in another month", and pressed nothing. Three more weaknesses sat behind it:

* a day was pressed through its nearest clickable *ancestor* when it refused the click.
  The ancestor of a day is the whole month, which accepts the click and selects nothing;
* `OK` was pressed when the dialog's selection could not be read at all, on the strength
  of "the tap was sent";
* a calendar left open across Pause → START was not handled at all (the recording shows
  the panel on `Waiting for a recognised Sudha screen` in front of the open dialog).

**What the step does now.**

1. Press the day through the tree, reading only what is really on screen, and pressing
   the day cell itself.
2. **Look.** Take a screenshot and find the calendar as drawn: the white card, the weekday
   letters (seven columns), the week rows, and the one filled circle. The month is checked
   against the calendar (first weekday, last weekday, number of rows), so a reading is
   never of the wrong month. No coordinate is stored anywhere; the grid is measured each
   time, so month, year, screen size and density do not matter.
3. `OK` is pressed **only when the circle is seen on the requested day.** If it is
   elsewhere, the day is pressed where it is drawn, with a real touch, and step 2 repeats
   (five presses at most).
4. If that fails, the dialog is closed with `CANCEL`, the same date is retried, and after
   three such rounds the retailer is skipped with the reason (what the screen showed and
   what the tree showed) in the ledger. The date ladder does not move for a bot-side miss.

**Not yet shown.** That a touch dispatched by the service lands on the day on the real
phone. Everything up to the touch is tested against frames of the real screen; the touch
itself can only be confirmed by a run on the device.

### 8.6 The list was never scrolled

Reported from the device: the bot handles the retailers visible on the Show Retailers list
(`SMR2255` … `SMR2251`) and then waits. Scrolling by hand makes it carry on.

**What was wrong with the scrolling code.** It existed, but it trusted three things it
never checked:

* it scrolled *the first scrollable thing on the screen*, whatever that was;
* when the app marks nothing as scrollable it sent no swipe at all and waited for rows to
  "load";
* when the scroll request was accepted it assumed the list had moved.

Which of the three the phone hits has not been observed. The fix does not depend on
knowing: `tools/selftest` runs the new code against all of them.

**What the bot does now** (`ListScroller`).

1. When every retailer on screen is handled, ask the container the rows actually sit in
   to scroll.
2. **Look at the rows again.** If they have not moved, that method is dropped and the list
   is dragged with a real touch instead: a slow drag that ends at rest, so the list moves
   by the distance dragged and does not run on.
3. A drag covers at most 70% of the list's height, so the last rows of one screen are
   still on the next; no retailer passes by unseen. It starts clear of the bot's own panel,
   which takes any touch that lands on it, and clear of the bottom edge of the screen.
4. The list has ended when drags no longer move it (three in a row; one, once the list's
   own scrolling has been seen to work and now refuses). Only then is the run reported
   complete.
5. If the panel covers the whole list, nothing is sent and the run is **not** finished:
   the panel says to drag it off the list.

Two guards go with it: after scrolling, a last screenful with two retailers or fewer is no
longer mistaken for a collapsed list (which would tap `Show Retailers` and fold it), and a
scrolled list is still recognised when its header is off screen.

### 8.7 "Today Order already created" stopped the run (fourth recording, 18 Nov 2026)

`SMR2255` and `SMR2252` already had an order for the day. The bot copied an order, pressed
`PLACE ORDER`, and the app answered with a small grey message at the bottom of the screen,
`Today Order already created`. The order form stayed open. The panel said `Waiting for the
next payment screen` and then stopped the whole run:
`In-flight order SMR2252 never reached a payment or success screen … then tap Reset`.

Three things were wrong.

* **The message is a toast.** A toast is not part of the screen the accessibility service
  reads, so no amount of reading the window finds it. It reaches the service only as a
  notification-state event, and the service had not asked for those events.
* **The waiting budget was never given back.** The counter behind "never reached a payment
  screen" was cleared only on START, so it kept adding up from one retailer to the next.
  In the recording the stop came ten seconds after `PLACE ORDER`; on a clean run it would
  have come a few retailers in regardless.
* **One retailer stopped everything**, and the message told the operator to tap Reset —
  which empties the day's ledger, so the next START began again at the first row.

Now:

1. The service asks for toast events. When one says the order already exists, the retailer
   is recorded `ALREADY_ORDERED` (counted under SKIPPED), `BACK` is pressed at once and the
   bot goes on. The same is done if the words ever appear inside a window.
2. If no such event arrives and the order form is simply still open 15 seconds after
   `PLACE ORDER` (the payment screen normally follows in about four), the retailer is
   recorded as unconfirmed (counted under ERRORS, with the reason) and the bot goes on.
   `PLACE ORDER` is never pressed a second time.
3. The waiting budget is per order.

Not yet seen on the phone: whether this phone delivers the toast event to the service. If it
does not, step 2 is what keeps the run moving, 15 seconds later.

### 8.8 After a retailer, the bot went back to the top of the list (same recording)

`SMR2250` was opened by hand and the bot completed it. Back on the list it opened
`SMR2255`, the first row, not the next row in displayed order.

The app reopens the customer list **folded up and at the top** after every retailer. The
rule was "open the first unhandled SMR on the screen", which at the top of the list is the
first row. (In the recording the ledger had also just been emptied with Reset, so every row
counted as unhandled.)

Now (`RetailerSequence`): the ledger remembers the exact customer ID handled last and an
approximate scroll count. The bot may replay that count, but it opens nothing until the
exact anchor ID is visible. If replay misses, it returns to the start and searches for the
exact ID; if a full checked scan cannot find it, the run stops. It then continues with the
first eligible row below the anchor, applying the visible type/name adjacency rule. IDs are
never sorted or used to infer a successor.

Cost: because the app starts the list at the top every time, the bot has to scroll down to
its place again after each retailer — about twenty swipes for the last retailers of 125.

### 8.9 The dates were counted from the phone's clock

T-1 … T-5 were worked out from the phone's date. They are now counted from the date the
calendar itself opens on (§3, "What T is").

### 8.10 Fast mode

Fast ON/OFF is available in the setup screen and floating panel. Fast only shortens guarded
home/list idle waits. It does not add a separate fast path or change copy, calendar, order
or payment waits.

## 9. Still to confirm

* **Toast delivery** — that `Today Order already created` reaches the service as an event on
  this phone (§8.7). The 15-second rule covers the case where it does not.
* **`BACK` from the customer list** leads to the Daily Orders first page (seen twice in the
  fourth recording). The fallback exact-anchor scan reopens this path and still verifies
  the anchor before choosing a row.

* **The audio narration** of both recordings — not transcribed (see the note at the top).
  If it describes a different order of operations, correct this file.
* **`OK` on the real dialog** — the second recording proves the dialog and its `OK` exist,
  but also proves the bot taps the wrong control when it trusts an exact label match. The
  dialog's `OK` must be resolved *inside the dialog window* and confirmed visible. Still
  open: whether that `OK` node is clickable through the tree at all, or needs a coordinate
  tap.
* **The `Orders` screen vs `New Order Milk`** — the recording shows the history list both
  under the title `Orders` and under `New Order Milk`, which is the same title as the order
  form itself. The bot therefore identifies screens by their *content*
  (`Order Id` + `No of Products`, `Item · Rate · MQty`, `PLACE ORDER`), never by the title.
