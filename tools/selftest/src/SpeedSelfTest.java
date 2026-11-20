import com.vrksignatures.sudhaautobot.Glass;
import com.vrksignatures.sudhaautobot.CopyOutcome;
import com.vrksignatures.sudhaautobot.DateRollbackPolicy;
import com.vrksignatures.sudhaautobot.ScrollMemory;
import com.vrksignatures.sudhaautobot.SkipLine;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The three things added to make a run quicker and easier to check afterwards:
 *
 *   1. the scroll memory - straight down to the retailer handled last, then look around;
 *   2. the quick wait for the copied order (polled instead of a fixed three seconds);
 *   3. the text file of retailers that were skipped.
 *
 * The list is the model the other list tests use, walked by the same copy of the service's
 * loop (ListRunner), once with the scroll memory and once without. The point of most
 * checks is that the memory changes how fast the bot gets there and nothing else.
 */
public final class SpeedSelfTest {
    private static int checks;
    private static int failures;

    public static void main(String[] args) throws Exception {
        lookAroundPlan();
        sameOrderFewerLooks();
        wrongCounts();
        listThatWillNotScrollByAction();
        lastRetailerGone();
        afterRestart();
        copiedOrder();
        skippedFile();
        wiring();
        miniPlayer();
        System.out.println();
        System.out.println(failures == 0
                ? "ALL " + checks + " CHECKS PASSED"
                : failures + " OF " + checks + " CHECKS FAILED");
        if (failures != 0) System.exit(1);
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("  FAIL  " + what);
        }
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("== " + title);
    }

    // -------------------------------------------------------- 1. scroll memory

    private static List<String> ids(String... ids) {
        return Arrays.asList(ids);
    }

    /** The order of moves, on its own: no list, just what is said to be on screen. */
    private static void lookAroundPlan() {
        section("Scroll memory: the plan");
        Set<String> done = new HashSet<>(Arrays.asList("SMR2255", "SMR2254", "SMR2253", "SMR2252", "SMR2251", "SMR2250"));
        ScrollMemory.Handled handled = done::contains;

        check(!ScrollMemory.usable(0) && !ScrollMemory.usable(-1) && ScrollMemory.usable(1)
                && ScrollMemory.usable(ScrollMemory.MAX_COUNT) && !ScrollMemory.usable(ScrollMemory.MAX_COUNT + 1),
                "a count of nought, a negative count or an absurd one is not used");

        ScrollMemory.Seek seek = new ScrollMemory.Seek("SMR2250", 7);
        check(seek.burst() == 7, "first: the saved number of scrolls, sent without looking");
        check(seek.burst() == 0, "and only once");
        check(seek.next(ids("SMR2251", "SMR2250", "SMR2249"), handled, "a") == ScrollMemory.Move.NONE
                && seek.end() == ScrollMemory.End.FOUND, "the retailer is there: nothing more to do");

        seek = new ScrollMemory.Seek("SMR2250", 7);
        seek.burst();
        check(seek.next(ids("SMR2253", "SMR2252", "SMR2251"), handled, "a") == ScrollMemory.Move.NONE
                && seek.end() == ScrollMemory.End.FURTHER_DOWN,
                "only handled retailers in sight: the place is further down, the ordinary scan goes on down");

        // Overshot: everything on screen is still to be done.
        seek = new ScrollMemory.Seek("SMR2250", 7);
        seek.burst();
        List<ScrollMemory.Move> moves = new ArrayList<>();
        List<Boolean> blind = new ArrayList<>();
        String[] screens = {"p9", "p8", "p10", "p9", "p8", "p7", "p6"};
        for (String screen : screens) {
            ScrollMemory.Move move = seek.next(ids("SMR2240", "SMR2239"), handled, screen);
            moves.add(move);
            blind.add(seek.blind());
            if (move == ScrollMemory.Move.FORWARD && seek.blind()) {
                move = seek.next(ids("ignored"), handled, "ignored");
                moves.add(move);
                blind.add(seek.blind());
            }
        }
        check(moves.subList(0, 6).equals(Arrays.asList(ScrollMemory.Move.BACK, ScrollMemory.Move.FORWARD,
                ScrollMemory.Move.FORWARD, ScrollMemory.Move.BACK, ScrollMemory.Move.BACK, ScrollMemory.Move.BACK)),
                "not found: up once, down twice, then up one at a time (" + moves + ")");
        check(!blind.get(0) && blind.get(1) && !blind.get(2) && !blind.get(3),
                "the rows are read after the scroll up, after the SECOND scroll down, and after every scroll up");
        check(!seek.over(), "it keeps going up while the list keeps moving");
        check(seek.next(ids("SMR2251", "SMR2250"), handled, "p5") == ScrollMemory.Move.NONE
                && seek.end() == ScrollMemory.End.FOUND, "and stops the moment the retailer is seen");

        // Found straight after the one scroll up.
        seek = new ScrollMemory.Seek("SMR2250", 7);
        seek.burst();
        check(seek.next(ids("SMR2248", "SMR2247"), handled, "a") == ScrollMemory.Move.BACK, "up once");
        check(seek.next(ids("SMR2250", "SMR2249"), handled, "b") == ScrollMemory.Move.NONE
                && seek.end() == ScrollMemory.End.FOUND, "found after the scroll up");

        // The top of the list: the rows stop changing.
        seek = new ScrollMemory.Seek("SMR2250", 2);
        seek.burst();
        seek.next(ids("SMR2240"), handled, "c");                 // up
        seek.next(ids("SMR2241"), handled, "b");                 // down (blind)
        seek.next(ids("x"), handled, "x");                       // down
        seek.next(ids("SMR2239"), handled, "d");                 // up
        seek.next(ids("SMR2240"), handled, "c");                 // up
        ScrollMemory.Move atTop = seek.next(ids("SMR2240"), handled, "c");
        check(atTop == ScrollMemory.Move.NONE && seek.end() == ScrollMemory.End.GAVE_UP,
                "the list will go no further up: it gives up and hands over, it does not stop the run");

        // A count that is far too large cannot make it climb for ever.
        seek = new ScrollMemory.Seek("SMR2250", 3);
        seek.burst();
        int asked = 0;
        while (!seek.over() && asked < 200) {
            seek.next(ids("SMR2240"), handled, "s" + asked);
            asked++;
        }
        check(seek.over() && asked < 20, "a list that never shows the retailer is left after a bounded search (" + asked + " looks)");

        // Rows not drawn yet.
        seek = new ScrollMemory.Seek("SMR2250", 3);
        seek.burst();
        check(seek.next(new ArrayList<>(), handled, "") == ScrollMemory.Move.WAIT, "no rows on screen yet: look again");
        check(seek.next(ids("SMR2250"), handled, "a") == ScrollMemory.Move.NONE && seek.end() == ScrollMemory.End.FOUND,
                "then found");

        long gap = ScrollMemory.BLIND_GAP_MS;
        check(ScrollMemory.nextGap(gap, ScrollMemory.End.FOUND) == gap, "found where the count said: the pace is kept");
        check(ScrollMemory.nextGap(gap, ScrollMemory.End.FURTHER_DOWN) > gap,
                "the counted scrolls fell short: they are sent further apart next time");
        for (int i = 0; i < 20; i++) gap = ScrollMemory.nextGap(gap, ScrollMemory.End.FURTHER_DOWN);
        check(gap == ScrollMemory.MAX_BLIND_GAP_MS, "but never slower than " + ScrollMemory.MAX_BLIND_GAP_MS + " ms apart");
        check(ScrollMemory.nextGap(0, ScrollMemory.End.NOT_ENDED) == ScrollMemory.BLIND_GAP_MS
                && ScrollMemory.nextGap(99999, ScrollMemory.End.NOT_ENDED) == ScrollMemory.MAX_BLIND_GAP_MS,
                "a nonsense pace from the settings is put right");

        seek = new ScrollMemory.Seek("SMR2250", 3);
        check(seek.next(ids("SMR2240"), handled, "a") == ScrollMemory.Move.NONE && seek.end() == ScrollMemory.End.REFUSED,
                "asked to look around before the counted scrolls were sent: it does nothing");
    }

    private static ListRunner runner(int retailers, boolean memory) {
        RetailerListModel model = new RetailerListModel(retailers);
        ListRunner runner = new ListRunner(model, true);
        runner.memory = memory;
        runner.start();
        return runner;
    }

    private static boolean inListOrder(ListRunner runner) {
        List<String> want = new ArrayList<>();
        for (String id : runner.model.ids) if (id.startsWith("SMR")) want.add(id);
        return runner.opened.equals(want);
    }

    /** A whole day, with and without. */
    private static void sameOrderFewerLooks() {
        section("Scroll memory: a whole list of 125");
        ListRunner plain = runner(125, false);
        plain.run(20000);
        ListRunner quick = runner(125, true);
        quick.run(20000);
        check(plain.finished && inListOrder(plain), "without it: every retailer, in list order");
        check(quick.finished && inListOrder(quick), "with it: every retailer, in list order");
        check(quick.opened.equals(plain.opened), "the same retailers in the same order");
        check(new HashSet<>(quick.opened).size() == quick.opened.size(), "nobody opened twice");
        check(quick.model.actionRequests <= plain.model.actionRequests + 125,
                "about the same number of scrolls are sent (" + quick.model.actionRequests + " against "
                        + plain.model.actionRequests + ")");
        check(quick.looks * 3 < plain.looks, "but the rows are read far fewer times: " + quick.looks
                + " looks instead of " + plain.looks);
        check(quick.upScrolls == 0, "with a right count nothing is ever scrolled up");
        boolean allFound = !quick.ends.isEmpty();
        for (ScrollMemory.End end : quick.ends) allFound &= end == ScrollMemory.End.FOUND;
        check(allFound, "every search ended with the retailer on screen");
        check(!quick.memoryOff, "the memory stayed in use all day");
        System.out.println("  looks at the list: " + plain.looks + " without, " + quick.looks + " with");

        for (int size : new int[] {1, 5, 6, 7, 40}) {
            ListRunner a = runner(size, false), b = runner(size, true);
            a.run(20000);
            b.run(20000);
            check(b.finished && b.opened.equals(a.opened), "a list of " + size + ": the same with and without");
        }

        // A bottom bar, a pager around the list, a sideways strip before it.
        ListRunner odd = runner(60, true);
        odd.model.listBottom = 1400;
        odd.model.insidePager = true;
        odd.model.decoyStripFirst = true;
        odd.run(20000);
        check(odd.finished && inListOrder(odd), "inside a pager with a strip above: every retailer, in order");
        check(odd.model.actionsOnPager == 0 && odd.model.actionsOnDecoy == 0,
                "the counted scrolls go to the list, never to the pager or the strip");
    }

    /** The count is wrong: the list moved, or the app scrolls by a different amount now. */
    private static void wrongCounts() {
        section("Scroll memory: the count is wrong");
        for (final int error : new int[] {1, 2, 3, 6, -1, -2, -3, -6}) {
            final ListRunner runner = runner(125, true);
            final int[] spoiled = {0};
            runner.beforeSeek = () -> {
                // Every fifth search is sent to the wrong depth.
                if (runner.opened.size() % 5 == 4 && runner.savedId != null) {
                    runner.savedCount = Math.max(1, runner.savedCount + error);
                    spoiled[0]++;
                }
            };
            runner.run(40000);
            check(runner.finished && inListOrder(runner), "count off by " + error + " (" + spoiled[0]
                    + " times): still every retailer, in list order");
            check(new HashSet<>(runner.opened).size() == runner.opened.size(), "count off by " + error + ": nobody opened twice");
            check(runner.opened.size() == 125, "count off by " + error + ": nobody left out (" + runner.opened.size() + ")");
            if (error > 0) check(runner.upScrolls > 0, "count too big by " + error + ": it looked above");
        }

        // The list itself changed between two retailers: rows above the place went away.
        final ListRunner shrunk = runner(125, true);
        final boolean[] removed = {false};
        shrunk.beforeSeek = () -> {
            if (!removed[0] && shrunk.opened.size() == 60) {
                removed[0] = true;
                for (int i = 0; i < 9; i++) shrunk.model.ids.remove(3);      // nine retailers already done
            }
        };
        shrunk.run(40000);
        check(removed[0] && shrunk.finished, "nine rows vanish from above the place: the run still ends");
        check(new HashSet<>(shrunk.opened).size() == shrunk.opened.size() && shrunk.opened.size() == 125,
                "and every retailer was opened exactly once (" + shrunk.opened.size() + ")");

        // ... and rows appeared above it.
        final ListRunner grown = runner(125, true);
        final boolean[] added = {false};
        grown.beforeSeek = () -> {
            if (!added[0] && grown.opened.size() == 60) {
                added[0] = true;
                for (int i = 0; i < 9; i++) {
                    grown.model.ids.add(1, "SMR" + (9000 + i));
                    grown.done.add("SMR" + (9000 + i));                      // not ours to open
                }
            }
        };
        grown.run(40000);
        check(added[0] && grown.finished, "nine rows appear above the place: the run still ends");
        check(new HashSet<>(grown.opened).size() == grown.opened.size() && grown.opened.size() == 125,
                "and every retailer was opened exactly once (" + grown.opened.size() + ")");
    }

    /** Lists that only move when dragged: the count is of drags, so it is never replayed. */
    private static void listThatWillNotScrollByAction() {
        section("Scroll memory: lists that do not answer the scroll action");
        for (RetailerListModel.Mode mode : new RetailerListModel.Mode[] {
                RetailerListModel.Mode.ACTION_ACCEPTED_BUT_IGNORED, RetailerListModel.Mode.ACTION_REFUSED,
                RetailerListModel.Mode.NOT_SCROLLABLE}) {
            ListRunner plain = runner(40, false), quick = runner(40, true);
            plain.model.mode = mode;
            quick.model.mode = mode;
            plain.run(40000);
            quick.run(40000);
            check(plain.finished && quick.finished && quick.opened.equals(plain.opened),
                    mode + ": the same retailers in the same order as without the memory");
            check(quick.blindScrolls == 0 && quick.model.backRequests == 0,
                    mode + ": nothing is sent by count (" + quick.blindScrolls + " down, "
                            + quick.model.backRequests + " up)");
            check(quick.model.drags == plain.model.drags, mode + ": exactly the drags it made before");
        }
    }

    /** The retailer handled last is no longer in the list at all. */
    private static void lastRetailerGone() {
        section("Scroll memory: the retailer handled last has left the list");
        final ListRunner runner = runner(80, true);
        final boolean[] removed = {false};
        runner.beforeSeek = () -> {
            if (!removed[0] && runner.opened.size() == 30) {
                removed[0] = true;
                runner.model.ids.remove(runner.last);
            }
        };
        runner.run(40000);
        check(removed[0] && runner.finished, "the run ends by itself");
        check(runner.ends.contains(ScrollMemory.End.GAVE_UP) || runner.ends.contains(ScrollMemory.End.FURTHER_DOWN)
                || runner.ends.contains(ScrollMemory.End.REFUSED), "the search came to an end (" + lastOf(runner.ends) + ")");
        check(new HashSet<>(runner.opened).size() == runner.opened.size(), "nobody opened twice");
        check(runner.opened.size() == 80, "everybody else was opened (" + runner.opened.size() + " of 80)");
    }

    private static String lastOf(List<ScrollMemory.End> ends) {
        return ends.isEmpty() ? "none" : String.valueOf(ends.get(ends.size() - 1));
    }

    /** The bot is stopped, the app closed, and START pressed again within the day. */
    private static void afterRestart() {
        section("Scroll memory: START again later in the day");
        ListRunner runner = runner(125, true);
        runner.run(120);
        int before = runner.opened.size();
        check(before > 20 && before < 125 && !runner.finished, "stopped part of the way (" + before + " done)");
        String last = runner.last;
        int saved = runner.savedCount;
        check(last.equals(runner.savedId) && saved > 0, "the count is saved with the retailer handled last ("
                + last + ", " + saved + " scrolls)");

        // The app was closed and reopened: the list is at its top; counters start afresh.
        runner.model.scroll = 0;
        runner.start();
        int looksBefore = runner.looks;
        int openedBefore = runner.opened.size();
        runner.run(runner.passes + 6);
        check(runner.opened.size() > openedBefore, "the next retailer is opened within a few looks ("
                + (runner.looks - looksBefore) + " looks for a retailer " + saved + " scrolls down)");
        check(runner.opened.get(openedBefore).equals("SMR" + (Integer.parseInt(last.substring(3)) - 1)),
                "and it is the one after " + last + ": " + runner.opened.get(openedBefore));
        runner.run(40000);
        check(runner.finished && inListOrder(runner), "the rest of the day follows in order");
    }

    // ------------------------------------------------------ 2. the copied order

    private static final String BLANK_FORM = "New Order Milk\nDate\n18-11-2026\nItem Group\nMILK\nZone\nPATNA\n"
            + "Party\nSMR2250\nItem Rate MQty EQty Total\n"
            + "DTM 180 ML ₹ 49.56 0 0 ₹ 0.0\nTM 500 ML ₹ 53.6 0 0 ₹ 0.0\nFCM 1000 ML ₹ 65.7 0 0 ₹ 0.0\n"
            + "Copy Order\nView Order Summary\nPLACE ORDER";
    private static final String DATED_FORM = BLANK_FORM.replace("18-11-2026", "17-11-2026");
    private static final String COPIED_FORM = DATED_FORM
            .replace("TM 500 ML ₹ 53.6 0 0 ₹ 0.0", "TM 500 ML ₹ 53.6 12 0 ₹ 643.2")
            .replace("FCM 1000 ML ₹ 65.7 0 0 ₹ 0.0", "FCM 1000 ML ₹ 65.7 4 0 ₹ 262.8");
    private static final long SETTLE = 3600;

    /** Feeds one screen per look, one poll apart, and returns when the verdict stopped being WAIT. */
    private static long verdictAfter(CopyOutcome.Watch watch, CopyOutcome.Verdict want, boolean canPlace, String... screens) {
        long now = 1_000_000;
        for (int look = 0; look < 60; look++) {
            String screen = screens[Math.min(look, screens.length - 1)];
            CopyOutcome.Verdict verdict = watch.look(screen, canPlace, now);
            if (verdict != CopyOutcome.Verdict.WAIT) return verdict == want ? now - 1_000_000 : -1;
            now += CopyOutcome.POLL_MS;
        }
        return -2;
    }

    private static void copiedOrder() {
        section("The copied order: how long the bot waits for the error dialog");
        check(CopyOutcome.POLL_MS >= 100 && CopyOutcome.POLL_MS <= 200, "the form is looked at every "
                + CopyOutcome.POLL_MS + " ms");

        long took = verdictAfter(new CopyOutcome.Watch(BLANK_FORM, SETTLE), CopyOutcome.Verdict.ARRIVED, true,
                DATED_FORM, DATED_FORM, COPIED_FORM, COPIED_FORM);
        check(took > 0 && took <= 600, "the order arrives: PLACE ORDER may be pressed " + took
                + " ms after the first look (it was a fixed " + SETTLE + " ms)");

        took = verdictAfter(new CopyOutcome.Watch(BLANK_FORM, SETTLE), CopyOutcome.Verdict.TIMED_OUT, true, DATED_FORM);
        check(took >= SETTLE && took < SETTLE + 2 * CopyOutcome.POLL_MS,
                "nothing on the form changes: the old wait applies in full (" + took + " ms)");

        // Only the date changes, in every way a form might write it.
        for (String date : new String[] {"17/11/2026", "17.11.26", "2026-11-17", "17 Nov 2026", "17 Nov,2026",
                "17-Nov-2026", "Nov 17, 2026", "17 November 2026", "Tue, 17 Nov 2026", "17 Nov"}) {
            String later = BLANK_FORM.replace("18-11-2026", date);
            took = verdictAfter(new CopyOutcome.Watch(BLANK_FORM, SETTLE), CopyOutcome.Verdict.TIMED_OUT, true,
                    BLANK_FORM, later);
            check(took >= SETTLE, "the Date box changing to \"" + date + "\" is not taken for an order");
        }

        // Caught half-written.
        String half = DATED_FORM.replace("TM 500 ML ₹ 53.6 0 0 ₹ 0.0", "TM 500 ML ₹ 53.6 12 0 ₹ 643.2");
        CopyOutcome.Watch watch = new CopyOutcome.Watch(BLANK_FORM, SETTLE);
        check(watch.look(DATED_FORM, true, 0) == CopyOutcome.Verdict.WAIT, "first look: wait");
        check(watch.look(half, true, 150) == CopyOutcome.Verdict.WAIT && watch.sawChange(),
                "the form is seen changing: not yet");
        check(watch.look(COPIED_FORM, true, 300) == CopyOutcome.Verdict.WAIT, "still being written: not yet");
        check(watch.look(COPIED_FORM, true, 450) == CopyOutcome.Verdict.ARRIVED,
                "the same on two looks in a row: the order is in");

        took = verdictAfter(new CopyOutcome.Watch(BLANK_FORM, SETTLE), CopyOutcome.Verdict.TIMED_OUT, true,
                DATED_FORM, DATED_FORM + "\nLoading...");
        check(took >= SETTLE, "\"Loading...\" on the form is not an order");
        took = verdictAfter(new CopyOutcome.Watch(BLANK_FORM, SETTLE), CopyOutcome.Verdict.TIMED_OUT, true,
                DATED_FORM, DATED_FORM + "\nNo order to copy from selected date");
        check(took >= SETTLE, "a refusal written on the form itself is not an order");
        took = verdictAfter(new CopyOutcome.Watch(BLANK_FORM, SETTLE), CopyOutcome.Verdict.TIMED_OUT, false,
                DATED_FORM, COPIED_FORM);
        check(took >= SETTLE, "no PLACE ORDER on the form: never taken as ready");
        took = verdictAfter(new CopyOutcome.Watch("", SETTLE), CopyOutcome.Verdict.TIMED_OUT, true,
                DATED_FORM, COPIED_FORM);
        check(took >= SETTLE, "no picture of the form before Copy Order (or the check is distrusted): the old wait");
        took = verdictAfter(new CopyOutcome.Watch(BLANK_FORM, SETTLE), CopyOutcome.Verdict.TIMED_OUT, true, COPIED_FORM);
        check(took >= SETTLE, "an order that was there at the first look is not guessed at: the old wait");
        took = verdictAfter(new CopyOutcome.Watch(BLANK_FORM, SETTLE), CopyOutcome.Verdict.TIMED_OUT, true,
                DATED_FORM, BLANK_FORM);
        check(took >= SETTLE, "the form going back to how it was before Copy Order is not an order");

        watch = new CopyOutcome.Watch(BLANK_FORM, SETTLE);
        watch.look(DATED_FORM, true, 0);
        check(!watch.sawChange(), "nothing has changed yet: a dialog now says nothing against the check");
        watch.look(COPIED_FORM, true, 150);
        check(watch.sawChange(), "the form changed: a dialog after this means the check misjudged the app");
    }

    // ------------------------------------------------------- 3. the skipped file

    private static void skippedFile() {
        section("The file of skipped retailers");
        String line = SkipLine.of(0L, "SMR2235", SkipLine.EMPTY_LIST, "The app said:\n  Orders list is empty.   Retry?");
        check(line.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}  SMR2235  EMPTY LIST   The app said: Orders list is empty\\. Retry\\?"),
                "date, time, retailer, why, what the app said: " + line);
        check(!line.contains("\n"), "always one line");
        check(SkipLine.of(0L, null, SkipLine.NO_ORDER_5_DATES, null).endsWith("  ?  NO ORDER T-1..T-5"),
                "a missing id or note does not break the line");
        StringBuilder longNote = new StringBuilder();
        for (int i = 0; i < 80; i++) longNote.append("word ");
        check(SkipLine.of(0L, "SMR1", SkipLine.COPY_PROBLEM, longNote.toString()).length() < 230, "a long note is cut");
        check(DateRollbackPolicy.MAX_LOOK_BACK_DAYS == 5, "five dates are tried before a retailer is skipped");
        check(DateRollbackPolicy.label(0).equals("T-1") && DateRollbackPolicy.label(4).equals("T-5"), "T-1 to T-5");
    }

    // ------------------------------------------------------- the folded panel

    /** Everything the person reads says Sutara: not the old names. */
    private static void theName(String strings) throws Exception {
        check(strings.contains("<string name=\"app_name\">Sutara</string>"), "the app is called Sutara on the home screen");
        check(strings.contains("<string name=\"accessibility_service_label\">Sutara</string>"),
                "and in the phone's Accessibility list");
        check(strings.contains("<string name=\"aakriti_name\" translatable=\"false\">Sutara</string>")
                && strings.contains("<string name=\"cd_chat\">Ask Sutara</string>"), "the helper who answers is Sutara too");
        java.util.regex.Matcher shown = java.util.regex.Pattern
                .compile("<string name=\"[^\"]+\"[^>]*>(.*?)</string>", java.util.regex.Pattern.DOTALL).matcher(strings);
        int old = 0;
        int all = 0;
        while (shown.find()) {
            all++;
            String said = shown.group(1);
            if (said.contains("Aakriti") || said.contains("AutoBot") || said.contains("Sutradhar") || said.contains("Sutra ")) old++;
        }
        check(all > 200 && old == 0, "no line on any screen still says Aakriti or AutoBot (" + old + " of " + all + ")");
        String chat = read("AakritiChat.java");
        check(chat.contains("Main Sutara") && !chat.contains("Main Aakriti"), "she gives her new name when she greets");
        String manifest = new String(Files.readAllBytes(new File("../../app/src/main/AndroidManifest.xml").toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        check(manifest.contains("android:name=\".SudhaAccessibilityService\"") && manifest.contains("android:name=\".MainActivity\""),
                "the names Android knows the app by are unchanged, so it installs over the old one and stays switched on");
        String gradle = new String(Files.readAllBytes(new File("../../app/build.gradle.kts").toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        check(gradle.contains("applicationId = \"com.vrksignatures.sudhaautobot\""), "and so is its package");
    }

    /** The glass itself: how see-through, and whether the words can still be read. */
    private static void glassRule() {
        final int text = 0x10122B;       // @color/text
        check(Glass.LEVELS == 5 && Glass.DEFAULT == 2, "five steps; the middle one is the default");
        for (int level = 0; level < Glass.LEVELS; level++) {
            int shows = Glass.showsThrough(level);
            check(shows >= 8 && shows <= 66, "step " + level + ": " + shows + "% of what is behind shows through");
            check((Glass.sheet(level) & 0xFFFFFF) == 0xFFFFFF && (Glass.sheet(level) >>> 24) == Math.round(Glass.percent(level) * 2.55f),
                    "step " + level + ": the sheet is white, " + Glass.percent(level) + "% there");
            if (level > 0) {
                check(Glass.showsThrough(level) > Glass.showsThrough(level - 1), "each step is more see-through than the last");
                check((Glass.halo(level) >>> 24) >= (Glass.halo(level - 1) >>> 24) && (Glass.halo(level) >>> 24) >= 0xB0,
                        "and gives the words a stronger pale edge");
            }
            // The Sudha app is a white page with dark print: there the words stay at full strength.
            check(Glass.contrast(text, Glass.over(level, 0xFFFFFF)) > 17, "step " + level + ": over a white page the words are as clear as on paper");
        }
        check(Glass.showsThrough(Glass.DEFAULT) == 40, "by default 40% of what is behind shows through");
        check(Glass.percent(Glass.DEFAULT) < 98, "which is far more than the old panel let through (2%)");
        // The hard cases: a black screen and a mid-grey photo behind the panel.
        check(Glass.contrast(text, Glass.over(Glass.DEFAULT, 0x000000)) >= 4.5,
                "by default the words pass the reading test (4.5 to 1) even over a black screen: "
                        + String.format(java.util.Locale.US, "%.1f", Glass.contrast(text, Glass.over(Glass.DEFAULT, 0x000000))));
        check(Glass.contrast(text, Glass.over(Glass.DEFAULT, 0x808080)) >= 7,
                "and comfortably over a grey one");
        check(Glass.contrast(text, Glass.over(Glass.LEVELS - 1, 0x000000)) < 4.5 && (Glass.halo(Glass.LEVELS - 1) >>> 24) == 0xFF,
                "at the most see-through step a black screen is too dark for plain print, so the pale edge is at full strength");
        check(Glass.clamp(-3) == 0 && Glass.clamp(99) == Glass.LEVELS - 1 && Glass.percent(99) == Glass.percent(Glass.LEVELS - 1),
                "a step out of range is brought back into it");

        int[] at = Glass.fit(1000, 300, 700, 900, 1080, 2400);
        check(at[0] == 380 && at[1] == 300, "a ring at the right edge: the dashboard opens moved left, all on screen");
        at = Glass.fit(40, 2000, 700, 900, 1080, 2400);
        check(at[0] == 40 && at[1] == 1500, "a ring near the bottom: it opens moved up");
        at = Glass.fit(40, 300, 700, 900, 1080, 2400);
        check(at[0] == 40 && at[1] == 300, "room enough: it opens where the ring was");
        at = Glass.fit(-50, -20, 1200, 900, 1080, 2400);
        check(at[0] == 0 && at[1] == 0, "never off the left or the top");
    }

    private static String resource(String path) throws Exception {
        return new String(Files.readAllBytes(new File("../../app/src/main/res/" + path).toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void miniPlayer() throws Exception {
        section("Folded, the panel is a bare ring; open, a sheet of glass with the sound row and the question box");
        String layout = resource("layout/floaty_layout.xml");
        int from = layout.indexOf("android:id=\"@+id/panel_mini\"");
        int to = layout.indexOf("android:id=\"@+id/panel_drag_handle\"");
        check(from > 0 && to > from, "the folded face comes first in the panel");
        String mini = from > 0 && to > from ? layout.substring(from, to) : "";
        check(mini.contains("@+id/panel_mini_art") && mini.contains("@drawable/mini_ring"), "it is the ring");
        check(mini.contains("android:visibility=\"gone\""), "it is hidden until the panel is folded");
        for (String word : new String[] {"<TextView", "<ImageView", "<ImageButton", "android:src=", "android:text=",
                "sutara", "aakriti", "panel_state", "panel_current", "panel_counts", "@string/panel_", "@color/"}) {
            check(!mini.toLowerCase(java.util.Locale.ROOT).contains(word.toLowerCase(java.util.Locale.ROOT)),
                    "nothing inside the ring: no " + word);
        }
        String ring = resource("drawable/mini_ring.xml");
        java.util.regex.Matcher fill = java.util.regex.Pattern.compile("<solid android:color=\"([^\"]+)\"").matcher(ring);
        int fills = 0;
        while (fill.find()) {
            fills++;
            check("@android:color/transparent".equals(fill.group(1)), "the ring has no fill (" + fill.group(1) + ")");
        }
        check(fills == 2 && count(ring, "<stroke") == 2 && !ring.contains("<gradient") && !ring.contains("android:drawable="),
                "it is two hairlines and nothing else: no colour inside, no picture");
        java.util.regex.Matcher width = java.util.regex.Pattern.compile("android:width=\"([0-9.]+)dp\"").matcher(ring);
        while (width.find()) check(Double.parseDouble(width.group(1)) <= 1.5, "a hairline is thin (" + width.group(1) + "dp)");
        String colors = resource("values/colors.xml");
        for (String name : new String[] {"ring_shade", "ring_light"}) {
            java.util.regex.Matcher colour = java.util.regex.Pattern
                    .compile("<color name=\"" + name + "\">#([0-9A-Fa-f]{8})</color>").matcher(colors);
            check(colour.find() && Integer.parseInt(colour.group(1).substring(0, 2), 16) < 0xFF,
                    name + " is itself see-through");
        }
        check(ring.contains("@color/ring_shade") && ring.contains("@color/ring_light"),
                "one dark hairline and one light one, so that it shows on a pale screen and on a dark one");

        String strings = resource("values/strings.xml");
        java.util.regex.Matcher shown = java.util.regex.Pattern
                .compile("<string name=\"(mini_[a-z_]+|cd_mini_[a-z_]+)\">([^<]*)</string>").matcher(strings);
        int words = 0;
        while (shown.find()) {
            words++;
            String said = shown.group(2).toLowerCase(java.util.Locale.ROOT);
            boolean plain = true;
            for (String word : new String[] {"sutara", "aakriti", "bot", "order", "retailer", "smr", "sudha", "placed", "skip"}) {
                plain &= !said.contains(word);
            }
            check(plain, "\"" + shown.group(2) + "\" says nothing about the bot");
        }
        check(words >= 5, "the sound row's words are in strings.xml (" + words + ")");

        String panel = read("FloatingPanelController.java");
        String body = layout.substring(Math.max(0, layout.indexOf("android:id=\"@+id/panel_body\"")));
        for (String id : new String[] {"panel_music_row", "panel_mini_title", "panel_mini_wave", "panel_mini_play", "panel_lines"}) {
            check(body.contains("@+id/" + id), "the dashboard's sound row has " + id);
        }
        String render = method(panel, "private void renderMini(");
        check(render.contains("MusicEngine.nowPlaying()") && !render.contains("lastRetailer")
                && !render.contains("success") && !render.contains("processed") && !render.contains("lastMessage"),
                "it shows the song title and no figure of the day");
        String play = method(panel, "private void togglePlay(");
        check(play.contains("MusicEngine.stop()") && play.contains("MusicEngine.playNow(context)"),
                "its button pauses and plays the music");
        check(panel.contains("if (!musicHeld) MusicEngine.step(context, step);"),
                "paused by hand, the music is not started again by the next order");
        check(panel.contains("if (started && attached && !collapsed) setCollapsed(true);"),
                "pressing Start folds the panel into the ring");
        String touch = panel.substring(Math.max(0, panel.indexOf("private final class MiniTouchListener")));
        check(touch.contains("case MotionEvent.ACTION_UP:") && touch.contains("if (!dragged && collapsed) setCollapsed(false);"),
                "a tap on the ring opens the dashboard");
        check(touch.contains("windowManager.updateViewLayout(root, params)") && !touch.contains("LONG_PRESS"),
                "a drag moves it; there is no long press to know about");
        String fold = method(panel, "private void setCollapsed(");
        check(fold.contains("handle.setVisibility(fold") && fold.contains("body.setVisibility(fold") && fold.contains("applyGlass();"),
                "folded, the top strip and the dashboard are both hidden, and the paint is changed");
        String glass = method(panel, "private void applyGlass(");
        int folded = glass.indexOf("if (collapsed) {");
        int open = glass.indexOf("Glass.sheet(level)");
        check(folded > 0 && open > folded && glass.substring(folded, open).contains("root.setBackground(null);")
                        && glass.substring(folded, open).contains("root.setPadding(0, 0, 0, 0);")
                        && glass.substring(folded, open).contains("return;"),
                "folded, the window has no background and no padding: only the ring is drawn");
        check(glass.contains("FeedbackPrefs.glass(context)") && glass.contains("halo(root, Glass.halo(level))"),
                "open, it is glass as see-through as the setting says, and every word gets its pale edge");
        String halo = method(panel, "private void halo(");
        check(halo.contains("setShadowLayer(") && !halo.contains("setAlpha") && !halo.contains("setTextColor")
                        && !panel.contains("root.setAlpha(") && !panel.contains("body.setAlpha("),
                "the words themselves are never thinned or recoloured");
        String ringPaint = method(panel, "private void renderRing(");
        check(ringPaint.contains("ring.setTintList(null)") && ringPaint.contains("MagicUi.WARN") && ringPaint.contains("MagicUi.GOOD"),
                "the ring has no colour of its own; amber for a stop and green for a finished list are the only hints");
        check(method(panel, "private void keepOnScreen(").contains("Glass.fit("),
                "opened at the edge of the screen, the dashboard is moved in so that all of it shows");
        glassRule();
        theName(strings);

        check(layout.contains("@+id/panel_ask") && layout.contains("@+id/panel_answer") && layout.contains("@+id/panel_last_done"),
                "the dashboard has the question box, the answer and the Last done ID box");
        String begin = method(panel, "private void beginEditing(");
        check(begin.contains("field == null || running || !attached"),
                "no box takes the keyboard while the bot is running (that would take the screen from the Sudha app)");
        check(method(panel, "private void answerTyped(").contains("AakritiChat.reply(asked, facts())"),
                "the question is answered by the same Sutara as in the app");

        com.vrksignatures.sudhaautobot.AakritiChat.Facts facts = new com.vrksignatures.sudhaautobot.AakritiChat.Facts();
        facts.placed = 40;
        facts.handled = 43;
        facts.expected = 125;
        String none = com.vrksignatures.sudhaautobot.AakritiChat.reply("skip list?", facts).text;
        check(none.contains("khaali"), "asked for the skipped retailers when there are none: " + none);
        facts.skippedLog.add(SkipLine.of(0L, "SMR2235", SkipLine.EMPTY_LIST, "Orders list is empty"));
        facts.skippedLog.add(SkipLine.of(0L, "SMR2231", SkipLine.NO_ORDER_5_DATES, "No order to copy for T-1 to T-5"));
        facts.skippedTotal = 9;
        for (String question : new String[] {"skip list?", "kaun chhoota?", "failed smr", "log dikhao"}) {
            String said = com.vrksignatures.sudhaautobot.AakritiChat.reply(question, facts).text;
            check(said.contains("SMR2235") && said.contains("SMR2231") && said.contains("9"),
                    "\"" + question + "\" lists the skipped retailers");
        }
        check(com.vrksignatures.sudhaautobot.AakritiChat.reply("Aaj ka haal?", facts).text.contains("40 order lag gaye"),
                "the older questions are answered as before: Aaj ka haal?");
        check(com.vrksignatures.sudhaautobot.AakritiChat.reply("Reset se kya hoga?", facts).text.contains("Reset sirf"),
                "Reset se kya hoga?");
        check(com.vrksignatures.sudhaautobot.AakritiChat.reply("Kyon ruki?", facts).text.contains("ruki hi nahin"),
                "Kyon ruki?");
        check(com.vrksignatures.sudhaautobot.AakritiChat.reply("Kitne retailer?", facts).text.contains("Sudha app ke hisaab se"),
                "Kitne retailer?");
    }

    // ------------------------------------------------------------------ wiring

    private static String read(String name) throws Exception {
        File file = new File("../../app/src/main/java/com/vrksignatures/sudhaautobot/" + name);
        return new String(Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static int count(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) count++;
        return count;
    }

    private static String method(String source, String signature) {
        int from = source.indexOf(signature);
        if (from < 0) return "";
        int to = source.indexOf("\n    }\n", from);
        return source.substring(from, to < 0 ? source.length() : to);
    }

    private static void wiring() throws Exception {
        section("How they are wired into the bot");
        String service = read("SudhaAccessibilityService.java");

        int sees = service.indexOf("if (scrollMemorySteps(rows, idsOnScreen)) return;");
        int decide = service.indexOf("sequence.decide(idsOnScreen");
        check(sees > 0 && decide > sees && decide - sees < 400 && count(service, "scrollMemorySteps(rows, idsOnScreen)") == 1,
                "the scroll memory runs once, just before the next retailer is chosen");
        check(count(service, "scrollMemoryNote(row.id);") == 1, "the count is saved where a retailer is opened");
        String steps = method(service, "private boolean scrollMemorySteps(");
        check(steps.contains("catch (Throwable") && steps.contains("scrollMemoryOff = true"),
                "a fault in it switches it off; it cannot stop a run");
        check(steps.contains("startSeeker != null"), "it stands aside while a typed start point is being followed");
        check(!steps.contains("ledger.record") && !steps.contains("AccessibilityTree.click") && !steps.contains("fail("),
                "it opens nothing, records nothing and never stops the bot");
        String sequence = read("RetailerSequence.java");
        check(!sequence.contains("ScrollMemory"), "RetailerSequence knows nothing about it");
        String memory = read("ScrollMemory.java");
        check(!memory.contains("import android"), "ScrollMemory is plain Java");
        String scroller = read("ListScroller.java");
        check(count(scroller, "ACTION_SCROLL_BACKWARD") == 1 && method(scroller, "public boolean jump(").contains("ACTION_SCROLL_BACKWARD"),
                "the list is scrolled up in one place only, and only for the look-around");
        String advance = method(scroller, "public Step advance(");
        check(!advance.isEmpty() && !advance.contains("jump(") && !advance.contains("ACTION_SCROLL_BACKWARD"),
                "the ordinary scan is as it was: it only ever goes down");

        String history = read("RunHistory.java");
        check(history.contains("\"scroll_count\"") && history.contains("\"scroll_at\"") && history.contains("\"scroll_id\""),
                "the count is kept in SharedPreferences with its retailer and the time");
        String expire = history.substring(history.indexOf("static boolean expire("),
                history.indexOf("the scroll count", history.indexOf("static boolean expire(")));
        check(expire.contains("SCROLL_AT") && expire.contains("StartPoint.fresh(") && expire.contains("prefs.edit().clear()"),
                "and is thrown away when the date changes, like the rest");
        check(method(history, "static int scrollCount(").contains("expire(context)"), "every read expires what is stale");

        String await = method(service, "private void onAwaitCopyResult(");
        check(await.indexOf("popupBlocksCopy(root, text)") > 0
                && await.indexOf("popupBlocksCopy(root, text)") < await.indexOf("copyOutcomeWaits(root, text)"),
                "the error dialog is looked for first, on every look");
        check(!service.contains("Waiting for the error dialog"), "the fixed wait for the error dialog is gone");
        check(await.contains("onReadyToPlace(root)") && await.contains("hasPlaceOrder(root)"),
                "PLACE ORDER is still pressed by the same code as before");
        String waits = method(service, "private boolean copyOutcomeWaits(");
        check(waits.contains("quick(CopyOutcome.POLL_MS)") && waits.contains("catch (Throwable"),
                "the wait is polled, and a fault in it falls back to going on");
        check(service.contains("(COPY_SETTLE_TICKS + 1) * ACTION_DELAY_MS"), "the longest wait is what it was");
        check(method(service, "private void copyOutcomeRefused(").contains("sawChange()"),
                "a dialog after a changed form turns the quick check off");
        String outcome = read("CopyOutcome.java");
        check(!outcome.contains("import android") && !outcome.contains("parseInt") && !outcome.contains("parseDouble")
                && !outcome.contains("parseFloat"), "CopyOutcome is plain Java and reads no quantity or total");

        check(count(service, "skipLog(") == 4, "three kinds of skip are written to the file ("
                + (count(service, "skipLog(") - 1) + ")");
        check(method(service, "private void abandonOrder(").contains("SkipLine.COPY_PROBLEM"), "a copy that could not be finished");
        check(service.contains("skipLog(expected, SkipLine.NO_ORDER_5_DATES"), "no order on any of the five dates");
        check(service.contains("skipLog(retailerId, SkipLine.EMPTY_LIST, note)"), "the empty order list");
        int recorded = service.indexOf("LedgerStatus.SKIPPED_NO_HISTORY, note)");
        int logged = service.indexOf("skipLog(retailerId, SkipLine.EMPTY_LIST, note)");
        check(recorded > 0 && logged > recorded, "the ledger is written first, the file after");
        String log = read("SkipLog.java");
        check(log.contains("getFilesDir()") && log.contains("\"skipped-smr.txt\"") && log.contains("catch (Throwable"),
                "the file is skipped-smr.txt in the app's own folder, and failing to write it is harmless");
        String ledger = read("DailyLedger.java");
        check(!ledger.contains("SkipLog"), "the day's ledger does not depend on the file");
        String quick = method(service, "private void quick(");
        check(count(service, "quick(") - 1 <= 8 && quick.contains("Math.max(60"),
                "only the two polling places book a look sooner than the usual gap");
    }
}
