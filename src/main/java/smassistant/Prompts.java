package smassistant;

/**
 * The three prompting strategies compared in the study.
 * All three share the SAME task definition, type taxonomy and output format,
 * so the only thing that changes is the strategy itself.
 */
public final class Prompts {

    public enum Strategy { ZERO_SHOT, FEW_SHOT, ROLE }

    /** Impediment taxonomy used in the output (RQ4). */
    public static final String TYPES =
            "DEPENDENCY  - work waits on another issue, component or team in the same project\n" +
            "EXTERNAL    - work waits on a third-party library, upstream project, vendor or release\n" +
            "TECHNICAL   - a build, test, infrastructure or environment problem stops progress\n" +
            "REQUIREMENTS- progress stops because requirements, design decisions or information are missing\n" +
            "REVIEW      - progress stops while waiting for a review, vote, approval or committer action\n" +
            "RESOURCE    - progress stops for lack of people, time, hardware or access\n" +
            "NONE        - no impediment";

    static final String TASK =
            "Task: Read the Jira issue below and decide whether the work on it is (or was) BLOCKED by an impediment, " +
            "meaning something outside the issue itself prevents the team from completing it.\n" +
            "Ordinary bugs, normal discussion, or work that simply has not started are NOT impediments.\n\n" +
            "Impediment types:\n" + TYPES + "\n\n" +
            "Answer ONLY with a JSON object, no other text:\n" +
            "{\"impediment\": true or false, \"type\": \"one type from the list\", " +
            "\"reason\": \"one short sentence citing the evidence\", \"confidence\": number between 0 and 1}";

    static final String FEW_SHOT_EXAMPLES =
            "Here are four examples (invented for illustration):\n\n" +
            "Example 1\nTitle: Upgrade connector to new client API\n" +
            "Description: Migrate the connector to the v3 client.\n" +
            "Comments:\n[1] We cannot finish this until the client library publishes 3.0 to Maven Central.\n" +
            "[2] Still waiting for their release, parking this.\n" +
            "Answer: {\"impediment\": true, \"type\": \"EXTERNAL\", \"reason\": \"Work waits for the third-party 3.0 release.\", \"confidence\": 0.9}\n\n" +
            "Example 2\nTitle: Fix typo in configuration guide\n" +
            "Description: The docs spell 'timeout' wrong in section 4.\n" +
            "Comments:\n[1] Patch attached.\n[2] Committed, thanks.\n" +
            "Answer: {\"impediment\": false, \"type\": \"NONE\", \"reason\": \"Small fix completed without obstacles.\", \"confidence\": 0.95}\n\n" +
            "Example 3\nTitle: Expose new metrics in the web UI\n" +
            "Description: Show queue latency in the dashboard.\n" +
            "Comments:\n[1] The REST endpoint for these metrics does not exist yet; this depends on the server-side task.\n" +
            "[2] Will resume once the backend issue is merged.\n" +
            "Answer: {\"impediment\": true, \"type\": \"DEPENDENCY\", \"reason\": \"UI work depends on an unfinished backend task.\", \"confidence\": 0.9}\n\n" +
            "Example 4\nTitle: NullPointerException when config file is empty\n" +
            "Description: Starting with an empty file throws NPE.\n" +
            "Comments:\n[1] I can reproduce. Here is a stack trace.\n[2] Added a null check and a unit test.\n" +
            "Answer: {\"impediment\": false, \"type\": \"NONE\", \"reason\": \"A normal bug fixed directly by the team.\", \"confidence\": 0.9}\n\n" +
            "Now classify the real issue.\n\n";

    static final String ROLE_SYSTEM =
            "You are an experienced, certified Scrum Master supporting an open-source software team. " +
            "Your responsibility in Scrum is to detect and help remove impediments that stop the team from making progress. " +
            "You read issue trackers carefully, recognise when developers are waiting on something, " +
            "and you only raise an impediment when there is real evidence for it.";

    static final String PLAIN_SYSTEM = "You are a helpful assistant that answers in JSON.";

    public static String system(Strategy s) { return s == Strategy.ROLE ? ROLE_SYSTEM : PLAIN_SYSTEM; }

    public static String user(Strategy s, Issue issue) {
        String body = TASK + "\n\n" + (s == Strategy.FEW_SHOT ? FEW_SHOT_EXAMPLES : "")
                + "Jira issue:\n" + issue.toPromptText();
        return body;
    }

    private Prompts() {}
}
