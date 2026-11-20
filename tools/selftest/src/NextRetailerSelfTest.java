import com.vrksignatures.sudhaautobot.AppNotices;
import com.vrksignatures.sudhaautobot.ListScroller;
import com.vrksignatures.sudhaautobot.RetailerSequence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Self-test for "which retailer is next" and for recognising the app's "already ordered"
 * message. Runs the app's own RetailerSequence, ListScroller and AppNotices on a plain JVM.
 *
 * <p>What this shows: on the model of the Customers screen, the bot always goes on to the
 * retailer after the one handled last, scrolling down to find it, and never back to an
 * earlier row. What it cannot show: that the phone delivers the toast to the service - that
 * needs the device.</p>
 */
public final class NextRetailerSelfTest {
    private static int checks;
    private static int failures;

    public static void main(String[] args) {
        rules();
        recording();
        everyStartingPoint();
        lostPlace();
        numberingDoesNotMatter();
        notices();
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

    private static List<String> retailersOf(RetailerListModel model) {
        List<String> all = new ArrayList<>();
        for (String id : model.ids) if (id.startsWith("SMR")) all.add(id);
        return all;
    }

    // ------------------------------------------------------------------ A
    private static void rules() {
        section("A. The rule itself, one screen at a time");
        List<String> top = Arrays.asList("SM65", "SMR2255", "SMR2254", "SMR2253", "SMR2252",
                "SMR2251", "SMR2250");
        List<String> lower = Arrays.asList("SMR2252", "SMR2251", "SMR2250", "SMR2249", "SMR2248",
                "SMR2247");
        Set<String> none = new HashSet<>();

        RetailerSequence s = new RetailerSequence();
        RetailerSequence.Decision d = s.decide(top, null, none::contains);
        check(d.action == RetailerSequence.Action.OPEN && "SMR2255".equals(d.id),
                "nothing handled yet: the first SMR on screen (got " + d.action + " " + d.id + ")");
        check(d.index == 1, "the distributor row SM65 is stepped over");

        // The case from the recording: SMR2250 was done; the list is back at the top.
        s = new RetailerSequence();
        Set<String> done = new HashSet<>(Collections.singletonList("SMR2250"));
        d = s.decide(top, "SMR2250", done::contains);
        check(d.action == RetailerSequence.Action.SCROLL_FOR_MORE && d.id == null,
                "SMR2250 done and last on screen: nothing above it is opened, scroll for more (got "
                        + d.action + " " + d.id + ")");
        d = s.decide(lower, "SMR2250", done::contains);
        check(d.action == RetailerSequence.Action.OPEN && "SMR2249".equals(d.id),
                "after scrolling: SMR2249 is next (got " + d.action + " " + d.id + ")");

        // What the bot did before: first unhandled row on screen.
        String before = null;
        for (String id : top) {
            if (id.startsWith("SMR") && !done.contains(id)) {
                before = id;
                break;
            }
        }
        System.out.println("  after SMR2250, list reopened at the top: previous rule opens " + before
                + ", new rule scrolls and opens SMR2249");
        check("SMR2255".equals(before), "previous rule went back to the first row");

        // The last retailer is further down than this screen: open nothing, scroll.
        s = new RetailerSequence();
        done = new HashSet<>(Collections.singletonList("SMR2240"));
        d = s.decide(top, "SMR2240", done::contains);
        check(d.action == RetailerSequence.Action.SCROLL_TO_LAST && d.id == null,
                "last retailer not in view yet: scroll towards it, open nothing (got " + d.action + ")");
        check(!s.lastSeen(), "... and it is not counted as seen");

        // Seen, then scrolled off the top: everything on screen is after it.
        s = new RetailerSequence();
        done = new HashSet<>(Arrays.asList("SMR2253", "SMR2252", "SMR2251"));
        s.decide(Arrays.asList("SMR2254", "SMR2253", "SMR2252", "SMR2251"), "SMR2253", done::contains);
        check(s.lastSeen(), "last retailer on screen is noted as seen");
        d = s.decide(Arrays.asList("SMR2252", "SMR2251", "SMR2250", "SMR2249"), "SMR2253", done::contains);
        check(d.action == RetailerSequence.Action.OPEN && "SMR2250".equals(d.id),
                "last retailer scrolled off the top: first unhandled on screen (got " + d.id + ")");

        // Handled rows below the last one are stepped over, never an earlier one taken.
        s = new RetailerSequence();
        done = new HashSet<>(Arrays.asList("SMR2253", "SMR2252"));
        d = s.decide(top, "SMR2253", done::contains);
        check(d.action == RetailerSequence.Action.OPEN && "SMR2251".equals(d.id),
                "handled rows below the last one are stepped over (got " + d.id + ")");

        // A new visit forgets what was seen.
        s.newVisit();
        check(!s.lastSeen(), "a fresh visit to the list starts with nothing seen");
        d = s.decide(Collections.<String>emptyList(), "SMR2253", done::contains);
        check(d.action == RetailerSequence.Action.SCROLL_TO_LAST, "an empty screen opens nothing");
    }

    // ------------------------------------------------------------------ B
    private static void recording() {
        section("B. The recording: SMR2250 done by hand, list reopens at the top");
        RetailerListModel model = new RetailerListModel(125);
        ListRunner bot = new ListRunner(model, true);
        bot.done.add("SMR2250");
        bot.last = "SMR2250";
        bot.start();
        bot.run(200000);

        List<String> all = retailersOf(model);
        int at = all.indexOf("SMR2250");
        List<String> below = all.subList(at + 1, all.size());
        List<String> above = all.subList(0, at);
        check(!bot.opened.isEmpty() && "SMR2249".equals(bot.opened.get(0)),
                "the first retailer opened is SMR2249 (was " + (bot.opened.isEmpty() ? "none" : bot.opened.get(0)) + ")");
        check(bot.opened.size() >= below.size() && bot.opened.subList(0, below.size()).equals(below),
                "then every retailer below it, in list order, down to the last (" + below.size() + ")");
        System.out.println("  opened " + bot.opened.get(0) + ", " + bot.opened.get(1) + ", "
                + bot.opened.get(2) + " ... " + bot.opened.get(below.size() - 1) + " (" + below.size()
                + " retailers; at most " + bot.mostScrollsForOne + " scroll steps before one retailer)");
        List<String> afterwards = bot.opened.subList(below.size(), bot.opened.size());
        check(afterwards.equals(above),
                "only after the bottom of the list: the five passed over, top to bottom " + afterwards);
        check(bot.sweeps == 1 && bot.restarts == 0, "one pass from the top at the end, no more");
        check(bot.finished && bot.done.size() == 125, "all 125 handled, run ended");
        check(bot.last == null, "the place in the list is forgotten once the run is over");

        // The list holds fewer retailers than the "expected" setting says: one extra pass
        // from the top finds nothing new, and the run still ends.
        RetailerListModel shortList = new RetailerListModel(23);
        ListRunner counted = new ListRunner(shortList, true);
        counted.expectedCount = 125;
        counted.start();
        counted.run(200000);
        check(counted.opened.equals(retailersOf(shortList)) && counted.finished && counted.sweeps == 1,
                "expected 125, list holds 23: all 23 in order, one extra pass, run ended (sweeps "
                        + counted.sweeps + ")");
    }

    // ------------------------------------------------------------------ C
    private static void everyStartingPoint() {
        section("C. From any retailer, for every list behaviour");
        int scenarios = 0;
        int[] sizes = {1, 3, 6, 7, 23, 125};
        for (RetailerListModel.Mode mode : RetailerListModel.Mode.values()) {
            for (int size : sizes) {
                for (boolean backToTop : new boolean[]{true, false}) {
                    for (int fling : new int[]{0, 120}) {
                        Set<Integer> starts = new HashSet<>(Arrays.asList(
                                0, 1, 5, 6, 7, size / 2, size - 2, size - 1));
                        for (int start : starts) {
                            if (start < 0 || start >= size) continue;
                            scenarios++;
                            RetailerListModel model = new RetailerListModel(size);
                            model.mode = mode;
                            model.flingAfterDrag = fling;
                            List<String> all = retailersOf(model);
                            ListRunner bot = new ListRunner(model, backToTop);
                            bot.done.add(all.get(start));
                            bot.last = all.get(start);
                            bot.start();
                            bot.run(200000);
                            String label = mode + ", " + size + " retailers, last done was no. "
                                    + (start + 1) + (backToTop ? ", list reopens at the top" : "")
                                    + (fling > 0 ? ", list runs on after a swipe" : "");
                            List<String> inOrder = new ArrayList<>(all.subList(start + 1, size));
                            inOrder.addAll(all.subList(0, start));
                            check(bot.opened.equals(inOrder),
                                    label + ": the " + (size - start - 1) + " below it in list order, "
                                            + "then the " + start + " passed over (opened "
                                            + bot.opened.size() + ")");
                            check(bot.finished && bot.restarts == 0 && bot.sweeps == (start > 0 ? 1 : 0),
                                    label + ": ended at the bottom without losing its place ("
                                            + bot.lastStep + ", restarts " + bot.restarts
                                            + ", passes from the top " + bot.sweeps + ")");
                        }
                    }
                }
            }
        }
        System.out.println("  " + scenarios + " scenarios");
    }

    // ------------------------------------------------------------------ D
    private static void lostPlace() {
        section("D. The retailer handled last is no longer in the list");
        for (RetailerListModel.Mode mode : RetailerListModel.Mode.values()) {
            RetailerListModel model = new RetailerListModel(23);
            model.mode = mode;
            List<String> all = retailersOf(model);
            ListRunner bot = new ListRunner(model, true);
            bot.done.add("SMR9999");
            bot.last = "SMR9999";
            bot.done.add(all.get(0));
            bot.start();
            bot.run(200000);
            check(bot.restarts == 1, mode + ": one fresh pass from the top (restarts " + bot.restarts + ")");
            check(bot.opened.equals(all.subList(1, all.size())),
                    mode + ": then every unhandled retailer, in list order (" + bot.opened.size() + ")");
            check(bot.finished, mode + ": the run ended");
        }
        System.out.println("  not found by the bottom of the list -> one pass from the top, then done");
    }

    // ------------------------------------------------------------------ E
    private static void numberingDoesNotMatter() {
        section("E. Only the order on screen counts, not the numbers");
        Random random = new Random(20261118);
        for (int round = 0; round < 6; round++) {
            RetailerListModel model = new RetailerListModel(40);
            List<String> retailers = retailersOf(model);
            if (round == 0) Collections.reverse(retailers);        // counting up
            else Collections.shuffle(retailers, random);           // any order at all
            model.ids.clear();
            model.ids.add("SM65");
            model.ids.addAll(retailers);
            int start = round * 6;
            ListRunner bot = new ListRunner(model, true);
            bot.done.add(retailers.get(start));
            bot.last = retailers.get(start);
            bot.start();
            bot.run(200000);
            List<String> inOrder = new ArrayList<>(retailers.subList(start + 1, retailers.size()));
            inOrder.addAll(retailers.subList(0, start));
            check(bot.opened.equals(inOrder) && bot.finished,
                    (round == 0 ? "ids counting up" : "ids in mixed order") + ", last done no. "
                            + (start + 1) + ": the rest in list order, then those passed over ("
                            + bot.opened.size() + ")");
        }
    }

    // ------------------------------------------------------------------ F
    private static void notices() {
        section("F. Recognising \"Today Order already created\"");
        String[] yes = {"Today Order already created", "today order already created",
                "Today  Order\nalready created.", "Order already placed for today",
                "Today's order already exists"};
        String[] no = {"", null, "Order placed", "Successfully Payment Created",
                "No order to copy from selected date", "Getting placed orders...",
                "In Progress", "Loading", "Are you sure you want to Proceed Pay?",
                "Scrolling down 9/125", "already", "Order created on 17 Nov,2026"};
        for (String text : yes) check(AppNotices.isAlreadyOrdered(text), "recognised: " + text);
        for (String text : no) check(!AppNotices.isAlreadyOrdered(text), "not mistaken for it: " + text);
        System.out.println("  " + yes.length + " wordings recognised, " + no.length
                + " other messages from the app left alone");
    }
}
