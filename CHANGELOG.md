# Changelog

All notable changes will be documented in this file.

## v1.0.1 (2026-03-15)

### Fixed

- **Tag/Limit ordering:** Fixed bug where `list_bookmarks(tag="x", limit=N)` could return empty results if tagged bookmarks appeared after position N in the full list
- **Session pruning:** Fixed session creation to guarantee the new session ID survives pruning when at capacity
- **Rate limiting:** Added 429 retry with 5-second backoff for Pinboard API rate limit responses
- **Thundering herd:** Added in-flight fetch gate to prevent duplicate API calls on cache miss

### Changed

- Renamed `api-get` to `pinboard-request` with documentation about Pinboard's GET-for-mutations design

### Nix

- Fixed `nixosModules` placement (was inside `eachDefaultSystem`, now at top-level for importability)
- Simplified source to single-file `src = ./pinboard_mcp.bb`
- Set `ProtectHome = "yes"` (consistent with `DynamicUser`)
- Use dynamic versioning (`self.shortRev or "dirty"`)

## v1.0.0 (2026-03-14)

### Added

- Initial release
- MCP Streamable HTTP server (spec 2025-03-26)
- 6 tools:
  - `list_bookmarks` - List all bookmarks, filter by tag, limit results
  - `search_bookmarks` - Full-text search across title, description, tags
  - `add_bookmark` - Create new bookmark
  - `delete_bookmark` - Delete bookmark by URL
  - `list_tags` - Get all tags with usage counts
  - `recent_bookmarks` - Get most recent bookmarks
- Session management with Mcp-Session-Id headers
- `/health` endpoint for health checks
- Integration tests with fake Pinboard API
- Staging tests against real Pinboard API:
  - Read-only tests: list, search, tags, recent
  - Read-write tests: add+delete workflows with cleanup
  - Rate limiting (3.5s between requests)
  - Requires PINBOARD_TEST_TOKEN env var
