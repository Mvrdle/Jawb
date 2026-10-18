package com.example.jawbus;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the Lord Jawbus kill count out of the SkyBlock Bestiary menu (SkyBlock Menu > Bestiary > Crimson Isle).
 * Pure Java so it can be tested alone. It only looks at text of an item you have opened yourself; no packets.
 *
 * The exact wording of the Bestiary tooltip is not documented, so the parser is deliberately forgiving: the item's
 * name must be "Lord Jawbus" (optionally with a level suffix, and never the shard), and the first lore line that has
 * the word "Kills" followed by a number is taken as the total.
 */
public final class Bestiary {
    private Bestiary() {
    }

    private static final Pattern KILLS = Pattern.compile("\\bkills\\b[^0-9]{0,8}([0-9][0-9,.]*)", Pattern.CASE_INSENSITIVE);

    /** Strips colour codes (section sign + one char) and trims. */
    static String clean(String s) {
        return s == null ? "" : s.replaceAll("§.", "").trim();
    }

    public static boolean isJawbusEntry(String itemName) {
        String n = clean(itemName).toLowerCase(Locale.ROOT);
        return n.startsWith("lord jawbus") && !n.contains("shard");
    }

    /** The kill total shown on this item, or -1 if the item is not the Lord Jawbus entry or shows no kill count. */
    public static int parseKills(String itemName, List<String> lore) {
        if (!isJawbusEntry(itemName) || lore == null) return -1;
        for (String raw : lore) {
            Matcher m = KILLS.matcher(clean(raw));
            if (m.find()) {
                String digits = m.group(1).replaceAll("[^0-9]", "");
                if (digits.isEmpty() || digits.length() > 9) continue;
                return Integer.parseInt(digits);
            }
        }
        return -1;
    }
}
