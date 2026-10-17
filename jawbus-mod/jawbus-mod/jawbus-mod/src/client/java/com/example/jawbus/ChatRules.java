package com.example.jawbus;

import java.util.Locale;

/** Chat-line rules. Pure Java. Player chat ("Name: message") is never accepted. */
public final class ChatRules {
    private ChatRules() {
    }

    public static String clean(String raw) {
        return raw == null ? "" : raw.replaceAll("§.", "").strip();
    }

    /** True if the tag appears and nothing before it looks like "Player: ". */
    public static boolean tagged(String line, String tag) {
        int i = line.indexOf(tag);
        return i >= 0 && !line.substring(0, i).contains(": ");
    }

    /** "RARE DROP!", "VERY RARE DROP!", "CRAZY RARE DROP!" ... */
    public static boolean isDropLine(String line) {
        return tagged(line, "DROP!");
    }

    /** A loot-share line about Lord Jawbus specifically. */
    public static boolean isLootShare(String line) {
        return tagged(line, "LOOT SHARE") && line.toLowerCase(Locale.ROOT).contains("lord jawbus");
    }
}
