# analyze-pr — ContextGuard PR Analysis Workflow

You are helping analyse a GitHub PR using the ContextGuard system. Follow this workflow:

## Step 1: Check what's running

```bash
curl -s http://localhost:8080/actuator/health | jq .status
```

If not running, tell the user: `docker compose up --build` from the project root.

## Step 2: Submit PR for analysis

```bash
curl -s -X POST http://localhost:8080/api/v1/pr-analysis \
  -H "Content-Type: application/json" \
  -d '{
    "prUrl": "$ARGUMENTS",
    "aiProvider": "OPENAI",
    "githubToken": "'$GITHUB_TOKEN'",
    "aiToken": "'$OPENAI_API_KEY'"
  }' | jq .
```

Note the `analysisId` from the response.

## Step 3: Poll until complete

```bash
curl -s http://localhost:8080/api/v1/pr-analysis/{analysisId} | jq '{
  risk: .risk.level,
  riskScore: .risk.overallScore,
  difficulty: .difficulty.level,
  difficultyScore: .difficulty.overallScore,
  blastRadius: .blastRadius.scope,
  reviewMinutes: .difficulty.estimatedReviewMinutes,
  diagramReady: (.mermaidDiagram != null),
  narrativeReady: (.narrative != null)
}'
```

Retry after 5s if `diagramReady` or `narrativeReady` is false — AST parsing is async.

## Step 4: Interpret the results

**Risk Score thresholds:** `<0.25 LOW | <0.50 MEDIUM | <0.75 HIGH | ≥0.75 CRITICAL`

**Difficulty Score thresholds:** `<0.15 TRIVIAL | <0.35 EASY | <0.55 MODERATE | <0.75 HARD | ≥0.75 VERY_HARD`

**Blast Radius scopes:** `LOCALIZED → COMPONENT → CROSS_MODULE → SYSTEM_WIDE`

**Risk formula (5 signals):**
```
PR_Risk = 0.20×avg_file_risk + 0.30×peak_file_risk + 0.20×complexity_delta
        + 0.20×critical_path_density + 0.10×test_coverage_gap
```

**Difficulty formula (5 signals):**
```
Difficulty = 0.35×cognitive_complexity + 0.25×code_size + 0.20×arch_context
           + 0.10×file_spread + 0.10×critical_file_concentration
```

## Step 5: Open the UI

Navigate to: `http://localhost:3000/review/{analysisId}`

The ReviewPage shows:
- RiskDifficultyPanel: full signal breakdown with formulas, weights, research evidence
- ASTMetricsPanel: complexityDelta, avgMethodCC, maxCallDepth, public API changes
- Mermaid call graph diagram (appears ~20s after submission once AST parsing finishes)
- 6-section AI narrative: Overview, Structural Impact, Behavioral Changes, Risk, Review Focus, Checklist
