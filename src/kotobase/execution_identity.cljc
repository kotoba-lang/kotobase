(ns kotobase.execution-identity
  "What the execution contract's identifiers *are*, rather than what a caller
  says they are.

  `kotobase.execution-contract` deliberately does not choose a codec or a hash,
  and that was right: the records are the contract, and a protocol adapter
  should not have to adopt one encoding to produce them. But the layer that
  runs an execution has to choose, because a receipt whose `:request/digest`
  and `:execution/manifest` were supplied by the caller is a claim about an
  execution, not evidence of one. Anyone auditing it can only re-derive those
  identifiers if there is one canonical answer.

  This namespace is that answer: the CIDv1 DAG-CBOR logical address of the
  admitted value, `kotoba.value.codec/value-cid`. Equal values have the same
  address across processes and runtimes, so an auditor holding the manifest can
  recompute the CID the receipt names, and a manifest edited in any field
  stops matching the receipt that cites it.

  It is deliberately a separate namespace from `kotobase.governed-execution`,
  which takes the address function as an argument. The composition stays codec
  free; the codec is named once, here."
  (:require [kotoba.value.codec :as codec]))

(defn value-cid
  "The canonical logical address of one admitted immutable value.

  Not a physical address, a runtime handle, or an authority grant — see
  `kotoba.value.codec/value-cid`. Map entry order does not affect it."
  [value]
  (codec/value-cid value))

(defn payload-cid
  "The address of the record a signature is over: the record without it.

  A signature cannot be inside the bytes it signs. `value-cid` of the whole
  record names the *signed document*, which is what a receipt cites; this
  names what the signer signed."
  [record]
  (value-cid (dissoc record :signature)))

(def conformance-value
  "One value exercising every shape these records contain."
  {:string "kotobase"
   :keyword :allow
   :integer 7
   :nil nil
   :vector [1 "two" :three]
   :set #{"a" "b"}
   :nested {:k [:v]}})

(def conformance-cid
  "The address of `conformance-value`.

  Measured 2026-09-02 under both runtimes this repository ships — the JVM and
  real ClojureScript compiled to Node — and identical in both. It is written
  down so that a codec which does not reproduce it can be refused before it is
  used, rather than after its answers are durable.

  This catches a codec that is absent, stubbed, or has drifted. It does not
  catch one that lies: a function that special-cases this value and answers
  freely elsewhere passes. The check is against accident, and says so."
  "bafyreifwbblvx26plld4pfflvox7vfs664pvbbzzdadiwgo7dappkfkqea")

(defn conformant?
  "Does VALUE-CID-FN agree with the canonical codec on the fixed vector?"
  [value-cid-fn]
  (and (ifn? value-cid-fn)
       (= conformance-cid (try (value-cid-fn conformance-value)
                               (catch #?(:clj Exception :cljs :default) _
                                 ::threw)))))
