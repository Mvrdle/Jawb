package com.example.jawbus;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Settings, saved to .minecraft/config/jawbusslots.json (the only file this mod touches).
 * The mod makes no network connections.
 */
public final class JawbusConfig {
    /** Master switch for real triggers (previews from the menu still work). */
    public boolean enabled = true;
    /** Play the animation for Lord Jawbus loot shares. */
    public boolean lootShare = true;
    /** Size of the whole animation. */
    public float scale = 1.0f;
    /** Length multiplier for the whole animation (2.0 = twice as slow). */
    public float duration = 1.0f;
    /** Overall opacity of everything the mod draws. */
    public float opacity = 1.0f;
    /** Volume of rumbles, cracks and impacts for tiers 1 and 2. */
    public float regularVolume = 1.0f;
    /** Volume of the tier 3 explosion, bass and jackpot sounds. */
    public float jackpotVolume = 1.0f;
    public boolean shakeEnabled = true;
    public float shakeIntensity = 1.0f;
    /** Hide the chat while a Lord Jawbus kill is being resolved / animated, so the reward is not spoiled. */
    public boolean hideChat = true;
    /** Where the animation sits: offset from the screen centre as a fraction of the screen (drag it in the menu). */
    public float offsetX = 0f;
    public float offsetY = 0f;
    /** Build-up speed: how fast each crack stage plays (2.0 = twice as fast, 0.5 = half speed). */
    public float buildSpeed = 1.0f;
    /** Length of the still pauses between stages (0.2 = barely a beat, 2.5 = long suspense). */
    public float pauseScale = 1.0f;
    /** How long the golem holds, almost frozen, right before it pops. */
    public float finalHold = 1.0f;
    /** How long the reward stays on screen after it lands. */
    public float rewardTime = 1.0f;
    /** Animation detail: 0 = low (fastest), 1 = medium, 2 = high. Lower detail merges the golem's colours more coarsely. */
    public int detail = 2;
    /** Chat lines explaining what the mod saw (useful while tuning). */
    public boolean debug = true;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static JawbusConfig instance = new JawbusConfig();

    public static JawbusConfig get() {
        return instance;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("jawbusslots.json");
    }

    public static void load() {
        try {
            Path p = path();
            if (!Files.isRegularFile(p)) return;
            try (Reader reader = Files.newBufferedReader(p)) {
                JawbusConfig c = GSON.fromJson(reader, JawbusConfig.class);
                if (c != null) {
                    c.clamp();
                    instance = c;
                }
            }
        } catch (Exception ignored) {
            // unreadable file: keep defaults
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

    private static float c(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void clamp() {
        scale = c(scale, 0.5f, 1.5f);
        duration = c(duration, 0.6f, 2.0f);
        opacity = c(opacity, 0.2f, 1.0f);
        detail = Math.max(0, Math.min(2, detail));
        buildSpeed = c(buildSpeed, 0.5f, 2.0f);
        pauseScale = c(pauseScale, 0.2f, 2.5f);
        finalHold = c(finalHold, 0.3f, 2.5f);
        rewardTime = c(rewardTime, 0.5f, 3.0f);
        regularVolume = c(regularVolume, 0f, 1.5f);
        jackpotVolume = c(jackpotVolume, 0f, 1.5f);
        shakeIntensity = c(shakeIntensity, 0f, 1.5f);
        offsetX = c(offsetX, -0.45f, 0.45f);
        offsetY = c(offsetY, -0.35f, 0.35f);
    }
}
