package com.vrksignatures.sudhaautobot;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The always-on-top control strip ("Oppo/Vivo safe UI").
 *
 * <p>Design notes for ColorOS / FuntouchOS class devices:</p>
 * <ul>
 *   <li>{@code TYPE_APPLICATION_OVERLAY} on API 26+, the deprecated
 *       {@code TYPE_PHONE} below that.</li>
 *   <li>{@code FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL} so the target app keeps
 *       keyboard focus while the panel is on screen.</li>
 *   <li>A small drag handle with a generous touch target; the panel is moved by
 *       updating {@link WindowManager.LayoutParams} rather than by re-inflating.</li>
 *   <li>Every text update is posted to the main looper, so callers may update the
 *       panel from a background thread.</li>
 * </ul>
 */
public final class FloatingPanelController {

    /** Callbacks wired to the three buttons. */
    public interface Actions {
        void onStart();

        void onPause();

        void onReset();

        void onHide();

        /** Read-only screen dump for verifying labels and the package name. */
        void onCapture();
    }

    private final Context context;
    private final WindowManager windowManager;
    private final View root;
    private final WindowManager.LayoutParams params;
    private final LinearLayout body;
    private final TextView stateView;
    private final TextView currentView;
    private final TextView countsView;
    private final TextView progressView;
    private final TextView messageView;
    private final Button startPauseButton;
    private final Button fastModeButton;
    private final Handler main = new Handler(Looper.getMainLooper());

    private boolean attached;
    private boolean collapsed;
    private boolean running;
    /** Last frame, replayed after the panel is re-attached. */
    private String lastState = "IDLE";
    private String lastRetailer = "—";
    private String lastMessage = "Ready";
    private int success, skipped, errors, processed, expected = RetailerPolicy.EXPECTED_RETAILER_COUNT;

    public FloatingPanelController(Context context, Actions actions) {
        this.context = context.getApplicationContext();
        this.windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        this.root = LayoutInflater.from(this.context).inflate(R.layout.floaty_layout, null, false);

        this.body = root.findViewById(R.id.panel_body);
        this.stateView = root.findViewById(R.id.panel_state);
        this.currentView = root.findViewById(R.id.panel_current);
        this.countsView = root.findViewById(R.id.panel_counts);
        this.progressView = root.findViewById(R.id.panel_progress);
        this.messageView = root.findViewById(R.id.panel_message);
        this.startPauseButton = root.findViewById(R.id.panel_start_pause);
        this.fastModeButton = root.findViewById(R.id.panel_fast_mode);

        this.params = buildParams();
        applyRoundedBackground();

        root.findViewById(R.id.panel_drag_handle).setOnTouchListener(new DragTouchListener());
        startPauseButton.setOnClickListener(v -> {
            if (running) actions.onPause();
            else actions.onStart();
        });
        ((Button) root.findViewById(R.id.panel_reset)).setOnClickListener(v -> actions.onReset());
        ((Button) root.findViewById(R.id.panel_hide)).setOnClickListener(v -> actions.onHide());
        ((Button) root.findViewById(R.id.panel_capture)).setOnClickListener(v -> actions.onCapture());
        fastModeButton.setOnClickListener(v -> {
            android.content.SharedPreferences prefs = this.context.getSharedPreferences(
                    MainActivity.PREFS, Context.MODE_PRIVATE);
            boolean enabled = !prefs.getBoolean(MainActivity.KEY_FAST_MODE, false);
            prefs.edit().putBoolean(MainActivity.KEY_FAST_MODE, enabled).apply();
            renderFastMode(enabled);
        });
    }

    private WindowManager.LayoutParams buildParams() {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        layoutParams.gravity = Gravity.TOP | Gravity.START;
        layoutParams.x = 16;
        layoutParams.y = 140;
        layoutParams.windowAnimations = 0;
        return layoutParams;
    }

    private void applyRoundedBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.parseColor("#F2202124"));
        background.setCornerRadius(dp(14));
        background.setStroke(Math.max(1, Math.round(dp(1))), Color.parseColor("#55FFFFFF"));
        root.setBackground(background);
    }

    private float dp(float value) {
        return value * context.getResources().getDisplayMetrics().density;
    }

    // ------------------------------------------------------------ attach/detach

    /** Adds the overlay; returns false when the permission is missing. */
    public boolean show() {
        if (attached || windowManager == null) return attached;
        try {
            windowManager.addView(root, params);
            attached = true;
            collapsed = false;
            body.setVisibility(View.VISIBLE);
            renderSnapshot();
            return true;
        } catch (Exception e) {
            attached = false;
            return false;
        }
    }

    public void remove() {
        if (!attached || windowManager == null) return;
        try {
            windowManager.removeViewImmediate(root);
        } catch (Exception ignored) {
            // Already detached by the system (e.g. overlay permission revoked).
        }
        attached = false;
    }

    public boolean isAttached() {
        return attached;
    }

    /**
     * Where the panel currently sits on screen, or null when it is not shown.
     *
     * <p>The panel takes any touch that lands on it, including the bot's own. The service
     * asks before pressing a spot so it can say "the panel is in the way" instead of
     * pressing its own window and wondering why nothing happened.</p>
     */
    public android.graphics.Rect boundsOnScreen() {
        if (!attached || root.getWidth() <= 0 || root.getHeight() <= 0) return null;
        int[] at = new int[2];
        root.getLocationOnScreen(at);
        return new android.graphics.Rect(at[0], at[1], at[0] + root.getWidth(), at[1] + root.getHeight());
    }

    // ------------------------------------------------------------- panel updates

    /**
     * Repaints the panel. Safe to call from any thread and with any state object;
     * values are snapshotted on the main looper.
     */
    public void update(BotState state, String retailerId, String message, boolean isRunning,
                       int successCount, int skippedCount, int errorCount,
                       int processedCount, int expectedCount) {
        main.post(() -> {
            running = isRunning;
            lastState = state == null ? "IDLE" : state.name();
            lastRetailer = retailerId == null || retailerId.isEmpty() ? "—" : retailerId;
            lastMessage = message == null ? "" : message;
            success = successCount;
            skipped = skippedCount;
            errors = errorCount;
            processed = processedCount;
            expected = Math.max(1, expectedCount);
            renderSnapshot();
        });
    }

    /** Convenience overload driven by the ledger directly. */
    public void updateFromLedger(BotState state, String retailerId, String message,
                                 boolean isRunning, DailyLedger ledger, int expectedCount) {
        if (ledger == null) {
            update(state, retailerId, message, isRunning, success, skipped, errors, processed, expectedCount);
            return;
        }
        update(state, retailerId, message, isRunning,
                ledger.countBucket("SUCCESS"),
                ledger.countBucket("SKIPPED"),
                ledger.countBucket("ERROR"),
                ledger.processedCount(),
                expectedCount);
    }

    private void renderSnapshot() {
        stateView.setText(lastState);
        stateView.setTextColor(colorForState());
        currentView.setText("Retailer: " + lastRetailer);
        countsView.setText("SUCCESS " + success + "    SKIPPED " + skipped + "    ERRORS " + errors);
        progressView.setText("Processed " + processed + " / " + expected);
        messageView.setText(lastMessage);
        startPauseButton.setText(running
                ? context.getString(R.string.action_pause)
                : context.getString(R.string.action_start));
        renderFastMode(context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .getBoolean(MainActivity.KEY_FAST_MODE, false));
        if (startPauseButton.getBackground() != null) {
            startPauseButton.getBackground().setTint(running
                    ? Color.parseColor("#FF9800") : Color.parseColor("#2E7D32"));
        }
        if (!attached) return;
        try {
            windowManager.updateViewLayout(root, params);
        } catch (Exception ignored) {
        }
    }

    private void renderFastMode(boolean enabled) {
        fastModeButton.setText(enabled
                ? context.getString(R.string.action_fast_on)
                : context.getString(R.string.action_fast_off));
    }

    private int colorForState() {
        switch (lastState) {
            case "SUCCESS":
            case "COMPLETE":
                return Color.parseColor("#66BB6A");
            case "ERROR":
                return Color.parseColor("#EF5350");
            case "PAUSED":
            case "IDLE":
                return Color.parseColor("#BDBDBD");
            default:
                return Color.parseColor("#4FC3F7");
        }
    }

    /** Flashes a short message without changing the state machine. */
    public void toastState(String message) {
        main.post(() -> {
            lastMessage = message;
            messageView.setText(message);
        });
    }

    // ------------------------------------------------------------------- drag

    @SuppressLint("ClickableViewAccessibility")
    private final class DragTouchListener implements View.OnTouchListener {
        private int startX, startY;
        private float downX, downY;
        private boolean dragged;

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = params.x;
                    startY = params.y;
                    downX = event.getRawX();
                    downY = event.getRawY();
                    dragged = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;
                    if (Math.abs(dx) + Math.abs(dy) > dp(8)) dragged = true;
                    if (dragged) {
                        params.x = startX + (int) dx;
                        params.y = Math.max(0, startY + (int) dy);
                        if (attached && windowManager != null) {
                            try {
                                windowManager.updateViewLayout(root, params);
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragged) toggleCollapsed();
                    return true;
                default:
                    return true;
            }
        }
    }

    /** Tap on the handle collapses the panel to just the state line. */
    private void toggleCollapsed() {
        collapsed = !collapsed;
        body.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        if (attached && windowManager != null) {
            try {
                windowManager.updateViewLayout(root, params);
            } catch (Exception ignored) {
            }
        }
    }
}
