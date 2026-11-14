package smassistant;
import com.google.gson.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal client for any OpenAI-compatible chat-completions API
 * (Gemini, OpenAI, Groq, Ollama ...). Temperature is fixed at 0.
 * model=mock gives a keyword baseline so the pipeline can be tested offline.
 */
public class LlmClient {
    /** Parsed answer of the model for one issue. */
    public static class Answer {
        public boolean impediment;
        public String type = "NONE";
        public String reason = "";
        public double confidence;
        public boolean parseError;
        public String raw;
        public long latencyMs;
    }

    private final Config cfg;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    private long lastCall = 0;

    public LlmClient(Config cfg) { this.cfg = cfg; }

    public Answer classify(String system, String user) throws InterruptedException {
        if (cfg.model().equalsIgnoreCase("mock")) return mock(user);
        throttle();
        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.model());
        body.addProperty("temperature", 0);
        // keep the model's internal "thinking" low: faster answers, and the comparison
        // then measures the prompting strategy rather than hidden reasoning (empty = provider default)
        String effort = cfg.get("reasoning_effort", "");
        if (!effort.isEmpty()) body.addProperty("reasoning_effort", effort);
        JsonArray msgs = new JsonArray();
        msgs.add(msg("system", system));
        msgs.add(msg("user", user));
        body.add("messages", msgs);

        HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.baseUrl() + "chat/completions"))
                .timeout(Duration.ofSeconds(cfg.getInt("timeout_seconds", 300)))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + cfg.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();

        for (int attempt = 0; attempt < 60; attempt++) {   // patient: busy servers can last hours
            long t0 = System.currentTimeMillis();
            try {
                HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) {
                    String text = JsonParser.parseString(r.body()).getAsJsonObject()
                            .getAsJsonArray("choices").get(0).getAsJsonObject()
                            .getAsJsonObject("message").get("content").getAsString();
                    Answer a = parse(text);
                    a.latencyMs = System.currentTimeMillis() - t0;
                    return a;
                }
                if (r.statusCode() == 400 || r.statusCode() == 401 || r.statusCode() == 403 || r.statusCode() == 404)
                    throw new IllegalStateException("API error " + r.statusCode() + ": check api.key / model in config.properties\n" + r.body());
                String msg = r.body().replaceAll("\\s+", " ");
                // show which quota was hit (per minute or per day) instead of only the start of the message
                int q = msg.indexOf("Quota exceeded for metric");
                String shown = q >= 0 ? msg.substring(q, Math.min(msg.length(), q + 400))
                                      : msg.substring(0, Math.min(300, msg.length()));
                System.out.println("    API HTTP " + r.statusCode() + " (rate limit or server busy), waiting ... " + shown);
            } catch (IOException e) {
                System.out.println("    network error: " + e.getMessage());
            }
            Thread.sleep(Math.min(300_000L, (long) Math.pow(2, Math.min(attempt, 10)) * 5000));
        }
        throw new IllegalStateException("LLM API failed after retries");
    }

    private void throttle() throws InterruptedException {
        long gap = 60_000L / Math.max(1, cfg.rpm());
        long wait = lastCall + gap - System.currentTimeMillis();
        if (wait > 0) Thread.sleep(wait);
        lastCall = System.currentTimeMillis();
    }

    private static JsonObject msg(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    /** Extract the first {...} block and read the fields; tolerant of code fences. */
    static Answer parse(String text) {
        Answer a = new Answer();
        a.raw = text;
        Matcher m = Pattern.compile("\\{.*\\}", Pattern.DOTALL).matcher(text);
        if (!m.find()) { a.parseError = true; return a; }
        try {
            JsonObject o = JsonParser.parseString(m.group()).getAsJsonObject();
            JsonElement imp = o.get("impediment");
            a.impediment = imp != null && (imp.getAsJsonPrimitive().isBoolean() ? imp.getAsBoolean()
                    : imp.getAsString().trim().toLowerCase().startsWith("t") || imp.getAsString().trim().toLowerCase().startsWith("y"));
            if (o.has("type")) a.type = o.get("type").getAsString().trim().toUpperCase();
            if (o.has("reason")) a.reason = o.get("reason").getAsString();
            if (o.has("confidence")) a.confidence = o.get("confidence").getAsDouble();
        } catch (RuntimeException e) {
            a.parseError = true;
        }
        return a;
    }

    /** Offline keyword baseline: used only to test the pipeline without an API key. */
    private static Answer mock(String user) {
        String issue = user.substring(user.lastIndexOf("Jira issue:")).toLowerCase();
        Answer a = new Answer();
        a.impediment = issue.matches("(?s).*(blocked|blocker|waiting for|wait for|depends on|until .* (is|are) (released|merged|fixed)).*");
        a.type = a.impediment ? (issue.contains("release") ? "EXTERNAL" : "DEPENDENCY") : "NONE";
        a.reason = "keyword baseline";
        a.confidence = 0.5;
        a.raw = "{mock}";
        return a;
    }
}
