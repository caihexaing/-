package cn.quietguard.app;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

final class UpdateChecker {
    static final String REPO = "caihexaing/-";
    static final String CURRENT = BuildConfig.VERSION_NAME;
    static final class Release {
        final String version, notes, apkUrl;
        Release(String v, String n, String u) { version = v; notes = n; apkUrl = u; }
    }
    static Release check() throws Exception {
        Release best = null;
        // Pagination avoids unrelated app releases hiding QuietGuard's newest release.
        for (int page = 1; page <= 5; page++) {
            JSONArray releases = new JSONArray(fetch("https://api.github.com/repos/" + REPO + "/releases?per_page=100&page=" + page));
            for (int i = 0; i < releases.length(); i++) {
                JSONObject r = releases.getJSONObject(i);
                if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue;
                String v = UpdateVersion.fromTag(r.optString("tag_name"));
                if (v == null || UpdateVersion.compare(v, CURRENT) <= 0 || (best != null && UpdateVersion.compare(v, best.version) <= 0)) continue;
                JSONArray assets = r.optJSONArray("assets"); if (assets == null) continue;
                for (int j = 0; j < assets.length(); j++) {
                    JSONObject asset = assets.getJSONObject(j);
                    if (!asset.optString("name").equals("QuietGuard-" + v + ".apk") || asset.optLong("size") <= 0) continue;
                    String url = asset.optString("browser_download_url");
                    URI uri = new URI(url);
                    if (!"https".equals(uri.getScheme()) || !"github.com".equals(uri.getHost()) || !uri.getPath().startsWith("/" + REPO + "/releases/download/")) continue;
                    String notes = r.optString("body", "新版本已发布");
                    best = new Release(v, notes.substring(0, Math.min(notes.length(), 4000)), url);
                }
            }
            if (releases.length() < 100) break;
            if (page == 5) throw new IOException("发布记录过多，请前往仓库检查版本");
        }
        return best;
    }
    private static String fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(10000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        c.setRequestProperty("User-Agent", "QuietGuard/" + CURRENT);
        try {
            int code = c.getResponseCode();
            if (code == 403 || code == 429) throw new IOException("GitHub 请求受限，请稍后再试");
            if (code != 200) throw new IOException("GitHub 返回 HTTP " + code);
            try (InputStream input = c.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = input.read(buffer)) != -1) { if (output.size() + n > 4 * 1024 * 1024) throw new IOException("版本信息过大"); output.write(buffer, 0, n); }
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally { c.disconnect(); }
    }
}
