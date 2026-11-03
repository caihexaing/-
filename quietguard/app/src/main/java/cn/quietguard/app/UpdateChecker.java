package cn.quietguard.app;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

final class UpdateChecker {
    static final String REPO = "caihexaing/-";
    static final String CURRENT = BuildConfig.VERSION_NAME;
    static final class Release {
        final String version, notes, apkUrl, sha256;
        final long versionCode;
        Release(String v, long code, String n, String u, String hash) { version = v; versionCode = code; notes = n; apkUrl = u; sha256 = hash; }
    }
    static Release check() throws Exception {
        JSONObject feed = new JSONObject(fetch("https://raw.githubusercontent.com/" + REPO + "/master/quietguard/update.json?check=" + System.currentTimeMillis()));
        if (!"cn.quietguard.app".equals(feed.getString("applicationId"))) throw new IOException("版本信息与应用不匹配");
        String version = feed.getString("versionName");
        if (!UpdateVersion.newer(version, feed.getLong("versionCode"), CURRENT, BuildConfig.VERSION_CODE)) return null;
        String url = feed.getString("apkUrl");
        if (!UpdateIntegrity.validApkUrl(REPO, version, url))
            throw new IOException("更新下载地址无效");
        String notes = feed.optString("notes", "新版本已发布");
        String hash = feed.getString("sha256");
        if (!UpdateIntegrity.validHash(hash)) throw new IOException("更新信息缺少有效的文件校验值");
        return new Release(version, feed.getLong("versionCode"), notes.substring(0, Math.min(notes.length(), 4000)), url, hash);
    }
    private static String fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(10000);
        c.setRequestProperty("Accept", "application/json");
        c.setUseCaches(false);
        c.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0");
        c.setRequestProperty("User-Agent", "QuietGuard/" + CURRENT);
        try {
            int code = c.getResponseCode();
            if (code == 403 || code == 429) throw new IOException("GitHub 请求受限，请稍后再试");
            if (code != 200) throw new IOException("GitHub 返回 HTTP " + code);
            try (InputStream input = c.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = input.read(buffer)) != -1) { if (output.size() + n > 128 * 1024) throw new IOException("版本信息过大"); output.write(buffer, 0, n); }
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally { c.disconnect(); }
    }
}
