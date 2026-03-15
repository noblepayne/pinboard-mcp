# DEVLOG

## 2026-03-14

### Starting from art19-mcp

Forked project setup from art19-mcp. Created our own AGENTS.md, updated README.md, flake.nix, bb.edn for pinboard-mcp.

### MVP Implementation

Built the full MCP server in a single file `pinboard_mcp.bb`:

1. **Configuration** - PINBOARD_TOKEN from env or ~/.config/pinboard/config.edn
1. **Session Management** - UUID-based sessions with Mcp-Session-Id headers
1. **Pinboard HTTP Client** - api-get with auth_token query param
1. **Normalization** - tags as sets, yes/no as booleans
1. **6 Tools** - list/search/add/delete bookmarks, tags, recent
1. **MCP Protocol** - initialize, tools/list, tools/call handlers
1. **HTTP Server** - http-kit on /mcp and /health

### Testing

Wrote integration tests in `tests/test_pinboard_mcp.clj`:

- Fake Pinboard API server (returns predetermined responses)
- Tests call real MCP server via JSON-RPC
- No mocks - test like a client would

13 tests, 21 assertions, all passing.

### Key Decisions

- **Single file** - Following art19-mcp pattern, everything in pinboard_mcp.bb
- **Search is client-side** - Pinboard has no search API, filter in Clojure
- **Tags as sets** - Normalize Pinboard's space-separated string
- **Booleans** - Normalize "yes"/"no" to true/false

### Next Steps

- Add linting with clj-kondo
- Add formatting with cljfmt
- ~~Try with real Pinboard API~~ - Done via staging tests
- Consider SQLite cache for search performance

## 2026-03-15

### Production Review

Ran final code review with calibrated MVP lens. Fixed:

- **H-1**: Tag/limit ordering - fetch all, filter, then limit
- **H-2**: Session pruning - guarantee new session survives via `dissoc` before sort
- **H-3**: 429 handling - 5s sleep + single retry
- **M-1**: Thundering herd - in-flight fetch gate
- **C-1**: Nix module - moved to top-level outputs
- **M-4**: Source - single file with `dontUnpack = true`

Added documentation:
- README.md: Technical Notes section
- CHANGELOG.md: v1.0.1 release notes

All tests passing (unit, integration, staging). Ready for merge.

---

## 2026-03-14 (continued)

### Staging Tests

Added `tests/test_pinboard_mcp_staging.clj` for testing against real Pinboard API:

- 4 read-only tests (list, search, tags, recent)
- 2 read-write tests (add+delete, add+delete again)
- Uses unique URLs with Jupiter Broadcasting as base
- Always cleans up in finally blocks
- 3.5s delay between tests for rate limiting
- Skips gracefully if PINBOARD_TEST_TOKEN not set

All 6 staging tests passed against real API.

### Live Test

Used MCP server to add Jupiter Broadcasting bookmark via curl:

- Successfully added bookmark with tags: podcast, linux, tech
- Verified through Pinboard UI
