package com.example.jawbus;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lifetime counters, saved to .minecraft/config/jawbusslots_stats.json. Only real kills and real drops are counted
 * (previews and /jawbusRoll simulate never touch it). Nothing is estimated: a kill is counted when a Lord Jawbus name
 * plate disappears near you, a drop when its chat line / inventory change was matched to a kill or to a loot share.
 */
public final class Stats {
    public int kills;
    public int lootShares;
    /** Drops from your own kills, by Reward enum name. */
    public Map<String, Integer> drops = new LinkedHashMap<>();
    /** Drops received through loot shares, by Reward enum name. */
    public Map<String, Integer> shareDrops = new LinkedHashMap<>();
    /** Kills since each reward last dropped from one of your own kills. */
    public Map<String, Integer> since = new LinkedHashMap<>();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Stats instance = new Stats();

    public static Stats get() {
        return instance;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("jawbusslots_stats.json");
    }

    public static void load() {
        try {
            Path p = path();
            if (!Files.isRegularFile(p)) return;
            try (Reader reader = Files.newBufferedReader(p)) {
                Stats s = GSON.fromJson(reader, Stats.class);
                if (s != null) {
                    if (s.drops == null) s.drops = new LinkedHashMap<>();
                    if (s.shareDrops == null) s.shareDrops = new LinkedHashMap<>();
                    if (s.since == null) s.since = new LinkedHashMap<>();
                    instance = s;
                }
            }
        } catch (Exception ignored) {
            // unreadable file: start fresh in memory (the old file is only replaced on the next change)
        }
    }

    public static void save() {
        try {
            Path p = path();
            Files.createDirectories(p.getParent());
            try (Writer w = Files.newBufferedWriter(p)) {
                GSON.toJson(instance, w);
            }
        } catch (Exception ignored) {
            // not worth crashing over
        }
    }

    public static void reset() {
        instance = new Stats();
        save();
    }

    /** One of your own Lord Jawbus kills was detected. */
    public static void recordKill() {
        Stats s = instance;
        s.kills++;
        for (Reward r : Reward.values()) s.since.merge(r.name(), 1, Integer::sum);
        save();
    }

    /** A reward was matched to one of your kills (lootShare = false) or arrived through a loot share (true). */
    public static void recordDrop(Reward r, boolean lootShare) {
        Stats s = instance;
        if (lootShare) {
            s.lootShares++;
            s.shareDrops.merge(r.name(), 1, Integer::sum);
        } else {
            s.drops.merge(r.name(), 1, Integer::sum);
            s.since.put(r.name(), 0);
        }
        save();
    }

    public int dropCount(Reward r) {
        return drops.getOrDefault(r.name(), 0);
    }

    public int shareCount(Reward r) {
        return shareDrops.getOrDefault(r.name(), 0);
    }

    /** Kills since this reward last dropped (or all kills if it never has). */
    public int killsSince(Reward r) {
        return since.getOrDefault(r.name(), kills);
    }
}
