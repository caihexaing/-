package cn.quietguard.app;

import java.io.*;
import java.net.IDN;
import java.util.*;

public final class RuleSet {
    private final Set<String> blocked = new HashSet<>(), exceptions = new HashSet<>(), exactExceptions = new HashSet<>();
    public RuleSet(InputStream input, String allow) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8))) {
            String line; while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("!")) exceptions.add(normalize(line.substring(1)));
                else if (line.startsWith("~")) exactExceptions.add(normalize(line.substring(1)));
                else blocked.add(normalize(line));
            }
        }
        for (String s : allow.split("[\\s,，]+")) if (!s.trim().isEmpty()) exceptions.add(normalize(s));
    }
    public static String normalize(String s) {
        s = s.trim().toLowerCase(Locale.ROOT);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        s = IDN.toASCII(s, IDN.USE_STD3_ASCII_RULES);
        if (s.isEmpty() || s.length() > 253 || !s.contains(".")) throw new IllegalArgumentException("请输入完整域名，例如 example.com");
        return s;
    }
    private boolean matches(Set<String> set, String name) {
        while (true) { if (set.contains(name)) return true; int dot = name.indexOf('.'); if (dot < 0) return false; name = name.substring(dot + 1); }
    }
    public boolean blocks(String name) {
        try { name = normalize(name); return !exactExceptions.contains(name) && !matches(exceptions, name) && matches(blocked, name); }
        catch (IllegalArgumentException e) { return false; }
    }
    public int size() { return blocked.size(); }
    public int exceptionSize() { return exceptions.size() + exactExceptions.size(); }
}
