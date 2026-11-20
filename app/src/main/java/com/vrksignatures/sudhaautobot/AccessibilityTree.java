package com.vrksignatures.sudhaautobot;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only accessibility tree utilities.
 *
 * <p>Rules followed throughout this class:</p>
 * <ul>
 *   <li>Every traversal is depth-limited so a pathological tree cannot hang the service.</li>
 *   <li>Nodes are never recycled. {@code recycle()} is deprecated since API 33 and
 *       double-recycling a shared node crashes older devices; leaking a few nodes per
 *       pass is cheaper and safer.</li>
 *   <li>Nothing here decides <em>business</em> rules. A row is only returned when its
 *       subtree contains one explicit customer ID and no other customer ID.</li>
 * </ul>
 */
public final class AccessibilityTree {
    private static final int MAX_DEPTH = 60;
    /** Up to six ancestor hops are needed to reach a clickable row container. */
    private static final int MAX_ANCESTOR_HOPS = 6;

    private static final Pattern CUSTOMER_TOTAL = Pattern.compile(
            "(?i)(?:total\\s*(?:customers?|retailers?)?|customers?|retailers?)\\s*[:\\-]?\\s*(\\d{1,5})");
    private static final Pattern BARE_TOTAL = Pattern.compile("(?i)\\b(\\d{1,5})\\s+(?:customers?|retailers?)\\b");
    private static final Pattern CUSTOMER_ID_LABEL = Pattern.compile(
            "(?i)\\bcustomer\\s*id(?:\\s*[:：#]\\s*|\\s+)(.*)$");
    private static final Pattern CUSTOMER_TYPE = Pattern.compile(
            "(?i)\\(\\s*(DIST|RETAILER)\\s*\\)");

    private AccessibilityTree() {
    }

    /**
     * Lets the service supply coordinate gestures for nodes that ignore
     * ACTION_CLICK / ACTION_SCROLL_FORWARD. Implemented by the service with
     * {@link android.accessibilityservice.GestureDescription}; kept as an interface
     * so this class stays trivially testable.
     */
    public interface Gestures {
        /** Single tap in the middle of {@code bounds}. */
        boolean tap(Rect bounds);

        /** Vertical swipe that moves content up (i.e. scrolls the list forward). */
        boolean swipeUp(Rect area);

        /**
         * Drags a finger straight up from ({@code x}, {@code fromY}) to ({@code x},
         * {@code toY}) and lifts it without a flick, so the content moves by the distance
         * dragged and no further. Used by {@link ListScroller}.
         *
         * @return true when the gesture was handed to Android
         */
        default boolean dragUp(int x, int fromY, int toY) {
            return false;
        }
    }

    /** One customer row found on the list screen. */
    public static final class RetailerRow {
        public enum Type { DIST, RETAILER, UNKNOWN }

        public final String id;
        public final String name;
        public final Type type;
        public final AccessibilityNodeInfo node;
        public final Rect bounds;

        RetailerRow(String id, AccessibilityNodeInfo node) {
            this.id = id;
            this.node = node;
            String rowText = allText(node);
            this.type = rowType(rowText);
            this.name = rowName(rowText);
            Rect rect = new Rect();
            node.getBoundsInScreen(rect);
            this.bounds = rect;
        }

        @Override
        public String toString() {
            return id + " " + type + " " + name + "@" + bounds.top;
        }
    }

    // ------------------------------------------------------------------ text

    /** Node label combining {@code text} and {@code contentDescription}. */
    public static String textOf(AccessibilityNodeInfo node) {
        if (node == null) return "";
        CharSequence text = node.getText();
        CharSequence description = node.getContentDescription();
        String value = text == null ? "" : text.toString().trim();
        String hint = description == null ? "" : description.toString().trim();
        if (value.isEmpty()) return hint;
        if (hint.isEmpty() || value.equals(hint)) return value;
        return value + " " + hint;
    }

    /** All labels concatenated with newlines; used for screen classification. */
    public static String allText(AccessibilityNodeInfo root) {
        StringBuilder builder = new StringBuilder(512);
        appendText(root, builder, 0);
        return builder.toString();
    }

    private static void appendText(AccessibilityNodeInfo node, StringBuilder out, int depth) {
        if (node == null || depth > MAX_DEPTH) return;
        String own = textOf(node);
        if (!own.isEmpty()) out.append(own).append('\n');
        int children = node.getChildCount();
        for (int i = 0; i < children; i++) {
            appendText(node.getChild(i), out, depth + 1);
        }
    }

    public static boolean containsText(AccessibilityNodeInfo root, String needle) {
        if (needle == null || needle.isEmpty()) return false;
        return allText(root).toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------- searching

    /** First node whose label equals {@code label} (case/space insensitive). */
    public static AccessibilityNodeInfo findExact(AccessibilityNodeInfo root, String label) {
        if (root == null) return null;
        return findExact(root, label, 0);
    }

    private static AccessibilityNodeInfo findExact(AccessibilityNodeInfo node, String label, int depth) {
        if (node == null || depth > MAX_DEPTH) return null;
        if (equalsLabel(textOf(node), label)) return node;
        int children = node.getChildCount();
        for (int i = 0; i < children; i++) {
            AccessibilityNodeInfo found = findExact(node.getChild(i), label, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    /** First node whose label contains {@code needle} (case insensitive). */
    public static AccessibilityNodeInfo findContaining(AccessibilityNodeInfo root, String needle) {
        if (root == null) return null;
        return findContaining(root, needle, 0);
    }

    private static AccessibilityNodeInfo findContaining(AccessibilityNodeInfo node, String needle, int depth) {
        if (node == null || depth > MAX_DEPTH) return null;
        String label = textOf(node).toLowerCase(Locale.ROOT);
        if (!label.isEmpty() && label.contains(needle.toLowerCase(Locale.ROOT))) return node;
        int children = node.getChildCount();
        for (int i = 0; i < children; i++) {
            AccessibilityNodeInfo found = findContaining(node.getChild(i), needle, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    /** All nodes whose label equals {@code label}. */
    public static List<AccessibilityNodeInfo> findAllExact(AccessibilityNodeInfo root, String label) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        collectExact(root, label, out, 0);
        return out;
    }

    private static void collectExact(AccessibilityNodeInfo node, String label,
                                     List<AccessibilityNodeInfo> out, int depth) {
        if (node == null || depth > MAX_DEPTH) return;
        if (equalsLabel(textOf(node), label)) out.add(node);
        int children = node.getChildCount();
        for (int i = 0; i < children; i++) collectExact(node.getChild(i), label, out, depth + 1);
    }

    /** First clickable node anywhere in the subtree whose label equals {@code label}. */
    public static AccessibilityNodeInfo findClickableLabel(AccessibilityNodeInfo root, String label) {
        AccessibilityNodeInfo node = findExact(root, label);
        if (node == null) return null;
        AccessibilityNodeInfo clickable = clickableAncestor(node, MAX_ANCESTOR_HOPS);
        return clickable != null ? clickable : node;
    }

    /** Closest ancestor (or the node itself) that reports itself clickable. */
    public static AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo node, int maxHops) {
        AccessibilityNodeInfo current = node;
        for (int hop = 0; current != null && hop <= maxHops; hop++) {
            if (current.isClickable()) return current;
            current = current.getParent();
        }
        return null;
    }

    public static AccessibilityNodeInfo findByViewId(AccessibilityNodeInfo root, String viewId) {
        if (root == null || viewId == null) return null;
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(viewId);
        return found == null || found.isEmpty() ? null : found.get(0);
    }

    // --------------------------------------------------------------- clicking

    /**
     * Clicks {@code node}: its own ACTION_CLICK, then the nearest clickable ancestor,
     * then a coordinate tap on the node's bounds as a last resort. Returns true when
     * some action was successfully dispatched.
     */
    public static boolean click(AccessibilityNodeInfo node, Gestures fallback) {
        if (node == null) return false;
        if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        AccessibilityNodeInfo ancestor = clickableAncestor(node, MAX_ANCESTOR_HOPS);
        if (ancestor != null && ancestor.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        // Some rows need focus first (TalkBack-style activation).
        if ((node.isFocusable() || node.isVisibleToUser())
                && node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        }
        return tapBounds(node, fallback);
    }

    /** Coordinate tap in the middle of the node's bounds. */
    public static boolean tapBounds(AccessibilityNodeInfo node, Gestures fallback) {
        if (node == null || fallback == null) return false;
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        return tapRect(fallback, rect);
    }

    public static boolean tapRect(Gestures dispatcher, Rect rect) {
        if (dispatcher == null || rect == null || rect.width() <= 0 || rect.height() <= 0) return false;
        return dispatcher.tap(new Rect(rect));
    }

    // ------------------------------------------------------------- customer rows

    /** Distinct exact customer IDs visible on screen, in visual (top-down) order. */
    public static List<String> visibleRetailerIds(AccessibilityNodeInfo root) {
        List<RetailerRow> rows = retailerRows(root);
        List<String> ids = new ArrayList<>(rows.size());
        for (RetailerRow row : rows) ids.add(row.id);
        return ids;
    }

    /**
     * Customer rows on screen, de-duplicated by exact ID and sorted by visual position.
     * IDs are read only from an explicit {@code Customer Id:} field. Type and name are
     * retained because ID shape alone does not establish retailer eligibility.
     */
    public static List<RetailerRow> retailerRows(AccessibilityNodeInfo root) {
        if (root == null) return Collections.emptyList();
        Map<String, AccessibilityNodeInfo> firstMatch = new LinkedHashMap<>();
        collectIdNodes(root, firstMatch, 0);

        List<RetailerRow> rows = new ArrayList<>(firstMatch.size());
        for (Map.Entry<String, AccessibilityNodeInfo> entry : firstMatch.entrySet()) {
            AccessibilityNodeInfo rowNode = ascendToRow(entry.getValue(), entry.getKey());
            if (rowNode != null) rows.add(new RetailerRow(entry.getKey(), rowNode));
        }
        Collections.sort(rows, (a, b) -> {
            int vertical = Integer.compare(a.bounds.top, b.bounds.top);
            return vertical != 0 ? vertical : Integer.compare(a.bounds.left, b.bounds.left);
        });
        return rows;
    }

    private static void collectIdNodes(AccessibilityNodeInfo node,
                                       Map<String, AccessibilityNodeInfo> out, int depth) {
        if (node == null || depth > MAX_DEPTH) return;
        String id = customerIdFromLabel(textOf(node));
        if (id != null && !out.containsKey(id)) {
            out.put(id, node);
        }
        int children = node.getChildCount();
        for (int i = 0; i < children; i++) collectIdNodes(node.getChild(i), out, depth + 1);
    }

    /**
     * Climbs from the ID field to its customer row: the highest ancestor (within a few
     * hops) whose subtree contains exactly this customer ID and no other customer ID.
     * Prefers a clickable ancestor when one exists.
     */
    private static AccessibilityNodeInfo ascendToRow(AccessibilityNodeInfo textNode, String id) {
        AccessibilityNodeInfo nearestClickable = null;
        AccessibilityNodeInfo current = textNode;
        for (int hop = 0; current != null && hop <= MAX_ANCESTOR_HOPS; hop++) {
            if (!subtreeHasOnly(current, id)) break;
            if (current.isClickable()) {
                if (nearestClickable == null) nearestClickable = current;
                String text = allText(current);
                if (rowType(text) != RetailerRow.Type.UNKNOWN && !rowName(text).isEmpty()) {
                    return current;
                }
            }
            current = current.getParent();
        }
        // If row context cannot be reached, keep the smallest safe target. Its missing
        // type/name will make the sequence stop instead of clicking based on ID shape.
        return nearestClickable != null ? nearestClickable : textNode;
    }

    /** True when every customer ID in the subtree equals {@code id} and at least one exists. */
    private static boolean subtreeHasOnly(AccessibilityNodeInfo node, String id) {
        Set<String> ids = new LinkedHashSet<>();
        collectIds(node, ids, 0);
        return ids.size() == 1 && ids.contains(id);
    }

    private static void collectIds(AccessibilityNodeInfo node, Set<String> out, int depth) {
        if (node == null || depth > MAX_DEPTH) return;
        String id = customerIdFromLabel(textOf(node));
        if (id != null) out.add(id);
        int children = node.getChildCount();
        for (int i = 0; i < children; i++) collectIds(node.getChild(i), out, depth + 1);
    }

    /** Row container for a specific id, or null when it is not on screen. */
    public static AccessibilityNodeInfo findRetailerRow(AccessibilityNodeInfo root, String id) {
        if (root == null || !RetailerPolicy.isRetailerId(id)) return null;
        for (RetailerRow row : retailerRows(root)) {
            if (row.id.equals(id)) return row.node;
        }
        return null;
    }

    /** How many distinct retailer rows are visible right now. */
    public static int visibleRetailerCount(AccessibilityNodeInfo root) {
        return visibleRetailerIds(root).size();
    }

    /** True only when the exact ID appears as a standalone value somewhere on screen. */
    public static boolean containsExactCustomerId(AccessibilityNodeInfo root, String expected) {
        if (root == null || !RetailerPolicy.isRetailerId(expected)) return false;
        String text = allText(root);
        int at = 0;
        while ((at = text.indexOf(expected, at)) >= 0) {
            int end = at + expected.length();
            boolean leftBoundary = at == 0 || !isIdWordChar(text.charAt(at - 1));
            boolean rightBoundary = end >= text.length() || !isIdWordChar(text.charAt(end));
            if (leftBoundary && rightBoundary) return true;
            at++;
        }
        return false;
    }

    private static boolean isIdWordChar(char value) {
        return Character.isLetterOrDigit(value) || value == '_';
    }

    private static String customerIdFromLabel(String label) {
        if (label == null) return null;
        Matcher matcher = CUSTOMER_ID_LABEL.matcher(label);
        if (!matcher.find()) return null;
        String value = matcher.group(1);
        int lineEnd = value.indexOf('\n');
        if (lineEnd >= 0) value = value.substring(0, lineEnd);
        int duplicate = value.toLowerCase(Locale.ROOT).indexOf(" customer id:");
        if (duplicate >= 0) value = value.substring(0, duplicate);
        Matcher followingField = Pattern.compile(
                "(?i)\\s+(?:customer\\s+type|type|name|phone|mobile|delivery\\s+point)\\s*[:：]")
                .matcher(value);
        if (followingField.find()) value = value.substring(0, followingField.start());
        String exact = value.trim();
        return RetailerPolicy.isRetailerId(exact) ? exact : null;
    }

    private static RetailerRow.Type rowType(String rowText) {
        Matcher matcher = CUSTOMER_TYPE.matcher(rowText == null ? "" : rowText);
        boolean retailer = false;
        while (matcher.find()) {
            if ("DIST".equalsIgnoreCase(matcher.group(1))) return RetailerRow.Type.DIST;
            retailer = true;
        }
        return retailer ? RetailerRow.Type.RETAILER : RetailerRow.Type.UNKNOWN;
    }

    private static String rowName(String rowText) {
        if (rowText == null || rowText.trim().isEmpty()) return "";
        String[] lines = rowText.split("[\\r\\n]+");
        String preceding = "";
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            Matcher type = CUSTOMER_TYPE.matcher(line);
            if (type.find()) {
                String prefix = cleanName(line.substring(0, type.start()));
                if (!prefix.isEmpty()) return prefix;
                if (!preceding.isEmpty()) return preceding;
            }
            String labelled = labelledName(line);
            if (!labelled.isEmpty()) return labelled;
            if (!isRowMetadata(line) && !CUSTOMER_ID_LABEL.matcher(line).find()) {
                preceding = cleanName(line);
            }
        }
        return preceding;
    }

    private static String labelledName(String line) {
        Matcher matcher = Pattern.compile("(?i)^(?:customer\\s+)?(?:delivery\\s+point\\s+)?name\\s*[:：-]\\s*(.+)$")
                .matcher(line);
        return matcher.find() ? cleanName(matcher.group(1)) : "";
    }

    private static String cleanName(String value) {
        if (value == null) return "";
        String cleaned = value.replaceAll("(?i)^(?:(?:delivery point|customer type)\\s*[:：-]?\\s*|(?:customer\\s+)?name\\s*[:：-]\\s*)", "")
                .replaceAll("\\s+", " ").trim();
        return isRowMetadata(cleaned) ? "" : cleaned;
    }

    private static boolean isRowMetadata(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.startsWith("customer id") || lower.startsWith("phone")
                || lower.startsWith("mobile") || lower.startsWith("distributor name")
                || lower.startsWith("delivery point name") || lower.startsWith("type:")
                || lower.startsWith("show retailer") || lower.startsWith("all distributor")
                || lower.startsWith("total customer") || lower.equals("customers")
                || lower.startsWith("search") || lower.startsWith("filter");
    }

    /**
     * Reads the expected customer total from header text such as
     * "Total Customers: 125". Returns -1 when the screen does not state it.
     */
    public static int customerTotalFromScreen(AccessibilityNodeInfo root) {
        String text = allText(root);
        Matcher labelled = CUSTOMER_TOTAL.matcher(text);
        while (labelled.find()) {
            int value = safeInt(labelled.group(1));
            if (value > 0 && value <= 100000) return value;
        }
        Matcher trailing = BARE_TOTAL.matcher(text);
        if (trailing.find()) {
            int value = safeInt(trailing.group(1));
            if (value > 0 && value <= 100000) return value;
        }
        return -1;
    }

    private static int safeInt(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (Exception ignored) {
            return -1;
        }
    }

    // --------------------------------------------------------------- scrolling

    /**
     * Scrolls the first scrollable container forward, falling back to a swipe.
     *
     * <p>No longer used for the retailer list: it takes the first scrollable node anywhere
     * on the screen, does nothing at all when the app exposes none, and reports success
     * without checking that anything moved. See {@link ListScroller}.</p>
     */
    public static boolean scrollForward(AccessibilityNodeInfo root, Gestures gestures) {
        AccessibilityNodeInfo scrollable = findScrollable(root, 0);
        if (scrollable != null && scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            return true;
        }
        // RecyclerView sometimes ignores ACTION_SCROLL_FORWARD, so swipe instead.
        if (gestures == null || scrollable == null) return false;
        Rect bounds = new Rect();
        scrollable.getBoundsInScreen(bounds);
        if (bounds.height() <= 0) return false;
        return gestures.swipeUp(bounds);
    }

    public static AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > MAX_DEPTH) return null;
        if (node.isScrollable() && node.isVisibleToUser()) return node;
        int children = node.getChildCount();
        for (int i = 0; i < children; i++) {
            AccessibilityNodeInfo found = findScrollable(node.getChild(i), depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    // ------------------------------------------------------------------- misc

    // ------------------------------------------------------------ add / FAB button

    /**
     * Candidate for the floating "+" button.
     *
     * <p>A target is kept even when it does not claim to be clickable or visible: custom
     * UI toolkits frequently expose a FAB as an unlabelled container whose click handler
     * lives in a parent, which is exactly why {@code performAction(ACTION_CLICK)} on it
     * does nothing. The caller is expected to tap the centre of {@link #bounds} with a
     * gesture instead.</p>
     */
    public static final class AddButtonTarget {
        /** Best node found, or null when detection was purely geometric. */
        public final AccessibilityNodeInfo node;
        /** Where to tap, in screen coordinates. */
        public final Rect bounds;
        /** Higher is better; see {@link #findAddButton}. */
        public final int score;
        /** Short human readable reason, for the floating panel and logcat. */
        public final String reason;
        /**
         * True when the node carries no identity hint at all and was accepted purely
         * because of its corner position and FAB-like shape. Still far better than a
         * blind coordinate guess, but the ladder only uses it after stronger evidence
         * has been tried.
         */
        public final boolean weak;

        AddButtonTarget(AccessibilityNodeInfo node, Rect bounds, int score, String reason,
                        boolean weak) {
            this.node = node;
            this.bounds = bounds;
            this.score = score;
            this.reason = reason;
            this.weak = weak;
        }

        /** True when nothing was found and the caller relies on fixed coordinates. */
        public boolean isGeometric() {
            return node == null;
        }

        @Override
        public String toString() {
            return reason + " score=" + score + " @"
                    + bounds.exactCenterX() + "," + bounds.exactCenterY();
        }
    }

    /**
     * Score given to a shape/position-only candidate. Sits below {@link #STRONG_SCORE}
     * so any node with real evidence always wins, and above the blind-coordinate stage.
     */
    private static final int WEAK_SCORE = 40;
    /** Minimum score for a candidate that carries at least one identity hint. */
    private static final int STRONG_SCORE = 50;

    /**
     * Square side (dp) of the FAB. A Material FAB is 56dp, which matches the measured
     * diameter on the target device (161px on a 1080px-wide / 440dpi screen).
     */
    private static final int ASSUMED_FAB_DP = 56;
    /**
     * Centre of the history-screen FAB, as a fraction of screen width / height.
     *
     * <p>Measured from a 1080x1920 capture of this app: the orange circular "+" sits at
     * roughly (937px, 1732px), i.e. 8.6% in from the right edge and above the
     * navigation bar — about 87% / 90% of the usable screen. These replaced earlier
     * guesses of 88% / 88%, which landed above the button on 19.5:9 devices.</p>
     */
    private static final float ASSUMED_FAB_X = 0.867f;
    private static final float ASSUMED_FAB_Y = 0.902f;

    /**
     * Locates the floating "+" / Add FAB on the order-history screen.
     *
     * <p>Scoring, strongest evidence first:</p>
     * <ul>
     *   <li>+100 label contains "+"</li>
     *   <li>+80 view id or class mentions {@code fab} / {@code floatingactionbutton}</li>
     *   <li>+40 label mentions add / new order</li>
     *   <li>+30 sits in the right-hand third of the screen</li>
     *   <li>+20 sits in the bottom half</li>
     *   <li>+15 its bounds are roughly square and small (the shape of a FAB)</li>
     *   <li>+10 nominally clickable</li>
     * </ul>
     *
     * <p>Clickability is deliberately only worth 10 points: this app's FAB reports
     * itself as a plain container. Layout position is worth 50 combined, which is what
     * makes detection work even when the node carries no text, id or class hint.</p>
     *
     * @param screenWidth  display width in pixels (used for the geometry score)
     * @param screenHeight display height in pixels
     * @return the best candidate, or null when nothing plausible was on screen
     */
    public static AddButtonTarget findAddButton(AccessibilityNodeInfo root,
                                                int screenWidth, int screenHeight) {
        if (root == null) return null;
        List<AddButtonTarget> candidates = new ArrayList<>();
        collectAddCandidates(root, screenWidth, screenHeight, candidates, 0);
        if (candidates.isEmpty()) return null;

        AddButtonTarget best = candidates.get(0);
        for (AddButtonTarget candidate : candidates) {
            if (candidate.score > best.score) best = candidate;
        }
        // Strong evidence is preferred; a weak (shape + corner) candidate is still
        // returned so the caller can use it before falling back to blind coordinates.
        if (best.score >= STRONG_SCORE) return best;
        return best.score >= WEAK_SCORE ? best : null;
    }

    private static void collectAddCandidates(AccessibilityNodeInfo node, int screenWidth,
                                             int screenHeight, List<AddButtonTarget> out,
                                             int depth) {
        if (node == null || depth > MAX_DEPTH) return;

        int score = scoreAddCandidate(node, screenWidth, screenHeight);
        if (score > 0) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (bounds.width() > 0 && bounds.height() > 0) {
                out.add(new AddButtonTarget(node, bounds, score, describeAdd(node),
                        score == WEAK_SCORE));
            }
        }

        int children = node.getChildCount();
        for (int i = 0; i < children; i++) {
            collectAddCandidates(node.getChild(i), screenWidth, screenHeight, out, depth + 1);
        }
    }

    private static int scoreAddCandidate(AccessibilityNodeInfo node, int screenWidth,
                                         int screenHeight) {
        String label = textOf(node).toLowerCase(Locale.ROOT);
        CharSequence viewIdValue = node.getViewIdResourceName();
        CharSequence classNameValue = node.getClassName();
        String id = viewIdValue == null ? "" : viewIdValue.toString().toLowerCase(Locale.ROOT);
        String cls = classNameValue == null ? "" : classNameValue.toString().toLowerCase(Locale.ROOT);

        // Identity evidence. At least one of these must be present: position alone is
        // not enough, or a normal list row in the bottom-right corner would qualify.
        int identity = 0;
        boolean labelHasPlus = label.contains("+");
        boolean classIsFab = id.contains("fab") || id.contains("floating")
                || cls.contains("floatingactionbutton");
        boolean labelIsAdd = label.contains("add") || label.contains("new order");
        if (labelHasPlus) identity += 100;
        if (classIsFab) identity += 80;
        if (labelIsAdd) identity += 40;
        int score = identity;

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.width() <= 0 || bounds.height() <= 0) return 0;

        if (screenWidth > 0 && bounds.exactCenterX() > screenWidth * 0.66f) score += 30;
        if (screenHeight > 0 && bounds.exactCenterY() > screenHeight * 0.45f) score += 20;

        // A FAB is a small roughly square blob. Without this shape bonus the candidate is
        // almost certainly a layout container, so it is rejected.
        float ratio = bounds.width() / (float) Math.max(1, bounds.height());
        boolean fabShaped = ratio > 0.6f && ratio < 1.6f && bounds.width() < screenWidth * 0.35f;
        if (fabShaped) score += 15;
        if (node.isClickable()) score += 10;

        // Require the blob shape unless the node carries an unambiguous "+" or fab id.
        if (!fabShaped && !labelHasPlus && !classIsFab) return 0;

        if (identity == 0) {
            // No text, no id, no class hint. Accept only a small square blob sitting in
            // the bottom-right corner — never a wide list row or a header control.
            boolean corner = screenWidth > 0 && screenHeight > 0
                    && bounds.exactCenterX() > screenWidth * 0.70f
                    && bounds.exactCenterY() > screenHeight * 0.66f;
            if (!corner || !fabShaped) return 0;
            if (screenWidth > 0 && bounds.width() > screenWidth * 0.25f) return 0;
            if (!node.isVisibleToUser()) return 0;
            return WEAK_SCORE;
        }

        return score;
    }

    private static String describeAdd(AccessibilityNodeInfo node) {
        String label = textOf(node);
        CharSequence viewId = node.getViewIdResourceName();
        CharSequence className = node.getClassName();
        StringBuilder out = new StringBuilder();
        out.append(label.isEmpty() ? "(no label)" : "\"" + label + "\"");
        if (viewId != null) out.append(" id=").append(viewId);
        if (className != null) out.append(" class=").append(className);
        return out.toString();
    }

    /**
     * Geometry-only fallback: the centre of a FAB-sized square in the bottom-right
     * corner. Uses the real screen size when known, otherwise assumes a 1080x2340
     * phone. Only reached when {@link #findAddButton} found nothing.
     */
    public static Rect assumedFabBounds(int screenWidth, int screenHeight, int densityDpi) {
        int width = screenWidth > 0 ? screenWidth : 1080;
        int height = screenHeight > 0 ? screenHeight : 2340;
        int side = Math.round(ASSUMED_FAB_DP * (densityDpi > 0 ? densityDpi / 160f : 3f));
        if (side < 48) side = 48;
        int centerX = Math.round(width * ASSUMED_FAB_X);
        int centerY = Math.round(height * ASSUMED_FAB_Y);
        int left = Math.max(0, centerX - side / 2);
        int top = Math.max(0, centerY - side / 2);
        return new Rect(left, top, Math.min(width, left + side), Math.min(height, top + side));
    }

    /** Same label comparison the tree search uses; shared so callers stay consistent. */
    public static boolean equalsLabel(String actual, String expected) {
        if (actual == null || expected == null) return false;
        return normalize(actual).equals(normalize(expected));
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
