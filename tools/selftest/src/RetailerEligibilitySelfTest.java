import com.vrksignatures.sudhaautobot.AccessibilityTree;
import com.vrksignatures.sudhaautobot.RetailerSequence;

import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Self-test for customer eligibility (spec section 3): visible type and name decide,
 * not the ID shape; (DIST) rows are skipped along with an immediately following
 * same-name (Retailer) shadow; a later same-name retailer stays eligible; and a fresh
 * visit to the list starts at its first row, which has nothing above it.
 *
 * <p>Runs {@link RetailerSequence} - the app's own class - on a plain JVM against
 * hand-built Customers screens.
 */
public final class RetailerEligibilitySelfTest {
    private static int checks;
    private static int failures;

    public static void main(String[] args) {
        section("A. Example A - distributor and its immediate same-name shadow");
        exampleADistributorAndShadow();
        section("B. Example B - same retailer name later in the list is still eligible");
        exampleBLaterSameNameIsEligible();
        section("C. Example C - a distributor carrying an SMR-style ID is still skipped");
        exampleCDistributorWithSmrId();
        section("D. Fresh start - nothing precedes the first row on screen");
        freshStartOpensTheFirstRetailerRow();
        section("E. Any nonblank exact ID is accepted");
        anyNonblankExactIdIsAccepted();
        section("F. Uncertainty stops the run instead of guessing");
        unreadableCustomerTypeStops();
        unreadableCustomerNameStops();
        duplicateCustomerIdStops();
        handledRowsAreSteppedOver();
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

    /**
     * One row as the accessibility tree exposes it: a "(Name) (TYPE)" label plus an
     * explicit {@code Customer Id: <id>} field, at the given screen position.
     */
    private static AccessibilityNodeInfo row(int top, String typeAndName, String id) {
        AccessibilityNodeInfo rowNode = new AccessibilityNodeInfo();
        rowNode.bounds.set(0, top, 700, top + 60);
        rowNode.clickable = true;   // real list rows are tappable targets
        AccessibilityNodeInfo desc = new AccessibilityNodeInfo();
        desc.text = typeAndName;
        rowNode.add(desc);
        AccessibilityNodeInfo idField = new AccessibilityNodeInfo();
        idField.text = "Customer Id: " + id;
        rowNode.add(idField);
        return rowNode;
    }

    /** A whole Customers screen: header plus the given rows, in top-down order. */
    private static List<AccessibilityTree.RetailerRow> rows(AccessibilityNodeInfo... rowNodes) {
        AccessibilityNodeInfo screen = new AccessibilityNodeInfo();
        screen.bounds.set(0, 0, 720, 1600);
        AccessibilityNodeInfo header = new AccessibilityNodeInfo();
        header.text = "Total Customers: " + rowNodes.length;
        screen.add(header);
        for (AccessibilityNodeInfo r : rowNodes) screen.add(r);
        return AccessibilityTree.retailerRows(screen);
    }

    /** One decision on a fresh visit to the list. */
    private static RetailerSequence.Decision decide(List<AccessibilityTree.RetailerRow> rowNodes,
                                                    String lastHandled, String... alreadyHandled) {
        Set<String> done = new LinkedHashSet<>(Arrays.asList(alreadyHandled));
        return new RetailerSequence().decide(rowNodes, lastHandled, done::contains);
    }

    private static void assertOpened(RetailerSequence.Decision d, String id, int index,
                                     String what) {
        check(d.action == RetailerSequence.Action.OPEN
                && id.equals(d.id) && d.index == index, what + " (got " + d.action + " "
                + d.id + "@" + d.index + ", detail: '" + d.detail + "')");
    }

    private static void assertScrolls(RetailerSequence.Decision d, String what) {
        check(d.action == RetailerSequence.Action.SCROLL_FOR_MORE && d.id == null, what);
    }

    private static void assertStops(RetailerSequence.Decision d, String what) {
        check(d.action == RetailerSequence.Action.STOP_UNCERTAIN
                && d.detail != null && !d.detail.isEmpty(),
                what + " (got " + d.action + ", detail: '" + d.detail + "')");
    }

    // ---------------------------------------------------------------- examples

    private static void exampleADistributorAndShadow() {
        List<AccessibilityTree.RetailerRow> list = rows(
                row(100, "North Dairy (DIST)", "SMR100"),
                row(200, "North Dairy (Retailer)", "778812"),
                row(300, "Village Shop (Retailer)", "SMR250"));
        RetailerSequence.Decision d = decide(list, null);
        assertOpened(d, "SMR250", 2,
                "Example A: DIST row skipped, its same-name shadow skipped, Village Shop opened");
        assertScrolls(decide(list, "SMR250", "SMR250"),
                "Example A: after Village Shop is handled, nothing else is left on this screen");
    }

    private static void exampleBLaterSameNameIsEligible() {
        List<AccessibilityTree.RetailerRow> list = rows(
                row(100, "North Dairy (DIST)", "SMR100"),
                row(200, "North Dairy (Retailer)", "778812"),
                row(300, "Village Shop (Retailer)", "SMR250"),
                row(400, "North Dairy (Retailer)", "SMR900"));
        RetailerSequence.Decision fresh = decide(list, null);
        assertOpened(fresh, "SMR250", 2,
                "Example B: fresh start opens Village Shop (row 3)");
        RetailerSequence.Decision after = decide(list, "SMR250");
        assertOpened(after, "SMR900", 3,
                "Example B: North Dairy at row 4 is eligible - same name, but not immediately after the DIST");
    }

    private static void exampleCDistributorWithSmrId() {
        List<AccessibilityTree.RetailerRow> list = rows(
                row(100, "Central Route (DIST)", "SMR555"),
                row(200, "Another Shop (Retailer)", "88421"));
        RetailerSequence.Decision d = decide(list, null);
        assertOpened(d, "88421", 1,
                "Example C: DIST skipped even though its ID looks like a customer ID");
    }

    // ---------------------------------------------------------------- fresh start

    private static void freshStartOpensTheFirstRetailerRow() {
        List<AccessibilityTree.RetailerRow> list = rows(row(100, "Alpha (Retailer)", "ABC-9"));
        RetailerSequence.Decision d = decide(list, null);
        assertOpened(d, "ABC-9", 0,
                "Fresh start: the first row has nothing above it, so it is not a shadow");
        list = rows(row(100, "First (DIST)", "SMR1"), row(200, "Second (Retailer)", "ABC-17"));
        d = decide(list, null);
        assertOpened(d, "ABC-17", 1,
                "Fresh start: the distributor row is stepped over, the retailer below it opened");
    }

    // ---------------------------------------------------------------- ID shapes

    private static void anyNonblankExactIdIsAccepted() {
        assertOpened(decide(rows(row(100, "One (Retailer)", "552731")), null), "552731", 0,
                "Numeric ID 552731 is accepted as-is");
        assertOpened(decide(rows(row(100, "Two (Retailer)", "R 204")), null), "R 204", 0,
                "ID with an internal space, R 204, is matched exactly");
        assertOpened(decide(rows(row(100, "Three (Retailer)", "ABC-17")), null), "ABC-17", 0,
                "ID ABC-17 is matched exactly");
    }

    // ---------------------------------------------------------------- stops

    private static void unreadableCustomerTypeStops() {
        List<AccessibilityTree.RetailerRow> list = rows(row(100, "Mystery Shop (Member)", "123"));
        assertStops(decide(list, null),
                "A row whose type cannot be read stops the run instead of guessing");
    }

    private static void unreadableCustomerNameStops() {
        List<AccessibilityTree.RetailerRow> list = rows(row(100, "(Retailer)", "X"));
        assertStops(decide(list, null),
                "A retailer row with no readable name stops the run");
    }

    private static void duplicateCustomerIdStops() {
        List<AccessibilityTree.RetailerRow> list = rows(
                row(100, "First (Retailer)", "SMR1"),
                row(200, "Second (Retailer)", "SMR1"));
        assertStops(decide(list, null),
                "The same exact ID on two visible rows stops the run");
    }

    private static void handledRowsAreSteppedOver() {
        List<AccessibilityTree.RetailerRow> list = rows(
                row(100, "North Dairy (DIST)", "SMR100"),
                row(200, "North Dairy (Retailer)", "778812"),
                row(300, "Village Shop (Retailer)", "SMR250"));
        assertScrolls(decide(list, "SMR250", "SMR250"),
                "An already-handled eligible row is skipped, nothing opened on this screen");
    }
}
