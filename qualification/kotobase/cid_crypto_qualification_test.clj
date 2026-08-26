(ns kotobase.cid-crypto-qualification-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files]
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
  ["bafyreiek4yprp7fqaa3zkrpc3zk7codjeolghwys5q75suuen3eo2ank5m"
   "bafyreievdinujzs67f2iqjf7qtrpt4gihowpkrrylp3lndvwrz3tox3lla"
   "bafyreihgdtv3t62mz5uekgmgxd6it5wwkhxawmeqlarm7h7w4mwmrcrqu4"
   "bafyreig5aky4vod7ntzvm5eny4sps6wqkdxfyg7opgtpvp5lf4yytamc5a"
   "bafyreibmcnjg2vhpg3iwvyyqg5ae2g43barquy26rwt2rnmqxugnepwnka"
   "bafyreihxlieebhc73b47uqe3mhmfshjgijolwmyec3uwkb33g6iybbi43i"
   "bafyreigvefq34kimvvnerd6wrglal7ckkvf56mhz57vtpzdhr3mjuhclfy"
   "bafyreihd4fqt2wu2z5yvy57xb6oqijlv4abfb66v2hyrejbyzzgb33wgtm"])

(defn- cbor-text-item-hex [value]
  (str "783b" (apply str (map #(format "%02x" (int %)) value))))

(defn- bytes->hex [^bytes value]
  (apply str (map #(format "%02x" (bit-and (int %) 0xff)) value)))

(defn- utf8-hex [value]
  (bytes->hex (.getBytes ^String value StandardCharsets/UTF_8)))

(defn- cbor-text-hex [value]
  (let [length (alength (.getBytes ^String value StandardCharsets/UTF_8))]
    (str (if (< length 24) (format "%02x" (+ 0x60 length))
           (str "78" (format "%02x" length)))
         (utf8-hex value))))

(defn- cbor-bytes-hex [payload-hex]
  (let [length (quot (count payload-hex) 2)]
    (str (if (< length 24) (format "%02x" (+ 0x40 length))
           (str "58" (format "%02x" length)))
         payload-hex)))

(defn- cbor-array-header [count]
  (when-not (<= 0 count 16)
    (throw (ex-info "bounded replay page array overflow" {:count count})))
  (format "%02x" (+ 0x80 count)))

(defn- cbor-uint-hex [value]
  (cond
    (< value 0) (throw (ex-info "negative CBOR uint" {:value value}))
    (< value 24) (format "%02x" value)
    (< value 256) (format "18%02x" value)
    (< value 65536) (format "19%04x" value)
    (< value 4294967296) (format "1a%08x" value)
    :else (throw (ex-info "CBOR uint exceeds v2 page bound" {:value value}))))

(defn- sha256-hex [payload-hex]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")
        bytes (byte-array
               (map #(unchecked-byte (Integer/parseInt % 16))
                    (map (partial apply str) (partition 2 payload-hex))))]
    (bytes->hex (.digest digest bytes))))

(defn- base32-lower [^bytes input]
  (let [alphabet "abcdefghijklmnopqrstuvwxyz234567"]
    (loop [offset 0 buffer 0 bits 0 output (StringBuilder.)]
      (if (>= bits 5)
        (let [remaining (- bits 5)
              divisor (bit-shift-left 1 remaining)
              index (quot buffer divisor)]
          (.append output (.charAt alphabet index))
          (recur offset (mod buffer divisor) remaining output))
        (if (< offset (alength input))
          (recur (inc offset)
                 (+ (* buffer 256) (bit-and (aget input offset) 0xff))
                 (+ bits 8) output)
          (do
            (when (pos? bits)
              (.append output (.charAt alphabet
                                       (* buffer (bit-shift-left 1 (- 5 bits))))))
            (str output)))))))

(defn- cid-from-digest-hex [digest-hex]
  (let [prefix (byte-array [(byte 0x01) (byte 0x71) (byte 0x12) (byte 0x20)])
        digest (byte-array
                (map #(unchecked-byte (Integer/parseInt % 16))
                     (map (partial apply str) (partition 2 digest-hex))))
        cid-bytes (byte-array (+ (alength prefix) (alength digest)))]
    (System/arraycopy prefix 0 cid-bytes 0 (alength prefix))
    (System/arraycopy digest 0 cid-bytes (alength prefix) (alength digest))
    (str "b" (base32-lower cid-bytes))))

(defn- cid-for-hex [payload-hex]
  (cid-from-digest-hex (sha256-hex payload-hex)))

(defn- dag-link-from-digest-hex [digest-hex]
  (str "d82a58250001711220" digest-hex))

(defn- wire-assert-hex [subject predicate object]
  (str "a3"
       (cbor-text-hex "o") (cbor-text-hex object)
       (cbor-text-hex "p") (cbor-text-hex predicate)
       (cbor-text-hex "s") (cbor-text-hex subject)))

(defn- wire-retract-hex [subject predicate object]
  (str "a4"
       (cbor-text-hex "o") (cbor-text-hex object)
       (cbor-text-hex "p") (cbor-text-hex predicate)
       (cbor-text-hex "s") (cbor-text-hex subject)
       (cbor-text-hex "op") (cbor-text-hex "retract")))

(defn- public-transaction-hex [quads]
  (let [plaintext (str "a1" (cbor-text-hex "quads")
                       (cbor-array-header (count quads))
                       (apply str quads))]
    (str "a1" (cbor-text-hex "ct") (cbor-bytes-hex plaintext))))

(defn- replay-novelty-hex [transaction-digest previous-novelty]
  (str "a2" (cbor-text-hex "e") (dag-link-from-digest-hex transaction-digest)
       (cbor-text-hex "rest")
       (if (str/blank? previous-novelty)
         "f6" (dag-link-from-digest-hex previous-novelty))))

(defn- replay-chain-hex [sequence previous-chain novelty-digest]
  (let [previous (if (str/blank? previous-chain)
                   "f6" (dag-link-from-digest-hex previous-chain))
        state (str (cbor-text-hex "indexed") "f6"
                   (cbor-text-hex "novelty-back")
                   (dag-link-from-digest-hex novelty-digest)
                   (cbor-text-hex "novelty-count")
                   (cbor-uint-hex (inc sequence))
                   (cbor-text-hex "novelty-front") "f6")]
    (str "a3" (cbor-text-hex "seq") (cbor-uint-hex sequence)
         (cbor-text-hex "prev") previous
         (cbor-text-hex "state") "a4" state)))

(defn- replay-checkpoints
  ([transactions] (replay-checkpoints transactions 0 "" ""))
  ([transactions start-sequence initial-novelty initial-chain]
   (loop [index 0 previous-novelty initial-novelty
          previous-chain initial-chain novelties [] states [] state-blocks []]
     (if (= index (count transactions))
       {:state-root (cid-from-digest-hex previous-chain)
        :novelty-digests novelties :state-digests states
        :state-blocks state-blocks}
       (let [transaction-digest (sha256-hex (nth transactions index))
             novelty-digest (sha256-hex
                             (replay-novelty-hex transaction-digest
                                                 previous-novelty))
             chain-block (replay-chain-hex (+ start-sequence index)
                                           previous-chain novelty-digest)
             chain-digest (sha256-hex chain-block)]
         (recur (inc index) novelty-digest chain-digest
                (conj novelties novelty-digest)
                (conj states chain-digest)
                (conj state-blocks chain-block)))))))

(defn- replay-page-entries [transactions]
  (let [transaction-digests (mapv sha256-hex transactions)
        transaction-links (mapv dag-link-from-digest-hex transaction-digests)
        {:keys [state-root state-digests state-blocks]}
        (replay-checkpoints transactions)
        state-links (mapv dag-link-from-digest-hex state-digests)
        page (str "a7"
                  (cbor-text-hex "next") "f6"
                  (cbor-text-hex "schema")
                  (cbor-text-hex "kotobase.transaction-replay-page.v2")
                  (cbor-text-hex "states")
                  (cbor-array-header (count transactions))
                  (apply str state-links)
                  (cbor-text-hex "transactions")
                  (cbor-array-header (count transactions))
                  (apply str transaction-links)
                  (cbor-text-hex "previous_state") "f6"
                  (cbor-text-hex "start_sequence") (cbor-uint-hex 0)
                  (cbor-text-hex "expected_state_root")
                  (cbor-text-hex state-root))
        page-cid (cid-for-hex page)]
    {:state-root state-root
     :page-cid page-cid
     :entries
     (into [["frontier" (cbor-text-hex page-cid)]
           [(str "block:" (cbor-text-hex page-cid)) page]]
           (concat
            (map (fn [link transaction]
                   [(str "block:" link) transaction])
                 transaction-links transactions)
            (map (fn [link state-block]
                   [(str "block:" link) state-block])
                 state-links state-blocks)))}))

(defn- replay-page-hex
  [{:keys [next-link previous-state-digest start-sequence
           transactions state-digests]}]
  (let [transaction-links (mapv (comp dag-link-from-digest-hex sha256-hex)
                                transactions)
        state-links (mapv dag-link-from-digest-hex state-digests)
        state-root (cid-from-digest-hex (peek state-digests))
        page (str "a7"
                  (cbor-text-hex "next") (or next-link "f6")
                  (cbor-text-hex "schema")
                  (cbor-text-hex "kotobase.transaction-replay-page.v2")
                  (cbor-text-hex "states")
                  (cbor-array-header (count state-links))
                  (apply str state-links)
                  (cbor-text-hex "transactions")
                  (cbor-array-header (count transaction-links))
                  (apply str transaction-links)
                  (cbor-text-hex "previous_state")
                  (if (str/blank? previous-state-digest)
                    "f6" (dag-link-from-digest-hex previous-state-digest))
                  (cbor-text-hex "start_sequence")
                  (cbor-uint-hex start-sequence)
                  (cbor-text-hex "expected_state_root")
                  (cbor-text-hex state-root))
        digest (sha256-hex page)]
    {:page page
     :page-cid (cid-from-digest-hex digest)
     :page-link (dag-link-from-digest-hex digest)
     :transactions transactions
     :transaction-links transaction-links
     :state-digests state-digests
     :previous-state-digest previous-state-digest
     :start-sequence start-sequence
     :transaction-count (count transactions)
     :state-root state-root}))

(defn- replay-page-chain
  "Build immutable pages from the final page backwards so every earlier page
  embeds the exact CID of its successor. The provider inventory contains both
  CID text and DAG-link lookup aliases because a root frontier is a CID text
  item while an in-page `next` field is a DAG-CBOR link."
  [transactions page-size]
  (let [{:keys [state-root state-digests state-blocks]}
        (replay-checkpoints transactions)
        ranges (mapv (fn [start]
                       [start (min (count transactions) (+ start page-size))])
                     (range 0 (count transactions) page-size))
        pages
        (loop [remaining (reverse ranges) next-link "f6" built []]
          (if-let [[start end] (first remaining)]
            (let [descriptor
                  (replay-page-hex
                   {:next-link next-link
                    :previous-state-digest (if (zero? start) ""
                                             (nth state-digests (dec start)))
                    :start-sequence start
                    :transactions (subvec transactions start end)
                    :state-digests (subvec state-digests start end)})]
              (recur (next remaining) (:page-link descriptor)
                     (conj built descriptor)))
            (vec (reverse built))))
        transaction-links
        (mapv (comp dag-link-from-digest-hex sha256-hex) transactions)
        state-links (mapv dag-link-from-digest-hex state-digests)
        page-entries
        (mapcat (fn [{:keys [page page-cid page-link]}]
                  [[(str "block:" (cbor-text-hex page-cid)) page]
                   [(str "block:" page-link) page]])
                pages)]
    {:root-cid (:page-cid (first pages))
     :state-root state-root
     :pages pages
     :entries
     (vec
      (concat
       page-entries
       (map (fn [link transaction] [(str "block:" link) transaction])
            transaction-links transactions)
       (map (fn [link state-block] [(str "block:" link) state-block])
            state-links state-blocks)))}))

(defn- replay-page-next-link [page]
  (let [offset (+ 2 (count (cbor-text-hex "next")))]
    (if (= "f6" (subs page offset (+ offset 2)))
      "f6"
      (subs page offset (+ offset 82)))))

(defn- dag-link->cid [link]
  (cid-from-digest-hex (subs link 18 82)))

(defn- relink-page-chain
  [chain changed-index changed-page extra-entries]
  (let [pages-with-change (assoc (:pages chain) changed-index changed-page)
        pages
        (loop [index (dec changed-index) rebuilt pages-with-change]
          (if (neg? index)
            rebuilt
            (let [old (nth rebuilt index)
                  child (nth rebuilt (inc index))
                  replacement
                  (replay-page-hex
                   {:next-link (:page-link child)
                    :previous-state-digest (:previous-state-digest old)
                    :start-sequence (:start-sequence old)
                    :transactions (:transactions old)
                    :state-digests (:state-digests old)})]
              (recur (dec index) (assoc rebuilt index replacement)))))
        page-entries
        (mapcat (fn [{:keys [page page-cid page-link]}]
                  [[(str "block:" (cbor-text-hex page-cid)) page]
                   [(str "block:" page-link) page]])
                pages)]
    (assoc chain
           :root-cid (:page-cid (first pages))
           :pages pages
           :entries (vec (concat (:entries chain)
                                 page-entries extra-entries)))))

(defn- flip-first-hex-nibble [hex]
  (str (if (= "0" (subs hex 0 1)) "1" "0") (subs hex 1)))

(defn- forge-first-replay-checkpoint [entries]
  (let [page (second (second entries))
        offset (.indexOf ^String page "d82a58250001711220")
        original-link (subs page offset (+ offset 82))
        original-block (some (fn [[key value]]
                               (when (= key (str "block:" original-link)) value))
                             entries)
        digest-offset (+ offset 18)
        replacement (if (= "0" (subs page digest-offset (inc digest-offset)))
                      "1" "0")
        forged-page (str (subs page 0 digest-offset) replacement
                         (subs page (inc digest-offset)))
        forged-link (subs forged-page offset (+ offset 82))
        forged-page-cid (cid-for-hex forged-page)]
    (-> entries
        (assoc 0 ["frontier" (cbor-text-hex forged-page-cid)])
        (assoc 1 [(str "block:" (cbor-text-hex forged-page-cid))
                  forged-page])
        (conj [(str "block:" forged-link) original-block]))))

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
          (concat (map (fn [key envelope]
                         [(str "offset:" key)
                          (str (.indexOf ^String envelope "67706172656e7473"))])
                       keys envelopes)
                  (map (fn [key envelope] [(str "block:" key) envelope])
                       keys envelopes)))))

(defn- synthetic-chain-entries [node-count]
  (let [cids (mapv (fn [index]
                     (str "bafy" (format "%055d" index)))
                   (range node-count))
        keys (mapv cbor-text-item-hex cids)
        marker "67706172656e7473"
        envelopes
        (mapv (fn [index]
                (if (zero? index)
                  (str marker "80")
                  (str marker "81" (nth keys (dec index)))))
              (range node-count))]
    (into [["frontier" (peek keys)]]
          (concat
           (map (fn [key] [(str "offset:" key) "0"]) keys)
           (map (fn [key envelope] [(str "block:" key) envelope])
                keys envelopes)))))

(defn- synthetic-cycle-entries []
  (let [keys (mapv cbor-text-item-hex
                   ["bafy0000000000000000000000000000000000000000000000000000001"
                    "bafy0000000000000000000000000000000000000000000000000000002"])
        marker "67706172656e7473"]
    [["frontier" (second keys)]
     [(str "offset:" (first keys)) "0"]
     [(str "offset:" (second keys)) "0"]
     [(str "block:" (first keys)) (str marker "81" (second keys))]
     [(str "block:" (second keys)) (str marker "81" (first keys))]]))

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

(defn- normalize-invocations [exports]
  (mapv (fn [export]
          (if (string? export)
            {:label export :export export :args []}
            export))
        exports))

(defn- javascript-invocations [invocations]
  (str "["
       (str/join
        ","
        (map (fn [{:keys [label export args]}]
               (str "{label:" (pr-str label) ",name:" (pr-str export)
                    ",args:[" (str/join "," (map #(str % "n") args)) "]}"))
             invocations))
       "]"))

(defn- run-wasm
  ([source directory export-names stem]
   (run-wasm source directory export-names stem capability-policy [1 3] []))
  ([source directory export-names stem policy allow-capabilities block-entries]
  (let [invocations (normalize-invocations export-names)
        compiled (compiler/compile-source source :wasm32-kotoba-v1
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
             "for(const call of " (javascript-invocations invocations) "){"
             "const h=await m.instantiateKotoba(bytes,{allowCapabilities:["
             (str/join "," allow-capabilities) "],typedCapCall:provider});"
             "console.log(call.label+'='+h.instance.exports[call.name](...call.args).toString());}"
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
  (let [invocations (normalize-invocations export-names)
        [target isa] (host-target)
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
        export-symbols (mapv #(assoc % :symbol (symbol (:export %))) invocations)]
    (when-not (zero? (:exit build))
      (throw (ex-info "Kotoba native crypto loader build failed" build)))
    (write-bytes! code
                  (byte-array
                   (map #(unchecked-byte (bit-and (int %) 0xff))
                        (get-in compiled [:artifact :code]))))
    (let [results
          (into {}
                (map (fn [{:keys [label symbol args]}]
                       (let [offset (get-in compiled [:artifact :exports symbol :offset])
                             {:keys [exit out err]}
                             (apply shell/sh
                                    (concat [(.getPath loader) (.getPath code)
                                             (str offset) (str (count args)) isa allow-csv]
                                            (map str args)
                                            [:env (cond-> {"KEXE_STRUCTURED_REPORT" "1"}
                                                    block-provider-file
                                                    (assoc "KOTOBASE_BLOCK_PROVIDER_FILE"
                                                           (.getPath block-provider-file)))]))]
                         (when-not (zero? exit)
                           (throw (ex-info "Kotoba native crypto execution failed"
                                           {:export symbol :args args
                                            :exit exit :stderr err})))
                         [(keyword label)
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
                  "bafyreidl4mgsqduk46zwsrbuftgibtpmbwlj3c3o4a2il6y477mnty2jim"
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
                          "external-closure-count" "external-root-height"
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
                      :external-closure-count 8
                      :external-root-height 4}]
        (testing "the compiled guest contains no signed-envelope fixture bytes"
          (is (not (str/includes? source (second (last entries)))))
          (is (not (str/includes? source "node0")))
          (is (not (str/includes? source "index:"))))
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
                  :bounded-dynamic-cid-page-qualified true
                  :local-page-entry-limit 128
                  :global-page-dag-qualified false})))
      (finally (delete-tree! directory)))))

(deftest the-same-guest-traverses-a-provider-generated-twelve-node-page
  (let [source-file (project-file "kotoba" "cid_external_dag_traversal.kotoba")
        directory (temp-dir)]
    (try
      (let [source (slurp source-file)
            export-names ["external-closure-count" "external-root-height"]
            entries (synthetic-chain-entries 12)
            provider-file (write-block-provider! directory entries)
            wasm (run-wasm source directory export-names "cid-dynamic-page-12"
                           external-capability-policy [14] entries)
            native (run-native source directory export-names
                               "cid-dynamic-page-12"
                               external-capability-policy "14" provider-file)
            expected {:external-closure-count 12 :external-root-height 11}]
        (is (= expected (:results wasm)))
        (is (= expected (:results native)))
        (is (= (:results wasm) (:results native)))
        (is (= 25 (count entries))
            "one frontier plus offset/block data; no positional inventory")
        (println
         (pr-str {:schema :kotobase.dynamic-cid-page-qualification/v1
                  :source "kotoba/cid_external_dag_traversal.kotoba"
                  :provider-generated-node-count 12
                  :expected-root-height 11
                  :wasm wasm :native native
                  :fixed-eight-inventory-required false
                  :local-page-entry-limit 128})))
      (finally (delete-tree! directory)))))

(deftest dynamic-page-faults-fail-closed-on-both-backends
  (let [source (slurp (project-file "kotoba" "cid_external_dag_traversal.kotoba"))
        directory (temp-dir)
        export-names ["external-closure-count" "external-root-height"]]
    (try
      (testing "a provider cycle is bounded and marked invalid"
        (let [entries (synthetic-cycle-entries)
              provider-file (write-block-provider! directory entries)
              wasm (run-wasm source directory export-names "cid-cycle"
                             external-capability-policy [14] entries)
              native (run-native source directory export-names "cid-cycle"
                                 external-capability-policy "14" provider-file)
              expected {:external-closure-count 2 :external-root-height -2}]
          (is (= expected (:results wasm)))
          (is (= expected (:results native)))))
      (testing "an unverified offset hint contributes no closure"
        (let [entries (mapv (fn [[key value]]
                              [key (if (str/starts-with? key "offset:") "2" value)])
                            (synthetic-chain-entries 12))
              provider-file (write-block-provider! directory entries)
              wasm (run-wasm source directory export-names "cid-bad-offset"
                             external-capability-policy [14] entries)
              native (run-native source directory export-names "cid-bad-offset"
                                 external-capability-policy "14" provider-file)
              expected {:external-closure-count 0 :external-root-height -2}]
          (is (= expected (:results wasm)))
          (is (= expected (:results native)))))
      (testing "a 129th local CID traps at the sealed page bound"
        (let [entries (synthetic-chain-entries 129)
              provider-file (write-block-provider! directory entries)]
          (is (thrown? clojure.lang.ExceptionInfo
                       (run-wasm source directory ["external-closure-count"]
                                 "cid-page-overflow"
                                 external-capability-policy [14] entries)))
          (is (thrown? clojure.lang.ExceptionInfo
                       (run-native source directory ["external-closure-count"]
                                   "cid-page-overflow"
                                   external-capability-policy "14" provider-file)))))
      (finally (delete-tree! directory)))))

(def ^:private canonical-replay-transactions
  [(public-transaction-hex
    [(wire-assert-hex "e1" ":name" "Alice")
     (wire-assert-hex "e1" ":status" "base")])
   (public-transaction-hex
    [(wire-retract-hex "e1" ":status" "base")
     (wire-assert-hex "e1" ":status" "branch-a")])
   (public-transaction-hex
    [(wire-assert-hex "e2" ":name" "Bob")
     (wire-assert-hex "e1" ":status" "branch-b")])])

(def ^:private generated-replay-transactions
  [(public-transaction-hex
    [(wire-assert-hex "doc:1" ":title" "one")])
   (public-transaction-hex
    [(wire-assert-hex "doc:1" ":tag" "a")
     (wire-assert-hex "doc:2" ":title" "two")])
   (public-transaction-hex
    [(wire-retract-hex "doc:1" ":tag" "a")
     (wire-assert-hex "doc:1" ":tag" "b")])
   (public-transaction-hex
    [(wire-assert-hex "doc:3" ":title" "three")])
   (public-transaction-hex
    [(wire-assert-hex "doc:2" ":tag" "shared")
     (wire-retract-hex "doc:3" ":title" "three")])])

(def ^:private boundary-replay-transactions
  (mapv (fn [index]
          (public-transaction-hex
           [(wire-assert-hex (str "entity:" index) ":value" (str index))]))
        (range 16)))

(def ^:private multi-page-replay-transactions
  (mapv (fn [index]
          (public-transaction-hex
           [(wire-assert-hex (str "page-entity:" index)
                             ":sequence" (str index))]))
        (range 40)))

(declare replay-stage-summary)

(defn- run-external-transaction-replay
  [source directory stem entries transaction-count]
  (let [exports
        (into ["check-page-cid" "transaction-count" "check-replay-root"
               "check-genesis-boundary" "has-next-page"
               "check-next-page-boundary"]
              (mapcat
               (fn [index]
                 [{:label (str "transaction-cid-" index)
                   :export "check-transaction-cid-at" :args [index]}
                  {:label (str "transaction-atoms-" index)
                   :export "transaction-atom-count-at" :args [index]}
                  {:label (str "replay-step-" index)
                   :export "check-replay-step-at" :args [index]}])
               (range transaction-count)))
        policy {:allow #{[:cap/call 3] [:cap/call 14]}}
        provider-file (write-block-provider! directory entries)]
    {:wasm (run-wasm source directory exports (str stem "-wasm")
                      policy [3 14] entries)
     :native (run-native source directory exports (str stem "-native")
                         policy "3,14" provider-file)}))

(defn- replay-page-provider-entries
  [current entries {:keys [page transaction-links state-digests
                           previous-state-digest]}]
  (let [next-link (replay-page-next-link page)
        entry-map (into {} entries)
        previous-state-links
        (cond-> (mapv dag-link-from-digest-hex (butlast state-digests))
          (not (str/blank? previous-state-digest))
          (into [(dag-link-from-digest-hex previous-state-digest)]))
        needed-keys
        (concat [(str "block:" (cbor-text-hex current))]
                (when-not (= next-link "f6") [(str "block:" next-link)])
                (map #(str "block:" %) transaction-links)
                (map #(str "block:" %) previous-state-links))]
    (into [["frontier" (cbor-text-hex current)]]
          (map (fn [key] [key (get entry-map key)]) needed-keys))))

(defn- replay-page-result-ok? [{:keys [wasm native parity]}]
  (and parity
       (:page-cid-ok wasm)
       (:genesis-boundary-ok wasm)
       (:has-next-page-ok wasm)
       (:next-page-boundary-ok wasm)
       (:transaction-cids-ok wasm)
       (:atoms-ok wasm)
       (:replay-steps-ok wasm)
       (:replay-root-ok wasm)
       (= wasm native)))

(defn- replay-page-revisit? [visited page-cid]
  (contains? visited page-cid))

(defn- run-replay-page-scheduler
  [source directory stem {:keys [root-cid entries pages state-root]}]
  (loop [current root-cid visited #{} page-index 0 total 0 results []]
    (cond
      (replay-page-revisit? visited current)
      {:ok false :error :cycle :page-cid current :results results}

      (>= page-index 128)
      {:ok false :error :page-budget-exhausted :results results}

      :else
      (if-let [{:keys [page transaction-count start-sequence
                       transaction-links state-digests previous-state-digest]
                :as descriptor}
               (some #(when (= current (:page-cid %)) %) pages)]
        (let [next-link (replay-page-next-link page)
              provider-entries
              (replay-page-provider-entries current entries descriptor)
              execution
              (run-external-transaction-replay
               source directory (str stem "-page-" page-index)
               provider-entries transaction-count)
              has-next (if (= next-link "f6") 0 1)
              genesis? (zero? page-index)
              wasm-summary (replay-stage-summary (:results (:wasm execution))
                                                 transaction-count genesis?
                                                 has-next)
              native-summary (replay-stage-summary (:results (:native execution))
                                                   transaction-count genesis?
                                                   has-next)
              record {:page-index page-index :page-cid current
                      :start-sequence start-sequence
                      :transaction-count transaction-count
                      :next-link next-link
                      :wasm wasm-summary :native native-summary
                      :parity (= (get-in execution [:wasm :results])
                                 (get-in execution [:native :results]))}
              next-results (conj results record)
              next-total (+ total transaction-count)]
          (if-not (replay-page-result-ok? record)
            {:ok false :error :page-validation :page-cid current
             :page-count (inc page-index) :transaction-count next-total
             :results next-results}
            (if (= next-link "f6")
              {:ok (and (= next-total 40)
                        (= state-root (:state-root descriptor)))
               :page-count (inc page-index)
               :transaction-count next-total
               :state-root state-root
               :results next-results}
              (recur (dag-link->cid next-link)
                     (conj visited current) (inc page-index)
                     next-total next-results))))
        {:ok false :error :missing-page :page-cid current :results results}))))

(defn- replay-stage-summary
  ([results transaction-count]
   (replay-stage-summary results transaction-count true 0))
  ([results transaction-count genesis? expected-has-next]
   {:page-cid-ok (= 1 (:check-page-cid results))
    :reported-transaction-count (:transaction-count results)
    :genesis-boundary-ok (= (if genesis? 1 0)
                            (:check-genesis-boundary results))
    :has-next-page (:has-next-page results)
    :has-next-page-ok (= expected-has-next (:has-next-page results))
    :next-page-boundary-ok (= 1 (:check-next-page-boundary results))
    :transaction-cids-ok
    (every? #(= 1 (get results (keyword (str "transaction-cid-" %))))
            (range transaction-count))
    :atom-count
    (reduce + (map #(get results (keyword (str "transaction-atoms-" %)))
                   (range transaction-count)))
    :atoms-ok
    (every? #(<= 0 (get results (keyword (str "transaction-atoms-" %))))
            (range transaction-count))
    :replay-steps-ok
    (every? #(= 1 (get results (keyword (str "replay-step-" %))))
            (range transaction-count))
    :replay-root-ok (= 1 (:check-replay-root results))}))

(deftest provider-supplied-transaction-atoms-replay-in-kotoba
  (let [source (slurp (project-file "kotoba"
                                    "cid_external_transaction_replay.kotoba"))
        directory (temp-dir)]
    (try
      (testing "the canonical three transactions are no longer guest fixtures"
        (let [{:keys [entries state-root]} (replay-page-entries
                                           canonical-replay-transactions)
              {:keys [wasm native]}
              (run-external-transaction-replay source directory
                                               "canonical-replay" entries 3)
              expected {:page-cid-ok true :reported-transaction-count 3
                        :genesis-boundary-ok true :has-next-page 0
                        :has-next-page-ok true
                        :next-page-boundary-ok true
                        :transaction-cids-ok true :atom-count 6 :atoms-ok true
                        :replay-steps-ok true
                        :replay-root-ok true}]
          (is (= "bafyreiglqe64tpi5fig43xbm3fequec2q53tjk2sb3mkooxejb6rqamyee"
                 state-root))
          (is (every? #(not (str/includes? source %))
                      canonical-replay-transactions))
          (is (= expected (replay-stage-summary (:results wasm) 3)))
          (is (= expected (replay-stage-summary (:results native) 3)))
          (is (= (:results wasm) (:results native)))))
      (testing "the unchanged guest replays a provider-generated five-transaction page"
        (let [{:keys [entries state-root page-cid]}
              (replay-page-entries generated-replay-transactions)
              {:keys [wasm native]}
              (run-external-transaction-replay source directory
                                               "generated-replay" entries 5)
              expected {:page-cid-ok true :reported-transaction-count 5
                        :genesis-boundary-ok true :has-next-page 0
                        :has-next-page-ok true
                        :next-page-boundary-ok true
                        :transaction-cids-ok true :atom-count 8 :atoms-ok true
                        :replay-steps-ok true
                        :replay-root-ok true}]
          (is (= expected (replay-stage-summary (:results wasm) 5)))
          (is (= expected (replay-stage-summary (:results native) 5)))
          (is (= (:results wasm) (:results native)))
          (println
           (pr-str {:schema :kotobase.external-transaction-replay-qualification/v1
                    :source "kotoba/cid_external_transaction_replay.kotoba"
                    :page-cid page-cid :state-root state-root
                    :transaction-count 5 :atom-count 8
                    :wasm wasm :native native
                    :provider-supplied-atoms-qualified true
                    :local-page-transaction-limit 16
                    :global-page-dag-qualified false}))))
      (testing "the sealed page limit is executable, not only parser metadata"
        (let [{:keys [entries]}
              (replay-page-entries boundary-replay-transactions)
              {:keys [wasm native]}
              (run-external-transaction-replay source directory
                                               "boundary-replay" entries 16)
              expected {:page-cid-ok true :reported-transaction-count 16
                        :genesis-boundary-ok true :has-next-page 0
                        :has-next-page-ok true
                        :next-page-boundary-ok true
                        :transaction-cids-ok true :atom-count 16 :atoms-ok true
                        :replay-steps-ok true
                        :replay-root-ok true}]
          (is (= expected (replay-stage-summary (:results wasm) 16)))
          (is (= expected (replay-stage-summary (:results native) 16)))
          (is (= (:results wasm) (:results native)))
          (println
           (pr-str {:schema :kotobase.external-transaction-boundary-qualification/v1
                    :transaction-count 16 :atom-count 16
                    :wasm wasm :native native
                    :sealed-page-limit-qualified true}))))
      (finally (delete-tree! directory)))))

(deftest cid-linked-multi-page-replay-scheduler
  (let [source (slurp (project-file "kotoba"
                                    "cid_external_transaction_replay.kotoba"))
        directory (temp-dir)
        chain (replay-page-chain multi-page-replay-transactions 16)]
    (try
      (let [result (run-replay-page-scheduler source directory
                                              "multi-page-replay" chain)]
        (is (:ok result) (pr-str result))
        (is (= 3 (:page-count result)))
        (is (= 40 (:transaction-count result)))
        (is (= [0 16 32] (mapv :start-sequence (:results result))))
        (is (= [16 16 8] (mapv :transaction-count (:results result))))
        (is (= [1 1 0]
               (mapv #(get-in % [:wasm :has-next-page]) (:results result))))
        (is (= 3 (count (set (map :page-cid (:results result)))))
            "the scheduler must visit three distinct immutable page CIDs")
        (is (str/includes? (:page (nth (:pages chain) 2))
                           (str (cbor-text-hex "start_sequence") "1820"))
            "sequence 32 must use canonical CBOR uint8 encoding")
        (println
         (pr-str {:schema :kotobase.cid-linked-transaction-page-chain/v1
                  :root-page-cid (:root-cid chain)
                  :state-root (:state-root result)
                  :page-count (:page-count result)
                  :page-transaction-counts
                  (mapv :transaction-count (:results result))
                  :transaction-count (:transaction-count result)
                  :native-wasm-parity true
                  :client-followed-next-cids true
                  :cycle-budget 128
                  :rust-required false
                  :cid-linked-transaction-page-chain-qualified true
                  :arbitrary-branching-page-dag-qualified false
                  :unbounded-replay-qualified false
                  :google-scale-qualified false
                  :public-cloud-qualified false
                  :neo4j-performance-qualified false})))
      (finally (delete-tree! directory)))))

(deftest forged-multi-page-boundaries-fail-closed
  (let [source (slurp (project-file "kotoba"
                                    "cid_external_transaction_replay.kotoba"))
        directory (temp-dir)
        chain (replay-page-chain multi-page-replay-transactions 16)
        first-page (first (:pages chain))
        second-page (second (:pages chain))]
    (try
      (testing "bytes under the claimed next-page CID are rejected"
        (let [next-link (replay-page-next-link (:page first-page))
              next-key (str "block:" next-link)
              original-next (get (into {} (:entries chain)) next-key)
              forged-entries (conj (:entries chain)
                                   [next-key (flip-first-hex-nibble original-next)])
              provider (replay-page-provider-entries
                        (:root-cid chain) forged-entries first-page)
              {:keys [wasm native]}
              (run-external-transaction-replay
               source directory "forged-next-bytes" provider 16)]
          (is (= 1 (get-in wasm [:results :check-page-cid])))
          (is (= 0 (get-in wasm [:results :check-next-page-boundary])))
          (is (= (:results wasm) (:results native)))))

      (testing "a CID-consistent next page cannot skip a sequence"
        (let [forged-second
              (replay-page-hex
               {:next-link (replay-page-next-link (:page second-page))
                :previous-state-digest (:previous-state-digest second-page)
                :start-sequence 17
                :transactions (:transactions second-page)
                :state-digests (:state-digests second-page)})
              forged-chain (relink-page-chain chain 1 forged-second [])
              result (run-replay-page-scheduler source directory
                                                "forged-sequence"
                                                forged-chain)]
          (is (= :page-validation (:error result)))
          (is (false? (get-in result [:results 0 :wasm
                                      :next-page-boundary-ok]))
              "the first page must reject successor sequence 17 after 16 items")
          (is (= (get-in result [:results 0 :wasm])
                 (get-in result [:results 0 :native])))))

      (testing "a CID-consistent next page cannot forge previous_state"
        (let [forged-previous
              (flip-first-hex-nibble (:previous-state-digest second-page))
              forged-second
              (replay-page-hex
               {:next-link (replay-page-next-link (:page second-page))
                :previous-state-digest forged-previous
                :start-sequence (:start-sequence second-page)
                :transactions (:transactions second-page)
                :state-digests (:state-digests second-page)})
              forged-chain (relink-page-chain chain 1 forged-second [])
              result (run-replay-page-scheduler source directory
                                                "forged-previous-state"
                                                forged-chain)]
          (is (= :page-validation (:error result)))
          (is (false? (get-in result [:results 0 :wasm
                                      :next-page-boundary-ok])))
          (is (= (get-in result [:results 0 :wasm])
                 (get-in result [:results 0 :native])))))

      (testing "self-reference fails CID verification and the host tracks revisits"
        (let [next-offset (+ 2 (count (cbor-text-hex "next")))
              page (:page first-page)
              self-page (str (subs page 0 next-offset)
                             (:page-link first-page)
                             (subs page (+ next-offset 82)))
              self-descriptor (assoc first-page :page self-page)
              self-entries
              (conj (:entries chain)
                    [(str "block:" (cbor-text-hex (:page-cid first-page)))
                     self-page]
                    [(str "block:" (:page-link first-page)) self-page])
              provider (replay-page-provider-entries
                        (:page-cid first-page) self-entries self-descriptor)
              {:keys [wasm native]}
              (run-external-transaction-replay
               source directory "forged-self-cycle" provider 16)]
          (is (= 0 (get-in wasm [:results :check-page-cid])))
          (is (= 0 (get-in wasm [:results :check-next-page-boundary])))
          (is (= (:results wasm) (:results native)))
          (is (replay-page-revisit? #{(:page-cid first-page)}
                                    (:page-cid first-page)))
          (is (not (replay-page-revisit? #{} (:page-cid first-page))))))
      (finally (delete-tree! directory)))))

(deftest forged-external-transaction-pages-fail-closed
  (let [source (slurp (project-file "kotoba"
                                    "cid_external_transaction_replay.kotoba"))
        directory (temp-dir)]
    (try
      (testing "bytes under a claimed transaction CID cannot alter replay"
        (let [{:keys [entries]} (replay-page-entries canonical-replay-transactions)
              forged (update-in entries [2 1]
                                #(str (subs % 0 (dec (count %)))
                                      (if (= "0" (subs % (dec (count %))))
                                        "1" "0")))
              {:keys [wasm native]}
              (run-external-transaction-replay source directory
                                               "forged-cid" forged 3)]
          (is (= 1 (get-in wasm [:results :check-page-cid])))
          (is (= 0 (get-in wasm [:results :transaction-cid-0])))
          (is (= 0 (get-in wasm [:results :replay-step-0])))
          (is (= 1 (get-in wasm [:results :check-replay-root]))
              "the final-root stage is accepted only with every preceding step")
          (is (= (:results wasm) (:results native)))))
      (testing "a CID-consistent page with a malformed quad is rejected"
        (let [malformed (assoc canonical-replay-transactions 0
                               (str/replace-first
                                (first canonical-replay-transactions) "a3" "a2"))
              {:keys [entries]} (replay-page-entries malformed)
              {:keys [wasm native]}
              (run-external-transaction-replay source directory
                                               "malformed-quad" entries 3)]
          (is (= 1 (get-in wasm [:results :check-page-cid])))
          (is (= 3 (get-in wasm [:results :transaction-count])))
          (is (= -1 (get-in wasm [:results :transaction-atoms-0])))
          (is (= 1 (get-in wasm [:results :transaction-cid-0])))
          (is (= 1 (get-in wasm [:results :replay-step-0])))
          (is (= 1 (get-in wasm [:results :check-replay-root]))
              "the root stage is byte-deterministic; atom admission is a separate fail-closed stage")
          (is (= (:results wasm) (:results native)))))
      (testing "a CID-consistent page cannot forge an intermediate checkpoint"
        (let [{:keys [entries]} (replay-page-entries canonical-replay-transactions)
              forged (forge-first-replay-checkpoint entries)
              {:keys [wasm native]}
              (run-external-transaction-replay source directory
                                               "forged-checkpoint" forged 3)]
          (is (= 1 (get-in wasm [:results :check-page-cid])))
          (is (= 1 (get-in wasm [:results :transaction-cid-0])))
          (is (= 0 (get-in wasm [:results :replay-step-0])))
          (is (= 0 (get-in wasm [:results :replay-step-1])))
          (is (= 1 (get-in wasm [:results :check-replay-root]))
              "final-root binding is accepted only after every replay step")
          (is (= (:results wasm) (:results native)))))
      (finally (delete-tree! directory)))))
