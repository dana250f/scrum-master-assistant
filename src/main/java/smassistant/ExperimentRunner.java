package smassistant;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * STEP 2 - Run every prompting strategy on every issue.
 *
 * Usage (program arguments in IntelliJ):
 *   Set "limit" in config.properties: 20 = pilot run on the first 20 issues, 0 = all issues.
 *   (A program argument, if given, overrides the config value.)
 *
 * Results are appended to results/predictions.jsonl. The run can be stopped
 * and restarted at any time: issues already answered are skipped.
 */
public class ExperimentRunner {
    static final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    /** One line of results/predictions.jsonl. */
    public static class Prediction {
        public String key, project, issueType, strategy, model;
        public int label;
        public boolean predicted;
        public String predictedType, reason, raw;
        public double confidence;
        public boolean parseError;
        public long latencyMs;
    }

    public static void main(String[] args) throws Exception {
        Config cfg = Config.load();
        List<Issue> issues = loadDataset();
        // limit from program argument, or from "limit" in config.properties (0 = all issues)
        int limit = args.length > 0 ? Integer.parseInt(args[0]) : cfg.getInt("limit", 0);
        if (limit <= 0) limit = issues.size();
        issues = issues.subList(0, Math.min(limit, issues.size()));

        Path out = Path.of("results/predictions.jsonl");
        Files.createDirectories(out.getParent());
        Set<String> done = new HashSet<>();
        if (Files.exists(out))
            for (String line : Files.readAllLines(out, StandardCharsets.UTF_8))
                if (!line.isBlank()) {
                    Prediction p = gson.fromJson(line, Prediction.class);
                    done.add(p.model + "|" + p.strategy + "|" + p.key);
                }

        LlmClient llm = new LlmClient(cfg);
        int total = issues.size() * Prompts.Strategy.values().length, n = 0;
        System.out.println("Model: " + cfg.model() + " | issues: " + issues.size() + " | calls: " + total);

        try (var w = Files.newBufferedWriter(out, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            for (Issue issue : issues) {
                for (Prompts.Strategy s : Prompts.Strategy.values()) {
                    n++;
                    if (done.contains(cfg.model() + "|" + s + "|" + issue.key)) continue;
                    LlmClient.Answer a = llm.classify(Prompts.system(s), Prompts.user(s, issue));
                    Prediction p = new Prediction();
                    p.key = issue.key; p.project = issue.project; p.issueType = issue.issueType;
                    p.strategy = s.name(); p.model = cfg.model(); p.label = issue.label;
                    p.predicted = a.impediment; p.predictedType = a.type; p.reason = a.reason;
                    p.confidence = a.confidence; p.parseError = a.parseError; p.raw = a.raw; p.latencyMs = a.latencyMs;
                    w.write(gson.toJson(p)); w.newLine(); w.flush();
                    System.out.printf("[%d/%d] %-10s %-14s truth=%s predicted=%s%s%n", n, total, s, issue.key,
                            issue.label == 1 ? "BLOCKED" : "ok", a.impediment ? "BLOCKED" : "ok",
                            a.parseError ? "  (parse error)" : "");
                }
            }
        }
        System.out.println("Done. Now run Evaluator.");
    }

    static List<Issue> loadDataset() throws Exception {
        Path p = Path.of("data/dataset.jsonl");
        if (!Files.exists(p)) throw new IllegalStateException("data/dataset.jsonl not found: run DataCollector first");
        List<Issue> list = new ArrayList<>();
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8))
            if (!line.isBlank()) list.add(gson.fromJson(line, Issue.class));
        return list;
    }
}
