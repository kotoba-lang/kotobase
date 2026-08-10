(ns kotobase.cid-graph-replay-qualification-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- source-file []
  (or (some-> (System/getenv "KOTOBASE_SOURCE_ROOT")
              (io/file "kotoba/cid_graph_replay.kotoba")
              (#(when (.isFile %) %)))
      (let [candidate (io/file "kotoba/cid_graph_replay.kotoba")]
        (when (.isFile candidate) candidate))
      (throw (ex-info "Kotobase CID graph replay source not found" {}))))

(defn- compiler-root []
  (let [resource (io/resource "kotoba/compiler/core.clj")]
    (when-not (= "file" (.getProtocol resource))
      (throw (ex-info "compiler source must be a checked-out file resource"
                      {:resource (str resource)})))
    (-> resource .toURI io/file
        .getParentFile .getParentFile .getParentFile .getParentFile)))

(defn- host-target []
  (case (str/lower-case (System/getProperty "os.arch"))
    ("aarch64" "arm64") [:aarch64-kotoba-v1 "aarch64"]
    ("amd64" "x86_64") [:x86_64-kotoba-v1 "x86_64"]
    (throw (ex-info "unsupported native qualification host"
                    {:os-arch (System/getProperty "os.arch")}))))

(defn- temp-dir []
  (.toFile (Files/createTempDirectory "kotobase-cid-graph-"
                                      (make-array FileAttribute 0))))

(defn- delete-tree! [root]
  (doseq [file (reverse (file-seq root))] (io/delete-file file true)))

(defn- write-bytes! [file bytes]
  (with-open [out (io/output-stream file)] (.write out ^bytes bytes)))

(defn- run-wasm [source directory]
  (let [compiled (compiler/compile-source source :wasm32-kotoba-v1 {:allow #{}})
        artifact (io/file directory "cid-graph-replay.wasm")
        browser-host (io/file (compiler-root) "runtime/browser-host.mjs")
        encoded (.encodeToString (java.util.Base64/getEncoder) ^bytes (:bytes compiled))]
    (write-bytes! artifact (:bytes compiled))
    (let [javascript
          (str "import(" (pr-str (str (.toURI browser-host))) ").then(async m=>{"
               "const bytes=Buffer.from(process.argv[1],'base64');"
               "for(const name of ['check-cid-order','check-forward','check-reversed',"
               "'check-shuffled-ancestry','check-repeated-merge','check-criss-cross']){"
               "const h=await m.instantiateKotoba(bytes);"
               "console.log(name+'='+h.instance.exports[name]().toString());}"
               "}).catch(e=>{console.error(e);process.exit(70)})")
          {:keys [exit out err]}
          (shell/sh "node" "--input-type=module" "-e" javascript encoded)]
      (when-not (zero? exit)
        (throw (ex-info "Kotoba Wasm execution failed"
                        {:exit exit :stdout out :stderr err})))
      {:format (:format compiled)
       :results (into {}
                      (map (fn [line]
                             (let [[name result] (str/split line #"=" 2)]
                               [(keyword name) (Long/parseLong result)])))
                      (str/split-lines (str/trim out)))
       :bytes (alength ^bytes (:bytes compiled))})))

(defn- run-native [source directory]
  (let [[target isa] (host-target)
        compiled (compiler/compile-source source target {:allow #{}})
        code (io/file directory "cid-graph-replay.bin")
        loader (io/file directory "kexe-loader")
        loader-source (io/file (compiler-root) "tools/kexe_loader.c")
        build (shell/sh "cc" "-std=c11" "-O2" "-Wall" "-Wextra" "-Werror"
                        (.getPath loader-source) "-o" (.getPath loader))
        export-names ['check-cid-order 'check-forward 'check-reversed
                      'check-shuffled-ancestry 'check-repeated-merge
                      'check-criss-cross]]
    (when-not (zero? (:exit build))
      (throw (ex-info "Kotoba native loader build failed" build)))
    (write-bytes! code (byte-array (map #(unchecked-byte (bit-and (int %) 0xff))
                                        (get-in compiled [:artifact :code]))))
    (let [results
          (into {}
                (map (fn [export-name]
                       (let [offset (get-in compiled [:artifact :exports export-name :offset])
                             {:keys [exit out err]}
                             (shell/sh (.getPath loader) (.getPath code) (str offset) "0" isa "-"
                                       :env (assoc (into {} (System/getenv))
                                                   "KEXE_STRUCTURED_REPORT" "1"))]
                         (when-not (zero? exit)
                           (throw (ex-info "Kotoba native execution failed"
                                           {:export export-name :exit exit :stderr err})))
                         [(keyword (name export-name))
                          (:result (edn/read-string (str/trim out)))])))
                export-names)]
      {:format (:format compiled)
       :results results
       :code-bytes (count (get-in compiled [:artifact :code]))
       :isa isa})))

(deftest fixed-cid-frontier-graph-semantics-executes-on-rust-free-backends
  (let [source (slurp (source-file))
        directory (temp-dir)]
    (try
      (let [wasm (run-wasm source directory)
            native (run-native source directory)]
        (testing "the same admitted .kotoba source executes on actual backends"
          (is (= :wasm/v1 (:format wasm)))
          (is (= :kexe/v1 (:format native)))
          (is (= {:check-cid-order 1 :check-forward 1 :check-reversed 1
                  :check-shuffled-ancestry 1 :check-repeated-merge 1
                  :check-criss-cross 1}
                 (:results wasm)))
          (is (= (:results wasm) (:results native))))
        (testing "qualification emits non-empty executable artifacts"
          (is (pos? (:bytes wasm)))
          (is (pos? (:code-bytes native))))
        (println
         (pr-str {:schema :kotobase.rust-free-graph-semantics-qualification/v1
                  :source "kotoba/cid_graph_replay.kotoba"
                  :compiler-revision "875e3882dd76508d1a9a51f7f5ac0f1e563e490d"
                  :wasm wasm
                  :native native
                  :fixed-scalarized-ancestry-matrix-qualified true
                  :native-decoded-dag-traversal-qualified false})))
      (finally (delete-tree! directory)))))
