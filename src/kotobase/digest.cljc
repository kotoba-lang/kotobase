(ns kotobase.digest
  "Pure portable SHA-256 hex digesting over EDN values. Replaces the previous
  java.math.BigInteger / java.security.MessageDigest usage (JVM-only) with
  the org-nist-sha2 pure backend (`sha2.core/sha256-hex`)."
  (:require [sha2.core :as sha2]))

(defn ^:private utf8-bytes
  "Unsigned UTF-8 byte vector of a string."
  [s]
  #?(:clj (map #(let [b (int %)] (if (neg? b) (+ b 256) b))
               (.getBytes ^String s "UTF-8"))
     :cljs (vec (.encode (js/TextEncoder.) s))))

(defn sha256-hex-of-string
  "64-char lowercase hex SHA-256 of a string."
  [s]
  (sha2/sha256-hex (utf8-bytes s)))

(defn digest
  "64-char lowercase hex SHA-256 of any printable value (via pr-str)."
  [value]
  (sha256-hex-of-string (pr-str value)))
