(ns kotobase.critical-path-test
  "IPFS is a provider adapter, not a dependency of the plane.

  The READMEs positioned it architecturally (`the target is the distributed
  web`, `trampolines synchronous IPLD traversal over asynchronous S3/R2/IPFS
  reads`), which reads as though the plane needs it. Structurally it does not:
  the core has no IPFS reference and no dependency on the IPFS adapter. That
  is worth locking in rather than restating, because a positioning claim is
  the kind that stops being true without anything failing."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- sources []
  (->> (file-seq (io/file "src"))
       (filter #(.isFile %))
       (filter #(re-find #"\.clj[cs]?$" (.getName %)))))

(deftest ipfs-is-not-on-the-plane-s-critical-path
  (let [offenders (->> (sources)
                       (keep (fn [f]
                               (let [text (slurp f)]
                                 (when (re-find #"(?i)\bipfs\b" text)
                                   (.getPath f)))))
                       vec)]
    (is (= [] offenders)
        (str "the plane's own sources now mention IPFS: " offenders
             ". It is a provider adapter (kotobase-storage-ipfs) and a served "
             "surface (kotobase-protocols), and the core must not need it.")))

  (let [deps (slurp (io/file "deps.edn"))]
    (is (not (str/includes? deps "kotobase-storage-ipfs"))
        "the core has taken a dependency on the IPFS adapter")
    ;; and the storage port itself stays provider-neutral: the core depends on
    ;; the port, and the port must not know about any one provider
    (is (str/includes? deps "kotobase-storage")
        "the core no longer depends on the storage port at all")))
