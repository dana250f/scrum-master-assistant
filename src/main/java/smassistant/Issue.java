package smassistant;
import java.util.List;

/**
 * One Jira issue in the evaluation dataset.
 * label = 1 if the issue has an "is blocked by" link (impediment), 0 otherwise.
 * blockedBy is ground-truth evidence only and is NEVER sent to the LLM.
 */
public class Issue {
    public String key;
    public String project;
    public String issueType;
    public String priority;
    public String created;
    public String title;
    public String description;
    public List<String> comments;
    public int label;
    public List<String> blockedBy;

    /** The only text the LLM sees: title, type, priority, description and comments. */
    public String toPromptText() {
        StringBuilder sb = new StringBuilder();
        sb.append("Title: ").append(title).append("\n");
        sb.append("Type: ").append(issueType).append(" | Priority: ").append(priority).append("\n");
        sb.append("Description: ").append(description).append("\n");
        sb.append("Comments:\n");
        if (comments == null || comments.isEmpty()) sb.append("(none)\n");
        else for (int i = 0; i < comments.size(); i++) sb.append("[").append(i + 1).append("] ").append(comments.get(i)).append("\n");
        return sb.toString();
    }
}
