import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import com.vrksignatures.sudhaautobot.AccessibilityTree;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * A model of the stock Android date dialog (android.app.DatePickerDialog in calendar mode),
 * which is the widget the Sudha app opens for "Copy Order".
 *
 * <p>It reproduces what an accessibility service really receives from that widget, taken
 * from the AOSP sources (DayPickerView, DayPickerPagerAdapter, SimpleMonthView and its
 * MonthViewTouchHelper):</p>
 * <ul>
 *   <li>the month title ("November 2026") and the weekday letters are <b>painted</b> on the
 *       canvas — they are not nodes at all;</li>
 *   <li>every day is a virtual node with text "17" <b>and</b> contentDescription
 *       "17 November 2026", clickable, with ACTION_CLICK only while it is enabled;</li>
 *   <li>the grid lives in a ViewPager, which keeps the neighbouring month attached
 *       off-screen. That page is still part of the tree (only isVisibleToUser is false),
 *       and because children are ordered by position it comes <b>before</b> the visible
 *       month.</li>
 * </ul>
 *
 * <p>Geometry is the real one, measured pixel by pixel from the 720x1600 recording of the
 * device: card 90..630, cells 66x75, first cell centre (160.6, 718.8).</p>
 */
final class StockDatePicker implements AccessibilityTree.Gestures {
    static final int CARD_LEFT = 90, CARD_RIGHT = 630, CARD_TOP = 318, CARD_BOTTOM = 1249;
    static final int PAGER_TOP = 509, PAGER_BOTTOM = 1131;
    static final int GRID_LEFT = 128, GRID_TOP = 681, CELL_W = 66, CELL_H = 75;

    final LocalDate today;
    LocalDate selected;
    YearMonth shown;
    /** Oldest selectable day (null = unlimited). The app only caps the future. */
    LocalDate minDate;
    final LocalDate maxDate;

    /** AOSP orders pager children by position, so the off-screen previous month is first. */
    boolean previousPageFirst = true;
    /** False models a custom-drawn grid: no day nodes in the tree at all. */
    boolean exposeDayNodes = true;
    /** True models a widget that accepts ACTION_CLICK on a day but ignores it. */
    boolean ignoreNodeClicks;
    /** A rectangle that swallows touches, like the bot's own floating panel. */
    Rect overlay;

    boolean open = true;
    boolean confirmed;
    boolean cancelled;
    int nodeClicks;
    int touches;
    final StringBuilder log = new StringBuilder();

    StockDatePicker(LocalDate today) {
        this.today = today;
        this.selected = today;
        this.maxDate = today;
        this.shown = YearMonth.from(today);
    }

    /** Screen rectangle of a day cell in the visible month. */
    Rect cellOf(LocalDate date) {
        int offset = date.withDayOfMonth(1).getDayOfWeek().getValue() % 7; // Sunday first
        int index = offset + date.getDayOfMonth() - 1;
        int col = index % 7;
        int row = index / 7;
        int left = GRID_LEFT + col * CELL_W;
        int top = GRID_TOP + row * CELL_H;
        return new Rect(left, top, left + CELL_W, top + CELL_H);
    }

    private boolean enabled(LocalDate date) {
        if (date.isAfter(maxDate)) return false;
        return minDate == null || !date.isBefore(minDate);
    }

    // ------------------------------------------------------------------ tree

    AccessibilityNodeInfo build() {
        AccessibilityNodeInfo root = node("android.widget.FrameLayout", null, null,
                CARD_LEFT, CARD_TOP, CARD_RIGHT, CARD_BOTTOM);
        if (!open) return root;

        AccessibilityNodeInfo picker = node("android.widget.DatePicker", null, null,
                CARD_LEFT, CARD_TOP, CARD_RIGHT, PAGER_BOTTOM);
        picker.viewId = "android:id/datePicker";
        root.add(picker);

        AccessibilityNodeInfo header = node("android.widget.LinearLayout", null, null,
                CARD_LEFT, CARD_TOP, CARD_RIGHT, 498);
        header.viewId = "android:id/date_picker_header";
        AccessibilityNodeInfo year = node("android.widget.TextView",
                String.valueOf(selected.getYear()), null, 135, 340, 230, 380);
        year.viewId = "android:id/date_picker_header_year";
        String headline = selected.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.US)
                + ", " + selected.getDayOfMonth() + " "
                + selected.getMonth().getDisplayName(TextStyle.SHORT, Locale.US);
        AccessibilityNodeInfo date = node("android.widget.TextView", headline, null,
                135, 390, 520, 470);
        date.viewId = "android:id/date_picker_header_date";
        header.add(year).add(date);
        picker.add(header);

        AccessibilityNodeInfo dayPicker = node("android.view.ViewGroup", null, null,
                CARD_LEFT, PAGER_TOP, CARD_RIGHT, PAGER_BOTTOM);
        dayPicker.viewId = "android:id/date_picker_day_picker";
        picker.add(dayPicker);

        AccessibilityNodeInfo pager = node("com.android.internal.widget.ViewPager", null, null,
                CARD_LEFT, PAGER_TOP, CARD_RIGHT, PAGER_BOTTOM);
        pager.viewId = "android:id/day_picker_view_pager";
        pager.scrollable = true;
        dayPicker.add(pager);

        int pageWidth = CARD_RIGHT - CARD_LEFT;
        AccessibilityNodeInfo current = monthPage(shown, 0);
        AccessibilityNodeInfo previous = hasPrevious() ? monthPage(shown.minusMonths(1), -pageWidth) : null;
        AccessibilityNodeInfo next = hasNext() ? monthPage(shown.plusMonths(1), pageWidth) : null;
        if (previousPageFirst) {
            if (previous != null) pager.add(previous);
            pager.add(current);
        } else {
            pager.add(current);
            if (previous != null) pager.add(previous);
        }
        if (next != null) pager.add(next);

        if (hasPrevious()) {
            AccessibilityNodeInfo prev = node("android.widget.ImageButton", null, "Previous month",
                    115, 516, 205, 606);
            prev.viewId = "android:id/prev";
            prev.clickable = true;
            prev.handler = (n, action) -> {
                if (action != AccessibilityNodeInfo.ACTION_CLICK) return false;
                shown = shown.minusMonths(1);
                log.append("prev-month;");
                return true;
            };
            dayPicker.add(prev);
        }

        AccessibilityNodeInfo buttons = node("android.widget.LinearLayout", null, null,
                CARD_LEFT, 1136, CARD_RIGHT, CARD_BOTTOM);
        AccessibilityNodeInfo cancel = node("android.widget.Button", "CANCEL", null,
                350, 1146, 480, 1236);
        cancel.viewId = "android:id/button2";
        cancel.clickable = true;
        cancel.handler = (n, action) -> {
            if (action != AccessibilityNodeInfo.ACTION_CLICK) return false;
            cancelled = true;
            open = false;
            log.append("CANCEL;");
            return true;
        };
        AccessibilityNodeInfo ok = node("android.widget.Button", "OK", null, 490, 1146, 610, 1236);
        ok.viewId = "android:id/button1";
        ok.clickable = true;
        ok.handler = (n, action) -> {
            if (action != AccessibilityNodeInfo.ACTION_CLICK) return false;
            confirmed = true;
            open = false;
            log.append("OK(").append(selected).append(");");
            return true;
        };
        buttons.add(cancel).add(ok);
        root.add(buttons);
        return root;
    }

    private boolean hasPrevious() {
        return minDate == null || YearMonth.from(minDate).isBefore(shown);
    }

    private boolean hasNext() {
        return YearMonth.from(maxDate).isAfter(shown);
    }

    /** One SimpleMonthView with its virtual day nodes, shifted by {@code dx} pixels. */
    private AccessibilityNodeInfo monthPage(YearMonth month, int dx) {
        boolean onScreen = dx == 0;
        AccessibilityNodeInfo view = node("android.view.View", null, null,
                CARD_LEFT + dx, PAGER_TOP, CARD_RIGHT + dx, PAGER_BOTTOM);
        view.viewId = "android:id/month_view";
        view.visibleToUser = onScreen;
        if (!exposeDayNodes) return view;

        String monthName = month.getMonth().getDisplayName(TextStyle.FULL, Locale.US);
        for (int day = 1; day <= month.lengthOfMonth(); day++) {
            final LocalDate date = month.atDay(day);
            Rect cell = cellOf(date);
            // SimpleMonthView: text = "17", contentDescription = "dd MMMM yyyy".
            AccessibilityNodeInfo dayNode = node("android.view.View", String.valueOf(day),
                    String.format(Locale.US, "%02d %s %d", day, monthName, month.getYear()),
                    cell.left + dx, cell.top, cell.right + dx, cell.bottom);
            dayNode.visibleToUser = onScreen;
            dayNode.clickable = true;
            dayNode.enabled = enabled(date);
            boolean isSelected = date.equals(selected);
            dayNode.selected = isSelected;
            dayNode.checked = isSelected;
            dayNode.handler = (n, action) -> {
                if (action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) return true;
                if (action != AccessibilityNodeInfo.ACTION_CLICK) return false;
                if (!n.enabled) return false;          // disabled days have no ACTION_CLICK
                nodeClicks++;
                if (ignoreNodeClicks) {
                    log.append("click-ignored(").append(date).append(");");
                    return true;
                }
                select(date, "click");
                return true;
            };
            view.add(dayNode);
        }
        return view;
    }

    private void select(LocalDate date, String how) {
        selected = date;
        shown = YearMonth.from(date);
        log.append(how).append('(').append(date).append(");");
    }

    private static AccessibilityNodeInfo node(String className, String text, String description,
                                              int l, int t, int r, int b) {
        AccessibilityNodeInfo node = new AccessibilityNodeInfo();
        node.className = className;
        node.text = text;
        node.contentDescription = description;
        node.bounds.set(l, t, r, b);
        return node;
    }

    // --------------------------------------------------------------- touches

    /** A real finger (or dispatchGesture) landing on the screen. */
    boolean touch(float x, float y) {
        touches++;
        if (overlay != null && overlay.contains((int) x, (int) y)) {
            log.append("touch-on-panel;");
            return true;                         // dispatched, but the panel swallowed it
        }
        if (!open) return true;
        for (int day = 1; day <= shown.lengthOfMonth(); day++) {
            LocalDate date = shown.atDay(day);
            if (cellOf(date).contains((int) x, (int) y)) {
                if (enabled(date)) select(date, "touch");
                else log.append("touch-disabled(").append(date).append(");");
                return true;
            }
        }
        if (new Rect(350, 1146, 480, 1236).contains((int) x, (int) y)) {
            cancelled = true;
            open = false;
            log.append("CANCEL;");
        } else if (new Rect(490, 1146, 610, 1236).contains((int) x, (int) y)) {
            confirmed = true;
            open = false;
            log.append("OK(").append(selected).append(");");
        } else if (new Rect(115, 516, 205, 606).contains((int) x, (int) y) && hasPrevious()) {
            shown = shown.minusMonths(1);
            log.append("prev-month;");
        } else {
            log.append("touch-nothing(").append((int) x).append(',').append((int) y).append(");");
        }
        return true;
    }

    @Override
    public boolean tap(Rect bounds) {
        return touch(bounds.exactCenterX(), bounds.exactCenterY());
    }

    @Override
    public boolean swipeUp(Rect area) {
        return false;
    }
}
