package com.salonreview.marketing;

/** One way to write a person's name on lead owner labels and alerts. */
public final class OwnerLabels {

    private OwnerLabels() {}

    /** providerName and accountName are the two possible display names; owners get a single "(owner)" suffix. */
    public static String label(String role, String providerName, String accountName, String username) {
        String name = firstNonBlank(providerName, accountName);
        if (!"OWNER".equals(role)) return name != null ? name : username;
        if (name == null) return "Owner (" + username + ")";
        return name.toLowerCase().endsWith("(owner)") ? name : name + " (owner)";
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a.trim();
        if (b != null && !b.isBlank()) return b.trim();
        return null;
    }
}
