# 1. System Spec

## System name

```
pinboard-mcp
```

Purpose:

```
Expose a Pinboard bookmark account as MCP tools
usable by LLM agents.
```

______________________________________________________________________

# 2. Domain Model (Values)

All internal values are **plain maps**.

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

Important decisions:

```
tags = set
shared = boolean
toread = boolean
```

We normalize Pinboard’s weird string format.

______________________________________________________________________

### Tag Stats

```clojure
{"ai" 12
 "tools" 4
 "research" 8}
```

______________________________________________________________________

### Config

```clojure
{:token "username:token"
 :endpoint "https://api.pinboard.in/v1"
}
```

______________________________________________________________________

# 3. MCP Tool Spec

Tools should map to **user intent**, not API endpoints.

### list-bookmarks

```
list-bookmarks
```

Input

```clojure
{:tag "optional"
 :limit 50}
```

Output

```
[bookmark ...]
```

______________________________________________________________________

### search-bookmarks

LLM-friendly.

```clojure
{:query "clojure"}
```

Searches title + description + tags.

______________________________________________________________________

### add-bookmark

```clojure
{:url "https://..."
 :title "..."
 :description "optional"
 :tags ["ai" "tools"]
 :toread false
 :shared true}
```

______________________________________________________________________

### delete-bookmark

```clojure
{:url "..."}
```

______________________________________________________________________

### list-tags

```
{}
```

______________________________________________________________________

### recent-bookmarks

```
{:count 10}
```

______________________________________________________________________

# 4. API Layer

Pinboard API is simple.

All requests:

```
GET
https://api.pinboard.in/v1/{endpoint}
```

Auth:

```
auth_token=username:token
```

______________________________________________________________________

# 5. Implementation

Single file:

```
pinboard_mcp.bb
```

______________________________________________________________________

## Namespace

```clojure
(ns pinboard-mcp.core
  (:require
   [babashka.http-client :as http]
   [cheshire.core :as json]
   [clojure.string :as str]))
```

______________________________________________________________________

# 6. Pinboard Client

### HTTP helper

```clojure
(defn api-get
  [{:keys [token endpoint]} path params]
  (let [url (str endpoint "/" path)
        q (merge params
                 {:auth_token token
                  :format "json"})]
    (-> (http/get url {:query-params q})
        :body
        (json/parse-string true))))
```

______________________________________________________________________

# 7. Normalization

Translate Pinboard → domain values.

```clojure
(defn parse-tags [s]
  (if (str/blank? s)
    #{}
    (set (str/split s #" "))))

(defn normalize-bookmark [b]
  {:url (:href b)
   :title (:description b)
   :description (:extended b)
   :tags (parse-tags (:tags b))
   :shared (= "yes" (:shared b))
   :toread (= "yes" (:toread b))
   :time (:time b)})
```

______________________________________________________________________

# 8. Pinboard Operations

### Fetch bookmarks

```clojure
(defn fetch-bookmarks [cfg opts]
  (let [res (api-get cfg "posts/all" opts)]
    (map normalize-bookmark res)))
```

______________________________________________________________________

### Add bookmark

```clojure
(defn add-bookmark! [cfg {:keys [url title description tags shared toread]}]
  (api-get cfg
           "posts/add"
           {:url url
            :description title
            :extended description
            :tags (str/join " " tags)
            :shared (if shared "yes" "no")
            :toread (if toread "yes" "no")}))
```

______________________________________________________________________

### Delete bookmark

```clojure
(defn delete-bookmark! [cfg {:keys [url]}]
  (api-get cfg "posts/delete" {:url url}))
```

______________________________________________________________________

### Recent

```clojure
(defn recent-bookmarks [cfg count]
  (->> (api-get cfg "posts/recent" {:count count})
       :posts
       (map normalize-bookmark)))
```

______________________________________________________________________

### Tags

```clojure
(defn list-tags [cfg]
  (api-get cfg "tags/get" {}))
```

______________________________________________________________________

# 9. MCP Tool Layer

Define tools as pure functions returning values.

______________________________________________________________________

### list-bookmarks tool

```clojure
(defn tool-list-bookmarks [cfg args]
  (let [tag (:tag args)
        limit (or (:limit args) 50)
        res (fetch-bookmarks cfg (when tag {:tag tag}))]
    (take limit res)))
```

______________________________________________________________________

### search-bookmarks

```clojure
(defn match? [q b]
  (let [q (str/lower-case q)]
    (or (str/includes? (str/lower-case (:title b)) q)
        (str/includes? (str/lower-case (:description b)) q)
        (some #(str/includes? (str/lower-case %) q) (:tags b)))))

(defn tool-search-bookmarks [cfg {:keys [query]}]
  (filter #(match? query %) (fetch-bookmarks cfg {})))
```

______________________________________________________________________

### add-bookmark

```clojure
(defn tool-add-bookmark [cfg args]
  (add-bookmark! cfg args)
  {:status "ok"})
```

______________________________________________________________________

### delete-bookmark

```clojure
(defn tool-delete-bookmark [cfg args]
  (delete-bookmark! cfg args)
  {:status "ok"})
```

______________________________________________________________________

### recent

```clojure
(defn tool-recent-bookmarks [cfg {:keys [count]}]
  (recent-bookmarks cfg (or count 10)))
```

______________________________________________________________________

### list-tags

```clojure
(defn tool-list-tags [cfg _]
  (list-tags cfg))
```

______________________________________________________________________

# 10. Tool Registry

Simple data structure.

```clojure
(def tools
  {"list-bookmarks" tool-list-bookmarks
   "search-bookmarks" tool-search-bookmarks
   "add-bookmark" tool-add-bookmark
   "delete-bookmark" tool-delete-bookmark
   "recent-bookmarks" tool-recent-bookmarks
   "list-tags" tool-list-tags})
```

______________________________________________________________________

# 11. Tool Dispatcher

```clojure
(defn run-tool [cfg name args]
  (if-let [f (get tools name)]
    (f cfg args)
    {:error "unknown tool"}))
```

______________________________________________________________________

# 12. Integration Tests

Testing philosophy:

```
Prefer integration over mocks.
Treat the API as a boundary.
```

Use **test.check style invariants** where possible.

______________________________________________________________________

### Test namespace

```clojure
(ns pinboard-mcp.core-test
  (:require
   [clojure.test :refer :all]
   [pinboard-mcp.core :as p]))
```

______________________________________________________________________

## Test normalization

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
```

______________________________________________________________________

## Test tag parsing

```clojure
(deftest tag-parsing
  (is (= #{"a" "b"} (p/parse-tags "a b")))
  (is (= #{} (p/parse-tags ""))))
```

______________________________________________________________________

## Integration test: add + delete

Requires test token.

```clojure
(deftest add-delete-roundtrip
  (let [cfg {:token (System/getenv "PINBOARD_TOKEN")
             :endpoint "https://api.pinboard.in/v1"}
        url "https://example-test-url.com"]

    (p/tool-add-bookmark cfg
                         {:url url
                          :title "test"
                          :tags ["test"]})

    (is (some #(= url (:url %))
              (p/tool-list-bookmarks cfg {:limit 100})))

    (p/tool-delete-bookmark cfg {:url url})))
```

______________________________________________________________________

## Integration test: recent

```clojure
(deftest recent-bookmarks-test
  (let [cfg {:token (System/getenv "PINBOARD_TOKEN")
             :endpoint "https://api.pinboard.in/v1"}]
    (is (seq (p/tool-recent-bookmarks cfg {:count 3})))))
```

______________________________________________________________________

# 13. MCP Transport Layer

Streamable HTTP (spec `2025-03-26`). Single `/mcp` POST endpoint.

## Session Management

```
Client initialize → server creates session, returns Mcp-Session-Id
Client notifications/initialized → 204, no response
All subsequent requests include Mcp-Session-Id
Server validates session on every non-initialize request
```

```clojure
(def sessions (atom {})) ;; session-id -> {:created-at ...}

(defn create-session! []
  (let [sid (str (java.util.UUID/randomUUID))]
    (swap! sessions assoc sid {:created-at (System/currentTimeMillis)})
    sid))

(defn valid-session? [sid]
  (boolean (and sid (contains? @sessions sid))))
```

## JSON-RPC Handlers

```clojure
(defn handle-initialize [id _params]
  {:jsonrpc "2.0"
   :id id
   :result {:protocolVersion "2025-03-26"
            :capabilities {:tools {:listChanged false}}
            :serverInfo {:name "pinboard-mcp" :version "1.0.0"}}})

(defn handle-tools-list [id _params]
  {:jsonrpc "2.0"
   :id id
   :result {:tools tools}})

(defn handle-tools-call [id params config]
  (let [tool-name (get params :name)
        args (get params :arguments {})
        result (dispatch-tool tool-name args config)]
    {:jsonrpc "2.0"
     :id id
     :result {:content [{:type "text" :text (json/generate-string result)}]
              :isError (boolean (:error result))}}))
```

## HTTP Server

```clojure
(defn handle-mcp [request config]
  (let [body (json/parse-string (slurp (:body request)) true)
        session-id (get (:headers request) "mcp-session-id")
        new-session? (= "initialize" (:method body))
        sid (if new-session? (create-session!) session-id)]
    (if (and (not new-session?) (not (valid-session? sid)))
      {:status 400 :body "Invalid or missing Mcp-Session-Id"}
      (let [response (dispatch-rpc body config)]
        {:status (if response 200 204)
         :headers (cond-> {"Content-Type" "application/json"}
                    new-session? (assoc "Mcp-Session-Id" sid))
         :body (when response (json/generate-string response))}))))

(defn handler [request config]
  (case (:uri request)
    "/mcp" (handle-mcp request config)
    "/health" {:status 200 :body "{\"status\":\"ok\"}"}
    {:status 404 :body "Not Found"}))

(defn -main [& _args]
  (let [port (or (some-> (System/getenv "PINBOARD_MCP_PORT") Integer/parseInt) 0)]
    (http/run-server #(handler % config) {:port port :ip "127.0.0.1"})))
```

______________________________________________________________________

# 14. Invariants (System Thinking)

The system should maintain:

### Bookmark invariants

```
:url is unique
:tags always a set
:shared boolean
:toread boolean
```

### API invariants

```
All API calls include auth_token
Rate limit respected
```

Add a small rate limiter if needed.

______________________________________________________________________

# 15. Example Config

```
PINBOARD_TOKEN=username:abcd1234
```

______________________________________________________________________

# 16. System Size

Entire system:

```
~350 lines
```

That’s the sweet spot.

Simple enough to read in one sitting.

______________________________________________________________________

# 17. Future Improvements

Natural next steps:

### SQLite cache

```
Pinboard → local index
```

Then search becomes instant.

______________________________________________________________________

### Full-text search

```
SQLite FTS5
```

Agents love this.

______________________________________________________________________

### Sync tool

```
sync-bookmarks
```

for agent indexing.
