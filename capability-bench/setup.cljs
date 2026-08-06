#!/usr/bin/env nbb
;; Fetch the pinned workspace libraries this benchmark is built on and print
;; the nbb classpath for them.
;;
;;   nbb setup.cljs                    # clone/verify into .deps/
;;   nbb setup.cljs --print-classpath  # just print the classpath
;;
;; These are real kotoba-lang libraries, not vendored copies: the Prolly Tree,
;; the IPLD/dag-cbor codec, the multiformats CID assembly, and the CRDT
;; primitives all come from their own repos at a pinned SHA, so the benchmark
;; cannot quietly diverge from what kotobase actually runs.

(ns setup
  (:require [clojure.string :as str]
            ["child_process" :as cp]
            ["fs" :as fs]
            ["path" :as path]))

(def deps
  [{:name "io-multiformats" :sha "eec3ee85c458ea830be94518b9a42f4a8f1aee40"}
   {:name "dag-cbor"}
   {:name "io-ipld" :sha "4591764514239c4777f227a7a56d46c901e2a4a0"}
   {:name "prolly-tree"}
   {:name "crdt"}])

(def dep-dir ".deps")

(defn- sh [cmd] (.execSync cp cmd #js {:stdio "inherit"}))

(defn- ensure! [{:keys [name sha]}]
  (let [dir (path/join dep-dir name)]
    (when-not (fs/existsSync dir)
      (println "cloning" name "…")
      (sh (str "GIT_LFS_SKIP_SMUDGE=1 git clone "
               (if sha "" "--depth 1 ")
               "https://github.com/kotoba-lang/" name " " dir))
      (when sha
        (sh (str "git -C " dir " checkout --quiet " sha))))
    (path/join dir "src")))

(defn -main []
  (let [print-only? (some #(= % "--print-classpath") (js->clj (.-argv js/process)))
        paths (if print-only?
                (mapv #(path/join dep-dir (:name %) "src") deps)
                (mapv ensure! deps))]
    (println (str/join ":" (conj (vec paths) "src")))))

(-main)
