package com.vrksignatures.sudhaautobot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Setup screen: pick the target package, grant the two permissions and read the
 * live status of today's ledger.
 *
 * <p>All UI is built in code on purpose - the app carries no layouts beyond the
 * overlay panel, so there is nothing to keep in sync with the Java.</p>
 */
public final class MainActivity extends Activity {

    /** Package of the first choice; used as the prefilled default. */
    static final String DEFAULT_TARGET_PACKAGE = "com.sudha.dailyorders";
    /** Package named in the brief, kept as a fallback suggestion. */
    static final String ALTERNATE_TARGET_PACKAGE = "com.vrksignatures.sudhadairy";

    static final String PREFS = "bot_config";
    static final String KEY_TARGET_PACKAGE = "target_package";
    static final String KEY_EXPECTED_COUNT = "expected_count";
    static final String KEY_LAST_HANDLED_ID = "last_handled_id_manual";
    static final String KEY_FAST_MODE = "fast_mode_enabled";
    static final String KEY_SCROLL_MEMORY_ID = "scroll_memory_id";
    static final String KEY_SCROLL_MEMORY_STEPS = "scroll_memory_steps";

    private EditText packageInput;
    private EditText expectedInput;
    private EditText lastHandledInput;
    private Button fastModeButton;
    private TextView packageStatus;
    private TextView overlayStatus;
    private TextView accessibilityStatus;
    private TextView ledgerStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(title(getString(R.string.setup_title)));
        root.addView(body(getString(R.string.setup_intro)));

        root.addView(label(getString(R.string.label_package)));
        packageInput = new EditText(this);
        packageInput.setSingleLine(true);
        packageInput.setInputType(InputType.TYPE_CLASS_TEXT);
        packageInput.setHint(R.string.hint_package);
        packageInput.setText(prefs().getString(KEY_TARGET_PACKAGE, DEFAULT_TARGET_PACKAGE));
        root.addView(packageInput);

        root.addView(label(getString(R.string.label_expected)));
        expectedInput = new EditText(this);
        expectedInput.setSingleLine(true);
        expectedInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        expectedInput.setText(String.valueOf(prefs().getInt(
                KEY_EXPECTED_COUNT, RetailerPolicy.EXPECTED_RETAILER_COUNT)));
        root.addView(expectedInput);

        root.addView(label(getString(R.string.label_last_handled_id)));
        lastHandledInput = new EditText(this);
        lastHandledInput.setSingleLine(true);
        lastHandledInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        lastHandledInput.setHint(R.string.hint_last_handled_id);
        lastHandledInput.setText(prefs().getString(KEY_LAST_HANDLED_ID, ""));
        root.addView(lastHandledInput);
        root.addView(body(getString(R.string.resume_instructions)));

        root.addView(button(getString(R.string.button_save), v -> saveSettings(true)));
        fastModeButton = button(getString(R.string.fast_setup_off), v -> toggleFastMode());
        root.addView(fastModeButton);
        root.addView(button(getString(R.string.button_overlay), v -> requestOverlayPermission()));
        root.addView(button(getString(R.string.button_accessibility), v -> openAccessibilitySettings()));
        root.addView(button(getString(R.string.button_install_target), v -> openTargetApp()));
        root.addView(button(getString(R.string.button_show_panel), v -> showPanel()));
        root.addView(button(getString(R.string.button_start), v -> startBot()));
        root.addView(button(getString(R.string.button_pause), v -> sendControl(SudhaAccessibilityService.ACTION_PAUSE)));
        root.addView(button(getString(R.string.button_reset), v -> confirmReset()));
        root.addView(button(getString(R.string.button_capture), v -> captureNow()));
        root.addView(button(getString(R.string.button_share_capture), v -> shareCapture()));
        root.addView(button(getString(R.string.button_clear_captures), v -> clearCaptures()));
        root.addView(button(getString(R.string.button_refresh), v -> refreshStatus()));

        packageStatus = statusLine();
        overlayStatus = statusLine();
        accessibilityStatus = statusLine();
        ledgerStatus = statusLine();
        root.addView(packageStatus);
        root.addView(overlayStatus);
        root.addView(accessibilityStatus);
        root.addView(ledgerStatus);

        root.addView(body(getString(R.string.legend)));

        setContentView(scroll);
        setTitle(getString(R.string.app_name));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshFastModeButton();
        refreshStatus();
    }

    // ------------------------------------------------------------------ UI bits

    private TextView title(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(22f);
        view.setTextColor(Color.parseColor("#1B5E20"));
        view.setPadding(0, 0, 0, dp(8));
        return view;
    }

    private TextView body(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(13f);
        view.setPadding(0, dp(6), 0, dp(10));
        return view;
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(13f);
        view.setPadding(0, dp(10), 0, dp(2));
        return view;
    }

    private TextView statusLine() {
        TextView view = new TextView(this);
        view.setTextSize(13f);
        view.setPadding(0, dp(6), 0, 0);
        return view;
    }

    private Button button(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(6);
        button.setLayoutParams(params);
        button.setOnClickListener(listener);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------- preferences

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private String savedPackage() {
        return prefs().getString(KEY_TARGET_PACKAGE, "").trim();
    }

    private int savedExpectedCount() {
        int value = prefs().getInt(KEY_EXPECTED_COUNT, RetailerPolicy.EXPECTED_RETAILER_COUNT);
        return value < 1 || value > 2000 ? RetailerPolicy.EXPECTED_RETAILER_COUNT : value;
    }

    /** Persists the form; returns false (and highlights the field) when invalid. */
    private boolean saveSettings(boolean showToast) {
        String target = packageInput.getText().toString().trim();
        if (target.isEmpty()) {
            packageInput.setError(getString(R.string.error_package_empty));
            return false;
        }
        int expected;
        try {
            expected = Integer.parseInt(expectedInput.getText().toString().trim());
        } catch (NumberFormatException e) {
            expected = RetailerPolicy.EXPECTED_RETAILER_COUNT;
        }
        if (expected < 1 || expected > 2000) expected = RetailerPolicy.EXPECTED_RETAILER_COUNT;

        prefs().edit()
                .putString(KEY_TARGET_PACKAGE, target)
                .putInt(KEY_EXPECTED_COUNT, expected)
                .putString(KEY_LAST_HANDLED_ID, lastHandledInput == null
                        ? "" : RetailerPolicy.normalize(lastHandledInput.getText().toString()))
                .apply();

        if (showToast) {
            Toast.makeText(this, "Saved: " + target + " (" + expected + " retailers)",
                    Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    // ------------------------------------------------------------- permissions

    private void requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Floating panel permission already granted",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
        }
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(this,
                    "Enable SudhaAutoBot under Installed / Downloaded services",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Accessibility settings unavailable", Toast.LENGTH_LONG).show();
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        String enabled = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        String expected = new ComponentName(this, SudhaAccessibilityService.class)
                .flattenToString().toLowerCase(Locale.ROOT);
        String expectedShort = new ComponentName(this, SudhaAccessibilityService.class)
                .flattenToShortString().toLowerCase(Locale.ROOT);
        for (String entry : enabled.split(":")) {
            String value = entry.trim().toLowerCase(Locale.ROOT);
            if (value.equals(expected) || value.equals(expectedShort)) return true;
        }
        return false;
    }

    private boolean isTargetInstalled(String target) {
        try {
            getPackageManager().getPackageInfo(target, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    // ----------------------------------------------------------------- actions

    private void openTargetApp() {
        if (!saveSettings(false)) return;
        Intent launch = getPackageManager().getLaunchIntentForPackage(savedPackage());
        if (launch == null) {
            Toast.makeText(this, getString(R.string.error_package_not_installed)
                    + " (" + savedPackage() + ")", Toast.LENGTH_LONG).show();
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);
    }

    /** Shows the overlay without starting a run (useful to reposition it). */
    private void showPanel() {
        if (!saveSettings(false)) return;
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission();
            return;
        }
        sendControl(SudhaAccessibilityService.ACTION_SHOW_PANEL);
        Toast.makeText(this, "Floating panel requested", Toast.LENGTH_SHORT).show();
    }

    private void startBot() {
        if (!saveSettings(false)) return;
        String target = savedPackage();

        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission();
            return;
        }
        if (!isAccessibilityServiceEnabled()) {
            openAccessibilitySettings();
            return;
        }
        if (!isTargetInstalled(target)) {
            new AlertDialog.Builder(this)
                    .setTitle("Package not found")
                    .setMessage("No app with package \"" + target + "\" is installed.\n\n"
                            + "Known candidates:\n• " + DEFAULT_TARGET_PACKAGE + "\n• "
                            + ALTERNATE_TARGET_PACKAGE
                            + "\n\nEnter the exact package name shown in "
                            + "Settings > Apps, then tap Save.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        sendControl(SudhaAccessibilityService.ACTION_START);
        String resumeId = RetailerPolicy.normalize(lastHandledInput.getText().toString());
        Toast.makeText(this, resumeId == null
                        ? getString(R.string.fresh_start_toast)
                        : getString(R.string.resume_anchor_toast, resumeId),
                Toast.LENGTH_SHORT).show();
        moveTaskToBack(true);
    }

    private void toggleFastMode() {
        boolean enabled = !prefs().getBoolean(KEY_FAST_MODE, false);
        prefs().edit().putBoolean(KEY_FAST_MODE, enabled).apply();
        refreshFastModeButton();
        Toast.makeText(this, enabled
                        ? getString(R.string.fast_enabled_toast)
                        : getString(R.string.fast_disabled_toast),
                Toast.LENGTH_LONG).show();
    }

    private void refreshFastModeButton() {
        if (fastModeButton == null) return;
        boolean enabled = prefs().getBoolean(KEY_FAST_MODE, false);
        fastModeButton.setText(enabled
                ? getString(R.string.fast_setup_on) : getString(R.string.fast_setup_off));
    }

    private void sendControl(String action) {
        Intent intent = new Intent(action).setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.reset_title)
                .setMessage(R.string.reset_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.reset_confirm, (dialog, which) -> {
                    sendControl(SudhaAccessibilityService.ACTION_RESET);
                    // The service owns the file; give it a moment, then re-read status.
                    new Handler(Looper.getMainLooper()).postDelayed(this::refreshStatus, 600);
                })
                .show();
    }

    // --------------------------------------------------------------- inspector

    /**
     * Captures what the accessibility service can see right now.
     *
     * <p>Ten seconds is enough to tap this button, switch to the Sudha app and let the
     * screen settle; the service samples whatever is in the foreground at that moment.</p>
     */
    private void captureNow() {
        if (!isAccessibilityServiceEnabled()) {
            openAccessibilitySettings();
            return;
        }
        sendControl(SudhaAccessibilityService.ACTION_CAPTURE);
        Toast.makeText(this, getString(R.string.capture_hint), Toast.LENGTH_LONG).show();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            File[] files = ScreenCapture.list(this);
            if (files.length == 0) {
                Toast.makeText(this, R.string.capture_none, Toast.LENGTH_LONG).show();
                return;
            }
            try {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                try (FileInputStream in = new FileInputStream(files[0])) {
                    byte[] chunk = new byte[4096];
                    int read;
                    while ((read = in.read(chunk)) > 0) buffer.write(chunk, 0, read);
                }
                showCaptureDialog(files[0].getName(),
                        new String(buffer.toByteArray(), StandardCharsets.UTF_8));
            } catch (Exception e) {
                Toast.makeText(this, "Could not read " + files[0].getName(),
                        Toast.LENGTH_LONG).show();
            }
        }, 10000);
    }

    /** Scrollable, copyable view of the newest capture. */
    private void showCaptureDialog(String fileName, String content) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextSize(10f);
        view.setTypeface(android.graphics.Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        int pad = dp(12);
        view.setPadding(pad, pad, pad, pad);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(view);

        new AlertDialog.Builder(this)
                .setTitle(fileName)
                .setView(scroll)
                .setPositiveButton(R.string.capture_share, (dialog, which) -> shareCapture())
                .setNeutralButton(R.string.close, null)
                .show();
    }

    private void shareCapture() {
        sendControl(SudhaAccessibilityService.ACTION_SHARE_CAPTURE);
        Toast.makeText(this, R.string.capture_share_hint, Toast.LENGTH_LONG).show();
    }

    private void clearCaptures() {
        boolean cleared = ScreenCapture.clear(this);
        Toast.makeText(this, cleared ? R.string.capture_cleared : R.string.capture_clear_failed,
                Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ status

    private void refreshStatus() {
        boolean overlay = Settings.canDrawOverlays(this);
        overlayStatus.setText(overlay
                ? getString(R.string.status_overlay_ok)
                : getString(R.string.status_overlay_missing));
        overlayStatus.setTextColor(overlay ? Color.parseColor("#2E7D32") : Color.parseColor("#C62828"));

        String target = savedPackage();
        if (target.isEmpty()) {
            packageStatus.setText(R.string.status_package_unset);
            packageStatus.setTextColor(Color.parseColor("#C62828"));
        } else if (isTargetInstalled(target)) {
            packageStatus.setText(getString(R.string.status_package_ok, target));
            packageStatus.setTextColor(Color.parseColor("#2E7D32"));
        } else {
            packageStatus.setText(getString(R.string.status_package_missing, target));
            packageStatus.setTextColor(Color.parseColor("#C62828"));
        }

        boolean accessibility = isAccessibilityServiceEnabled();
        accessibilityStatus.setText(accessibility
                ? getString(R.string.status_accessibility_ok)
                : getString(R.string.status_accessibility_missing));
        accessibilityStatus.setTextColor(accessibility
                ? Color.parseColor("#2E7D32") : Color.parseColor("#C62828"));

        DailyLedger ledger = new DailyLedger(this);
        StringBuilder builder = new StringBuilder();
        builder.append("Ledger ").append(ledger.sessionDate()).append('\n');
        builder.append(ledger.summary()).append('\n');
        builder.append("Processed ").append(ledger.processedCount())
                .append(" / ").append(savedExpectedCount());
        if (ledger.hasPending()) {
            builder.append("\n⚠ IN FLIGHT: ").append(ledger.pendingId())
                    .append(" (").append(ledger.pendingPhase()).append(')');
        }
        ledgerStatus.setText(builder.toString());
        ledgerStatus.setTextColor(ledger.hasPending()
                ? Color.parseColor("#EF6C00") : Color.parseColor("#37474F"));
    }
}
