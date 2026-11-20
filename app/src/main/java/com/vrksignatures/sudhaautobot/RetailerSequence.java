package com.vrksignatures.sudhaautobot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Selects the next customer in the order actually displayed by the list.
 * IDs are exact anchors only; their shape and numeric value never imply ordering.
 *
 * <p>Eligibility comes from the visible row, never from the ID: a (DIST) row is always
 * skipped, and a (Retailer) row is skipped only when it immediately follows a (DIST)
 * row with the same name. A fresh visit to the list starts at its first row, which has
 * nothing above it and therefore cannot be a same-name shadow.</p>
 */
public final class RetailerSequence {
    public enum Action {
        OPEN,
        SCROLL_TO_LAST,
        SCROLL_FOR_MORE,
        STOP_UNCERTAIN
    }

    public interface Handled {
        boolean is(String id);
    }

    public static final class Decision {
        public final Action action;
        public final String id;
        public final int index;
        public final String detail;

        private Decision(Action action, String id, int index, String detail) {
            this.action = action;
            this.id = id;
            this.index = index;
            this.detail = detail == null ? "" : detail;
        }
    }

    private boolean lastSeen;
    private List<AccessibilityTree.RetailerRow> previousRows = new ArrayList<>();
    /** Verified immediate predecessor for rows whose predecessor was seen above the viewport. */
    private final Map<String, AccessibilityTree.RetailerRow> predecessorById = new HashMap<>();
    private String lastVisitFingerprint = "";

    public void newVisit() {
        lastSeen = false;
        previousRows = new ArrayList<>();
        predecessorById.clear();
        lastVisitFingerprint = "";
    }

    public boolean lastSeen() {
        return lastSeen;
    }

    /**
     * Reads customer rows (the production path) or plain IDs (legacy harness path).
     * The row path refuses to guess when a screen transition loses the list order.
     */
    public Decision decide(List<?> visible, String last, Handled handled) {
        if (visible == null || visible.isEmpty()) {
            if (last != null && !lastSeen) return decision(Action.SCROLL_TO_LAST, null, -1, "");
            return decision(Action.SCROLL_FOR_MORE, null, -1, "");
        }
        if (visible.get(0) instanceof AccessibilityTree.RetailerRow) {
            List<AccessibilityTree.RetailerRow> rows = new ArrayList<>(visible.size());
            for (Object value : visible) {
                if (!(value instanceof AccessibilityTree.RetailerRow)) {
                    return uncertain("The customer list changed shape while it was being read");
                }
                rows.add((AccessibilityTree.RetailerRow) value);
            }
            return decideRows(rows, last, handled);
        }

        List<String> ids = new ArrayList<>(visible.size());
        for (Object value : visible) ids.add(value == null ? "" : String.valueOf(value));
        return decideIds(ids, last, handled);
    }

    private Decision decideIds(List<String> ids, String last, Handled handled) {
        int from = 0;
        if (RetailerPolicy.isRetailerId(last)) {
            int at = ids.indexOf(last);
            if (at >= 0) {
                lastSeen = true;
                from = at + 1;
            } else if (!lastSeen) {
                return decision(Action.SCROLL_TO_LAST, null, -1, "");
            }
        }
        for (int i = from; i < ids.size(); i++) {
            String id = ids.get(i);
            if (!RetailerPolicy.isRetailerId(id)) continue;
            if (handled == null || !handled.is(id)) return decision(Action.OPEN, id, i, "");
        }
        return decision(Action.SCROLL_FOR_MORE, null, -1, "");
    }

    private Decision decideRows(List<AccessibilityTree.RetailerRow> rows,
                                String last, Handled handled) {
        for (int i = 0; i < rows.size(); i++) {
            for (int j = i + 1; j < rows.size(); j++) {
                if (rows.get(i).id.equals(rows.get(j).id)) {
                    return uncertain("Customer ID " + rows.get(i).id
                            + " appears on more than one visible row");
                }
            }
        }
        String fingerprint = fingerprint(rows);
        if (!lastVisitFingerprint.equals(fingerprint)) {
            String continuity = verifyContinuity(rows);
            if (continuity != null) return uncertain(continuity);
            rememberPredecessors(rows);
            previousRows = new ArrayList<>(rows);
            lastVisitFingerprint = fingerprint;
        }

        int anchorAt = RetailerPolicy.isRetailerId(last) ? indexOf(rows, last) : -1;
        if (anchorAt == -2) return uncertain("The last-handled ID appears more than once on screen");
        int from = 0;
        if (RetailerPolicy.isRetailerId(last)) {
            if (anchorAt >= 0) {
                lastSeen = true;
                from = anchorAt + 1;
            } else if (!lastSeen) {
                return decision(Action.SCROLL_TO_LAST, null, -1, "");
            }
        }

        for (int i = from; i < rows.size(); i++) {
            AccessibilityTree.RetailerRow row = rows.get(i);
            if (!RetailerPolicy.isRetailerId(row.id)) {
                return uncertain("A visible customer row has no readable ID");
            }
            if (handled != null && handled.is(row.id)) continue;
            if (row.type == AccessibilityTree.RetailerRow.Type.DIST) continue;
            if (row.type != AccessibilityTree.RetailerRow.Type.RETAILER) {
                return uncertain("Cannot verify whether customer " + row.id + " is a retailer or distributor");
            }
            if (row.name == null || row.name.trim().isEmpty()) {
                return uncertain("Cannot read the name needed to check distributor adjacency for " + row.id);
            }

            AccessibilityTree.RetailerRow predecessor = i > 0
                    ? rows.get(i - 1) : predecessorById.get(row.id);
            if (predecessor == null && !RetailerPolicy.isRetailerId(last)) {
                // Fresh visit: nothing is above the first row on screen, so it cannot be a
                // same-name shadow of anything. It may be opened when it is a readable
                // (Retailer) row. IDs never imply a position.
                return decision(Action.OPEN, row.id, i, "");
            }
            if (predecessor == null) {
                return uncertain("Cannot verify the row immediately before " + row.id
                        + "; position it after a visible predecessor or enter a last-handled ID");
            }
            if (predecessor.type == AccessibilityTree.RetailerRow.Type.UNKNOWN) {
                return uncertain("Cannot verify the customer type immediately before " + row.id);
            }
            if (predecessor.type == AccessibilityTree.RetailerRow.Type.DIST) {
                if (predecessor.name == null || predecessor.name.trim().isEmpty()) {
                    return uncertain("Cannot read the distributor name immediately before " + row.id);
                }
                if (sameName(predecessor.name, row.name)) continue;
            }
            return decision(Action.OPEN, row.id, i, "");
        }
        return decision(Action.SCROLL_FOR_MORE, null, -1, "");
    }

    private String verifyContinuity(List<AccessibilityTree.RetailerRow> rows) {
        if (previousRows.isEmpty()) return null;
        int previousIndex = -1;
        int currentIndex = -1;
        for (int i = 0; i < rows.size(); i++) {
            int found = indexOf(previousRows, rows.get(i).id);
            if (found < 0) continue;
            if (previousIndex >= 0 && found <= previousIndex) {
                return "The customer list order changed while scrolling; stopped for a manual check";
            }
            previousIndex = found;
            currentIndex = i;
        }
        if (currentIndex < 0) {
            return "The list moved without an overlapping customer row, so the skipped rows cannot be checked";
        }
        for (int i = 1; i < rows.size(); i++) {
            AccessibilityTree.RetailerRow old = predecessorById.get(rows.get(i).id);
            if (old != null && !sameRow(old, rows.get(i - 1))) {
                return "A visible customer's immediate predecessor changed; stopped for a manual check";
            }
        }
        return null;
    }

    private void rememberPredecessors(List<AccessibilityTree.RetailerRow> rows) {
        for (int i = 1; i < rows.size(); i++) {
            AccessibilityTree.RetailerRow current = rows.get(i);
            AccessibilityTree.RetailerRow predecessor = rows.get(i - 1);
            AccessibilityTree.RetailerRow old = predecessorById.get(current.id);
            if (old == null || sameRow(old, predecessor)) predecessorById.put(current.id, predecessor);
        }
    }

    private static boolean sameRow(AccessibilityTree.RetailerRow a,
                                   AccessibilityTree.RetailerRow b) {
        return a != null && b != null && a.id.equals(b.id)
                && a.type == b.type && sameName(a.name, b.name);
    }

    private static boolean sameName(String a, String b) {
        if (a == null || b == null) return false;
        return a.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT)
                .equals(b.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT));
    }

    private static int indexOf(List<AccessibilityTree.RetailerRow> rows, String id) {
        int found = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (!rows.get(i).id.equals(id)) continue;
            if (found >= 0) return -2;
            found = i;
        }
        return found;
    }

    private static String fingerprint(List<AccessibilityTree.RetailerRow> rows) {
        StringBuilder out = new StringBuilder();
        for (AccessibilityTree.RetailerRow row : rows) {
            out.append(row.id).append('\u0001').append(row.type).append('\u0001')
                    .append(row.name).append('\u0002');
        }
        return out.toString();
    }

    private static Decision uncertain(String detail) {
        return decision(Action.STOP_UNCERTAIN, null, -1, detail);
    }

    private static Decision decision(Action action, String id, int index, String detail) {
        return new Decision(action, id, index, detail);
    }
}
