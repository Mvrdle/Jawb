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
    SILVER_MAGMAFISH("Silver Magmafish", Rarity.EPIC, 1, 1, "silver magmafish"),
    MAGMA_LORD_FRAGMENT("Magma Lord Fragment", Rarity.LEGENDARY, 2, 2, "magma lord fragment"),
    LORD_JAWBUS_SHARD("Lord Jawbus Shard", Rarity.LEGENDARY, 2, 3, "lord jawbus shard"),
    BOBBIN_SCRIPTURES("Bobbin' Scriptures", Rarity.RARE, 3, 4, "bobbin scriptures"),
    CARMINE_DYE("Carmine Dye", Rarity.DIVINE, 3, 6, "carmine dye"),
    RADIOACTIVE_VIAL("Radioactive Vial", Rarity.MYTHIC, 3, 5, "radioactive vial");

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
    private final String key;

    Reward(String displayName, Rarity rarity, int tier, int priority, String key) {
        this.displayName = displayName;
        this.rarity = rarity;
        this.tier = tier;
        this.priority = priority;
        this.key = key;
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
        for (Reward r : values()) if (n.contains(r.key)) return r;
        return null;
    }

    /** Every reward whose name appears in the text. */
    public static List<Reward> findAll(String text) {
        String n = normalize(text);
        List<Reward> out = new ArrayList<>();
        for (Reward r : values()) if (n.contains(r.key)) out.add(r);
        return out;
    }

    /** The highest-priority reward, or null if the collection is empty. */
    public static Reward best(Collection<Reward> rewards) {
        Reward best = null;
        for (Reward r : rewards) if (best == null || r.priority > best.priority) best = r;
        return best;
    }
}
