(ns kotobase.qualification-host
  "Host adapter for the qualification harnesses — the ONE place in kotobase
  that touches host filesystem/process/encoding primitives.

  Design mirrors the kotoba.* capability shape: every function takes and
  returns plain data (paths are strings, bytes are vectors/arrays of unsigned
  values), no java.* type leaks past this boundary, and each function is
  fail-closed (nil or throw, never a silent default).

  This namespace is .clj deliberately: the qualification harness drives the
  real compiler, real node, and a real C toolchain — that is build-time host
  work with no ClojureScript meaning, exactly like the js-kotoba-v1 and
  wasm32-browser host runtimes it exercises."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [kotoba.lang.text :as str]))

;; ---------- paths (strings in, strings out) ----------

(defn path-join
  "Join path segments into a string (adapter boundary: no java.io.File type
  escapes)."
  [& segs]
  (.getPath (apply io/file segs)))

(defn path-parent
  "Parent directory of a path string, or nil."
  [path]
  (some-> path io/file .getParentFile .getPath))

(defn path-uri-string
  "file:// URI string for a path (node import() needs one)."
  [path]
  (str (.toURI (io/file path))))

;; ---------- read / write ----------

(defn read-string!
  "Slurp a file path to a string. Throws on missing (callers decide)."
  [path]
  (slurp path))

(defn resource-path!
  "Resolve a classpath resource to a filesystem path string. Fails closed when
  the resource is not a checked-out file (e.g. inside a jar)."
  [resource-name]
  (let [resource (io/resource resource-name)]
    (when-not (and resource (= "file" (.getProtocol resource)))
      (throw (ex-info "resource must be a checked-out file, not a jar entry"
                      {:resource resource-name :resolved (str resource)})))
    (.getPath (io/file (.toURI resource)))))

(defn write-bytes!
  "Write a byte array to a path string."
  [path bytes]
  (with-open [out (io/output-stream path)]
    (.write out ^bytes bytes)))

(defn temp-dir!
  "Create a unique temp directory; returns its path string."
  [prefix]
  (str (java.nio.file.Files/createTempDirectory
        prefix (make-array java.nio.file.attribute.FileAttribute 0))))

(defn delete-tree!
  "Recursively delete a directory tree (path string). Missing = no-op."
  [path]
  (let [root (io/file path)]
    (when (.exists root)
      (doseq [f (reverse (file-seq root))]
        (io/delete-file f true)))))

(defn utf8-bytes
  "UTF-8 of a string as a vector of unsigned byte values (0..255)."
  [^String s]
  (map #(let [b (int %)] (if (neg? b) (+ b 256) b))
       (.getBytes s "UTF-8")))

;; ---------- process ----------

(defn exec!
  "Run a command. `argv` is a vector of strings; optional trailing map
  {:env {..}} sets the environment. Returns {:exit int :out string :err string}.
  Non-zero exit is the caller's decision."
  [argv]
  (let [[argv' opts] (if (map? (last argv))
                       [(butlast argv) (last argv)]
                       [argv {}])
        {:keys [exit out err]} (apply shell/sh (concat argv'
                                                       (when (:env opts)
                                                         [:env (:env opts)])))]
    {:exit exit :out out :err err}))

(defn file-exists?
  "Does a path exist as a regular file? (adapter boundary for .isFile checks)"
  [path]
  (some-> path io/file .isFile))

;; ---------- encoding ----------

(defn b64-encode
  "Standard base64 of a byte array -> string."
  [^bytes bytes]
  (.encodeToString (java.util.Base64/getEncoder) bytes))

;; ---------- host facts ----------

(defn os-name [] (System/getProperty "os.name"))
(defn os-arch [] (System/getProperty "os.arch"))
(defn env-or [name default] (or (System/getenv name) default))

(defn child-env
  "Current process environment as a map, with overrides merged — the base for
  spawning child processes without reaching for System/getenv at call sites."
  [overrides]
  (merge (into {} (System/getenv)) overrides))
