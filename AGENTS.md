# AGENTS.md — pinboard-mcp

> "Simple is the opposite of complex. Easy is the opposite of hard."
> — Rich Hickey

## What This Is

A Babashka Streamable HTTP MCP server wrapping the Pinboard bookmarking API. AI agents can manage bookmarks better than humans typing commands.

**Transport:** MCP Streamable HTTP (spec `2025-03-26`)
**Endpoint:** Single `/mcp` POST + `/health`
**Compatible with:** mcp-injector `{:pinboard {:url "http://127.0.0.1:PORT/mcp"}}`

## Project Structure

```
pinboard-mcp/
├── pinboard_mcp.bb    # Single-file MCP server (the whole thing)
├── bb.edn             # Tasks: run, start, test, lint, health
├── flake.nix          # Nix package + NixOS service module
├── README.md          # Project docs
├── SPEC.md            # Original specification
├── DEVLOG.md          # Development notes
└── tests/
    └── test_pinboard_mcp.clj  # Integration tests (fake API)
```

## Philosophy: Grumpy Pragmatism

Follow Rich Hickey, Eric Normand, and Hillel Wayne:

- **Simple over Easy** — Complection is the enemy
- **Actions, Calculations, Data** — Keep distinct
- **Functional Core, Imperative Shell** — Push impurity to edges
- **YAGNI** — Don't build for imagined futures
- **Test-First, Not Test-All** — Write tests that verify real client usage
- **Integration over Mocks** — Test like a client would

## Workflow

- **Test-driven** — tests guide development
- **Integration tests only** — fake API server, no mocks, test like a client would
- **Clean lint** — no warnings tolerated (`clj-kondo`)
- **Formatting** — uniform across all types:
  - Clojure/Babashka: `nix run nixpkgs#cljfmt -- fix <file>`
  - Markdown: `nix run nixpkgs#mdformat -- <file>`
  - Nix: `nix fmt .`
  - EDN: `clojure.pprint`
- **Feature branches** — commit often as snapshots, rewrite history later
- **Docs up to date** — update before commit
- **Keep bb.edn current** — tasks mirror actual commands

## Running

```bash
# Dev — OS-assigned port, logs JSON startup line
bb serve

# Dev — fixed port (or PINBOARD_MCP_PORT)
bb start
```

Auth via env vars or `~/.config/pinboard/config.edn`:

```bash
export PINBOARD_TOKEN="username:abcd1234"
```

```clojure
;; ~/.config/pinboard/config.edn
{:token "username:abcd1234"}
```

## mcp-injector Config

Add to `mcp-servers.edn`:

```clojure
{:servers
 {:pinboard
  {:url "http://127.0.0.1:PORT/mcp"
   :tools ["list_bookmarks" "search_bookmarks" "add_bookmark"
           "delete_bookmark" "list_tags" "recent_bookmarks"]}}}
```

## Tools

| Tool | Description |
|------|-------------|
| `list_bookmarks` | List all bookmarks; filter by tag, limit results |
| `search_bookmarks` | Full-text search across title, description, tags |
| `add_bookmark` | Create new bookmark with url, title, description, tags |
| `delete_bookmark` | Delete bookmark by URL |
| `list_tags` | Get all tags with usage counts |
| `recent_bookmarks` | Get most recent bookmarks |

## Architecture

### MCP Transport (Streamable HTTP, 2025-03-26)

Single `/mcp` POST endpoint. Session lifecycle:

1. Client sends `initialize` → server creates session, returns `Mcp-Session-Id` header
1. Client sends `notifications/initialized` (no response needed, 204)
1. All subsequent requests include `Mcp-Session-Id` header
1. Server validates session on every non-initialize request

### Code Structure

Everything lives in `pinboard_mcp.bb` — it's one file, intentionally:

```
Configuration / auth loading
│
Pinboard HTTP client (api-get)
│
Normalization helpers (parse-tags, normalize-bookmark)
│
Tool implementations (tool-*)
│
Tool registry (tools vector — the schemas the LLM sees)
│
Tool dispatch (case on name → tool-*)
│
JSON-RPC handlers (handle-initialize, handle-tools-list, handle-tools-call)
│
HTTP server (http-kit, handler, handle-mcp)
│
Entry point (-main)
```

### Error Handling

Tool errors return `{:error true :message "..."}` which dispatch-tool wraps in an MCP `isError: true` content block. The LLM sees the error message and can reason about it (e.g., retry with different args, report back to user).

API errors (4xx/5xx) are surfaced the same way — they never throw past the tool boundary.

## Development

### Adding a Tool

1. Write `tool-<name> [args config]` function that returns data or `{:error ...}`
1. Add entry to `tools` vector with `:name`, `:description`, `:inputSchema`
1. Add case branch in `dispatch-tool`
1. Add test in `tests/test_pinboard_mcp.clj`

### Testing

```bash
# Run integration tests (fake API, no real credentials needed)
bb test

# Run staging tests against real Pinboard API
PINBOARD_TEST_TOKEN="username:real-token" bb test-staging
```

Tests use a fake API server that mimics Pinboard's responses. Tests call the real server process via JSON-RPC, exercising the full request/response cycle. No mocks — test like a client would.

**Staging tests** modify real data. Use a test account:

- 4 read-only tests: list, search, tags, recent
- 2 read-write tests: add+delete (always cleanup)
- 3.5s delay between tests (rate limit)
- Skips gracefully if token not set

### Common Gotchas

**Port 0 allocation:** The server uses port 0 by default (OS assigns). The actual port is in the startup JSON line on stdout. For NixOS services, use a fixed port via `PINBOARD_MCP_PORT`.

**Session validation:** mcp-injector re-initializes sessions on startup (via `warm-up!`). If the server restarts, stale session IDs in mcp-injector will hit a 400. mcp-injector handles this by re-calling `initialize` on 400/401/404 — don't fight it.

**Pinboard API rate limits:** Pinboard limits requests. The API uses `auth_token` in query params. Build in small delays if doing bulk operations.

**Tag normalization:** Pinboard returns tags as a space-separated string. Always convert to set internally (`#{"ai" "tools"}`).

**Boolean normalization:** Pinboard returns "yes"/"no" strings. Normalize to true/false booleans.

**search-bookmarks is client-side:** Pinboard doesn't have a search API. The MCP server fetches bookmarks and filters in Clojure. Use `limit` parameter to avoid fetching thousands of bookmarks.

## Data Model

### Bookmark

```clojure
{:url "https://example.com"
 :title "Example"
 :description "notes"
 :tags #{"ai" "tools"}
 :shared true
 :toread false
 :time "2023-01-01T10:00:00Z"}
```

### Tag Stats

```clojure
{"ai" 12
 "tools" 4
 "research" 8}
```

### Config

```clojure
{:token "username:token"
 :endpoint "https://api.pinboard.in/v1"}
```

## Coding Standards

### Clojure Conventions

**Naming:**

- Functions: `kebab-case` (e.g., `handle-chat-completion`)
- Constants: `UPPER_SNAKE_CASE` for env vars, `*earmuffs*` for dynamic vars
- Namespaces: `pinboard-mcp.core`
- Private functions: suffix with `-` (e.g., `helper-fn-`)

**Formatting:**

- 2-space indentation
- 80-100 character line limit
- Align map values and let bindings
- Trailing newline at EOF

### Babashka-Specific

- Prefer built-in `babashka.*` libraries over external deps
- Use `babashka.http-client` for HTTP
- Keep startup time fast - avoid heavy JVM deps
- Single file is fine — don't split into namespaces until you genuinely need to

### Error Handling

```clojure
;; Use try/catch with specific exceptions
(try
  (risky-operation)
  (catch Exception e
    (log/error e "Operation failed")
    {:error "friendly message"}))

;; Return consistent error shapes
{:error "description" :details additional-info}
```

## Nix Commands

```bash
# Install to profile
nix profile install

# Update flake inputs
nix flake update

# Check flake
nix flake check

# Format Nix files
nixfmt flake.nix
```

## Test Guidelines

> "Write tests. Not too many. Mostly integration."

- **Integration tests** verify the system actually works
- **Unit tests** are guardrails for pure functions (normalization, parsing)
- **Don't mock what you don't own**
- **Test behavior, not implementation**
- **Avoid testing trivial code**

### Unit Tests (Normalization)

```clojure
(deftest normalize-bookmark-test
  (let [raw {:href "https://a"
             :description "A"
             :extended "notes"
             :tags "ai tools"
             :shared "yes"
             :toread "no"}]
    (is (= {:url "https://a"
            :title "A"
            :description "notes"
            :tags #{"ai" "tools"}
            :shared true
            :toread false
            :time nil}
           (p/normalize-bookmark raw)))))

(deftest tag-parsing
  (is (= #{"a" "b"} (p/parse-tags "a b")))
  (is (= #{} (p/parse-tags ""))))
```

### Integration Tests (Fake API)

Tests spin up a fake Pinboard API server that returns predetermined responses. Tests call the real MCP server process via JSON-RPC.

```clojure
(deftest list-bookmarks-test
  (let [cfg {:token "test:test" :endpoint (str "http://localhost:" fake-port)}
        result (tool-list-bookmarks cfg {})]
    (is (seq result))
    (is (every? :url result))))
```

## Project Tracking

- `SPEC.md` — Original feature specification
- `DEVLOG.md` — Running development narrative
- `dev/specs/` — Feature specs (for features >500 LOC)

## Philosophy

Follow the same grumpy pragmatism as the rest of J.O.E.:

- **Actions, Calculations, Data** — tool functions are actions, keep them thin; pure extraction/formatting logic lives in `-row` helpers or inline maps
- **One file is fine** — don't split into namespaces until you genuinely need to
- **No abstractions until they hurt** — the dispatch `case` is fine, resist the urge to make it data-driven
- **Test against real services** — mock drift kills confidence
- **YAGNI** — resources/prompts MCP extensions not implemented because they're not needed yet
