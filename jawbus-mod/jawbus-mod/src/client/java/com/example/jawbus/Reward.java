package com.example.jawbus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * The only rewards that can ever be shown. Each maps to one animation tier (1, 2 or 3).
 * Rarities are the SkyBlock ones. Pure Java (no Minecraft classes) so it can be tested on its own.
 */
public enum Reward {
    // chances (percent) are the base drop table from the wiki: normal kill / loot share; NaN = not listed there
    SILVER_MAGMAFISH("Silver Magmafish", Rarity.EPIC, 1, 1, "silver magmafish", 100, 100),
    // tier 1, but priority 2 so it beats the guaranteed drops and actually gets its own animation
    JAWBUS_SHARD("Jawbus Shard", Rarity.RARE, 1, 2, "jawbus shard", Double.NaN, Double.NaN, "jawbuss shard"),
    MAGMA_LORD_FRAGMENT("Magma Lord Fragment", Rarity.LEGENDARY, 2, 2, "magma lord fragment", 100, 20),
    BOBBIN_SCRIPTURES("Bobbin' Scriptures", Rarity.RARE, 3, 4, "bobbin scriptures", 4, Double.NaN),
    CARMINE_DYE("Carmine Dye", Rarity.DIVINE, 3, 6, "carmine dye", 0.0004, Double.NaN),
    RADIOACTIVE_VIAL("Radioactive Vial", Rarity.MYTHIC, 3, 5, "radioactive vial", 0.5, 0.06);

    public enum Rarity {
        EPIC("EPIC", 0xAA00AA),
        LEGENDARY("LEGENDARY", 0xFFAA00),
        RARE("RARE", 0x5555FF),
        MYTHIC("MYTHIC", 0xFF55FF),
        DIVINE("DIVINE", 0xAA0000);   // shown in dark red

        public final String label;
        public final int rgb;

        Rarity(String label, int rgb) {
            this.label = label;
            this.rgb = rgb;
        }
    }

    public final String displayName;
    public final Rarity rarity;
    /** Animation tier: 1 = flop, 2 = rupture, 3 = detonation. */
    public final int tier;
    /** Higher wins when one kill drops several known rewards. */
    public final int priority;
    private final String[] keys;
    private final double killChance, shareChance;

    Reward(String displayName, Rarity rarity, int tier, int priority, String key, double killChance,
           double shareChance, String... extraKeys) {
        this.killChance = killChance;
        this.shareChance = shareChance;
        this.displayName = displayName;
        this.rarity = rarity;
        this.tier = tier;
        this.priority = priority;
        this.keys = new String[extraKeys.length + 1];
        this.keys[0] = key;
        System.arraycopy(extraKeys, 0, this.keys, 1, extraKeys.length);
    }

    /** Base drop chance in percent for a normal kill (NaN when the wiki table does not list it). */
    public double killChance() {
        return killChance;
    }

    /** "0.5%  (1 in 200)" for the normal-kill or loot-share table, or null when the wiki table does not list it. */
    public String chanceLabel(boolean lootShare) {
        double p = lootShare ? shareChance : killChance;
        if (Double.isNaN(p) || p <= 0) return null;
        String pct = p >= 1 ? String.format(Locale.ROOT, "%.0f%%", p) : String.format(Locale.ROOT, "%s%%", trim(p));
        if (p >= 100) return pct;
        return pct + "  (1 in " + String.format(Locale.ROOT, "%,d", Math.round(100.0 / p)) + ")";
    }

    private static String trim(double p) {
        String t = String.format(Locale.ROOT, "%.4f", p);
        t = t.replaceAll("0+$", "");
        return t.endsWith(".") ? t.substring(0, t.length() - 1) : t;
    }

    /** Carmine Dye (the rarest drop, DIVINE) gets the maximum version of tier 3. */
    public boolean maxIntensity() {
        return this == CARMINE_DYE;
    }

    /** Lower-case, no colour codes, no punctuation: "Bobbin' Scriptures" -> "bobbin scriptures". */
    public static String normalize(String s) {
        if (s == null) return "";
        String t = s.replaceAll("§.", "").toLowerCase(Locale.ROOT);
        t = t.replaceAll("[^a-z0-9 ]", "");
        return t.replaceAll("\\s+", " ").trim();
    }

    /** The reward whose name appears in the text, or null. */
    public static Reward match(String text) {
        String n = normalize(text);
        for (Reward r : values()) {
            for (String k : r.keys) if (n.contains(k)) return r;
        }
        return null;
    }

    /** Every reward whose name appears in the text. */
    public static List<Reward> findAll(String text) {
        String n = normalize(text);
        List<Reward> out = new ArrayList<>();
        for (Reward r : values()) {
            for (String k : r.keys) {
                if (n.contains(k)) {
                    out.add(r);
                    break;
                }
            }
        }
        return out;
    }

    /** The highest-priority reward, or null if the collection is empty. */
    public static Reward best(Collection<Reward> rewards) {
        Reward best = null;
        for (Reward r : rewards) if (best == null || r.priority > best.priority) best = r;
        return best;
    }
}
