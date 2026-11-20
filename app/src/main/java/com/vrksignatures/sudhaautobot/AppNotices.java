package com.vrksignatures.sudhaautobot;

import java.util.Locale;

/**
 * Short messages the Sudha app shows that the bot has to act on.
 *
 * <p>"Today Order already created" is shown as a <b>toast</b> when PLACE ORDER is pressed
 * for a retailer who has already ordered today. A toast is not part of the window's
 * accessibility tree, so reading the screen never finds it; it reaches the service only as
 * a notification-state event. The order form simply stays where it is, which is why the
 * bot used to sit there waiting for a payment screen that was never going to open.</p>
 */
public final class AppNotices {
    private AppNotices() {
    }

    /**
     * True when {@code said} tells us the retailer already has an order for today.
     *
     * <p>Seen on the device: "Today Order already created". Small variations in wording
     * are accepted, but the sentence must be about an order that already exists.</p>
     */
    public static boolean isAlreadyOrdered(CharSequence said) {
        if (said == null) return false;
        String text = said.toString().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        if (text.isEmpty() || text.length() > 160) return false;
        if (!text.contains("already")) return false;
        if (!text.contains("order")) return false;
        return text.contains("already created")
                || text.contains("already placed")
                || text.contains("already exist")
                || text.contains("already done")
                || text.contains("already submitted");
    }
}
