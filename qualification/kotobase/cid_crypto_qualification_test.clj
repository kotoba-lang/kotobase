(ns kotobase.cid-crypto-qualification-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:private capability-policy
  {:allow #{[:cap/call 1] [:cap/call 3]}})

(def ^:private external-capability-policy
  {:allow #{[:cap/call 14]}})

(def ^:private execution-metadata {:fuel 1048576})

(defn- project-file [& segments]
  (let [root (or (System/getenv "KOTOBASE_SOURCE_ROOT") ".")]
    (apply io/file root segments)))

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
  (.toFile (Files/createTempDirectory "kotobase-cid-crypto-"
                                      (make-array FileAttribute 0))))

(defn- delete-tree! [root]
  (doseq [file (reverse (file-seq root))] (io/delete-file file true)))

(defn- write-bytes! [file bytes]
  (with-open [out (io/output-stream file)] (.write out ^bytes bytes)))

(defn- parse-results [output]
  (into {}
        (map (fn [line]
               (let [[name result] (str/split line #"=" 2)]
                 [(keyword name) (Long/parseLong result)])))
        (str/split-lines (str/trim output))))

(def ^:private criss-cross-commit-cids
  ["bafyreiffw4suqdc4xspoynrmgmopmb7coeswj7t3bepkt5d4ekckhs7uhi"
   "bafyreifzwgiv54mo6jqwg2shy67hlc22ms75nnwil3m6xy2vd33moek5ga"
   "bafyreiagclnpln5ktgej5r5bfuatmxrffnjn6dxfavys4k32glw53fnrmy"
   "bafyreigryi4wmpyrfo3nzyxwtf4anas2zegwpc6v6633d53ido2nzmj52i"
   "bafyreigv4olpcmyj74fxpxtjxruqqyf5pyt3nlpyscpvoivzeyht35s6bi"
   "bafyreih2imvhagfyy5fqo6eaksge4qtrfo72iz22evd3b52ybnn7sb6eyi"
   "bafyreifq3ymz7r22wdxcoft2pgligaisqiuqw43o773iaz3aaaonhthqqu"
   "bafyreidgroqyvk5h6a6susrowhncmd6whbmbvzgpqqy53nyqjecqpvnf4a"])

(defn- cbor-text-item-hex [value]
  (str "783b" (apply str (map #(format "%02x" (int %)) value))))

(defn- external-block-entries []
  (let [fixture-source (slurp (project-file "kotoba" "cid_dag_traversal.kotoba"))
        envelopes (mapv second
                        (re-seq #"\(defn- cc-[0-7]-hex \[\] :string \"([0-9a-f]+)\"\)"
                                fixture-source))
        keys (mapv cbor-text-item-hex criss-cross-commit-cids)]
    (when-not (= 8 (count envelopes))
      (throw (ex-info "formal criss-cross envelopes are incomplete"
                      {:count (count envelopes)})))
    (into [["frontier" (peek keys)]]
          (concat (map-indexed (fn [index key] [(str "node" index) key]) keys)
                  (map-indexed (fn [index key] [(str "index:" key) (str index)]) keys)
                  (map (fn [key envelope]
                         [(str "offset:" key)
                          (str (.indexOf ^String envelope "67706172656e7473"))])
                       keys envelopes)
                  (map (fn [key envelope] [(str "block:" key) envelope])
                       keys envelopes)))))

(defn- write-block-provider! [directory entries]
  (let [file (io/file directory "cid-block-provider.tsv")]
    (spit file (str (str/join "\n" (map #(str (first %) "\t" (second %)) entries))
                    "\n"))
    file))

(defn- javascript-map [entries]
  (str "new Map(["
       (str/join "," (map (fn [[key value]]
                            (str "[" (pr-str key) "," (pr-str value) "]"))
                          entries))
       "])"))

(defn- run-wasm
  ([source directory export-names stem]
   (run-wasm source directory export-names stem capability-policy [1 3] []))
  ([source directory export-names stem policy allow-capabilities block-entries]
  (let [compiled (compiler/compile-source source :wasm32-kotoba-v1
                                          policy execution-metadata)
        artifact (io/file directory (str stem ".wasm"))
        browser-host (io/file (compiler-root) "runtime/browser-host.mjs")
        encoded (.encodeToString (java.util.Base64/getEncoder) ^bytes (:bytes compiled))
        javascript
        (str "import(" (pr-str (str (.toURI browser-host))) ").then(async m=>{"
             "const c=await import('node:crypto');"
             "const bytes=Buffer.from(process.argv[1],'base64');"
             "const seed=Buffer.from('0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20','hex');"
             "const key=c.createPrivateKey({key:Buffer.concat([Buffer.from('302e020100300506032b657004220420','hex'),seed]),format:'der',type:'pkcs8'});"
             "const pub=c.createPublicKey(key).export({format:'der',type:'spki'}).subarray(-32);"
             "const blocks=" (javascript-map block-entries) ";"
             "const provider=(id,request)=>{if(id===14){if(!blocks.has(request))throw Error('missing-block');return blocks.get(request)}"
             "if(!/^hex:[0-9a-f]*$/.test(request))throw Error('bad-request');"
             "const msg=Buffer.from(request.slice(4),'hex');"
             "if(id===3)return c.createHash('sha256').update(msg).digest('hex');"
             "if(id===1)return pub.toString('hex')+':'+c.sign(null,msg,key).toString('hex');"
             "throw Error('capability-denied')};"
             "for(const name of [" (str/join "," (map pr-str export-names)) "]){"
             "const h=await m.instantiateKotoba(bytes,{allowCapabilities:["
             (str/join "," allow-capabilities) "],typedCapCall:provider});"
             "console.log(name+'='+h.instance.exports[name]().toString());}"
             "}).catch(e=>{console.error(e);process.exit(70)})")]
    (write-bytes! artifact (:bytes compiled))
    (let [{:keys [exit out err]}
          (shell/sh "node" "--input-type=module" "-e" javascript encoded)]
      (when-not (zero? exit)
        (throw (ex-info "Kotoba Wasm crypto execution failed"
                        {:exit exit :stdout out :stderr err})))
      {:format (:format compiled)
       :results (parse-results out)
       :bytes (alength ^bytes (:bytes compiled))}))))

(defn- openssl-flags []
  (if (= "Mac OS X" (System/getProperty "os.name"))
    (let [{:keys [exit out err]} (shell/sh "brew" "--prefix" "openssl@3")]
      (when-not (zero? exit)
        (throw (ex-info "OpenSSL 3 is required for native qualification"
                        {:exit exit :stderr err})))
      (let [prefix (str/trim out)]
        [(str "-I" prefix "/include") (str "-L" prefix "/lib") "-lcrypto"]))
    ["-lcrypto"]))

(defn- run-native
  ([source directory export-names stem]
   (run-native source directory export-names stem capability-policy "1,3" nil))
  ([source directory export-names stem policy allow-csv block-provider-file]
  (let [[target isa] (host-target)
        compiled (compiler/compile-source source target
                                          policy execution-metadata)
        code (io/file directory (str stem ".bin"))
        loader (io/file directory "kexe-provider-loader")
        qualification-root (project-file "qualification" "native")
        compiler-tools (io/file (compiler-root) "tools")
        loader-source (io/file qualification-root "kexe_provider_loader.c")
        provider-source (io/file qualification-root "kotobase_crypto_provider.c")
        build (apply shell/sh
                     (concat ["cc" "-std=c11" "-O2" "-Wall" "-Wextra" "-Werror"
                              (str "-I" (.getPath qualification-root))
                              (str "-I" (.getPath compiler-tools))
                              (.getPath loader-source) (.getPath provider-source)
                              "-o" (.getPath loader)]
                             (openssl-flags)))
        export-symbols (mapv symbol export-names)]
    (when-not (zero? (:exit build))
      (throw (ex-info "Kotoba native crypto loader build failed" build)))
    (write-bytes! code
                  (byte-array
                   (map #(unchecked-byte (bit-and (int %) 0xff))
                        (get-in compiled [:artifact :code]))))
    (let [results
          (into {}
                (map (fn [export-name]
                       (let [offset (get-in compiled [:artifact :exports export-name :offset])
                             {:keys [exit out err]}
                             (apply shell/sh
                                    (concat [(.getPath loader) (.getPath code)
                                             (str offset) "0" isa allow-csv]
                                            [:env (cond-> {"KEXE_STRUCTURED_REPORT" "1"}
                                                    block-provider-file
                                                    (assoc "KOTOBASE_BLOCK_PROVIDER_FILE"
                                                           (.getPath block-provider-file)))]))]
                         (when-not (zero? exit)
                           (throw (ex-info "Kotoba native crypto execution failed"
                                           {:export export-name :exit exit :stderr err})))
                         [(keyword (name export-name))
                          (:result (edn/read-string (str/trim out)))])))
                export-symbols)]
      {:format (:format compiled)
       :results results
       :code-bytes (count (get-in compiled [:artifact :code]))
       :isa isa}))))

(deftest sha256-and-ed25519-execute-on-rust-free-kotoba-backends
  (let [source-file (project-file "kotoba" "cid_crypto_primitives.kotoba")
        directory (temp-dir)]
    (try
      (let [source (slurp source-file)
            export-names ["check-sha256" "check-ed25519"]
            wasm (run-wasm source directory export-names "cid-crypto-primitives")
            native (run-native source directory export-names "cid-crypto-primitives")
            expected {:check-sha256 1 :check-ed25519 1}]
        (testing "both actual targets use the same admitted Kotoba capability program"
          (is (= :wasm/v1 (:format wasm)))
          (is (= :kexe/v1 (:format native)))
          (is (= expected (:results wasm)))
          (is (= (:results wasm) (:results native))))
        (testing "both executable artifacts are non-empty"
          (is (pos? (:bytes wasm)))
          (is (pos? (:code-bytes native))))
        (println
         (pr-str {:schema :kotobase.rust-free-crypto-qualification/v1
                  :source "kotoba/cid_crypto_primitives.kotoba"
                  :wasm wasm :native native
                  :dag-cbor-qualified false
                  :full-cid-graph-replay-qualified false})))
      (finally (delete-tree! directory)))))

(deftest canonical-dag-cbor-cid-and-signed-envelope-execute-in-kotoba
  (let [source-file (project-file "kotoba" "cid_signed_commit.kotoba")
        directory (temp-dir)]
    (try
      (let [source (slurp source-file)
            export-names ["check-state-root" "check-merge-transaction-cid"
                          "check-payload-digest" "check-payload-cid" "check-signed-cid"]
            wasm (run-wasm source directory export-names "cid-signed-commit")
            native (run-native source directory export-names "cid-signed-commit")
            expected {:check-state-root 1 :check-merge-transaction-cid 1
                      :check-payload-digest 1 :check-payload-cid 1
                      :check-signed-cid 1}]
        (testing "Kotoba owns DAG-CBOR, CIDv1/base32, framing, and base64url"
          (is (= expected (:results wasm)))
          (is (= (:results wasm) (:results native))))
        (testing "the full fixed signed-commit CID is identical on both targets"
          (is (= :wasm/v1 (:format wasm)))
          (is (= :kexe/v1 (:format native)))
          (is (pos? (:bytes wasm)))
          (is (pos? (:code-bytes native))))
        (println
         (pr-str {:schema :kotobase.rust-free-signed-commit-qualification/v1
                  :source "kotoba/cid_signed_commit.kotoba"
                  :expected-signed-commit-cid
                  "bafyreidmcqtvhsr5aj4nxxvjvvrygaretdzmqfa2m73hlazxgevc3pdpva"
                  :wasm wasm :native native
                  :fixed-vector-full-layer-replay-qualified true
                  :generalized-cid-graph-replay-qualified false})))
      (finally (delete-tree! directory)))))

(deftest signed-commit-dag-cbor-parents-drive-native-ancestry
  (let [source-file (project-file "kotoba" "cid_dag_traversal.kotoba")
        directory (temp-dir)]
    (try
      (let [source (slurp source-file)
            export-names ["check-parent-decode" "check-shuffled-frontier"
                          "check-causal-order" "check-criss-cross-parent-decode"
                          "check-criss-cross-closure"
                          "check-criss-cross-causal-heights"]
            wasm (run-wasm source directory export-names "cid-dag-traversal")
            native (run-native source directory export-names "cid-dag-traversal")
            expected {:check-parent-decode 1 :check-shuffled-frontier 1
                      :check-causal-order 1
                      :check-criss-cross-parent-decode 1
                      :check-criss-cross-closure 1
                      :check-criss-cross-causal-heights 1}]
        (testing "actual signed envelope bytes supply the traversed parent links"
          (is (= expected (:results wasm)))
          (is (= (:results wasm) (:results native))))
        (testing "the bounded decoder executes as real artifacts on both targets"
          (is (= :wasm/v1 (:format wasm)))
          (is (= :kexe/v1 (:format native)))
          (is (pos? (:bytes wasm)))
          (is (pos? (:code-bytes native))))
        (println
         (pr-str {:schema :kotobase.rust-free-dag-traversal-qualification/v1
                  :source "kotoba/cid_dag_traversal.kotoba"
                  :formal-fixtures
                  ["contracts/conformance/cid-graph-execution-v1.json"
                   "contracts/conformance/cid-criss-cross-execution-v1.json"]
                  :wasm wasm :native native
                  :signed-parent-decode-qualified true
                  :signed-criss-cross-closure-qualified true
                  :generalized-unbounded-dag-qualified false})))
      (finally (delete-tree! directory)))))

(deftest provider-supplied-immutable-blocks-drive-kotoba-dag-traversal
  (let [source-file (project-file "kotoba" "cid_external_dag_traversal.kotoba")
        directory (temp-dir)]
    (try
      (let [source (slurp source-file)
            export-names ["external-frontier-length" "external-envelope-length"
                          "external-parent-count" "external-first-parent-length"
                          "external-closure-0" "external-closure-1"
                          "external-closure-2" "external-closure-3"
                          "external-closure-4" "external-closure-5"
                          "external-closure-6" "external-closure-7"
                          "check-external-parent-decode"
                          "check-external-closure"
                          "check-external-causal-height"]
            entries (external-block-entries)
            provider-file (write-block-provider! directory entries)
            wasm (run-wasm source directory export-names "cid-external-dag-traversal"
                           external-capability-policy [14] entries)
            native (run-native source directory export-names
                               "cid-external-dag-traversal"
                               external-capability-policy "14" provider-file)
            expected {:check-external-parent-decode 1
                      :check-external-closure 1
                      :check-external-causal-height 1
                      :external-frontier-length 122
                      :external-envelope-length 1654
                      :external-parent-count 2
                      :external-first-parent-length 122
                      :external-closure-0 1 :external-closure-1 3
                      :external-closure-2 5 :external-closure-3 11
                      :external-closure-4 21 :external-closure-5 47
                      :external-closure-6 87 :external-closure-7 255}]
        (testing "the compiled guest contains no signed-envelope fixture bytes"
          (is (not (str/includes? source (second (nth entries 25))))))
        (testing "provider-supplied CID blocks produce the same traversal on both targets"
          (is (= expected (:results wasm)))
          (is (= (:results wasm) (:results native))))
        (testing "both provider-neutral executable artifacts are non-empty"
          (is (= :wasm/v1 (:format wasm)))
          (is (= :kexe/v1 (:format native)))
          (is (pos? (:bytes wasm)))
          (is (pos? (:code-bytes native))))
        (println
         (pr-str {:schema :kotobase.rust-free-external-dag-qualification/v1
                  :source "kotoba/cid_external_dag_traversal.kotoba"
                  :provider-contract :immutable-cid-block-get/v1
                  :signed-commit-count 8
                  :wasm wasm :native native
                  :provider-supplied-dag-qualified true
                  :unbounded-dag-qualified false})))
      (finally (delete-tree! directory)))))
