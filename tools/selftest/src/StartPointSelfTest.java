import com.vrksignatures.sudhaautobot.RetailerSequence;
import com.vrksignatures.sudhaautobot.StartPoint;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The day's history (wiped when the date changes) and the "Last done ID" box: where the bot carries on, which rows it
 * leaves unopened, and what a mistyped number costs.
 *
 * The list is walked the way the service walks it: look at the rows on screen, let the
 * start point look first, ask RetailerSequence what to do, open or scroll. After every
 * retailer the Sudha app shows the list again from the top.
 */
public final class StartPointSelfTest {
    private static int checks;
    private static int failures;

    public static void main(String[] args) throws Exception {
        typing();
        freshness();
        pauseAndComeBack();
        typedStartPoint();
        mistypedStartPoint();
        partlyKnown();
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

    // ----------------------------------------------------------------- a day

    /** The day's ledger, as far as the list is concerned. */
    private static final class Day implements StartPoint.Book {
        final Map<String, String> records = new LinkedHashMap<>();
        String last;

        @Override
        public boolean isProcessed(String id) {
            return records.containsKey(id);
        }

        @Override
        public String lastHandled() {
            return last;
        }

        @Override
        public boolean markDoneBefore(String id, String why) {
            if (records.containsKey(id)) return false;
            records.put(id, "DONE_BEFORE");
            return true;
        }

        void record(String id) {
            records.put(id, "SUCCESS");
            last = id;
        }
    }

    /** One distributor on top, then retailers counting down from SMR{@code first}. */
    private static List<String> list(int first, int howMany) {
        List<String> ids = new ArrayList<>();
        ids.add("SM65");
        for (int i = 0; i < howMany; i++) ids.add("SMR" + (first - i));
        return ids;
    }

    private static final class Run {
        final List<String> opened = new ArrayList<>();
        int restarts;
        int scrolls;
        boolean complete;
    }

    /**
     * Walks the list until the run completes, {@code stopAfter} retailers have been opened,
     * or something goes round in circles.
     */
    private static Run walk(List<String> ids, Day day, StartPoint.Seeker probe,
                            int expected, int stopAfter) {
        final int rowsOnScreen = 7;
        final int rowsPerScroll = 5;
        Run run = new Run();
        RetailerSequence sequence = new RetailerSequence();
        int top = 0;
        boolean swept = false;
        sequence.newVisit();
        for (int tick = 0; tick < 20000; tick++) {
            List<String> onScreen = ids.subList(top, Math.min(ids.size(), top + rowsOnScreen));
            if (top == 0) sequence.newVisit();
            if (probe != null) probe.sees(onScreen, day);
            RetailerSequence.Decision decision = sequence.decide(onScreen, day.last, day::isProcessed);
            if (decision.action == RetailerSequence.Action.OPEN) {
                run.opened.add(decision.id);
                day.record(decision.id);
                if (run.opened.size() >= stopAfter) return run;
                top = 0;                       // the app reopens the list at the top
                sequence.newVisit();
                continue;
            }
            boolean seeking = decision.action == RetailerSequence.Action.SCROLL_TO_LAST;
            if (top + rowsOnScreen < ids.size()) {
                top = Math.min(ids.size() - rowsOnScreen, top + rowsPerScroll);
                run.scrolls++;
                continue;
            }
            // The end of the list - the service's own three cases.
            if (seeking || (!swept && day.records.size() < expected)) {
                if (!seeking) swept = true;
                day.last = null;
                top = 0;
                sequence.newVisit();
                run.restarts++;
                continue;
            }
            run.complete = true;
            return run;
        }
        return run;
    }

    // ---------------------------------------------------------------- tests

    private static void typing() {
        section("What may be typed in the box");
        String[][] cases = {
                {"2244", "SMR2244"}, {" 2244 ", "SMR2244"}, {"smr2244", "SMR2244"},
                {"SMR 2244", "SMR2244"}, {"smr-2244", "SMR2244"}, {"7", "SMR7"},
                {"", null}, {"  ", null}, {"SM65", null}, {"abc", null}, {"22a4", null},
                {"1234567", null}, {"RTR12", null}, {"-5", "SMR5"},
        };
        for (String[] one : cases) {
            String got = StartPoint.normalise(one[0]);
            check(one[1] == null ? got == null : one[1].equals(got),
                    "\"" + one[0] + "\" should give " + one[1] + ", gave " + got);
        }
        check(StartPoint.normalise(null) == null, "null gives null");
        check("2244".equals(StartPoint.digits("SMR2244")), "digits of SMR2244");
    }

    private static void freshness() {
        section("The calendar date decides, not a count of hours");
        long hour = 60L * 60 * 1000;
        long minute = 60_000L;
        long saved = 1_800_000_000_000L;
        String day = "2026-11-17";
        String next = "2026-11-18";
        check(StartPoint.fresh(saved, day, saved + 1, day), "a moment later: kept");
        check(StartPoint.fresh(saved, day, saved + 2 * hour, day), "2 hours later, same date: kept");
        check(StartPoint.fresh(saved, day, saved + 23 * hour + 59 * minute, day),
                "saved at 00:00, looked at 23:59 the same date: kept (no 24-hour limit cuts it short)");
        check(!StartPoint.fresh(saved, day, saved + 2 * minute, next),
                "saved at 23:59, looked at 00:01: gone, though only two minutes have passed");
        check(!StartPoint.fresh(saved, day, saved + 2 * hour, next),
                "2 hours later but past midnight: gone (a new day starts from the top)");
        check(!StartPoint.fresh(saved, day, saved + 30 * hour, "2026-11-19"), "two dates later: gone");
        check(!StartPoint.fresh(saved, next, saved, day), "a date that is not today's (clock put back a day): gone");
        check(!StartPoint.fresh(0, day, saved, day), "never saved: gone");
        check(!StartPoint.fresh(saved, null, saved, day), "no day: gone");
        check(!StartPoint.fresh(saved, day, saved, null) && !StartPoint.fresh(saved, day, saved, ""),
                "today unknown: gone");
        check(StartPoint.fresh(saved, day, saved - 5 * hour, day),
                "the clock put back within the same date: kept, the date is what counts");
        check(StartPoint.sameDay(day, day) && !StartPoint.sameDay(day, next) && !StartPoint.sameDay(null, day),
                "the same date is the same day");

        check(StartPoint.dayTurned(day, next), "the history is from the 17th and today is the 18th: wipe all of it");
        check(StartPoint.dayTurned(next, day), "the history is from a later date than today (clock put back): wipe it");
        check(!StartPoint.dayTurned(day, day), "the same date: nothing is wiped");
        check(!StartPoint.dayTurned(null, day), "a history without a date (from an older version) is not wiped whole");
        check(!StartPoint.dayTurned(day, null) && !StartPoint.dayTurned(day, ""), "today unknown: nothing is wiped");

        java.util.TimeZone india = java.util.TimeZone.getTimeZone("Asia/Kolkata");
        java.util.Calendar at = java.util.Calendar.getInstance(india, java.util.Locale.US);
        at.clear();
        at.set(2026, java.util.Calendar.NOVEMBER, 17, 23, 59, 0);
        check(StartPoint.untilMidnight(at.getTimeInMillis(), india) == minute, "at 23:59 midnight is a minute away");
        at.set(2026, java.util.Calendar.NOVEMBER, 17, 0, 0, 0);
        check(StartPoint.untilMidnight(at.getTimeInMillis(), india) == 24 * hour, "at 00:00 the next midnight is a day away");
        at.set(2026, java.util.Calendar.NOVEMBER, 17, 21, 30, 0);
        check(StartPoint.untilMidnight(at.getTimeInMillis(), india) == 150 * minute, "at 21:30 it is two and a half hours away");
        at.set(2026, java.util.Calendar.DECEMBER, 31, 23, 59, 59);
        check(StartPoint.untilMidnight(at.getTimeInMillis(), india) == 1000, "the last second of the year");
        for (long t = 1_790_000_000_000L; t < 1_790_000_000_000L + 3 * 24 * hour; t += 37 * minute) {
            long wait = StartPoint.untilMidnight(t, india);
            java.util.Calendar then = java.util.Calendar.getInstance(india, java.util.Locale.US);
            then.setTimeInMillis(t + wait);
            if (wait < 1000 || wait > 24 * hour || then.get(java.util.Calendar.HOUR_OF_DAY) != 0
                    || then.get(java.util.Calendar.MINUTE) != 0) {
                check(false, "midnight is found from " + t);
            }
        }
        check(true, "midnight is found from every time of day");
        check("just now".equals(StartPoint.age(saved, saved + 20_000)), "age: just now");
        check("25 min ago".equals(StartPoint.age(saved, saved + 25 * 60_000)), "age: minutes");
        check("2 h ago".equals(StartPoint.age(saved, saved + 2 * hour + 5)), "age: hours");
    }

    /** Ten retailers, pause, everything closed, START again: the eleventh is opened first. */
    private static void pauseAndComeBack() {
        section("Pause after ten, close everything, come back");
        for (int size : new int[]{40, 125, 300}) {
            List<String> ids = list(2255, size);
            Day day = new Day();
            Run first = walk(ids, day, null, size, 10);
            check(first.opened.size() == 10 && "SMR2246".equals(day.last), size + ": ten handled, last is SMR2246");
            // The process is gone. What survives is the day's file: the records and the place.
            Day later = new Day();
            later.records.putAll(day.records);
            later.last = day.last;
            Run second = walk(ids, later, null, size, Integer.MAX_VALUE);
            check(!second.opened.isEmpty() && "SMR2245".equals(second.opened.get(0)),
                    size + ": the first retailer opened after coming back is SMR2245");
            boolean reopened = false;
            for (String id : second.opened) if (first.opened.contains(id)) reopened = true;
            check(!reopened, size + ": none of the ten is opened again");
            check(second.opened.size() == size - 10, size + ": every other retailer is opened once");
            check(second.complete && second.restarts == 0, size + ": the run completes without going back to the top");
        }
    }

    private static void typedStartPoint() {
        section("\"2244\" typed in the box after a Reset");
        for (int size : new int[]{40, 125, 300}) {
            List<String> ids = list(2255, size);
            Day day = new Day();
            // START: the box becomes the place in the list.
            day.last = "SMR2244";
            StartPoint.Seeker probe = new StartPoint.Seeker("SMR2244", "self-test");
            Run run = walk(ids, day, probe, size, Integer.MAX_VALUE);
            check(!run.opened.isEmpty() && "SMR2243".equals(run.opened.get(0)),
                    size + ": the first retailer opened is SMR2243");
            boolean aboveOpened = false;
            for (int n = 2255; n >= 2244; n--) if (run.opened.contains("SMR" + n)) aboveOpened = true;
            check(!aboveOpened, size + ": SMR2255 to SMR2244 are never opened");
            int before = 0;
            for (String status : day.records.values()) if ("DONE_BEFORE".equals(status)) before++;
            check(before == 12 && probe.marked() == 12, size + ": those twelve are written as done before (" + before + ")");
            check(run.opened.size() == size - 12, size + ": every retailer below is opened once (" + run.opened.size() + ")");
            check(day.records.size() == size, size + ": the day ends with every retailer accounted for");
            check(run.complete && run.restarts == 0,
                    size + ": no second pass from the top (" + run.restarts + " restarts)");
            check(probe.reached(), size + ": the start point was reached");
        }
        // The rows above are written only when the start point is actually seen.
        List<String> ids = list(2255, 125);
        Day day = new Day();
        day.last = "SMR2200";
        StartPoint.Seeker probe = new StartPoint.Seeker("SMR2200", "self-test");
        probe.sees(ids.subList(0, 7), day);
        probe.sees(ids.subList(5, 12), day);
        check(day.records.isEmpty(), "nothing is written while the start point is still out of sight");
        check(probe.waiting() == 11, "eleven rows are waiting in memory (" + probe.waiting() + ")");
    }

    private static void mistypedStartPoint() {
        section("A number that is not in the list");
        List<String> ids = list(2255, 125);
        Day day = new Day();
        day.last = "SMR9999";
        StartPoint.Seeker probe = new StartPoint.Seeker("SMR9999", "self-test");
        Run run = walk(ids, day, probe, 125, Integer.MAX_VALUE);
        check(probe.lost() && probe.marked() == 0, "the start point is given up; nothing was written as done");
        check(run.restarts == 1, "the bot goes back to the top once (" + run.restarts + ")");
        check(run.opened.size() == 125 && "SMR2255".equals(run.opened.get(0)),
                "every retailer is opened, from SMR2255 (" + run.opened.size() + ")");
        boolean anyBefore = day.records.containsValue("DONE_BEFORE");
        check(!anyBefore, "no retailer is marked done on the strength of a typing mistake");
        check(run.complete, "the run still completes");
    }

    private static void partlyKnown() {
        section("A start point above work already in the ledger");
        List<String> ids = list(2255, 60);
        Day day = new Day();
        for (int n = 2255; n >= 2250; n--) day.records.put("SMR" + n, "SUCCESS");
        day.last = "SMR2240";
        StartPoint.Seeker probe = new StartPoint.Seeker("SMR2240", "self-test");
        Run run = walk(ids, day, probe, 60, Integer.MAX_VALUE);
        check("SMR2239".equals(run.opened.get(0)), "carries on at SMR2239");
        check(probe.marked() == 10, "only the ten rows without a record are written (" + probe.marked() + ")");
        boolean kept = true;
        for (int n = 2255; n >= 2250; n--) if (!"SUCCESS".equals(day.records.get("SMR" + n))) kept = false;
        check(kept, "records that already existed are left exactly as they were");
        check(run.complete && day.records.size() == 60, "all sixty accounted for");
    }

    private static String read(String name) throws Exception {
        File file = new File("../../app/src/main/java/com/vrksignatures/sudhaautobot/" + name);
        return new String(Files.readAllBytes(file.toPath()), "UTF-8");
    }

    private static String method(String source, String signature) {
        int from = source.indexOf(signature);
        if (from < 0) return "";
        int to = source.indexOf("\n    }\n", from);
        return source.substring(from, to < 0 ? source.length() : to);
    }

    private static int count(String text, String what) {
        int n = 0;
        for (int at = text.indexOf(what); at >= 0; at = text.indexOf(what, at + what.length())) n++;
        return n;
    }

    /** The hooks are where they are said to be, and nowhere else. */
    private static void wiring() throws Exception {
        section("How it is wired into the bot");
        String service = read("SudhaAccessibilityService.java");
        Map<String, Integer> calls = new HashMap<>();
        calls.put("startPointAtStart();", 1);
        calls.put("startPointSees(idsOnScreen);", 1);
        calls.put("startPointNote();", 1);
        calls.put("startPointForget();", 1);
        for (Map.Entry<String, Integer> call : calls.entrySet()) {
            check(count(service, call.getKey()) == call.getValue(), call.getKey() + " is called once");
        }
        int scrollCalls = count(service, "RunHistory.noteScroll(") + count(service, "RunHistory.scrollCount(");
        int dayCalls = count(service, "RunHistory.expire(this)");
        check(dayCalls == 2, "and about the change of date in two (" + dayCalls + ")");
        check(count(service, "RunHistory.") - scrollCalls - dayCalls == 4, "the service talks to RunHistory about the start point in four places ("
                + (count(service, "RunHistory.") - scrollCalls) + ")");
        int reset = service.indexOf("private void resetLedger()");
        int forget = service.indexOf("startPointForget();");
        check(reset > 0 && forget > reset && forget - reset < 900, "Reset clears the history");
        int sees = service.indexOf("startPointSees(idsOnScreen);");
        int decide = service.indexOf("sequence.decide(idsOnScreen");
        check(sees > 0 && decide > sees && decide - sees < 400, "the start point looks at the list before the next retailer is chosen");
        String sequence = read("RetailerSequence.java");
        check(!sequence.contains("StartPoint") && !sequence.contains("RunHistory"),
                "RetailerSequence knows nothing about start points");
        String history = read("RunHistory.java");
        check(history.contains("getSharedPreferences(FILE") && history.contains("\"run_history\""),
                "the history is kept in SharedPreferences");
        check(history.contains("StartPoint.fresh(") && count(history, "expire(context)") >= 3,
                "every read expires what is stale");
        String expire = history.substring(history.indexOf("static boolean expire(Context context)"));
        check(expire.contains("StartPoint.dayTurned(held, today)") && expire.contains("prefs.edit().clear()"),
                "when the date has changed the whole history is wiped");
        check(!history.contains("24") && !read("StartPoint.java").contains("KEEP_MS"),
                "no count of hours is left in the history");
        check(history.contains("if (!StartPoint.sameDay(String.valueOf(ledger.sessionDate()), today())) return;"),
                "a ledger left over from yesterday puts nothing into today's history");
        String watch = service.substring(service.indexOf("private void dayWatchBegin()"));
        check(watch.contains("Intent.ACTION_DATE_CHANGED") && watch.contains("Intent.ACTION_TIME_CHANGED")
                && watch.contains("Intent.ACTION_TIMEZONE_CHANGED") && watch.contains("StartPoint.untilMidnight("),
                "the service is told when the date changes, and sets a timer for midnight as well");
        check(method(service, "private void dayTurned()").contains("RunHistory.expire(this)")
                && !method(service, "private void dayTurned()").contains("ledger"),
                "at midnight the history is wiped; the day's ledger is not touched");
        check(!history.contains("aakriti") && !history.contains("LearnedUi"),
                "the history and Aakriti's notebook are separate: clearing one never touches the other");
        String ledger = read("DailyLedger.java");
        int from = ledger.indexOf("public synchronized boolean recordDoneBefore");
        int to = ledger.indexOf("/** Flushes the pre-submit marker", from);
        check(from > 0 && to > from && !ledger.substring(from, to).contains("\"lastHandled\""),
                "writing \"done before\" never moves the place in the list");
    }
}
