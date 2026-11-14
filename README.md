# LLM-Based Scrum Master Assistant for Impediment Detection

Course project for MSCS811 (Advanced Software Design and Development), Taibah University.
Author: Dana Fahad Alahmadi.

The assistant reads a Jira issue (title, type, priority, description and comments) and asks an LLM
whether the work is blocked by an impediment. The LLM answers in JSON with four fields:
`impediment` (true/false), `type`, `reason` and `confidence`.

We compare three prompting strategies on the same issues: zero-shot, few-shot and role prompting.

## Data

- 200 real resolved issues from 38 Apache projects (issues.apache.org/jira), created between 2021 and 2026.
- 100 blocked issues: they have an "is blocked by" link.
- 100 not-blocked issues: they have no blocker link and come from the same projects and years.
- Ground truth comes from the "is blocked by" links. The link itself is never shown to the LLM.
- Code blocks, logs and usernames are replaced by placeholders, and each issue's text is limited to about 6,000 characters (the latest comments are kept).

Files: `data/dataset.jsonl` (used by the program) and `data/dataset.csv` (for Excel).

## Impediment types

DEPENDENCY, EXTERNAL, TECHNICAL, REQUIREMENTS, REVIEW, RESOURCE, NONE.

## Code (`src/main/java/smassistant`)

| File | Role |
|---|---|
| `Config.java` | Reads `config.properties` (endpoint, model, API key, request rate). |
| `Issue.java` | One Jira issue and the text sent to the LLM. |
| `DataCollector.java` | Collects and cleans the 200 issues from the Apache Jira REST API. |
| `Prompts.java` | The three prompting strategies and the impediment types. |
| `LlmClient.java` | Sends each prompt to the LLM (temperature 0) and parses the JSON answer. |
| `ExperimentRunner.java` | Runs every strategy on every issue and saves `results/predictions.jsonl`. |
| `Evaluator.java` | Computes accuracy, precision, recall and F1, and writes the result tables. |

## How to run

Requirements: Java 17 or newer and Maven (or IntelliJ IDEA).

1. Create a file `config.properties` in the project folder with these lines (use your own Gemini API key):

   ```
   api.key=YOUR_KEY
   api.base_url=https://generativelanguage.googleapis.com/v1beta/openai/
   model=gemini-3.5-flash-lite
   reasoning_effort=low
   ```

2. Run `DataCollector` to collect the dataset (no API key needed).
3. Run `ExperimentRunner` to send 600 requests (200 issues x 3 strategies). It resumes if stopped.
4. Run `Evaluator` to compute the metrics (`results/metrics.csv`).

Model used: `gemini-3.5-flash-lite` through Google's OpenAI-compatible endpoint, temperature 0.

## Results

| Strategy | Accuracy | Precision | Recall | F1 |
|---|---|---|---|---|
| Zero-shot | 0.625 | 0.736 | 0.390 | 0.510 |
| Few-shot | 0.625 | 0.766 | 0.360 | 0.490 |
| Role | 0.595 | 0.686 | 0.350 | 0.464 |

`results/predictions.jsonl` holds the 600 answers of the model. Its first line comes from an early test with another model; the Evaluator ignores it because it only reads answers from `gemini-3.5-flash-lite`.
