(ns kotobase.cid-graph-replay-qualification-test
  "Qualification: fixed-vector CID graph replay runs on real wasm + native
  backends. All host filesystem/process access goes through the ONE adapter
  namespace `kotobase.qualification-host` — no java.* or clojure.java.*
  here; paths are strings, bytes are arrays, processes are {:exit :out :err}."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotobase.qualification-host :as host]))

(defn- source-file []
  (or (some-> (host/env-or "KOTOBASE_SOURCE_ROOT" nil)
              (host/path-join "kotoba/cid_graph_replay.kotoba")
              (#(when (host/file-exists? %) %)))
      (let [candidate "kotoba/cid_graph_replay.kotoba"]
        (when (host/file-exists? candidate) candidate))
      (throw (ex-info "Kotobase CID graph replay source not found" {}))))

(defn- compiler-root []
  ;; resource is <root>/src/kotoba/compiler/core.clj — four parents up to <root>.
  (let [resource-path (host/resource-path! "kotoba/compiler/core.clj")]
    (-> resource-path host/path-parent host/path-parent host/path-parent host/path-parent)))

(defn- host-target []
  (case (str/lower-case (host/os-arch))
    ("aarch64" "arm64") [:aarch64-kotoba-v1 "aarch64"]
    ("amd64" "x86_64") [:x86_64-kotoba-v1 "x86_64"]
    (throw (ex-info "unsupported native qualification host"
                    {:os-arch (host/os-arch)}))))

(defn- run-wasm [source directory]
  (let [compiled (compiler/compile-source source :wasm32-kotoba-v1 {:allow #{}})
        artifact (host/path-join directory "cid-graph-replay.wasm")
        browser-host (host/path-join (compiler-root) "runtime/browser-host.mjs")
        encoded (host/b64-encode (:bytes compiled))]
    (host/write-bytes! artifact (:bytes compiled))
    (let [javascript
          (str "import(" (pr-str (host/path-uri-string browser-host)) ").then(async m=>{"
               "const bytes=Buffer.from(process.argv[1],'base64');"
               "for(const name of ['check-cid-order','check-forward','check-reversed',"
               "'check-shuffled-ancestry','check-repeated-merge','check-criss-cross']){"
               "const h=await m.instantiateKotoba(bytes);"
               "console.log(name+'='+h.instance.exports[name]().toString());}"
               "}).catch(e=>{console.error(e);process.exit(70)})")
          {:keys [exit out err]}
          (host/exec! ["node" "--input-type=module" "-e" javascript encoded])]
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
        code (host/path-join directory "cid-graph-replay.bin")
        loader (host/path-join directory "kexe-loader")
        loader-source (host/path-join (compiler-root) "tools/kexe_loader.c")
        build (host/exec! ["cc" "-std=c11" "-O2" "-Wall" "-Wextra" "-Werror"
                           loader-source "-o" loader])
        export-names ['check-cid-order 'check-forward 'check-reversed
                      'check-shuffled-ancestry 'check-repeated-merge
                      'check-criss-cross]]
    (when-not (zero? (:exit build))
      (throw (ex-info "Kotoba native loader build failed" build)))
    (host/write-bytes! code (byte-array (map #(unchecked-byte (bit-and (int %) 0xff))
                                             (get-in compiled [:artifact :code]))))
    (let [results
          (into {}
                (map (fn [export-name]
                       (let [offset (get-in compiled [:artifact :exports export-name :offset])
                             {:keys [exit out err]}
                             (host/exec! [loader code (str offset) "0" isa "-"
                                          {:env (assoc (into {} (System/getenv))
                                                       "KEXE_STRUCTURED_REPORT" "1")}])]
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
  (let [source (host/read-string! (source-file))
        directory (host/temp-dir! "kotobase-cid-graph-")]
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
                  :compiler-revision "3febafca47ec89eb7167a7756cdd3fd579686dc3"
                  :wasm wasm
                  :native native
                  :fixed-scalarized-ancestry-matrix-qualified true
                  :native-decoded-dag-traversal-qualified false})))
      (finally (host/delete-tree! directory)))))
