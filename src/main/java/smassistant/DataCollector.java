package smassistant;
import com.google.gson.*;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/**
 * STEP 1 - Build the evaluation dataset from the public Apache Jira.
 *
 * Apache Jira (issues.apache.org/jira) is one of the public repositories in the
 * Montgomery et al. (MSR 2022) Jira dataset. We query it through its REST API.
 *
 *   Blocked (label 1):     resolved issues with an inward "is blocked by" link.
 *   Not blocked (label 0): resolved issues from the SAME project and SAME year, with no Blocker link.
 *   Both: created 2021-2026, non-empty description, at least one comment.
 *
 * Output: data/dataset.jsonl
 */
public class DataCollector {
    static final String JIRA = "https://issues.apache.org/jira/rest/api/2/search";
    static final String FIELDS = "summary,description,comment,issuelinks,issuetype,project,priority,created";
    static final String DATE_RANGE = "created >= \"2021-01-01\"";
    static final int MAX_TEXT_CHARS = 6000;   // keep prompts a reasonable size
    static final int MAX_PER_PROJECT = 8;     // diversity across projects
    static final long SEED = 42;

    static final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    static final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    public static void main(String[] args) throws Exception {
        Config cfg = Config.load();
        int perClass = args.length > 0 ? Integer.parseInt(args[0]) : cfg.perClass();
        Random rng = new Random(SEED);

        System.out.println("Fetching candidate BLOCKED issues ...");
        String jqlPos = "issueLinkType = \"is blocked by\" AND resolution is not EMPTY AND " + DATE_RANGE + " ORDER BY created DESC";
        List<JsonObject> pool = new ArrayList<>();
        for (JsonObject it : fetch(jqlPos, perClass * 15))
            if (eligible(it) && !blockedBy(it).isEmpty()) pool.add(it);
        System.out.println("  eligible blocked candidates: " + pool.size());
        List<JsonObject> positives = balancedSample(pool, perClass, rng);

        // negatives: matched to the positives by project AND year, so the two classes
        // do not differ in project or age (otherwise date alone could separate them)
        Map<String, Integer> perGroup = new LinkedHashMap<>();
        for (JsonObject it : positives) perGroup.merge(projectKey(it) + "|" + year(it), 1, Integer::sum);
        System.out.println("Fetching NOT-blocked issues matched by project and year (" + perGroup.size() + " groups) ...");
        List<JsonObject> negatives = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (var e : perGroup.entrySet()) {
            String project = e.getKey().split("\\|")[0];
            int y = Integer.parseInt(e.getKey().split("\\|")[1]);
            String jql = "project = \"" + project + "\" AND resolution is not EMPTY AND created >= \"" + y
                    + "-01-01\" AND created < \"" + (y + 1) + "-01-01\" ORDER BY created DESC";
            List<JsonObject> cands = new ArrayList<>();
            for (JsonObject it : fetch(jql, 150)) if (eligible(it) && hasNoBlockerLink(it)) cands.add(it);
            Collections.shuffle(cands, rng);
            for (JsonObject it : cands.subList(0, Math.min(e.getValue(), cands.size()))) {
                negatives.add(it); used.add(it.get("key").getAsString());
            }
            System.out.println("  " + e.getKey() + ": " + Math.min(e.getValue(), cands.size()) + "/" + e.getValue());
        }
        if (negatives.size() < perClass) {   // top up from the same projects (any year in range)
            String projects = String.join(",", perGroup.keySet().stream().map(k -> "\"" + k.split("\\|")[0] + "\"").distinct().toList());
            String jql = "project in (" + projects + ") AND resolution is not EMPTY AND " + DATE_RANGE;
            List<JsonObject> extra = new ArrayList<>();
            for (JsonObject it : fetch(jql, 1000))
                if (!used.contains(it.get("key").getAsString()) && eligible(it) && hasNoBlockerLink(it)) extra.add(it);
            Collections.shuffle(extra, rng);
            negatives.addAll(extra.subList(0, Math.min(perClass - negatives.size(), extra.size())));
        }

        List<Issue> records = new ArrayList<>();
        for (JsonObject it : positives) records.add(toIssue(it, 1));
        for (JsonObject it : negatives) records.add(toIssue(it, 0));
        Collections.shuffle(records, rng);

        Files.createDirectories(Path.of("data"));
        try (var w = Files.newBufferedWriter(Path.of("data/dataset.jsonl"), StandardCharsets.UTF_8)) {
            for (Issue r : records) { w.write(gson.toJson(r)); w.newLine(); }
        }
        writeCsv(records, Path.of("data/dataset.csv"));
        long pos = records.stream().filter(r -> r.label == 1).count();
        System.out.printf("Saved %d issues (%d blocked, %d not blocked) to data/dataset.jsonl%n",
                records.size(), pos, records.size() - pos);
    }

    // ---------------------------------------------------------------- Jira API
    static JsonObject search(String jql, int startAt) throws InterruptedException {
        String url = JIRA + "?jql=" + enc(jql) + "&startAt=" + startAt + "&maxResults=50&fields=" + enc(FIELDS);
        for (int attempt = 0; attempt < 8; attempt++) {
            try {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(60)).header("Accept", "application/json").GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) return JsonParser.parseString(r.body()).getAsJsonObject();
                System.out.println("  Jira HTTP " + r.statusCode() + ", retrying");
            } catch (IOException e) {
                System.out.println("  network error: " + e.getMessage() + ", retrying");
            }
            Thread.sleep(Math.min(60000L, (long) Math.pow(2, attempt) * 3000));
        }
        throw new IllegalStateException("Jira API not reachable after retries");
    }

    static List<JsonObject> fetch(String jql, int limit) throws InterruptedException {
        List<JsonObject> out = new ArrayList<>();
        int start = 0;
        while (out.size() < limit) {
            JsonObject data = search(jql, start);
            JsonArray issues = data.getAsJsonArray("issues");
            if (issues == null || issues.isEmpty()) break;
            for (JsonElement e : issues) out.add(e.getAsJsonObject());
            start += issues.size();
            if (start >= data.get("total").getAsInt()) break;
            Thread.sleep(1000); // be polite to the public server
        }
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    // ---------------------------------------------------------------- helpers
    static JsonObject fields(JsonObject it) { return it.getAsJsonObject("fields"); }
    static int year(JsonObject it) { return Integer.parseInt(str(fields(it), "created").substring(0, 4)); }
    static String projectKey(JsonObject it) { return fields(it).getAsJsonObject("project").get("key").getAsString(); }

    static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return (e == null || e.isJsonNull()) ? "" : e.getAsString();
    }

    static String nameOf(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return (e == null || e.isJsonNull()) ? "" : str(e.getAsJsonObject(), "name");
    }

    static boolean isBlockerType(JsonObject link) {
        JsonObject t = link.getAsJsonObject("type");
        String all = (str(t, "name") + str(t, "inward") + str(t, "outward")).toLowerCase();
        return all.contains("block");
    }

    static List<String> blockedBy(JsonObject it) {
        List<String> keys = new ArrayList<>();
        JsonElement links = fields(it).get("issuelinks");
        if (links == null || links.isJsonNull()) return keys;
        for (JsonElement l : links.getAsJsonArray()) {
            JsonObject link = l.getAsJsonObject();
            if (isBlockerType(link) && link.has("inwardIssue"))
                keys.add(link.getAsJsonObject("inwardIssue").get("key").getAsString());
        }
        return keys;
    }

    static boolean hasNoBlockerLink(JsonObject it) {
        JsonElement links = fields(it).get("issuelinks");
        if (links == null || links.isJsonNull()) return true;
        for (JsonElement l : links.getAsJsonArray()) if (isBlockerType(l.getAsJsonObject())) return false;
        return true;
    }

    static List<String> comments(JsonObject it) {
        List<String> out = new ArrayList<>();
        JsonElement c = fields(it).get("comment");
        if (c == null || c.isJsonNull()) return out;
        for (JsonElement e : c.getAsJsonObject().getAsJsonArray("comments")) {
            String body = clean(str(e.getAsJsonObject(), "body"));
            if (!body.isEmpty()) out.add(body);
        }
        return out;
    }

    static boolean eligible(JsonObject it) {
        return !clean(str(fields(it), "description")).isEmpty() && !comments(it).isEmpty();
    }

    /** Strip code/log blocks and user mentions; collapse whitespace. */
    static String clean(String t) {
        if (t == null) return "";
        t = t.replaceAll("(?s)\\{code[^}]*\\}.*?\\{code\\}", "[code block removed]");
        t = t.replaceAll("(?s)\\{noformat\\}.*?\\{noformat\\}", "[log removed]");
        t = t.replaceAll("\\[~[^\\]]+\\]", "@user");
        return t.replaceAll("\\s+", " ").trim();
    }

    static List<JsonObject> balancedSample(List<JsonObject> pool, int n, Random rng) {
        Collections.shuffle(pool, rng);
        Map<String, Integer> count = new HashMap<>();
        List<JsonObject> out = new ArrayList<>();
        for (JsonObject it : pool) {
            String p = projectKey(it);
            if (count.getOrDefault(p, 0) >= MAX_PER_PROJECT) continue;
            count.merge(p, 1, Integer::sum);
            out.add(it);
            if (out.size() == n) break;
        }
        return out;
    }

    static Issue toIssue(JsonObject it, int label) {
        JsonObject f = fields(it);
        Issue r = new Issue();
        r.key = it.get("key").getAsString();
        r.project = projectKey(it);
        r.issueType = nameOf(f, "issuetype");
        r.priority = nameOf(f, "priority");
        r.created = str(f, "created");
        r.title = clean(str(f, "summary"));
        r.label = label;
        r.blockedBy = blockedBy(it);
        int budget = MAX_TEXT_CHARS - r.title.length();
        String desc = clean(str(f, "description"));
        r.description = desc.length() > budget / 2 ? desc.substring(0, budget / 2) : desc;
        // keep the most recent comments that fit in the remaining budget
        List<String> all = comments(it);
        LinkedList<String> kept = new LinkedList<>();
        int used = 0;
        for (int i = all.size() - 1; i >= 0; i--) {
            String c = all.get(i).length() > 800 ? all.get(i).substring(0, 800) : all.get(i);
            if (used + c.length() > budget / 2) break;
            kept.addFirst(c);
            used += c.length();
        }
        r.comments = new ArrayList<>(kept);
        return r;
    }

    /** Excel-friendly copy of the dataset (UTF-8 with BOM) to show and inspect the data. */
    static void writeCsv(List<Issue> records, Path path) throws IOException {
        try (var w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write('﻿');
            w.write("key,url,project,issue_type,priority,created,label,blocked_by,title,description,num_comments,comments\n");
            for (Issue r : records) {
                w.write(String.join(",", csv(r.key), csv("https://issues.apache.org/jira/browse/" + r.key), csv(r.project),
                        csv(r.issueType), csv(r.priority), csv(r.created), csv(r.label == 1 ? "BLOCKED" : "NOT_BLOCKED"),
                        csv(String.join(" ", r.blockedBy)), csv(r.title), csv(r.description),
                        csv(String.valueOf(r.comments.size())), csv(String.join(" || ", r.comments))));
                w.write("\n");
            }
        }
    }

    static String csv(String s) {
        if (s == null) s = "";
        if (s.length() > 32000) s = s.substring(0, 32000);  // Excel cell limit
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
