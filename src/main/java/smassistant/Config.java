package smassistant;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** Reads config.properties from the project folder. */
public final class Config {
    private final Properties p = new Properties();

    public Config(String path) {
        try (var in = new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8)) {
            p.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + path + " (run from the project folder)", e);
        }
    }

    public static Config load() { return new Config("config.properties"); }

    public String get(String key, String def) { return p.getProperty(key, def).trim(); }
    public int getInt(String key, int def) { return Integer.parseInt(get(key, String.valueOf(def))); }

    public String apiKey()   { return get("api.key", ""); }
    public String baseUrl()  { String u = get("api.base_url", ""); return u.endsWith("/") ? u : u + "/"; }
    public String model()    { return get("model", "mock"); }
    public int rpm()         { return getInt("requests_per_minute", 8); }
    public int perClass()    { return getInt("per_class", 100); }
}
