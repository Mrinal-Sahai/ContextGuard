# check-secrets — Scan for Hardcoded Credentials Before Committing

Run this before every commit to ensure no real API keys or tokens are included.

## Quick scan

```bash
# Scan for common secret patterns in tracked files
git diff --cached | grep -E "(ghp_|github_pat_|sk-proj-|AIzaSy|AKIA|Bearer [a-zA-Z0-9]{20,})" && echo "⚠️  SECRETS DETECTED IN STAGED FILES" || echo "✅ No obvious secrets in staged changes"
```

## Full repo scan (for audits)

```bash
# GitHub tokens
grep -rn "ghp_[a-zA-Z0-9]" --include="*.yaml" --include="*.yml" --include="*.properties" --include="*.env" . 2>/dev/null | grep -v ".env.example"

# GitHub PATs
grep -rn "github_pat_" --include="*.yaml" --include="*.yml" . 2>/dev/null

# OpenAI keys
grep -rn "sk-proj-\|sk-[a-zA-Z0-9]\{48\}" --include="*.yaml" --include="*.yml" . 2>/dev/null

# Gemini keys
grep -rn "AIzaSy[a-zA-Z0-9_-]\{33\}" --include="*.yaml" --include="*.yml" . 2>/dev/null

# Anthropic keys
grep -rn "sk-ant-api" --include="*.yaml" --include="*.yml" . 2>/dev/null
```

## The correct pattern for application.yaml

All secrets should use `${ENV_VAR:}` with an EMPTY default (not a real key):

```yaml
# ✅ CORRECT — fails fast if env var not set
token: ${GITHUB_TOKEN:}
api-key: ${OPENAI_API_KEY:}

# ❌ WRONG — real key in fallback
token: ${GITHUB_TOKEN:ghp_abc123realkey}
```

## Where real values live

1. **Local dev:** `.env` file (gitignored) — copy from `.env.example`
2. **Docker Compose:** Set in `.env`, read via `${VAR}` in `docker-compose.yml`
3. **Production:** Inject via CI/CD secrets or container orchestration env vars

## If you find leaked credentials

1. Revoke the key immediately (GitHub → Settings → Developer Settings → Personal access tokens)
2. Rotate the key
3. Remove from git history: `git filter-repo --path backend/src/main/resources/application.yaml --invert-paths` (nuclear option) or use BFG
4. Force-push the cleaned history (coordinate with team)
