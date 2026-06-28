(ns kotobase.local
  "LocalStore — the pure, in-process IStore. This is what an app uses STANDALONE
  (OSS, no cloud): an atom holding the doc space and the append streams, with a
  monotonic `:seq`. It is also the reference ORACLE the contract checks KotobaseStore
  against (`KotobaseStore ≡ LocalStore`). Runs identically on JVM and cljs (it is
  just maps + an atom), so the same store backs a desktop app, a test, or a worker
  with no network."
  (:require [kotobase.store :as st]))

(deftype LocalStore [state]   ; state: atom {:docs {coll {k v}} :streams {s [ev]} :seq n}
  st/IStore
  (-put [_ coll k v] (swap! state assoc-in [:docs coll k] v) v)
  (-get [_ coll k] (get-in @state [:docs coll k]))
  (-list [_ coll] (vec (keys (get-in @state [:docs coll]))))

  (-append [_ stream event]
    (let [s (:seq (swap! state update :seq inc))
          ev (assoc event :seq s)]
      (swap! state update-in [:streams stream] (fnil conj []) ev)
      ev))

  (-read [_ stream since]
    (let [since (or since 0)]
      (->> (get-in @state [:streams stream])
           (filter #(> (long (:seq %)) (long since)))
           (sort-by :seq)
           vec))))

(defn local-store
  "A fresh in-process store. Pass `:seed` to preload `{:docs … :streams … :seq …}`."
  ([] (local-store {}))
  ([{:keys [docs streams seq] :or {docs {} streams {} seq 0}}]
   (->LocalStore (atom {:docs docs :streams streams :seq seq}))))

(defn snapshot
  "The store's full state as a value — for checkpointing / pushing to the cloud."
  [^LocalStore s]
  @(.-state s))
