# Results (model: gemini-3.5-flash-lite)

Issues evaluated: 200 (100 blocked, 100 not blocked)

## RQ1-RQ2: Detection performance per prompting strategy

| Strategy | Precision | Recall | F1 | Accuracy | TP | FP | FN | TN | Parse errors | Avg latency (s) |
|---|---|---|---|---|---|---|---|---|---|---|
| ZERO_SHOT | 0.736 | 0.390 | 0.510 | 0.625 | 39 | 14 | 61 | 86 | 0 | 1.3 |
| FEW_SHOT | 0.766 | 0.360 | 0.490 | 0.625 | 36 | 11 | 64 | 89 | 0 | 2.4 |
| ROLE | 0.686 | 0.350 | 0.464 | 0.595 | 35 | 16 | 65 | 84 | 0 | 1.3 |

## Recall by Jira issue type (blocked issues only)

| Strategy | Bug | Dependency upgrade | Improvement | New Feature | Sub-task | Task |
|---|---|---|---|---|---|---|
| ZERO_SHOT | 0.50 (13/26) | 1.00 (2/2) | 0.35 (11/31) | 0.33 (3/9) | 0.29 (4/14) | 0.33 (6/18) |
| FEW_SHOT | 0.42 (11/26) | 1.00 (2/2) | 0.29 (9/31) | 0.22 (2/9) | 0.21 (3/14) | 0.50 (9/18) |
| ROLE | 0.46 (12/26) | 1.00 (2/2) | 0.32 (10/31) | 0.22 (2/9) | 0.14 (2/14) | 0.39 (7/18) |

## RQ4: Predicted impediment types among flagged issues

| Strategy | DEPENDENCY | EXTERNAL | TECHNICAL | REQUIREMENTS | REVIEW | RESOURCE | Total |
|---|---|---|---|---|---|---|---|
| ZERO_SHOT | 19 | 12 | 13 | 3 | 6 | 0 | 53 |
| FEW_SHOT | 17 | 12 | 8 | 5 | 5 | 0 | 47 |
| ROLE | 14 | 14 | 6 | 4 | 12 | 1 | 51 |

## Recall on blocked issues by written evidence and number of comments

| Strategy | Blocker key written in the text | Blocker recorded only as a link | Three or more comments | Fewer than three comments |
|---|---|---|---|---|
| ZERO_SHOT | 0.49 (18/37) | 0.33 (21/63) | 0.47 (28/59) | 0.27 (11/41) |
| FEW_SHOT | 0.49 (18/37) | 0.29 (18/63) | 0.41 (24/59) | 0.29 (12/41) |
| ROLE | 0.49 (18/37) | 0.27 (17/63) | 0.44 (26/59) | 0.22 (9/41) |

## Hard cases

Issues misclassified by all 3 strategies: 65 (listed in errors.csv with all_wrong=true)
