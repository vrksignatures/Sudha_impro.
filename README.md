# SudhaAutoBot

Accessibility-service bot that automates the repetitive daily order-entry task inside
the third-party **Sudha Daily Orders** app (`com.sudha.dailyorders`, also seen as
`com.vrksignatures.sudhadairy`).

The user presses **▶ START** on a floating panel; the bot then walks the whole retailer
list on its own, copies the most recent order from the previous 3 days, and places the
order through the payment chain — without asking for confirmation at every step.

* Java only, **zero third-party dependencies** (framework APIs only, `minSdk 24`).
* Per-day JSON ledger so a pause, restart or crash never processes a retailer twice.
* Pre-submit marker flushed to disk before `PLACE ORDER` so an order is never placed twice.

---

## Repository layout

```
.
├── .github/workflows/build-apk.yml      # CI: builds the debug APK on push to main
├── build.gradle.kts                     # AGP 8.7.3, declared only
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
└── app
    ├── build.gradle.kts
    └── src/main
        ├── AndroidManifest.xml
        ├── java/com/vrksignatures/sudhaautobot/
        │   ├── MainActivity.java               # setup screen + permissions + live status
        │   ├── SudhaAccessibilityService.java  # the bot loop (screen state machine)
        │   ├── AccessibilityTree.java          # node search / click / scroll helpers
        │   ├── WorkflowStateMachine.java       # current state + active retailer
        │   ├── BotState.java
        │   ├── DailyLedger.java                # per-day JSON ledger + pending marker
        │   ├── LedgerStatus.java
        │   ├── DateRollbackPolicy.java         # the strict T-1 / T-2 / T-3 window
        │   ├── CalendarDateSelector.java       # taps one exact day in the dialog
        │   ├── FloatingPanelController.java    # overlay panel with START/PAUSE/Reset
        │   └── RetailerPolicy.java             # exact nonblank customer IDs
        └── res/
            ├── layout/floaty_layout.xml
            ├── values/strings.xml
            ├── values/themes.xml
            ├── xml/accessibility_service_config.xml
            └── drawable/ic_launcher.xml
```

## Build

### On GitHub (no local toolchain needed)

1. Create an empty repository and push this folder to `main`.
2. `.github/workflows/build-apk.yml` runs automatically and provisions JDK 17,
   Gradle 8.11.1 and the Android SDK (`compileSdk 35`).
3. Download the APK from **Actions → Build APK → Artifacts → `SudhaAutoBot-debug`**,
   or from **Releases → latest** (`SudhaAutoBot-debug.apk`).

You can also trigger it manually with **workflow_dispatch**.

### Locally

```bash
# Android Studio: open the folder, let it sync, then Run.
gradle assembleDebug          # or ./gradlew assembleDebug in Android Studio
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`debug` builds are signed with the standard Android debug keystore, so the artifact is
installable even though it is not release-signed.

## First-time setup on the phone

1. Install the APK and open **SudhaAutoBot**.
2. Enter the exact target package (default `com.sudha.dailyorders`) and tap **Save**.
3. **1. Allow floating panel** → enable *Display over other apps*.
4. **2. Enable accessibility service** → Settings → Accessibility →
   *Installed services* → **SudhaAutoBot** → On.
5. Open **Sudha Daily Orders** and leave it on the home screen.
6. Tap **Show floating panel** (or return to the Sudha app; the panel persists).
7. Press **▶ START** on the panel.

On **Oppo / Vivo / Realme / Xiaomi**, additionally allow *Auto-start*, *Background
activity* and lock the app in the recents screen; otherwise ColorOS/FuntouchOS will kill
the accessibility service mid-run.

## What the bot does

| Step | Screen | Action |
| --- | --- | --- |
| 1 | Home (`Daily Orders`) | Tap **Customers** |
| 2 | Customer list | Count visible customer IDs; if ≤ 2, tap **Show Retailers** until the list expands (the checkbox's `isChecked()` is never read) |
| 3 | Customer list | Read exact IDs and visible type/name; skip `(DIST)` rows and only the immediately following same-name `(Retailer)` row; follow displayed order |
| 4 | Customer details | Verify the exact same customer ID is visible, then tap **Milk** |
| 5 | Milk history | Tap the floating **+** (FAB) — physically, at its bounds centre (see below) |
| 6 | New Order Milk | Tap **Copy Order** immediately — the form is always empty here and is never inspected |
| 7 | Calendar | Tap the **T-1** day cell, then **OK**. Today is never selectable |
| 8 | Calendar | If the dialog says *"No order to copy from selected date"*: tap its **OK**, then repeat with T-2, T-3, T-4, T-5 (max 5 days, never a week) |
| 9 | Order form | When **no dialog** appears within ~3 s: assume the order loaded → mark pending → **PLACE ORDER** → **PAY** / **PROCEED** / **CONFIRM** / **OK** |
| 10 | Success | Record `SUCCESS`, tap **Done** (or Back), return to the list, re-expand if it collapsed, continue with the next eligible customer |

When all five dates fail, the final dialog is dismissed, `GLOBAL_ACTION_BACK` leaves the
order form, the customer is recorded as `SKIPPED_NO_DATA`, and the bot moves to the next eligible customer.

### Exact IDs, eligibility and resume

Customer IDs are exact nonblank strings. The bot does not add a prefix, remove internal
characters, sort IDs or calculate a next ID. Row eligibility comes from the visible
`(DIST)` / `(Retailer)` type and name: it skips every distributor and the immediately
following retailer shadow only when the name matches. A later retailer with that name is
still eligible. If the type, name or list order cannot be read safely, the bot stops.

The daily ledger keeps the exact last-handled ID and an approximate count of list moves.
On return, the bot replays that count when available and verifies the exact anchor before
continuing below it. If the anchor is missed, it returns to the start and scans for that
exact ID; it never substitutes the nearest visible row. The setup screen also accepts an
optional exact **Last handled customer ID** override. Leave it blank to use saved progress.
For a fresh run, make sure the customer list is at the top: the app cannot prove the true
top position on every phone.

### Fast mode

The **Fast ON/OFF** control is available on the setup screen and floating panel. Fast only
shortens guarded idle waits while looking for the home/list controls or waiting for the
customer list to scroll. Copy, calendar, order and payment timing remains on the existing
workflow waits.

### The copy flow is driven entirely by the dialog

Success is never inferred from the order items. The app reports a failed copy with an error
dialog and reports success by simply returning a filled form, so:

* **dialog present** → that date has no data → `OK` → next older date;
* **no dialog after ~3 s** → the copy worked → `PLACE ORDER`.

Nothing in the item table is parsed. An earlier version summed numbers near the words
"total"/"qty" and read the `1000` in the item name `FCM 1000 ML` as a quantity, which made an
empty form look ready — the bug this design removes by construction.

## Verified against the real app

The strings below were read off a screen recording of the target app (1080x1920). They
are what the screen predicates now match on:

| Screen | Verified strings |
| --- | --- |
| Retailer list | `Total Customers: 125`, `All Distributors`, `Show Retailers`, `Customer Id: SMR2196` |
| Customer Details | `Customer Details`, `Name`, `Distributor Name ( SM65 )`, `View Orders`, tabs `Milk` / `PROD` |
| Order history | `Order created on 17 Nov,2026`, `Order Id`, `Products 6`, `Order Qty 22.0 Liter/Kg`, `< November,2026 >` |
| Order form | `Date`, `Item Group`, `Zone`, `Party`, `Copy Order`, `Item Rate MQty EQty Total`, `PLACE ORDER` |
| Payment | `Payment`, `Paid Amount`, `Payment Mode`, `CASH`, `Remarks`, orange `Pay` |
| Success | `Successfully Payment Created` + green `Done` |

Two traps this app sets, both now handled:

1. **The order-history screen is titled "New Order Milk"** — the *same* title as the order
   form. History is therefore detected by its content (`Order created on`, `Order Qty`) and
   is routed **before** the form in the screen dispatcher.
2. **"Order placed" appears on history cards** as the status of a *previous* order. It is
   deliberately **not** treated as a success signal, otherwise every retailer would have
   been marked SUCCESS the moment its history opened.

## How the floating "+" button is found

The button is painted by the app but exposes **no label, no view id, and is not reported
as clickable**. Its position therefore cannot be read from the accessibility tree, and a
dp-based guess depends on screen size, aspect ratio, insets, DPI and navigation-bar mode.

So the bot asks the screen instead. On Android 11+ it takes a screenshot
(`canTakeScreenshot`), finds the **orange disc** in the pixels, and taps its centre
(`FabFinder`). Nothing is stored or uploaded. If the screenshot is unavailable the bot
falls back to the node scan and then to fixed coordinates, so behaviour degrades rather
than breaking.

`FabFinder` accepts a blob only when it is roughly square, disc-sized (4.5–30% of screen
width), fills 30%+ of its box and sits in the bottom-right half of the screen. It first
matches this app's orange hue, then falls back to the dominant accent hue so a theme
change does not break it. A wide orange button such as `PLACE ORDER` is rejected by the
squareness test. Verified against 7 synthetic screens (including "no FAB", a beige card
and a row of small orange dots) with 0 errors.

## Tapping the floating "+" button

The add button on the order-history screen is a custom view: it does not run its click
handler for `performAction(ACTION_CLICK)` and may not report itself as clickable at all.
So `onHistory` escalates over successive screen passes rather than repeating one call:

| Attempt | What is tapped |
| --- | --- |
| 1–2 | Best node with an identity hint (`+` label, `fab` id/class, "add" text), tapped **physically at the centre of its bounds** via `dispatchGesture` |
| 3 | Native `ACTION_CLICK` on its clickable ancestor, as a second opinion |
| 4–5 | A shape + corner candidate (small square blob in the bottom-right) that has no text or id at all |
| 6–7 | Fixed coordinates: a FAB-sized square centred at **86.7% width / 90.2% height** (measured from the real device: 937px, 1732px on 1080x1920) |
| 8+ | A ladder of neighbouring corner positions (90/92, 83/88, 90/86, 82/93, 78/88) |

That is 10 physical tap attempts before the retailer is abandoned. Candidates are scored
so a wide list row or a header control can never win: position alone is only worth 50
points and needs corroboration, while a `+` label is worth 100.

If the form still never appears, the customer is recorded as **`ERROR`** in the ledger and
the run moves on to a different eligible ID — it never walks back to "Milk" for the same
retailer.

## Safety rules baked into the code

* **Exact IDs and row eligibility.** Any nonblank ID can be used, but only a visible
  `(Retailer)` row can be opened. `(DIST)` rows and an immediately following same-name
  retailer shadow are skipped. A row must carry exactly one customer ID.
* **Header must match.** Opening `Milk` requires the details screen to show the exact id
  that was tapped; otherwise the row is recorded as `SKIPPED_ID_MISMATCH` and the bot
  backs out.
* **Never twice.** Any ledger record (`SUCCESS`, `SKIPPED_*`, `ERROR`) removes the
  retailer from today's queue.
* **Never place twice.** Before `PLACE ORDER` the ledger is fsynced with a `pending`
  marker. A restart mid-payment refuses to re-tap `PAY` and stops for a human instead.
  That is the `IN_FLIGHT_UNVERIFIED` state shown in **MainActivity**.
* **Bounded everything.** Every wait has a tick budget; `Show Retailers` is tapped at
  most 4 times, Back at most 6, the payment chain at most 12 steps. When a budget runs
  out the bot *pauses with a precise reason* instead of guessing.
* **Package isolation.** No window outside the configured package is read or tapped.
* **No network.** The manifest requests no `INTERNET` permission.

## Day boundary

The ledger file is `files/ledger-yyyyMMdd.json`. Starting a run after midnight creates a
new file, so yesterday's results never block today's orders. **Reset** clears the current
day, including an in-flight marker left behind by an interrupted run — without that, a
half-finished order would make every later START wait for a payment screen that never
appears.

## Known limitations

* Screen recognition is text-based (`daily order`, `show retailers`, `customer details`,
  `new order milk`, `no order to copy from selected date`, `place order`, `payment`, …). If your build of the target
  app uses different wording, adjust the `looksLike*` methods in
  `SudhaAccessibilityService`. The bot pauses instead of guessing, so a wording mismatch
  is a pause — never a wrong order.
* The bot never decides whether a copy worked by reading quantities. It uses the app's own
  signal: **no popup after the calendar's OK means the order loaded**. Quantities are
  deliberately ignored because the item name `FCM 1000 ML` was once parsed as a total,
  which made an empty form look filled. "Did anything appear?" is answered by comparing
  the form's text before and after the copy.
* Automating a third-party app may violate its terms of service. Use it on your own
  account, at your own risk, and keep an eye on the first few runs.
