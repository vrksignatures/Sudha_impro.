import com.vrksignatures.sudhaautobot.Tempo;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Fast mode (Tempo): when a new page has come up and is still, the bot looks at it there and
 * then, instead of a fixed time after the last change on the screen.
 *
 * The clock here is a made-up one. Sim is a hand-written copy of how the service books its
 * looks - the old timers (a 900 ms timer after each look, and every change on the screen
 * re-booking the look for 320 + 420 ms later) with, in fast mode, the peeks on top. Tempo
 * itself is the app's own class. The figures printed are what this model gives; what a
 * phone gives depends on how fast the Sudha app and its server are.
 */
public final class TempoSelfTest {
    private static int checks;
    private static int failures;

    public static void main(String[] args) throws Exception {
        rules();
        aPageOpens();
        nothingToBeEarlyFor();
        waitingForTheServer();
        aScreenThatNeverSettles();
        aWholeRetailer();
        wiring();
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

    // ------------------------------------------------------------- the model

    /** What the screen reads as from a given moment on; and whether a page or dialog opened then. */
    private static final class Change {
        final long at;
        final String screen;
        final boolean opened;
        /** False for a change the app does not report (no accessibility event). */
        final boolean reported;

        Change(long at, String screen, boolean opened, boolean reported) {
            this.at = at;
            this.screen = screen;
            this.opened = opened;
            this.reported = reported;
        }
    }

    private static final long DEBOUNCE = 320, MIN_GAP = 420, ACTION_DELAY = 900;

    /** The service's booking of looks, on a made-up clock. Starts just after a press at time 0. */
    private static final class Sim {
        final boolean fast;
        final float scale;
        final List<Change> script = new ArrayList<>();
        final Tempo tempo = new Tempo();
        String screen = "1";
        /** The screen the bot is waiting for; a look that sees it presses the next button. */
        String wanted = "";
        /** A second screen the bot can also act on: the wanted page with its last touch still to come. */
        String alsoGood;
        long stepAt = -1, debounceAt = -1, peekAt = -1;
        long lastActionAt;
        /** When each look was taken; the last one is the look that saw the wanted screen. */
        final List<Long> looks = new ArrayList<>();
        int peeks, mostPeeksBetweenLooks, peeksSinceLook, eagerLooks;
        /** An extra look was taken after the bot's own pause had ended. Must never happen. */
        boolean pauseWasOver;
        long done = -1;

        Sim(boolean fast, float scale) {
            this.fast = fast;
            this.scale = scale;
            // The look at time 0 pressed something and booked the usual next look.
            tempo.stepped(0, screen, true, 0);
            stepAt = ACTION_DELAY;
        }

        Sim at(long at, String screen) {
            script.add(new Change(at, screen, false, true));
            return this;
        }

        Sim opens(long at, String screen) {
            script.add(new Change(at, screen, true, true));
            return this;
        }

        Sim silently(long at, String screen) {
            script.add(new Change(at, screen, false, false));
            return this;
        }

        private void look(long now) {
            looks.add(now);
            mostPeeksBetweenLooks = Math.max(mostPeeksBetweenLooks, peeksSinceLook);
            peeksSinceLook = 0;
            boolean acted = screen.equals(wanted) || screen.equals(alsoGood);
            if (acted) {
                lastActionAt = now;
                done = now;
            } else {
                stepAt = now + Math.max(ACTION_DELAY, MIN_GAP);       // next(ACTION_DELAY_MS)
            }
            if (fast) tempo.stepped(now, screen, acted, lastActionAt);
        }

        /** Runs until the wanted screen has been seen or the clock passes {@code until}. */
        long run(long until) {
            int next = 0;
            while (done < 0) {
                long change = next < script.size() ? script.get(next).at : Long.MAX_VALUE;
                long now = change;
                if (debounceAt >= 0) now = Math.min(now, debounceAt);
                if (peekAt >= 0) now = Math.min(now, peekAt);
                if (stepAt >= 0) now = Math.min(now, stepAt);
                if (now > until) return -1;
                if (now == change) {
                    Change c = script.get(next++);
                    screen = c.screen;
                    if (!c.reported) continue;
                    debounceAt = now + DEBOUNCE;                       // onAccessibilityEvent
                    if (fast) peekAt = now + tempo.changed(now, c.opened);
                } else if (debounceAt == now) {
                    debounceAt = -1;
                    stepAt = now + Math.max(DEBOUNCE, MIN_GAP);        // scheduleStep(DEBOUNCE_MS)
                } else if (peekAt == now) {
                    peekAt = -1;
                    peeks++;
                    peeksSinceLook++;
                    Tempo.Answer answer = tempo.peek(now, screen, scale, lastActionAt);
                    if (answer.verdict == Tempo.Verdict.PEEK) {
                        peekAt = now + answer.delay;
                    } else if (answer.verdict == Tempo.Verdict.STEP) {
                        // The look the running pause (debounceAt) was about to book, taken
                        // now in its place. It books its own next, as every look does.
                        eagerLooks++;
                        if (debounceAt < 0) pauseWasOver = true;
                        debounceAt = -1;
                        stepAt = -1;
                        look(now);
                    }
                } else {
                    stepAt = -1;
                    look(now);
                }
            }
            return done;
        }
    }

    // ------------------------------------------------------------------ tests

    /** A screen as the service writes it down for Tempo: the window, then one line per thing on it. */
    private static String scr(int window, String... words) {
        StringBuilder out = new StringBuilder().append(window);
        int y = 0;
        for (String word : words) {
            out.append(Tempo.NODE).append(word).append(' ').append(Tempo.WORDS_END).append("e---0,").append(y).append(",720,").append(y += 80);
        }
        return out.toString();
    }

    /** The same screen with one thing on it in another state (a ripple, a tick in a box). */
    private static String touched(String screen) {
        return screen.replaceFirst("e---", "e-s-");
    }

    private static final String LIST = scr(1, "Total Customers", "SMR1001", "Asha Dairy", "SMR1002", "Bela Stores", "SMR1003", "Chand Milk");
    private static final String LIST_BUSY = scr(1, "Total Customers", "SMR1001", "Asha Dairy", "SMR1002", "Bela Stores", "SMR1003", "Chand Milk", "Loading");
    private static final String DETAILS_1 = scr(1, "Customer Details");
    private static final String DETAILS_2 = scr(1, "Customer Details", "Name", "Asha Dairy (SMR1001)");
    private static final String DETAILS = scr(1, "Customer Details", "Name", "Asha Dairy (SMR1001)", "Distributor Name", "View Orders", "Milk", "PROD");
    private static final String DIALOG_1 = scr(2, "Select date");
    private static final String DIALOG = scr(2, "Select date", "November 2026", "16", "17", "CANCEL", "OK");
    private static final String BUSY_BOX = scr(3, "Loading");

    private static void rules() {
        section("Which screens count as a different page");
        check(!Tempo.differentPage(LIST, LIST), "the same screen: no");
        check(!Tempo.differentPage(LIST, touched(LIST)), "a ripple or a tick on the same page: no");
        check(!Tempo.differentPage(LIST, LIST_BUSY) && !Tempo.differentPage(LIST_BUSY, LIST), "a spinner on the page: no");
        check(Tempo.differentPage(LIST, DETAILS) && Tempo.differentPage(DETAILS, LIST), "the page replaced by another, same window: yes");
        check(Tempo.differentPage(DETAILS, DIALOG) && Tempo.differentPage(DIALOG, DETAILS), "a dialog over the page (another window): yes");
        check(Tempo.differentPage(LIST, BUSY_BOX), "the app's Loading box (another window): yes");
        String scrolled = scr(1, "Total Customers", "SMR1004", "Dev Dairy", "SMR1005", "Ekta Stores", "SMR1006", "Fateh Milk");
        String nudged = scr(1, "Total Customers", "SMR1002", "Bela Stores", "SMR1003", "Chand Milk", "SMR1004", "Dev Dairy");
        check(Tempo.differentPage(LIST, scrolled), "the list after a scroll that brought new rows: yes");
        check(!Tempo.differentPage(LIST, nudged), "the list moved by one row: no");
        String form = scr(1, "New Order Milk", "Copy Order", "PLACE ORDER", "Date", "Total");
        String copied = scr(1, "New Order Milk", "Copy Order", "PLACE ORDER", "Date", "Total", "FCM 1000 ML", "12");
        check(!Tempo.differentPage(form, copied), "the copied rows arriving in the order form: no");
        check(!Tempo.differentPage(DETAILS, scr(1, "Customer Details", "Name", "Asha Dairy (SMR1001)", "Distributor Name", "View Orders", "Milk", "PROD", "Transactions", "Pay")),
                "a page with its last few lines filled in: no");
        check(!Tempo.differentPage(scr(1), scr(1)) && !Tempo.differentPage(scr(1, ""), touched(scr(1, ""))),
                "screens without words cannot be told apart: no");
        check(!Tempo.differentPage("", LIST) && !Tempo.differentPage(LIST, "") && !Tempo.differentPage(null, LIST)
                && !Tempo.differentPage(LIST, null), "nothing read: no");
        check(Tempo.SAME_PAGE_PERCENT == 50, "the line is at half the words");

        section("The rules, one at a time");
        String A = LIST, B = DETAILS;
        Tempo tempo = new Tempo();
        check(tempo.peek(100, B, 1f, 0).verdict == Tempo.Verdict.LEAVE, "before the bot has looked once: nothing");
        tempo.stepped(1000, A, true, 1000);
        check(tempo.peek(1100, A, 1f, 1000).verdict == Tempo.Verdict.LEAVE, "the screen the bot already saw: nothing to be early for");
        check(tempo.peek(1100, touched(A), 1f, 1000).verdict == Tempo.Verdict.LEAVE
                && tempo.peek(1160, touched(A), 1f, 1000).verdict == Tempo.Verdict.LEAVE
                && tempo.peek(1900, touched(A), 1f, 1000).verdict == Tempo.Verdict.LEAVE,
                "the same page with something small changed: left to the bot's own timers, however long it is still");
        check(tempo.peek(1100, LIST_BUSY, 1f, 1000).verdict == Tempo.Verdict.LEAVE
                && tempo.peek(1900, LIST_BUSY, 1f, 1000).verdict == Tempo.Verdict.LEAVE, "a spinner on the page: the same");
        check(tempo.peek(1100, "", 1f, 1000).verdict == Tempo.Verdict.LEAVE
                && tempo.peek(1100, null, 1f, 1000).verdict == Tempo.Verdict.LEAVE, "the app is not on screen: nothing");

        tempo.changed(1150, false);
        Tempo.Answer first = tempo.peek(1210, B, 1f, 1000);
        check(first.verdict == Tempo.Verdict.PEEK && first.delay == Tempo.SETTLE_MS, "a new page, seen once: read it again in a moment");
        Tempo.Answer second = tempo.peek(1270, B, 1f, 1000);
        check(second.verdict == Tempo.Verdict.PEEK && second.delay == 1000 + Tempo.AFTER_ACTION_MS - 1270,
                "the same twice, but too soon after the press: wait for the gap (" + second.delay + " ms)");
        check(tempo.peek(1420, B, 1f, 1000).verdict == Tempo.Verdict.STEP,
                "the gap is over: look now, " + Tempo.AFTER_ACTION_MS + " ms after the press");

        tempo.stepped(2000, A, true, 2000);
        tempo.changed(2100, false);
        tempo.peek(2160, DETAILS_2, 1f, 2000);
        Tempo.Answer moved = tempo.peek(2220, B, 1f, 2000);
        check(moved.verdict == Tempo.Verdict.PEEK && moved.delay == Tempo.SETTLE_MS, "still being written: not yet");
        tempo.changed(2300, false);
        tempo.peek(2360, B, 1f, 2000);
        Tempo.Answer shifted = tempo.peek(2420, touched(B), 1f, 2000);
        check(shifted.verdict == Tempo.Verdict.PEEK && shifted.delay == Tempo.SETTLE_MS,
                "the same words but something moved or changed state: not yet");

        // The ceiling: only while the bot's own 320 ms pause after the last change is running.
        check(Tempo.PAUSE_MS == 320, "the bot's own pause is 320 ms");
        tempo.stepped(2400, A, true, 2400);
        tempo.changed(2600, false);
        tempo.peek(2660, B, 1f, 2400);
        check(tempo.peek(2919, B, 1f, 2400).verdict == Tempo.Verdict.STEP, "in the last millisecond of the pause: look");
        tempo.stepped(2400, A, true, 2400);
        tempo.changed(2600, false);
        tempo.peek(2660, B, 1f, 2400);
        check(tempo.peek(2920, B, 1f, 2400).verdict == Tempo.Verdict.LEAVE,
                "the pause is over: the bot's own look is 420 ms away or less, and is left to come");
        tempo.stepped(2500, A, true, 2500);
        tempo.changed(2590, false);
        tempo.peek(2650, B, 1f, 2500);
        check(tempo.peek(2710, B, 1f, 2500).verdict == Tempo.Verdict.LEAVE,
                "the page was complete so soon after the press that the gap ends after the pause: nothing to gain, nothing done");
        tempo.changed(2601, false);
        tempo.peek(2661, B, 1f, 2500);
        check(tempo.peek(2721, B, 1f, 2500).verdict == Tempo.Verdict.PEEK && tempo.peek(2920, B, 1f, 2500).verdict == Tempo.Verdict.STEP,
                "one more change a moment later, and there is: " + (Tempo.BETWEEN_IDLE_MS + 2601 - 2920) + " ms saved");

        // A page opened: the phone's animation is waited out.
        tempo.stepped(3000, A, true, 3000);
        tempo.changed(3100, true);
        tempo.changed(3200, false);
        tempo.peek(3260, DIALOG, 1f, 3000);
        Tempo.Answer sliding = tempo.peek(3320, DIALOG, 1f, 3000);
        check(sliding.verdict == Tempo.Verdict.PEEK && sliding.delay == 3100 + Tempo.WINDOW_ANIMATION_MS - 3320,
                "a dialog that has just opened is still coming in: not yet");
        check(tempo.peek(3100 + Tempo.WINDOW_ANIMATION_MS, DIALOG, 1f, 3000).verdict == Tempo.Verdict.STEP, "the animation is over: look now");

        tempo.stepped(4000, A, true, 4000);
        tempo.changed(4150, true);
        tempo.peek(4210, DIALOG, 0f, 4000);
        check(tempo.peek(4420, DIALOG, 0f, 4000).verdict == Tempo.Verdict.STEP,
                "animations switched off on the phone: no wait for them");
        tempo.stepped(5000, A, true, 5000);
        tempo.changed(5150, true);
        tempo.peek(5210, DIALOG, 0.5f, 5000);
        check(tempo.peek(5420, DIALOG, 0.5f, 5000).verdict == Tempo.Verdict.STEP, "animations at half speed: half the wait");
        tempo.stepped(6000, A, true, 6000);
        tempo.changed(6150, true);
        tempo.peek(6210, DIALOG, Float.NaN, 6000);
        check(tempo.peek(6420, DIALOG, Float.NaN, 6000).verdict != Tempo.Verdict.STEP, "an unreadable animation setting counts as normal speed");
        tempo.stepped(6500, A, true, 6500);
        tempo.changed(6650, true);
        tempo.peek(6710, DIALOG, 3f, 6500);
        check(tempo.peek(6920, DIALOG, 3f, 6500).verdict == Tempo.Verdict.LEAVE, "animations slowed down three times: no early look at all");

        // After a look that pressed nothing, the old rhythm.
        tempo.stepped(7000, A, false, 6000);
        tempo.changed(7450, false);
        tempo.peek(7510, B, 1f, 6000);
        Tempo.Answer idle = tempo.peek(7570, B, 1f, 6000);
        check(idle.verdict == Tempo.Verdict.PEEK && idle.delay == 7000 + Tempo.BETWEEN_IDLE_MS - 7570,
                "after a look that pressed nothing: not before the old rhythm of " + Tempo.BETWEEN_IDLE_MS + " ms");
        check(tempo.peek(7740, B, 1f, 6000).verdict == Tempo.Verdict.STEP, "then look");

        // A press the look made without the bot's clock being told (Back is one such).
        tempo.stepped(8000, A, false, 6000);
        tempo.changed(8450, false);
        tempo.peek(8510, B, 0f, 6000);
        check(tempo.peek(8570, B, 0f, 6000).verdict == Tempo.Verdict.PEEK && tempo.peek(8740, B, 0f, 6000).verdict == Tempo.Verdict.STEP,
                "a look that is not known to have pressed anything is treated as the slow kind");

        // A press made outside a look (the scroll memory, a toast) counts too.
        tempo.stepped(9000, A, true, 9000);
        tempo.changed(9600, false);
        tempo.peek(9660, B, 1f, 9450);
        Tempo.Answer late = tempo.peek(9720, B, 1f, 9450);
        check(late.verdict == Tempo.Verdict.PEEK && late.delay == 9450 + Tempo.AFTER_ACTION_MS - 9720,
                "a press made since the last look moves the gap with it");

        tempo.stepped(10000, A, true, 10000);
        int asked = 0;
        Tempo.Answer answer;
        do {
            answer = tempo.peek(10500 + asked * 60L, scr(4, "spin " + asked), 1f, 10000);
            asked++;
        } while (answer.verdict == Tempo.Verdict.PEEK && asked < 100);
        check(answer.verdict == Tempo.Verdict.LEAVE && asked == Tempo.MAX_PEEKS + 1,
                "a screen that never stops changing is left to the old timer after " + Tempo.MAX_PEEKS + " peeks");

        // The look was taken while the copy check or the scroll memory kept the time: no signature.
        tempo.stepped(11000, "", true, 11000);
        tempo.changed(11100, false);
        check(tempo.peek(11160, B, 1f, 11000).verdict == Tempo.Verdict.LEAVE && tempo.peek(11900, B, 1f, 11000).verdict == Tempo.Verdict.LEAVE,
                "after a look that did not write the screen down: nothing early");
        tempo.reset();
        check(tempo.peek(100, B, 1f, 0).verdict == Tempo.Verdict.LEAVE, "START forgets everything");
    }

    /** Pressed at 0 on the list; the next page is reported at 80 ms and written by {@code ready}. */
    private static Sim pageOpens(boolean fast, float scale, long ready, boolean newWindow) {
        Sim sim = new Sim(fast, scale);
        sim.screen = LIST;
        sim.tempo.stepped(0, LIST, true, 0);
        String[] stages = newWindow ? new String[] {DIALOG_1, DIALOG} : new String[] {DETAILS_1, DETAILS_2, DETAILS};
        sim.wanted = stages[stages.length - 1];
        long first = Math.min(80, ready);
        if (newWindow) sim.opens(first, stages[0]); else sim.at(first, stages[0]);
        if (!newWindow && ready > first + 2) sim.at((first + ready) / 2, stages[1]);
        if (ready > first) sim.at(ready, sim.wanted);
        else sim.script.set(0, new Change(first, sim.wanted, newWindow, true));
        return sim;
    }

    private static void aPageOpens() {
        section("A button is pressed and the next page opens");
        long old = pageOpens(false, 1f, 250, true).run(5000);
        long fast = pageOpens(true, 1f, 250, true).run(5000);
        long noAnimations = pageOpens(true, 0f, 250, true).run(5000);
        long samePage = pageOpens(true, 1f, 250, false).run(5000);
        System.out.println("  page complete 250 ms after the press; next button pressed after: " + old + " ms before; "
                + "in fast mode " + fast + " ms (a new window), " + samePage + " ms (same window), "
                + noAnimations + " ms with animations off");
        check(old == 250 + DEBOUNCE + MIN_GAP, "before: " + (DEBOUNCE + MIN_GAP) + " ms after the last change (" + old + ")");
        check(fast > 0 && fast < old, "fast mode is earlier (" + fast + ")");
        check(fast >= 80 + Tempo.WINDOW_ANIMATION_MS, "but not before the window has finished coming in");
        check(noAnimations == Tempo.AFTER_ACTION_MS, "with animations off: at the minimum gap, " + Tempo.AFTER_ACTION_MS + " ms");
        check(old - fast >= Tempo.AFTER_ACTION_MS, "what is saved here is more than " + Tempo.AFTER_ACTION_MS + " ms");
        check(samePage == Tempo.AFTER_ACTION_MS, "a page swapped inside the same window: at the minimum gap");

        // The page is written slowly: the look waits for it.
        Sim slow = pageOpens(true, 0f, 700, false);
        long slowDone = slow.run(5000);
        check(slowDone >= 700 + 2 * Tempo.SETTLE_MS, "a page still being written is not acted on (" + slowDone + " ms)");

        int cases = 0, later = 0;
        long worstLater = 0, sumBefore = 0, sumAfter = 0;
        for (boolean window : new boolean[] {true, false}) {
            for (float scale : new float[] {0f, 0.5f, 1f, 1.5f, 2f}) {
                for (long ready = 40; ready <= 3000; ready += 37) {
                    Sim before = pageOpens(false, scale, ready, window), after = pageOpens(true, scale, ready, window);
                    long a = before.run(20000), b = after.run(20000);
                    cases++;
                    sumBefore += a;
                    sumAfter += b;
                    if (b > a) {
                        later++;
                        worstLater = Math.max(worstLater, b - a);
                    }
                    check(b > 0 && b <= a + 600, "page ready at " + ready + " ms, x" + scale + ": handled (" + b + " against " + a + ")");
                    check(!after.pauseWasOver, "page ready at " + ready + " ms: an extra look only while the bot's own pause is running");
                    check(b >= ready && b >= Tempo.AFTER_ACTION_MS, "and never before the page is there, nor inside the gap after the press");
                    for (int n = 1; n < after.looks.size(); n++) {
                        check(after.looks.get(n) - after.looks.get(n - 1) >= MIN_GAP,
                                "page ready at " + ready + " ms: two looks are never closer than the minimum gap");
                    }
                                check(after.looks.size() <= before.looks.size() + 1, "page ready at " + ready + " ms: at most one more look than before ("
                            + after.looks.size() + " against " + before.looks.size() + ")");
                }
            }
        }
        System.out.println("  " + cases + " timings tried: on average " + sumBefore / cases + " ms before, " + sumAfter / cases
                + " ms in fast mode; later than before in " + later + " (by at most " + worstLater + " ms)");
        check(sumAfter < sumBefore, "on the whole it is quicker");
        // The cost, stated: a page that stops half-written for a moment is now looked at, found
        // not ready, and the look that would have caught it complete is the one that was taken
        // early. This grid is hard on purpose - every page stops half-written, for up to 1.5 s.
        check(later * 5 <= cases && worstLater <= 600, "later than before in fewer than one case in five of this grid, by at most 0.6 s ("
                + later + " of " + cases + ", " + worstLater + " ms)");

        // Pages that come up in one piece - which is what most are - are never later.
        int whole = 0, wholeEarlier = 0;
        long wholeBefore = 0, wholeAfter = 0;
        for (boolean window : new boolean[] {true, false}) {
            for (float scale : new float[] {0f, 1f, 2f}) {
                for (long ready = 40; ready <= 3000; ready += 37) {
                    Sim before = new Sim(false, scale), after = new Sim(true, scale);
                    before.screen = after.screen = LIST;
                    after.tempo.stepped(0, LIST, true, 0);
                    before.wanted = after.wanted = window ? DIALOG : DETAILS;
                    before.alsoGood = after.alsoGood = touched(before.wanted);
                    if (window) {
                        before.opens(ready, DIALOG);
                        after.opens(ready, DIALOG);
                    } else {
                        before.at(ready, DETAILS);
                        after.at(ready, DETAILS);
                    }
                    // a last small change as the page finishes drawing
                    before.at(ready + 150, touched(before.wanted));
                    after.at(ready + 150, touched(after.wanted));
                    long a = before.run(20000), b = after.run(20000);
                    whole++;
                    check(b > 0 && b <= a, "a page that comes up in one piece at " + ready + " ms, x" + scale + ": never later (" + b + " <= " + a + ")");
                    wholeBefore += a;
                    wholeAfter += b;
                    if (b < a) wholeEarlier++;
                }
            }
        }
        System.out.println("  " + whole + " pages that come up in one piece: none later, " + wholeEarlier + " earlier; on average "
                + wholeBefore / whole + " ms before, " + wholeAfter / whole + " ms in fast mode");
        // Not all: once the app has taken longer than the bot's 900 ms timer, the bot is in the
        // slow rhythm of a wait, and an early look is only allowed 740 ms after the last one.
        check(wholeEarlier * 3 >= whole, "and a good share of them are earlier (" + wholeEarlier + " of " + whole + ")");
    }

    private static void nothingToBeEarlyFor() {
        section("The same page, changed a little: exactly the old timing");
        Sim fast = new Sim(true, 1f);
        fast.screen = LIST;
        fast.tempo.stepped(0, LIST, true, 0);
        fast.wanted = LIST;
        check(fast.run(5000) == ACTION_DELAY, "a press that changes nothing: the next look comes with the 900 ms timer, as before");

        String[][] cases = {
                {touched(LIST), "a ripple, a tick in a box"},
                {LIST_BUSY, "a spinner on the page"},
                {scr(1, "Total Customers", "SMR1002", "Bela Stores", "SMR1003", "Chand Milk", "SMR1004", "Dev Dairy"), "the list moved by one row"},
        };
        for (String[] one : cases) {
            for (long at = 30; at <= 2400; at += 110) {
                Sim a = new Sim(false, 1f).at(at, one[0]);
                Sim b = new Sim(true, 1f).at(at, one[0]);
                a.screen = b.screen = LIST;
                b.tempo.stepped(0, LIST, true, 0);
                a.wanted = b.wanted = one[0];
                long was = a.run(9000), is = b.run(9000);
                check(was > 0 && is == was && a.looks.equals(b.looks), one[1] + " at " + at + " ms: the same looks at the same times (" + is + ")");
            }
        }

        // The + button: pressed, the history page ripples, the form opens 600 ms later.
        // Before, the look came 740 ms after the ripple; it must not come sooner now, or + is pressed twice.
        Sim plus = new Sim(true, 0f).at(50, touched(LIST)).at(600, DETAILS);
        plus.screen = LIST;
        plus.tempo.stepped(0, LIST, true, 0);
        plus.wanted = DETAILS;
        plus.run(9000);
        check(plus.looks.get(0) >= 600, "a page that answers slowly gets its full beat: no look while it only shows a ripple (" + plus.looks + ")");

        // A change the app does not report.
        Sim c = new Sim(true, 1f).silently(200, DETAILS);
        c.screen = LIST;
        c.tempo.stepped(0, LIST, true, 0);
        c.wanted = DETAILS;
        check(c.run(5000) == ACTION_DELAY && c.peeks == 0, "a change the app does not report is found by the timer, as before");
    }

    private static Sim waiting(boolean fast, long reply, boolean box) {
        // Pressed at 0; busy at 60 ms; the answer - another page - comes at `reply`.
        Sim sim = new Sim(fast, 1f);
        sim.screen = LIST;
        sim.tempo.stepped(0, LIST, true, 0);
        sim.wanted = DETAILS;
        if (box) sim.opens(60, BUSY_BOX); else sim.at(60, LIST_BUSY);
        return sim.at(reply, DETAILS);
    }

    private static void waitingForTheServer() {
        section("Waiting for the server");
        for (boolean box : new boolean[] {false, true}) {
            int worstExtra = 0, later = 0, cases = 0;
            long worstEarly = 0, bestGain = 0, worstLater = 0;
            for (long reply = 300; reply <= 12000; reply += 170) {
                Sim before = waiting(false, reply, box), after = waiting(true, reply, box);
                long a = before.run(60000), b = after.run(60000);
                cases++;
                check(b > 0 && b <= a, "answer at " + reply + " ms: acted on no later than before (" + b + " <= " + a + ")");
                check(!after.pauseWasOver, "answer at " + reply + " ms: an extra look only while the bot's own pause is running");
                if (b > a) {
                    later++;
                    worstLater = Math.max(worstLater, b - a);
                }
                bestGain = Math.max(bestGain, a - b);
                int idleBefore = before.looks.size() - 1, idleAfter = after.looks.size() - 1;
                worstExtra = Math.max(worstExtra, idleAfter - idleBefore);
                for (int n = 0; n < Math.min(idleBefore, idleAfter); n++) {
                    worstEarly = Math.max(worstEarly, before.looks.get(n) - after.looks.get(n));
                }
                check(after.eagerLooks <= (box ? 2 : 1), "answer at " + reply + " ms: extra looks: "
                        + (box ? "one for the Loading box, one for the answer" : "one, for the answer") + " (" + after.eagerLooks + ")");
                for (int n = 1; n < after.looks.size(); n++) {
                    check(after.looks.get(n) - after.looks.get(n - 1) >= MIN_GAP, "answer at " + reply + " ms: two looks never closer than the minimum gap");
                }
            }
            String kind = box ? "a Loading box over the page" : "a spinner on the page";
            check(worstExtra <= 1, kind + ": at most one more look is spent on the same wait (" + worstExtra + ")");
            check(worstEarly <= ACTION_DELAY, kind + ": so \"give up after N looks\" comes at most one look (" + worstEarly + " ms) sooner");
            check(bestGain >= 300, kind + ": the answer is acted on up to " + bestGain + " ms sooner");
            System.out.println("  " + kind + ": answer acted on up to " + bestGain + " ms sooner; later in " + later + " of " + cases
                    + " (by at most " + worstLater + " ms); a wait counted in looks ends at most " + worstEarly + " ms sooner");
        }
    }

    private static void aScreenThatNeverSettles() {
        section("A screen that never stops changing");
        Sim before = new Sim(false, 1f), after = new Sim(true, 1f);
        before.screen = after.screen = LIST;
        after.tempo.stepped(0, LIST, true, 0);
        before.wanted = after.wanted = DETAILS;
        for (long at = 50; at < 6000; at += 100) {
            before.at(at, scr(5, "frame " + at));
            after.at(at, scr(5, "frame " + at));
        }
        before.at(6000, DETAILS);
        after.at(6000, DETAILS);
        long a = before.run(30000), b = after.run(30000);
        check(b > 0 && b <= a, "it is still handled, no later than before (" + b + " <= " + a + ")");
        check(after.looks.size() <= before.looks.size() + 1, "with no more looks than before (" + after.looks.size()
                + " against " + before.looks.size() + ")");
        check(after.mostPeeksBetweenLooks <= Tempo.MAX_PEEKS + 1, "and a bounded number of peeks between two looks ("
                + after.mostPeeksBetweenLooks + ")");
    }

    private static void aWholeRetailer() {
        section("One retailer, up to PLACE ORDER: eight presses that each bring a new page (model figures)");
        long old = 0, fast = 0, off = 0;
        long[] ready = {180, 250, 300, 220, 350, 200, 260, 240};
        boolean[] window = {false, false, false, true, true, true, false, false};
        for (int n = 0; n < ready.length; n++) {
            old += pageOpens(false, 1f, ready[n], window[n]).run(9000);
            fast += pageOpens(true, 1f, ready[n], window[n]).run(9000);
            off += pageOpens(true, 0f, ready[n], window[n]).run(9000);
        }
        System.out.println("  waiting between presses: " + old + " ms before, " + fast
                + " ms in fast mode, " + off + " ms with the phone's animations off");
        check(fast * 10 <= old * 7, "fast mode spends at most seven tenths of the time between presses");
        check(off <= fast, "less still with animations off");
        check(off >= ready.length * Tempo.AFTER_ACTION_MS, "and never less than the minimum gap per press");
    }

    // ------------------------------------------------------------------ wiring

    private static String read(String name) throws Exception {
        File file = new File("../../app/src/main/java/com/vrksignatures/sudhaautobot/" + name);
        return new String(Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String method(String source, String signature) {
        int from = source.indexOf(signature);
        if (from < 0) return "";
        int to = source.indexOf("\n    }\n", from);
        return source.substring(from, to < 0 ? source.length() : to);
    }

    private static long constant(String source, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(name + " = (\\d+);").matcher(source);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    private static void wiring() throws Exception {
        section("How it is wired into the bot");
        String service = read("SudhaAccessibilityService.java");
        check(Tempo.AFTER_ACTION_MS == constant(service, "MIN_ACTION_GAP_MS"),
                "the gap after a press is the bot's own minimum gap (" + constant(service, "MIN_ACTION_GAP_MS") + " ms)");
        check(Tempo.BETWEEN_IDLE_MS == constant(service, "DEBOUNCE_MS") + constant(service, "MIN_ACTION_GAP_MS"),
                "the rhythm of a wait is the old one (" + Tempo.BETWEEN_IDLE_MS + " ms)");
        check(DEBOUNCE == constant(service, "DEBOUNCE_MS") && MIN_GAP == constant(service, "MIN_ACTION_GAP_MS")
                && ACTION_DELAY == constant(service, "ACTION_DELAY_MS"), "this test's copy of the old timers matches the app's");
        for (String name : new String[] {"IDLE_DELAY_MS = 1200", "ACTION_DELAY_MS = 900", "FAST_DELAY_MS = 550",
                "DEBOUNCE_MS = 320", "MIN_ACTION_GAP_MS = 420"}) {
            check(service.contains("private static final long " + name + ";"), "the old timer is untouched: " + name);
        }
        check(method(service, "private void scheduleStep(").contains("handler.postDelayed(stepRunnable, Math.max(delay, MIN_ACTION_GAP_MS));"),
                "a timed look is still never booked inside the minimum gap");
        check(method(service, "private boolean tapLabel(").contains("System.currentTimeMillis() - lastActionAt < MIN_ACTION_GAP_MS"),
                "the bot still refuses to press twice inside the minimum gap");
        String event = method(service, "public void onAccessibilityEvent(");
        check(event.contains("handler.postDelayed(debouncedStep, DEBOUNCE_MS);")
                && event.indexOf("tempoChanged(event)") > event.indexOf("handler.postDelayed(debouncedStep, DEBOUNCE_MS);"),
                "every change on the screen still books the old look; the peek is booked as well");
        String step = method(service, "private void step(");
        check(step.contains("inspect(root)") && step.contains("resolveRoot()") && step.contains("next(IDLE_DELAY_MS)"),
                "a look is the same look as before");
        String changed = method(service, "private void tempoChanged(");
        check(changed.trim().contains("if (!fastMode) return;"), "switched off, nothing is added");
        int peekFrom = service.indexOf("private final Runnable tempoPeek");
        String peek = peekFrom < 0 ? "" : service.substring(peekFrom, service.indexOf("private void tempoStepped("));
        check(peek.contains("now < quietUntil") && peek.contains("catch (Throwable") && peek.contains("fastMode = false"),
                "the peek stands aside for the copy check and the scroll memory, and a fault switches fast mode off");
        check(peek.contains("step();") && !peek.contains("inspect(") && !peek.contains("tapLabel(")
                && !peek.contains("AccessibilityTree.click"), "a peek presses nothing; it can only run the ordinary look");
        check(peek.contains("handler.removeCallbacks(debouncedStep);") && peek.contains("handler.removeCallbacks(stepRunnable);")
                && peek.indexOf("handler.removeCallbacks(stepRunnable);") < peek.indexOf("step();"),
                "the early look takes the place of the one that was coming: the pause and the booked look are dropped first");
        check(!peek.contains("scheduleStep(") && !peek.contains("next(") && !peek.contains("quick(")
                && peek.contains("handler.postDelayed(this, answer.delay)"),
                "the peek books nothing but itself");
        check(peek.contains("ledger.hasPending()") && peek.indexOf("ledger.hasPending()") < peek.indexOf("tempo.peek("),
                "while an order is being placed and paid for, fast mode stands aside");
        String signature = method(service, "private String tempoSignature(");
        check(signature.contains("!isTargetRoot(root)") && signature.contains("return \"\";"),
                "only the Sudha app's own screen is written down");
        String start = method(service, "private void tempoAtStart(");
        check(start.contains("MainActivity.KEY_FAST_MODE") && start.contains("TRANSITION_ANIMATION_SCALE")
                && start.contains("WINDOW_ANIMATION_SCALE"), "START reads the switch and the phone's animation setting");
        check(read("Tempo.java").indexOf("import android") < 0, "Tempo is plain Java");
        check(read("MainActivity.java").contains("putBoolean(KEY_FAST_MODE, on)"), "the switch is on the settings screen");

        File folder = new File("../../app/src/main/java/com/vrksignatures/sudhaautobot");
        File[] sources = folder.listFiles();
        int sleeps = 0, files = 0;
        if (sources != null) {
            for (File source : sources) {
                if (!source.getName().endsWith(".java")) continue;
                files++;
                String code = new String(Files.readAllBytes(source.toPath()), java.nio.charset.StandardCharsets.UTF_8)
                        .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
                if (code.contains("Thread.sleep") || code.contains("SystemClock.sleep") || code.contains(".sleep(")) sleeps++;
            }
        }
        check(files > 40 && sleeps == 0, "no Thread.sleep anywhere in the app (" + files + " files read)");
    }
}
