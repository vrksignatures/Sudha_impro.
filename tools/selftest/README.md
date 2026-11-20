# Self-tests

Runs the app's real `CalendarDateSelector`, `CalendarGridFinder`, `DateRollbackPolicy`,
`ListScroller`, `RetailerSequence` and `AppNotices` on a plain JVM. No Android SDK, no
emulator, no third-party library. CI runs them before building the APK.

    bash tools/selftest/run.sh                      # all three
    bash tools/selftest/run.sh ListScrollSelfTest   # one

## Calendar (`CalendarSelfTest`)

| Part | What runs | Against |
| --- | --- | --- |
| A | pixel reader | frames cut from the device's own recording (`fixtures/`) |
| B | pixel reader | painted calendars: 4/5/6-row months, three screen sizes, Sunday/Monday first |
| C | tree selector | a model of the stock date dialog, off-screen month page listed first and last |
| D | press → look → confirm | the same model: normal, click ignored, no day cells, month boundary, touches swallowed, day greyed out |
| E | reading T, then T-1 … T-5 | the same model opened on seven different dates (month, year and leap-day boundaries, dates years from the build machine's own): T is read from the selected day, the headline and the painted disc; the five dates are counted from it; T and later are refused; the calendar code contains no call that reads the clock |

Part A is the real screen. Parts B–D are a model built from the Android sources and the
measurements in `docs/REFERENCE-FLOW.md`; they show the logic is right for the dialog as
modelled, not how a particular phone reacts to a touch. Part D follows the same decision
order as `SudhaAccessibilityService.onCalendarLook()`, which itself needs Android to run.

* `stubs/` – minimal stand-ins for `android.graphics.Rect` and `AccessibilityNodeInfo`.
  Never packaged in the app.
* `src/StockDatePicker.java` – the dialog model. `src/Painter.java` – draws it as pixels.
* `fixtures/recording-day*.png` – the dialog card cut from the 720x1600 recording of
  18 Nov 2026, with the highlight on 18, 17, 16, 15 and 14; `recording-between.png` is a
  frame caught while the highlight was moving.

## Retailer list scrolling (`ListScrollSelfTest`)

| Part | What it shows |
| --- | --- |
| A | the previous scrolling code on the same models: where it stands still |
| B | every retailer is handled, in order, and the run ends — for 4 list behaviours × 6 list lengths × list staying put / reopening at the top × with and without a bottom bar × with and without the list running on after a swipe |
| C | a scrollable strip earlier on the screen and a tab pager are left alone; the bot panel lying on the list is avoided; the panel covering the whole list is reported, not treated as the end |
| D | the run ends at the very bottom; no drag exceeds 70% of the list height |

`src/RetailerListModel.java` is the model of the Customers screen. How the real list
answers a scroll request has not been observed, so that is a setting of the model
(scrolls / accepts and ignores / refuses / not marked scrollable) and every setting is run.

## Which retailer is next (`NextRetailerSelfTest`)

| Part | What it shows |
| --- | --- |
| A | the rule one screen at a time, including the case from the recording (`SMR2250` done, list back at the top: the old rule opens `SMR2255`, the new one scrolls and opens `SMR2249`) |
| B | that case as a whole run on the 125-retailer model: `SMR2249` down to the last, then one pass from the top for the five passed over |
| C | the same from eight starting points × 4 list behaviours × 6 list lengths × list reopening at the top / staying put × with and without the list running on after a swipe |
| D | the retailer handled last is no longer listed: one fresh pass from the top, then done |
| E | ids counting up, or in no order at all: only the order on screen is used |
| F | the wording of "Today Order already created" is recognised; the app's other messages are not mistaken for it |

`src/ListRunner.java` is the list loop shared with `ListScrollSelfTest`: a hand-written copy
of the order of decisions in `SudhaAccessibilityService.onRetailerList()`.

Not covered by any of this, because it needs the phone: that Android delivers the toast to
the service, and what the service does with it (`onAlreadyOrdered`, and the 15-second rule
in `onPendingScreen`). Those are type-checked and reviewed, not run.

