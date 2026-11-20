import android.view.accessibility.AccessibilityNodeInfo;

import com.vrksignatures.sudhaautobot.AccessibilityTree;
import com.vrksignatures.sudhaautobot.ListScroller;
import com.vrksignatures.sudhaautobot.RetailerSequence;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The bot's loop over the customer list, as SudhaAccessibilityService.onRetailerList() runs
 * it: look at the rows, ask {@link RetailerSequence} which retailer is next, open it, and
 * when there is none on this screen ask {@link ListScroller} for the next screenful.
 *
 * <p>The service itself needs Android to run, so this is a hand-written copy of its order
 * of decisions. The two classes that make the decisions are the app's own.</p>
 */
final class ListRunner {
    final RetailerListModel model;
    /** The app reopens the list at the top after every retailer (as seen on the device). */
    final boolean backToTop;
    final ListScroller scroller = new ListScroller();
    final RetailerSequence sequence = new RetailerSequence();

    /** The day's ledger: every retailer with an outcome. */
    final Set<String> done = new LinkedHashSet<>();
    /** The ledger's "handled last". */
    String last;

    /** Retailers the bot opened, in the order it opened them. */
    final List<String> opened = new ArrayList<>();
    ListScroller.Step lastStep;
    int passes, scrollSteps, blockedPasses, restarts;
    /** Scroll steps spent before each retailer was opened. */
    int scrollsSinceOpen, mostScrollsForOne;
    String note = "";
    boolean finished;

    ListRunner(RetailerListModel model, boolean backToTop) {
        this.model = model;
        this.backToTop = backToTop;
    }

    /** The "expected retailers" setting; by default what the list really holds. */
    int expectedCount = -1;
    boolean swept;
    int sweeps;

    private int expected() {
        if (expectedCount >= 0) return expectedCount;
        int count = 0;
        for (String id : model.ids) if (id.startsWith("SMR")) count++;
        return count;
    }

    /** Pressing START: counters are cleared, the ledger is kept. */
    void start() {
        scroller.reset();
        sequence.newVisit();
        swept = false;
        finished = false;
    }

    void run(int maxPasses) {
        while (passes++ < maxPasses) {
            AccessibilityNodeInfo root = model.build();
            List<AccessibilityTree.RetailerRow> rows = AccessibilityTree.retailerRows(root);
            scroller.observe(rows);

            List<String> ids = new ArrayList<>();
            for (AccessibilityTree.RetailerRow row : rows) ids.add(row.id);
            if (!scroller.scrolledDown()) sequence.newVisit();
            RetailerSequence.Decision decision = sequence.decide(ids, last, done::contains);
            if (decision.action == RetailerSequence.Action.OPEN) {
                // ... details, orders, copy, pay (or skip) happen here; the outcome is
                // written to the ledger, which also makes this retailer "handled last".
                done.add(decision.id);
                last = decision.id;
                opened.add(decision.id);
                mostScrollsForOne = Math.max(mostScrollsForOne, scrollsSinceOpen);
                scrollsSinceOpen = 0;
                scroller.leftList();
                sequence.newVisit();
                if (backToTop) model.scroll = 0;
                continue;
            }
            boolean seekingLast = decision.action == RetailerSequence.Action.SCROLL_TO_LAST;

            lastStep = scroller.advance(rows, RetailerListModel.SCREEN_W, RetailerListModel.SCREEN_H,
                    model.panel, model);
            note = scroller.note();
            if (lastStep == ListScroller.Step.END_OF_LIST) {
                if (seekingLast) {
                    // Bottom reached, "handled last" never seen: forget the place, go back
                    // to the first page and come down the list again from the top.
                    last = null;
                    sequence.newVisit();
                    scroller.leftList();
                    model.scroll = 0;
                    restarts++;
                    continue;
                }
                if (!swept && done.size() < expected()) {
                    // Fewer handled than the list should hold: once more from the top.
                    swept = true;
                    sweeps++;
                    last = null;
                    sequence.newVisit();
                    scroller.leftList();
                    model.scroll = 0;
                    continue;
                }
                last = null;
                finished = true;
                return;
            }
            if (lastStep == ListScroller.Step.BLOCKED) blockedPasses++;
            if (lastStep == ListScroller.Step.SCROLLED_BY_ACTION
                    || lastStep == ListScroller.Step.SCROLLED_BY_DRAG) {
                scrollSteps++;
                scrollsSinceOpen++;
            }
        }
    }
}
