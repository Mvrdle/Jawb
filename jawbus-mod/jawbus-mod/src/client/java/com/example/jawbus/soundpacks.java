package com.example.jawbus;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Optional sound pack. .minecraft/config/jawbroll_sounds.json maps a cue slot to any sound id, so players can
 * swap the mod's sounds for vanilla ones, another mod's, or their own from a resource pack.
 * A blank or missing entry keeps the built-in sound.
 */
final class SoundPacks {
    private SoundPacks() {
    }

    /** Every cue the mod can play. Point a slot at a sound id to override it. */
    static final String[] SLOTS = {
            "rumble", "impact", "burst", "chime", "blast", "levelup", "bell",
            "orb", "twinkle", "thunder", "golem", "golem_hurt", "resonate",
            "crystal", "glass", "conduit", "totem", "sonic", "growl", "portal", "toast"
    };

    private static final Map<String, SoundEvent> OVERRIDE = new LinkedHashMap<>();

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("jawbroll_sounds.json");
    }

    static void load() {
        OVERRIDE.clear();
        Path p = path();
        try {
            if (!Files.isRegularFile(p)) {
                writeTemplate(p);
                return;
            }
            try (Reader r = Files.newBufferedReader(p)) {
                Map<String, String> map = new Gson().fromJson(r, new TypeToken<Map<String, String>>() {
                }.getType());
                if (map == null) return;
                for (Map.Entry<String, String> e : map.entrySet()) {
                    SoundEvent ev = resolve(e.getValue());
                    if (ev != null) OVERRIDE.put(e.getKey(), ev);
                }
            }
        } catch (Throwable ignored) {
            // a broken file just means built-in sounds
        }
    }

    /** null when this slot should use the mod's built-in sound. */
    static SoundEvent override(String slot) {
        return OVERRIDE.get(slot);
    }

    static String current(String slot) {
        SoundEvent ev = OVERRIDE.get(slot);
        return ev == null ? "(built in)" : ev.location().toString();
    }

    private static SoundEvent resolve(String id) {
        if (id == null || id.isBlank()) return null;
        try {
            Identifier rl = Identifier.tryParse(id.trim());
            if (rl == null) return null;
            return BuiltInRegistries.SOUND_EVENT.get(rl);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeTemplate(Path p) {
        try {
            Files.createDirectories(p.getParent());
            Map<String, String> map = new LinkedHashMap<>();
            map.put("_comment", "sound id per cue slot. leave blank for the built-in sound. e.g. minecraft:block.anvil.land");
            for (String s : SLOTS) map.put(s, "");
            try (Writer w = Files.newBufferedWriter(p)) {
                new GsonBuilder().setPrettyPrinting().create().toJson(map, w);
            }
        } catch (Throwable ignored) {
        }
    }
}