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
  "Pinned to the commits the published results were actually produced against,
  not to whatever is at HEAD today."
  [{:name "io-multiformats" :sha "b3b157e683a943d4f2e9362d9366cceefff3f656"}
   {:name "dag-cbor" :sha "4a8a08efb8668fae9c1884d390a6610ca42890d8"}
   {:name "io-ipld" :sha "5d8de535ebc2c10bb90a671ea6019783ecbd2137"}
   {:name "prolly-tree" :sha "1f2e779352d0b4d0eca517c9f139925f0e21aac6"}
   {:name "crdt" :sha "34ae7931914f974f759de8806ddb2f4b24c7f0ea"}
   ;; the real semantic definition identity: canonical DAG-CBOR of checked,
   ;; alpha-normalised, name-free IR (used by run-semantic.cljs)
   {:name "codebase" :sha "bc85d8d11e6013cd3d75dde657f4e4526c9f7bf6"}])

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
