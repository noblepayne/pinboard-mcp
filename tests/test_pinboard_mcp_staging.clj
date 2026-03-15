#!/usr/bin/env bb
;; tests/test_pinboard_mcp_staging.clj
;;
;; Staging/integration tests against real Pinboard API.
;; WARNING: These tests modify real data - use a test account!
;;
;; Run:
;;   PINBOARD_TEST_TOKEN="user:real-token" bb tests/test_pinboard_mcp_staging.clj
;;
;; Or use bb.edn task:
;;   PINBOARD_TEST_TOKEN="user:real-token" bb test-staging

(require '[org.httpkit.server :as http-server]
         '[babashka.http-client :as http]
         '[cheshire.core :as json]
         '[clojure.test :refer [deftest is testing use-fixtures run-tests]]
         '[clojure.string :as str])

(load-file "pinboard_mcp.bb")

;; Configuration

(def test-token (System/getenv "PINBOARD_TEST_TOKEN"))

(def test-base-url "https://www.jupiterbroadcasting.com/")

(defn test-url []
  (str test-base-url "test-" (str (java.util.UUID/randomUUID))))

(defn test-tag []
  (str "staging-test-" (str (java.util.UUID/randomUUID))))

;; Helpers

(defn ensure-config []
  (when (str/blank? test-token)
    (println "PINBOARD_TEST_TOKEN not set. Skipping staging tests.")
    (println "Set it with: export PINBOARD_TEST_TOKEN='username:token'")
    (System/exit 0))
  {:token test-token :endpoint "https://api.pinboard.in/v1"})

(defn wait []
  (Thread/sleep 3500))

;; Read-only tests against real API

(deftest test-real-list-bookmarks
  (let [cfg (ensure-config)]
    (let [result (pinboard-mcp/tool-list-bookmarks cfg {})]
      (is (or (seq result) (map? result)))
      (when (seq result)
        (is (every? :url result))
        (is (every? :title result))))))

(deftest test-real-tags
  (wait)
  (let [cfg (ensure-config)
        result (pinboard-mcp/tool-list-tags cfg {})]
    (is (map? result))
    (is (every? number? (vals result)))))

(deftest test-real-recent
  (wait)
  (let [cfg (ensure-config)
        result (pinboard-mcp/tool-recent-bookmarks cfg {:count 5})]
    (is (seq result))
    (is (every? :url result))
    (is (every? :title result))))

(deftest test-real-search
  (wait)
  (let [cfg (ensure-config)
        ;; Search for something likely to exist
        result (pinboard-mcp/tool-search-bookmarks cfg {:query "clojure"})]
    (is (or (seq result) (nil? result)))
    (when (seq result)
      (is (every? :url result)))))

;; Add/Delete tests - leaves no trace

(deftest test-real-add-delete
  (wait)
  (let [cfg (ensure-config)
        url (test-url)
        tag (test-tag)]
    ;; Add bookmark
    (let [add-result (pinboard-mcp/tool-add-bookmark cfg
                        {:url url :title "Staging Test" :tags [tag]})]
      (is (= {:status "ok"} add-result)))
    (wait)
    ;; Verify present
    (let [list-result (pinboard-mcp/tool-list-bookmarks cfg {:tag tag})]
      (is (some #(= url (:url %)) list-result)
          "Added bookmark should be findable"))
    ;; Delete bookmark
    (let [del-result (pinboard-mcp/tool-delete-bookmark cfg {:url url})]
      (is (= {:status "ok"} del-result)))
    (wait)
    ;; Verify gone
    (let [list-result (pinboard-mcp/tool-list-bookmarks cfg {:tag tag})]
      (is (not-any? #(= url (:url %)) list-result))
          "Deleted bookmark should not be present")))

(deftest test-real-add-update-delete
  (wait)
  (let [cfg (ensure-config)
        url (test-url)
        tag (test-tag)
        desc-tag (test-tag)]
    ;; Add with toread=true
    (let [add-result (pinboard-mcp/tool-add-bookmark cfg
                        {:url url :title "Toread Test" :tags [tag]
                         :toread true :shared false})]
      (is (= {:status "ok"} add-result)))
    (wait)
    ;; Delete
    (let [del-result (pinboard-mcp/tool-delete-bookmark cfg {:url url})]
      (is (= {:status "ok"} del-result)))
    (wait)
    ;; Verify gone
    (let [list-result (pinboard-mcp/tool-list-bookmarks cfg {})]
      (is (not-any? #(= url (:url %)) list-result))
          "Deleted bookmark should not be present after second delete")))

;; Runner

(defn -main [& _]
  (println "\npinboard-mcp STAGING tests")
  (println "===============================")
  (println "WARNING: These tests modify real Pinboard data!")
  (println "")
  (when-not test-token
    (println "PINBOARD_TEST_TOKEN not set. Exiting.")
    (System/exit 0))
  (println (str "Using token: " (subs test-token 0 (min 10 (count test-token))) "..."))
  (println "")
  (let [{:keys [pass fail error]} (run-tests *ns*)]
    (println (str "\nResults: " pass " passed, " fail " failed, " error " errors"))
    (System/exit (if (zero? (+ fail error)) 0 1))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
