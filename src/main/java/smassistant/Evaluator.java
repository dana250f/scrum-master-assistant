package smassistant;

import com.google.gson.Gson;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * STEP 3 - Compute the results.
 *
 *  results/results.md      summary tables for the report (RQ1-RQ4)
 *  results/metrics.csv     precision / recall / F1 / accuracy per strategy
 *  results/types.csv       predicted impediment types among flagged issues
 *  results/errors.csv      misclassified issues for qualitative analysis
 */

public class Evaluator {
    static final Gson gson = new Gson();

    record Metrics(String strategy, int tp, int fp, int fn, int tn, int parseErrors, double avgLatency) {
        int n() { return tp + fp + fn + tn; }
        double precision() { return tp + fp == 0 ? 0 : (double) tp / (tp + fp); }
        double recall()    { return tp + fn == 0 ? 0 : (double) tp / (tp + fn); }
        double f1()        { double p = precision(), r = recall(); return p + r == 0 ? 0 : 2 * p * r / (p + r); }
        double accuracy()  { return n() == 0 ? 0 : (double) (tp + tn) / n(); }
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);   // always print 0.736, not Arabic digits
        String model = Config.load().model();
        List<ExperimentRunner.Prediction> all = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of("results/predictions.jsonl"), StandardCharsets.UTF_8))
            if (!line.isBlank()) {
                var p = gson.fromJson(line, ExperimentRunner.Prediction.class);
                if (p.model.equals(model)) all.add(p);
            }
        Map<String, List<ExperimentRunner.Prediction>> byStrategy = new LinkedHashMap<>();
        for (Prompts.Strategy s : Prompts.Strategy.values()) byStrategy.put(s.name(), new ArrayList<>());
        for (var p : all) byStrategy.get(p.strategy).add(p);

        // keep only issues answered by ALL strategies so the comparison is fair
        Set<String> common = null;
        for (var list : byStrategy.values()) {
            Set<String> keys = list.stream().map(p -> p.key).collect(Collectors.toSet());
            if (common == null) common = keys; else common.retainAll(keys);
        }
        final Set<String> keys = common;
        byStrategy.replaceAll((s, list) -> list.stream().filter(p -> keys.contains(p.key)).toList());

        List<Metrics> metrics = new ArrayList<>();
        for (var e : byStrategy.entrySet()) metrics.add(metricsOf(e.getKey(), e.getValue()));

        Files.createDirectories(Path.of("results"));
        try (var md = new PrintWriter(Files.newBufferedWriter(Path.of("results/results.md"), StandardCharsets.UTF_8));
             var csv = new PrintWriter(Files.newBufferedWriter(Path.of("results/metrics.csv"), StandardCharsets.UTF_8))) {

            long blocked = byStrategy.values().iterator().next().stream().filter(p -> p.label == 1).count();
            md.printf("# Results (model: %s)%n%nIssues evaluated: %d (%d blocked, %d not blocked)%n%n",
                    model, keys.size(), blocked, keys.size() - blocked);

            // ---- RQ1 / RQ2: detection performance
            md.println("## RQ1-RQ2: Detection performance per prompting strategy\n");
            md.println("| Strategy | Precision | Recall | F1 | Accuracy | TP | FP | FN | TN | Parse errors | Avg latency (s) |");
            md.println("|---|---|---|---|---|---|---|---|---|---|---|");
            csv.println("strategy,precision,recall,f1,accuracy,tp,fp,fn,tn,parse_errors,avg_latency_s");
            for (Metrics m : metrics) {
                md.printf("| %s | %.3f | %.3f | %.3f | %.3f | %d | %d | %d | %d | %d | %.1f |%n", m.strategy(),
                        m.precision(), m.recall(), m.f1(), m.accuracy(), m.tp(), m.fp(), m.fn(), m.tn(),
                        m.parseErrors(), m.avgLatency() / 1000);
                csv.printf(Locale.ROOT, "%s,%.4f,%.4f,%.4f,%.4f,%d,%d,%d,%d,%d,%.2f%n", m.strategy(), m.precision(),
                        m.recall(), m.f1(), m.accuracy(), m.tp(), m.fp(), m.fn(), m.tn(), m.parseErrors(), m.avgLatency() / 1000);
            }


            // ---- recall by Jira issue type (which issues are hard?)
            md.println("\n## Recall by Jira issue type (blocked issues only)\n");
            md.println("| Strategy | " + issueTypes(byStrategy).stream().collect(Collectors.joining(" | ")) + " |");
            md.println("|---|" + "---|".repeat(issueTypes(byStrategy).size()));
            for (var e : byStrategy.entrySet()) {
                List<String> cells = new ArrayList<>();
                for (String t : issueTypes(byStrategy)) {
                    var sub = e.getValue().stream().filter(p -> p.label == 1 && t.equals(p.issueType)).toList();
                    long hit = sub.stream().filter(p -> p.predicted).count();
                    cells.add(sub.isEmpty() ? "-" : String.format("%.2f (%d/%d)", (double) hit / sub.size(), hit, sub.size()));
                }
                md.println("| " + e.getKey() + " | " + String.join(" | ", cells) + " |");
            }

            extraTables(md, byStrategy);

            // ---- hard cases: missed or false alarms by every strategy
            Map<String, Integer> wrongCount = new HashMap<>();
            for (var list : byStrategy.values()) for (var p : list) if (p.predicted != (p.label == 1)) wrongCount.merge(p.key, 1, Integer::sum);
            long allWrong = wrongCount.values().stream().filter(c -> c == byStrategy.size()).count();
            md.printf("%n## Hard cases%n%nIssues misclassified by all %d strategies: %d (listed in errors.csv with all_wrong=true)%n",
                    byStrategy.size(), allWrong);

            try (var ecsv = new PrintWriter(Files.newBufferedWriter(Path.of("results/errors.csv"), StandardCharsets.UTF_8))) {
                ecsv.println("key,strategy,truth,predicted,predicted_type,all_wrong,reason");
                for (var list : byStrategy.values())
                    for (var p : list)
                        if (p.predicted != (p.label == 1))
                            ecsv.printf("%s,%s,%s,%s,%s,%s,\"%s\"%n", p.key, p.strategy, p.label == 1 ? "blocked" : "not_blocked",
                                    p.predicted ? "blocked" : "not_blocked", normType(p),
                                    wrongCount.get(p.key) == byStrategy.size(), p.reason == null ? "" : p.reason.replace("\"", "'"));
            }
        }
        System.out.println(Files.readString(Path.of("results/results.md")));
        System.out.println("Saved results/results.md, metrics.csv, types.csv, errors.csv");
    }

    static Metrics metricsOf(String strategy, List<ExperimentRunner.Prediction> list) {
        int tp = 0, fp = 0, fn = 0, tn = 0, pe = 0;
        long lat = 0;
        for (var p : list) {
            if (p.parseError) pe++;   // parse errors count as "not blocked" (conservative)
            boolean pred = p.predicted && !p.parseError;
            if (p.label == 1 && pred) tp++;
            else if (p.label == 0 && pred) fp++;
            else if (p.label == 1) fn++;
            else tn++;
            lat += p.latencyMs;
        }
        return new Metrics(strategy, tp, fp, fn, tn, pe, list.isEmpty() ? 0 : (double) lat / list.size());
    }

    static List<String> typeNames() {
        return List.of("DEPENDENCY", "EXTERNAL", "TECHNICAL", "REQUIREMENTS", "REVIEW", "RESOURCE", "NONE", "OTHER");
    }

    static String normType(ExperimentRunner.Prediction p) {
        if (!p.predicted || p.parseError) return "NONE";
        String t = p.predictedType == null ? "" : p.predictedType.replaceAll("[^A-Z]", "");
        return typeNames().contains(t) && !t.equals("NONE") ? t : "OTHER";
    }

    static List<String> issueTypes(Map<String, List<ExperimentRunner.Prediction>> by) {
        return by.values().iterator().next().stream().filter(p -> p.label == 1).map(p -> p.issueType)
                .distinct().sorted().toList();
    }

    /** Report Tables 5 and 6: predicted types among flagged issues, and recall by written evidence / comments. */
    static void extraTables(PrintWriter md, Map<String, List<ExperimentRunner.Prediction>> byStrategy) throws Exception {
        // ---- Table 5: predicted types among ALL flagged issues (TP + FP)
        md.println("\n## RQ4: Predicted impediment types among flagged issues\n");
        md.println("| Strategy | " + String.join(" | ", typeNames().subList(0, 6)) + " | Total |");
        md.println("|---|" + "---|".repeat(7));
        try (var tcsv = new PrintWriter(Files.newBufferedWriter(Path.of("results/types.csv"), StandardCharsets.UTF_8))) {
            tcsv.println("strategy," + String.join(",", typeNames().subList(0, 6)) + ",total");
            for (var e : byStrategy.entrySet()) {
                Map<String, Long> c = e.getValue().stream().filter(p -> p.predicted && !p.parseError)
                        .collect(Collectors.groupingBy(p -> normType(p), Collectors.counting()));
                long total = c.values().stream().mapToLong(Long::longValue).sum();
                List<String> cells = typeNames().subList(0, 6).stream().map(t -> String.valueOf(c.getOrDefault(t, 0L))).toList();
                md.println("| " + e.getKey() + " | " + String.join(" | ", cells) + " | " + total + " |");
                tcsv.println(e.getKey() + "," + String.join(",", cells) + "," + total);
            }
        }

        // ---- Table 6: recall on blocked issues by written evidence and number of comments
        Map<String, com.google.gson.JsonObject> data = new HashMap<>();
        for (String line : Files.readAllLines(Path.of("data/dataset.jsonl"), StandardCharsets.UTF_8))
            if (!line.isBlank()) {
                var o = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
                data.put(o.get("key").getAsString(), o);
            }
        String[] groups = {"Blocker key written in the text", "Blocker recorded only as a link",
                           "Three or more comments", "Fewer than three comments"};
        md.println("\n## Recall on blocked issues by written evidence and number of comments\n");
        md.println("| Strategy | " + String.join(" | ", groups) + " |");
        md.println("|---|---|---|---|---|");
        for (var e : byStrategy.entrySet()) {
            int[] hit = new int[4], n = new int[4];
            for (var p : e.getValue()) {
                if (p.label != 1) continue;
                var o = data.get(p.key);
                var comments = o.getAsJsonArray("comments");
                StringBuilder text = new StringBuilder();
                text.append(o.get("title").getAsString()).append(' ').append(o.get("description").getAsString());
                for (var c : comments) text.append(' ').append(c.getAsString());
                boolean written = false;
                for (var b : o.getAsJsonArray("blockedBy"))
                    if (text.toString().contains(b.getAsString())) written = true;
                int g1 = written ? 0 : 1;
                int g2 = comments.size() >= 3 ? 2 : 3;
                n[g1]++; n[g2]++;
                if (p.predicted && !p.parseError) { hit[g1]++; hit[g2]++; }
            }
            StringBuilder row = new StringBuilder("| " + e.getKey());
            for (int g = 0; g < 4; g++)
                row.append(String.format(Locale.ROOT, " | %.2f (%d/%d)", n[g] == 0 ? 0.0 : (double) hit[g] / n[g], hit[g], n[g]));
            md.println(row + " |");
        }
    }
}
