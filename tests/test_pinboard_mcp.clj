#!/usr/bin/env bb
;; tests/test_pinboard_mcp.clj

(require '[org.httpkit.server :as http-server]
         '[babashka.http-client :as http]
         '[cheshire.core :as json]
         '[clojure.test :refer [deftest is testing use-fixtures run-tests]]
         '[clojure.string :as str])

(load-file "pinboard_mcp.bb")

;; Fake Pinboard API

(def fixture-posts
  [{:href "https://example.com" :description "Example" :extended "Notes"
    :tags "ai tools" :shared "yes" :toread "no" :time "2026-01-15T10:00:00Z"}
   {:href "https://clojure.org" :description "Clojure" :extended "Lang"
    :tags "clojure programming" :shared "yes" :toread "yes" :time "2026-01-10T08:00:00Z"}
   {:href "https://babashka.org" :description "Babashka" :extended "Native"
    :tags "clojure babashka tools" :shared "no" :toread "no" :time "2026-01-05T14:00:00Z"}])

(def fixture-tags {:ai 5 :tools 12 :clojure 8 :programming 10 :babashka 3})

(def update-time "2026-01-15T10:00:00Z")
(def fake-update-time (atom update-time))
(def fake-error-mode (atom nil)) ;; :error-posts-all, :error-posts-update, :error-500
(def posts-all-call-count (atom 0))

(defn fake-handler [req]
  (let [u (:uri req)
        ;; Parse query params
        qs (when-let [s (:query-string req)]
             (into {} (for [pair (clojure.string/split s #"&")
                            :let [[k v] (clojure.string/split pair #"=" 2)]]
                        [k (or v "")])))]
    (cond
      (= u "/posts/all")
      (do
        (swap! posts-all-call-count inc)
        (if (= @fake-error-mode :error-posts-all)
          {:status 500 :headers {"Content-Type" "application/json"}
           :body (json/generate-string {:error "Internal Server Error"})}
          {:status 200 :headers {"Content-Type" "application/json"}
           :body (let [limit (some-> (get qs "results") Integer/parseInt)
                       items (if limit (take limit fixture-posts) fixture-posts)]
                   (json/generate-string items))}))

      (= u "/posts/recent") {:status 200 :headers {"Content-Type" "application/json"}
                             :body (json/generate-string {:posts fixture-posts})}
      (= u "/posts/delete") {:status 200 :headers {"Content-Type" "application/json"}
                             :body (json/generate-string {:result "done" :code "done"})}
      (= u "/posts/add") {:status 200 :headers {"Content-Type" "application/json"}
                          :body (json/generate-string {:result "done" :code "done"})}
      (= u "/tags/get") {:status 200 :headers {"Content-Type" "application/json"}
                         :body (json/generate-string fixture-tags)}
      (= u "/posts/update")
      (cond
        (= @fake-error-mode :error-posts-update)
        {:status 500 :headers {"Content-Type" "application/json"}
         :body (json/generate-string {:error "Internal Server Error"})}
        
        (= @fake-error-mode :error-500)
        {:status 500 :headers {"Content-Type" "application/json"} :body ""}
        
        :else
        {:status 200 :headers {"Content-Type" "application/json"}
         :body (json/generate-string {:update_time @fake-update-time})})

      :else {:status 404 :headers {"Content-Type" "application/json"}
             :body (json/generate-string {:error "Not found"})})))

(defn start-fake []
  (let [s (http-server/run-server fake-handler {:port 0})]
    {:port (:local-port (meta s)) :stop s}))

(defn stop-fake [{:keys [stop]}] (stop))

;; MCP Helpers

(defn mcp-init [url]
  (let [r (http/post url {:headers {"Content-Type" "application/json"}
                          :body (json/generate-string
                                 {:jsonrpc "2.0" :id "i" :method "initialize"
                                  :params {:protocolVersion "2025-03-26" :capabilities {}
                                           :clientInfo {:name "t" :version "0"}}})})
        sid (get-in r [:headers "mcp-session-id"])]
    (http/post url {:headers {"Mcp-Session-Id" sid} :body "{}"})
    sid))

(defn mcp-call [url sid m p]
  (json/parse-string
   (:body (http/post url {:headers {"Content-Type" "application/json" "Mcp-Session-Id" sid}
                          :body (json/generate-string
                                 {:jsonrpc "2.0" :id (str (java.util.UUID/randomUUID))
                                  :method m :params p})})) true))

(defn tool-call [url sid name args]
  (mcp-call url sid "tools/call" {:name name :arguments args}))

(defn tool-result [resp]
  (let [t (get-in resp [:result :content 0 :text])]
    (when t (try (json/parse-string t true) (catch Exception _ t)))))

;; Fixtures

(def ^:dynamic *url* nil)
(def ^:dynamic *sid* nil)

(defn fixture [f]
  (let [fake (start-fake)
        cfg {:token "t:t" :endpoint (str "http://127.0.0.1:" (:port fake))}
        srv (http-server/run-server (fn [r] (pinboard-mcp/handler r cfg)) {:port 0 :ip "127.0.0.1"})
        port (:local-port (meta srv))
        url (str "http://127.0.0.1:" port "/mcp")]
    (try
      (binding [*url* url *sid* (mcp-init url)] (f))
      (finally
        (srv)
        (stop-fake fake)))))

(use-fixtures :each fixture)

;; Tests

(deftest test-init
  (let [r (http/post *url* {:headers {"Content-Type" "application/json"}
                            :body (json/generate-string
                                   {:jsonrpc "2.0" :id "1" :method "initialize"
                                    :params {:protocolVersion "2025-03-26" :capabilities {}
                                             :clientInfo {:name "t" :version "0"}}})})
        b (json/parse-string (:body r) true)]
    (is (= 200 (:status r)))
    (is (= "2025-03-26" (get-in b [:result :protocolVersion])))
    (is (= "pinboard-mcp" (get-in b [:result :serverInfo :name])))
    (is (get-in r [:headers "mcp-session-id"]))))

(deftest test-tools-list
  (let [r (mcp-call *url* *sid* "tools/list" {})
        tools (get-in r [:result :tools])]
    (is (= 6 (count tools)))
    (is (every? :name tools))))

(deftest test-invalid-session
  (let [r (http/post *url* {:headers {"Content-Type" "application/json" "Mcp-Session-Id" "bad"}
                            :body (json/generate-string {:jsonrpc "2.0" :id "1" :method "tools/list" :params {}})
                            :throw false})]
    (is (= 400 (:status r)))))

(deftest test-health
  (let [r (http/get (str/replace *url* "/mcp" "/health"))
        b (json/parse-string (:body r) true)]
    (is (= 200 (:status r)))
    (is (= "ok" (:status b)))))

(deftest test-list-bookmarks
  (let [result (tool-result (tool-call *url* *sid* "list_bookmarks" {}))]
    (is (seq result))
    (is (= 3 (count result)))
    (is (every? :url result))))

(deftest test-list-limit
  (testing "list_bookmarks respects limit parameter"
    (let [result (tool-result (tool-call *url* *sid* "list_bookmarks" {:limit 2}))]
      (is (= 2 (count result))))))

(deftest test-add
  (let [result (tool-result (tool-call *url* *sid* "add_bookmark"
                                       {:url "https://x.com" :title "X" :tags ["t"]}))]
    (is (= {:status "ok"} result))))

(deftest test-delete
  (let [result (tool-result (tool-call *url* *sid* "delete_bookmark" {:url "x.com"}))]
    (is (= {:status "ok"} result))))

(deftest test-recent
  (let [result (tool-result (tool-call *url* *sid* "recent_bookmarks" {:count 3}))]
    (is (= 3 (count result)))))

(deftest test-recent-default
  (let [result (tool-result (tool-call *url* *sid* "recent_bookmarks" {}))]
    (is (seq result))))

(deftest test-tags
  (let [result (tool-result (tool-call *url* *sid* "list_tags" {}))]
    (is (= 5 (get result :ai)))
    (is (= 12 (get result :tools)))))

(deftest test-search
  (let [result (tool-result (tool-call *url* *sid* "search_bookmarks" {:query "Clojure"}))]
    (is (seq result))))

(deftest test-search-empty
  (let [result (tool-result (tool-call *url* *sid* "search_bookmarks" {:query "xyz123"}))]
    (is (or (nil? result) (empty? result)))))

(deftest test-cache-ttl-refresh
  (testing "Cache updates when update_time changes"
    ;; Reset global state
    (reset! posts-all-call-count 0)
    (reset! fake-update-time update-time)
    (reset! fake-error-mode nil)
    ;; Reset the bookmark cache (internal to pinboard_mcp.bb)
    (reset! pinboard-mcp/bookmark-cache {:last-update nil :data nil :checked-at 0})
    
    ;; First call - should fetch posts/all (count becomes 1)
    (let [result1 (tool-result (tool-call *url* *sid* "list_bookmarks" {}))]
      (is (seq result1))
      (is (= 1 @posts-all-call-count)))
    
    ;; Second call immediately - should use cache (count stays 1)
    (let [result2 (tool-result (tool-call *url* *sid* "list_bookmarks" {}))]
      (is (seq result2))
      (is (= 1 @posts-all-call-count)))
    
    ;; Change update_time (simulating a remote update)
    (reset! fake-update-time "2026-02-20T12:00:00Z")
    
    ;; Manually make the cache stale by setting checked-at far in the past
    ;; (since System/currentTimeMillis is real time, we can't mock it easily)
    (swap! pinboard-mcp/bookmark-cache assoc :checked-at (- (System/currentTimeMillis) 120000)) ;; 2 minutes ago
    
    ;; Third call - should see update_time change, call posts/update, then posts/all (count becomes 2)
    (let [result3 (tool-result (tool-call *url* *sid* "list_bookmarks" {}))]
      (is (seq result3))
      (is (= 2 @posts-all-call-count)))))

(deftest test-api-error-handling
  (testing "Handles API errors gracefully"
    (reset! fake-error-mode :error-posts-update)
    ;; Should handle posts/update error by returning cached data if available
    ;; Since cache was populated in test-cache-ttl-refresh, it should return that
    (let [result (tool-result (tool-call *url* *sid* "list_bookmarks" {}))]
      (is (seq result)) ;; Should return cached data, not crash
      ;; Note: The current implementation actually returns the cache if posts/update fails
      ;; but only if :data is present in cache. Since we populated it, it should work.
      )
    (reset! fake-error-mode nil)))

(deftest test-invalid-json-body
  (testing "Returns 400 for invalid JSON"
    (let [r (http/post *url* {:headers {"Content-Type" "application/json" "Mcp-Session-Id" *sid*}
                              :body "{invalid json}"
                              :throw false})]
      (is (= 400 (:status r)))
      (is (str/includes? (:body r) "Invalid JSON")))))

;; Runner

(defn -main [& _]
  (println "\npinboard-mcp integration tests")
  (println "================================")
  (let [{:keys [pass fail error]} (run-tests *ns*)]
    (println (str "\nResults: " pass " passed, " fail " failed, " error " errors"))
    (System/exit (if (zero? (+ fail error)) 0 1))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
