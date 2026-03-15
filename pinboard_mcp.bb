#!/usr/bin/env bb
;; pinboard_mcp.bb — Pinboard MCP Server (Streamable HTTP, 2025-03-26)
;;
;; Serves the Pinboard API as an MCP server.
;; Transport: Streamable HTTP (single /mcp POST endpoint)
;; Compatible with mcp-injector {:pinboard {:url "http://localhost:PORT/mcp"}}
;;
;; Auth: PINBOARD_TOKEN env var or ~/.config/pinboard/config.edn {:token "..."}

(ns pinboard-mcp
  (:require [org.httpkit.server :as http]
            [babashka.http-client :as http-client]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; Configuration

(def default-base-url "https://api.pinboard.in/v1")
(def protocol-version "2025-03-26")
(def server-info {:name "pinboard-mcp" :version "1.0.0"})

(defn load-config-file []
  (let [path (str (System/getProperty "user.home") "/.config/pinboard/config.edn")]
    (when (.exists (java.io.File. path))
      (try
        (edn/read-string (slurp path))
        (catch Exception e
          (binding [*out* *err*]
            (println (str "Warning: Failed to parse config file: " path " - " (.getMessage e))))
          nil)))))

(defn get-pinboard-config []
  (let [file-cfg (load-config-file)]
    {:token (or (:token file-cfg) (System/getenv "PINBOARD_TOKEN"))
     :endpoint (or (System/getenv "PINBOARD_MCP_ENDPOINT") default-base-url)}))

(defn parse-port [s]
  (if (str/blank? s)
    0
    (try
      (let [port (Integer/parseInt s)]
        (if (and (>= port 1) (<= port 65535))
          port
          (throw (IllegalArgumentException. (str "Port must be between 1 and 65535: " s)))))
      (catch NumberFormatException _
        (throw (IllegalArgumentException. (str "Invalid port number: " s)))))))

(defn log [level message data]
  (let [output (json/generate-string
                {:timestamp (str (java.time.Instant/now))
                 :level level
                 :message message
                 :data data})]
    (if (contains? #{"error" "warn"} level)
      (.println System/err output)
      (println output))))

;; Session Management

(def sessions (atom {}))
(def max-sessions 1000)
(def session-ttl-ms 3600000) ; 1 hour

;; Bookmark cache for search (avoid refetching on every request)
(def bookmark-cache (atom {:last-update nil :data nil :checked-at 0}))
(def cache-ttl-ms 60000)

(def fetch-inflight? (atom false)) ; 1 minute throttle on posts/update check

(defn new-session-id []
  (str (java.util.UUID/randomUUID)))

(defn create-session! []
  (let [sid (new-session-id)
        now (System/currentTimeMillis)
        cutoff (- now session-ttl-ms)]
    (loop []
      (let [old-sessions @sessions
            ;; Build new sessions with new one added
            new-sessions (assoc old-sessions sid {:created-at now})
            ;; Quick check if we need pruning
            session-count (count new-sessions)]
        (if (<= session-count (+ max-sessions 10)) ;; Buffer to avoid frequent pruning
          ;; Simple case: just add, no pruning needed yet
          (if (compare-and-set! sessions old-sessions new-sessions)
            sid
            (recur))
          ;; Need to prune expired and maybe some active ones
          (let [pruned (reduce-kv (fn [acc k v]
                                    (if (< (:created-at v) cutoff)
                                      acc ;; Drop expired
                                      (assoc acc k v)))
                                  {}
                                  new-sessions)
                pruned-count (count pruned)]
            (if (<= pruned-count max-sessions)
              ;; Pruning expired was enough
              (if (compare-and-set! sessions old-sessions pruned)
                sid
                (recur))
              ;; Still over limit after removing expired, need to remove oldest
              ;; Remove sid from pruning pool to guarantee it survives
              (let [others (dissoc pruned sid)
                    sorted (sort-by (fn [[_ v]] (:created-at v)) others)
                    kept (take-last (dec max-sessions) sorted) ;; Keep one slot for sid
                    final (assoc (into {} kept) sid {:created-at now})]
                (if (compare-and-set! sessions old-sessions final)
                  sid
                  (recur))))))))))

(defn valid-session? [sid]
  (boolean (and sid (contains? @sessions sid))))

;; Pinboard HTTP Client

(defn pinboard-request
  "Calls a Pinboard API endpoint. Note: Pinboard uses HTTP GET for all
   operations including mutations (posts/add, posts/delete). This is
   their design, not a bug here."
  ([path config] (pinboard-request path {} config))
  ([path params {:keys [token endpoint]}]
   (let [url (str endpoint "/" path)
         q (merge params {:auth_token token :format "json"})]
     (log "debug" "API Request" {:method "GET" :path path :query-params (keys q)})
     (try
       (let [start (System/currentTimeMillis)
             resp (http-client/get url {:query-params q :throw false :timeout 10000})
             elapsed (- (System/currentTimeMillis) start)
             status (:status resp)
             body (:body resp)
             data (when-not (str/blank? body)
                    (try (json/parse-string body true)
                         (catch Exception _ {:raw body})))]
         (log "debug" "API Response" {:method "GET" :path path :status status :elapsed_ms elapsed})
         (cond
           (= status 429)
           (do (log :warn "Pinboard rate limit hit, retrying in 5s..." {})
               (Thread/sleep 5000)
               (pinboard-request path params {:token token :endpoint endpoint}))

           (>= status 400)
           {:error true :status status :message (or (:error data) (:raw data) "API error")}

           :else
           {:data data :status status}))
       (catch clojure.lang.ExceptionInfo e
         (log "error" "API ExceptionInfo" {:method "GET" :path path
                                           :error (ex-message e)
                                           :data (ex-data e)})
         (merge {:error true :message (ex-message e)} (ex-data e)))
       (catch Exception e
         (log "error" "API Exception" {:method "GET" :path path :error (.getMessage e)})
         {:error true :message (.getMessage e)})))))

;; Normalization

(defn parse-tags [s]
  (if (str/blank? s)
    #{}
    (->> (str/split (str/trim s) #"\s+")
         (filter seq)
         set)))

(defn normalize-bookmark [b]
  {:url (:href b)
   :title (:description b)
   :description (:extended b)
   :tags (parse-tags (:tags b))
   :shared (= "yes" (:shared b))
   :toread (= "yes" (:toread b))
   :time (:time b)})

;; Pinboard Operations

(defn fetch-bookmarks [cfg opts]
  (let [now (System/currentTimeMillis)
        {:keys [data checked-at last-update]} @bookmark-cache
        limit (:results opts)
        apply-limit (fn [d] (if limit (take limit d) d))
        fresh? (and data (< (- now checked-at) cache-ttl-ms))]
    (cond
      fresh?
      (apply-limit data)

      ;; Another thread is already fetching — return stale data if available
      (not (compare-and-set! fetch-inflight? false true))
      (if data
        (apply-limit data)
        {:error "Cache cold, fetch in progress"})

      :else
      (try
        (let [update-res (pinboard-request "posts/update" {} cfg)]
          (cond
            (:error update-res)
            (if data (apply-limit data) update-res)

            (= (:update_time (:data update-res)) last-update)
            (do (swap! bookmark-cache assoc :checked-at now)
                (apply-limit data))

            :else
            (let [res (pinboard-request "posts/all" opts cfg)]
              (if (:error res)
                (if data (apply-limit data) res)
                (let [normalized (map normalize-bookmark (:data res))]
                  (swap! bookmark-cache assoc
                         :last-update (:update_time (:data update-res))
                         :data normalized
                         :checked-at now)
                  (apply-limit normalized))))))
        (finally
          (reset! fetch-inflight? false))))))
(defn add-bookmark! [cfg {:keys [url title description tags shared toread]}]
  (let [res (pinboard-request "posts/add"
                              {:url url :description title :extended description
                               :tags (str/join " " (or tags []))
                               :shared (if (false? shared) "no" "yes")
                               :toread (if toread "yes" "no")}
                              cfg)]
    (when-not (:error res)
      (swap! bookmark-cache assoc :checked-at 0))
    res))

(defn delete-bookmark! [cfg {:keys [url]}]
  (let [res (pinboard-request "posts/delete" {:url url} cfg)]
    (when-not (:error res)
      (swap! bookmark-cache assoc :checked-at 0))
    res))

(defn recent-bookmarks [cfg count]
  (let [res (pinboard-request "posts/recent" {:count (str count)} cfg)]
    (if (:error res) res (map normalize-bookmark (:posts (:data res))))))

(defn list-tags [cfg]
  (pinboard-request "tags/get" {} cfg))

;; Tool Implementations

(def default-bookmark-limit 50)
(def default-search-limit 100)

(defn tool-list-bookmarks [cfg {:keys [tag limit]}]
  (let [bookmarks (fetch-bookmarks cfg {})] ;; Fetch all, no limit
    (if (:error bookmarks)
      bookmarks
      (let [effective-limit (or limit default-bookmark-limit)
            result (cond->> bookmarks
                     tag (filter #(contains? (:tags %) tag))
                     true (take effective-limit)
                     true vec)]
        result))))

(defn match? [q-lower b]
  (or (some-> (:title b) str/lower-case (str/includes? q-lower))
      (some-> (:description b) str/lower-case (str/includes? q-lower))
      (some #(str/includes? (str/lower-case %) q-lower) (:tags b))))

(defn tool-search-bookmarks [cfg {:keys [query limit]}]
  (let [res (fetch-bookmarks cfg {})
        q-lower (str/lower-case query)]
    (if (:error res) res (->> res (filter #(match? q-lower %)) (take (or limit default-search-limit))))))

(defn tool-add-bookmark [cfg args]
  (try
    (let [res (add-bookmark! cfg args)]
      (if (:error res) {:error (:message res)} {:status "ok"}))
    (catch Exception e
      {:error (.getMessage e)})))

(defn tool-delete-bookmark [cfg {:keys [url]}]
  (let [res (delete-bookmark! cfg {:url url})]
    (if (:error res) {:error (:message res)} {:status "ok"})))

(defn tool-recent-bookmarks [cfg {:keys [count]}]
  (recent-bookmarks cfg (or count 10)))

(defn tool-list-tags [cfg _]
  (let [res (list-tags cfg)]
    (if (:error res) res (:data res))))

;; Tool Registry

(def tools
  [{:name "list_bookmarks"
    :description (str "List all bookmarks. Filter by tag, limit results. "
                      "Default limit: " default-bookmark-limit)
    :inputSchema {:type "object"
                  :properties {:tag {:type "string" :description "Filter by tag"}
                               :limit {:type "integer"
                                       :description (str "Max results (default: " default-bookmark-limit ")")}}
                  :required []}}

   {:name "search_bookmarks"
    :description (str "Full-text search across title, description, and tags. "
                      "Default fetches " default-search-limit " bookmarks for search.")
    :inputSchema {:type "object"
                  :properties {:query {:type "string" :description "Search query"}
                               :limit {:type "integer"
                                       :description (str "Max bookmarks to fetch (default: " default-search-limit ")")}}
                  :required ["query"]}}

   {:name "add_bookmark"
    :description "Create a new bookmark."
    :inputSchema {:type "object"
                  :properties {:url {:type "string" :description "Bookmark URL"}
                               :title {:type "string" :description "Bookmark title"}
                               :description {:type "string" :description "Notes"}
                               :tags {:type "array" :items {:type "string"} :description "Tags"}
                               :shared {:type "boolean" :description "Shared (default true)"}
                               :toread {:type "boolean" :description "Add to read later"}}
                  :required ["url" "title"]}}

   {:name "delete_bookmark"
    :description "Delete a bookmark by URL."
    :inputSchema {:type "object"
                  :properties {:url {:type "string" :description "URL to delete"}}
                  :required ["url"]}}

   {:name "list_tags"
    :description "Get all tags with usage counts."
    :inputSchema {:type "object" :properties {} :required []}}

   {:name "recent_bookmarks"
    :description "Get most recent bookmarks."
    :inputSchema {:type "object"
                  :properties {:count {:type "integer" :description "Number of results"}}
                  :required []}}])

;; Tool Dispatch

(defn dispatch-tool [name args config]
  (case name
    "list_bookmarks" (tool-list-bookmarks config args)
    "search_bookmarks" (tool-search-bookmarks config args)
    "add_bookmark" (tool-add-bookmark config args)
    "delete_bookmark" (tool-delete-bookmark config args)
    "list_tags" (tool-list-tags config args)
    "recent_bookmarks" (tool-recent-bookmarks config args)
    {:error (str "Unknown tool: " name)}))

;; JSON-RPC Handlers

(defn handle-initialize [id _params]
  {:jsonrpc "2.0" :id id
   :result {:protocolVersion protocol-version
            :capabilities {:tools {:listChanged false}}
            :serverInfo server-info}})

(defn handle-tools-list [id _params]
  {:jsonrpc "2.0" :id id :result {:tools tools}})

(defn handle-tools-call [id params config]
  (let [tool-name (get params :name)
        args (get params :arguments)]
    (if (and (some? args) (not (map? args)))
      {:jsonrpc "2.0" :id id
       :error {:code -32600 :message "Invalid Request" :data "arguments must be an object"}}
      (let [args (or args {})]
        (log "info" "Tool Call" {:tool tool-name :args (vec (keys args))})
        (try
          (let [start (System/currentTimeMillis)
                result (dispatch-tool tool-name args config)
                elapsed (- (System/currentTimeMillis) start)
                content (if (:error result)
                          [{:type "text" :text (str "Error: " (:error result))}]
                          [{:type "text" :text (json/generate-string result {:pretty true})}])]
            (log "info" "Tool Result" {:tool tool-name :elapsed_ms elapsed :error (:error result)})
            {:jsonrpc "2.0" :id id
             :result {:content content :isError (boolean (:error result))}})
          (catch clojure.lang.ExceptionInfo e
            (log "error" "Tool Exception" {:tool tool-name :error (ex-message e)})
            {:jsonrpc "2.0" :id id
             :result {:content [{:type "text" :text (str "Error: " (ex-message e))}] :isError true}})
          (catch Exception e
            (log "error" "Tool Exception" {:tool tool-name :error (.getMessage e)})
            {:jsonrpc "2.0" :id id
             :result {:content [{:type "text" :text (str "Error: " (.getMessage e))}] :isError true}}))))))

(defn dispatch-rpc [body config]
  (let [method (keyword (:method body)) id (:id body) params (:params body)]
    (case method
      :initialize (handle-initialize id params)
      :notifications/initialized nil
      :tools/list (handle-tools-list id params)
      :tools/call (handle-tools-call id params config)
      {:jsonrpc "2.0" :id id
       :error {:code -32601 :message (str "Method not found: " (:method body))}})))

;; HTTP Server

(defn json-response [status body]
  {:status status :headers {"Content-Type" "application/json"}
   :body (json/generate-string body)})

(defn handle-mcp [request config]
  (let [body (:body request)
        parsed (try (if (string? body)
                      (json/parse-string body true)
                      (json/parse-stream (io/reader body) true))
                    (catch Exception _ :parse-failed))]
    (cond
      (nil? body)
      (json-response 400 {:error "Missing request body"})

      (nil? parsed)
      (json-response 400 {:error "Empty request body"})

      (= parsed :parse-failed)
      (json-response 400 {:error "Invalid JSON"})

      (= (:request-method request) :post)
      (let [session-id (or (get-in request [:headers "mcp-session-id"])
                           (get-in request [:headers "Mcp-Session-Id"]))
            new-session? (= "initialize" (:method parsed))
            sid (if new-session? (create-session!) session-id)
            base-headers {"Content-Type" "application/json"}
            headers (if (and new-session? sid)
                      (assoc base-headers "Mcp-Session-Id" sid)
                      base-headers)]
        (log "debug" "MCP Request" {:method (:method parsed) :session-id sid :new-session? new-session?})
        (if (and (not new-session?) (not (valid-session? sid)))
          (do (log "warn" "Session rejected" {:sid sid})
              (json-response 400 {:error "Invalid or missing Mcp-Session-Id"}))
          (let [response (dispatch-rpc parsed config)]
            (if (nil? response)
              {:status 204 :headers headers :body ""}
              {:status 200 :headers headers :body (json/generate-string response)}))))

      :else {:status 405 :body "Method Not Allowed"})))

(defn handler [request config]
  (let [uri (:uri request)]
    (cond
      (or (= uri "/mcp") (= uri "/mcp/")) (handle-mcp request config)
      (= uri "/health") {:status 200 :headers {"Content-Type" "application/json"}
                         :body (json/generate-string {:status "ok" :server "pinboard-mcp"})}
      :else {:status 404 :body "Not Found"})))

;; Entry Point

(defn -main [& _args]
  (try
    (let [config (get-pinboard-config)
          port (parse-port (System/getenv "PINBOARD_MCP_PORT"))
          srv (http/run-server (fn [req] (handler req config)) {:port port :ip "127.0.0.1"})
          actual-port (:local-port (meta srv))]
      (when (str/blank? (:token config))
        (.println System/err "Warning: PINBOARD_TOKEN not set. API calls will fail."))
      (println (json/generate-string
                {:status "started" :server "pinboard-mcp" :port actual-port
                 :url (str "http://127.0.0.1:" actual-port "/mcp") :tools (count tools)}))
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. #(http/server-stop! srv)))
      (deref (promise)))
    (catch Exception e
      (.println System/err (str "Error: " (.getMessage e)))
      (System/exit 1))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
