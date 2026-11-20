package com.vrksignatures.sudhaautobot;

/**
 * Shared identity rules for customer IDs.
 *
 * <p>The Sudha list can use different ID formats. An ID is therefore an exact,
 * nonblank string; its prefix, characters and numeric value do not determine
 * whether the row is eligible or where it appears in the list.</p>
 */
public final class RetailerPolicy {
    /** Progress display default, not an ID or eligibility rule. */
    public static final int EXPECTED_RETAILER_COUNT = 125;

    private RetailerPolicy() {
    }

    /** True for any nonblank customer ID. The value itself is never rewritten. */
    public static boolean isRetailerId(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /** Exact ID from a user field; whitespace is preserved except for blank checking. */
    public static String normalize(String raw) {
        if (raw == null) return null;
        return isRetailerId(raw) ? raw : null;
    }

    /** Legacy helper retained for callers; numeric ordering is no longer used. */
    public static int numericPart(String id) {
        return -1;
    }
}
