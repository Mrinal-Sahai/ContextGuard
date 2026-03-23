# add-language — Add a New Language to ContextGuard AST Parser

Follow these 5 steps to add support for a new language. Each step has a concrete file to modify.

## Assumptions

- The language has a tree-sitter grammar available on npm as `tree-sitter-<lang>`
- If it has a type inference tool (like tsc for TypeScript), you'll add a Tier 2 bridge handler

---

## Step 1: Add the tree-sitter grammar

**File:** `tree-sitter-bridge/package.json`

```json
{
  "dependencies": {
    "tree-sitter-<lang>": "^X.Y.Z"
  }
}
```

Then run: `cd tree-sitter-bridge && npm install`

---

## Step 2: Add parsing logic to the Node.js bridge

**File:** `tree-sitter-bridge/tree-sitter-bridge.js`

Add a `parse<Lang>` function following the pattern of `parsePython` or `parseGoTree`:

```javascript
function parseLang(source, filePath) {
  const parser = new Parser();
  parser.setLanguage(Lang);  // require('tree-sitter-<lang>')
  const tree = parser.parse(source);

  const nodes = [];
  const edges = [];

  // Walk the tree, extract function/method definitions as nodes
  // Extract call expressions as edges
  // Return { nodes, edges }
}
```

Register it in the dispatch switch at the bottom of `tree-sitter-bridge.js`:
```javascript
case 'lang':
  result = parseLang(source, filePath);
  break;
```

---

## Step 3: Register the language in ASTParserService

**File:** `backend/src/main/java/io/contextguard/analysis/flow/ASTParserService.java`

Add the file extension to `SUPPORTED_EXTENSIONS`:
```java
private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
    ".java", ".js", ".ts", ".py", ".rb", ".go", ".dart",
    ".<ext>"  // ← add here
);
```

Add to `detectLanguageFromPath()`:
```java
case ".<ext>" -> "<lang>"
```

Add to `parseFilePass2()` dispatch:
```java
case "<lang>" -> parseViaTreeSitter(content, filePath, language);
```

---

## Step 4: (Optional) Add a Tier 2 type inference bridge

If the language has a type inference tool that can give better call edge resolution than tree-sitter alone:

**File:** `tree-sitter-bridge/index-batch-handler.js`

Add an `index_<lang>_batch` handler that:
1. Accepts `{ type: "index_batch", language: "<lang>", files: { path: content } }`
2. Runs the type checker
3. Returns `{ symbols[], importAliases[], variableTypes[] }`

**File:** `backend/src/main/java/io/contextguard/analysis/flow/LanguageToolBridgeService.java`

Register the language in `languageAvailability` map and add a `buildLanguageIndexBatchRequest()` call.

---

## Step 5: Update documentation

**File:** `.claude/CLAUDE.md` — update the Multi-Language AST Support table:

| Language | Tool | CC Formula | Call Edges | Overall |
|----------|------|------------|------------|---------|
| `<Lang>` | Tree-sitter (+ Tier 2 tool if added) | compatible formula | estimated % | X/10 |

---

## Verification

```bash
# Test the bridge directly
echo '{"id":"test1","type":"parse","language":"<lang>","source":"your test code","filePath":"test.<ext>"}' \
  | node tree-sitter-bridge/tree-sitter-bridge.js

# Run the backend and submit a PR with .<ext> files
curl -X POST http://localhost:8080/api/v1/pr-analysis \
  -H "Content-Type: application/json" \
  -d '{"prUrl":"https://github.com/owner/repo/pull/N", ...}'
```

Check logs for: `Parsed <lang> file: <n> nodes, <m> edges`
