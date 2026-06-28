(ns kotobase.contract
  "One IStore contract, run against any backend. The LocalStore is the oracle; a
  KotobaseStore wired to a faithful xrpc must reproduce it — `KotobaseStore ≡
  LocalStore`, the same guarantee the actors give for `MemStore ≡ DatomicStore`.
  `check` is `(fn [pass? label])`."
  (:require [kotobase.store :as st]))

(defn verify [s check]
  ;; document space
  (st/-put s "nodes" "asher" {:role :worker :gas 42})
  (st/-put s "nodes" "giemon" {:role :gateway :gas 7})
  (check (= {:role :worker :gas 42} (st/-get s "nodes" "asher")) "get returns the put value")
  (check (nil? (st/-get s "nodes" "absent")) "get of a missing key is nil")
  (check (= #{"asher" "giemon"} (set (st/-list s "nodes"))) "list shows both keys")
  (st/-put s "nodes" "asher" {:role :worker :gas 99})
  (check (= 99 (:gas (st/-get s "nodes" "asher"))) "put is last-writer-wins")
  ;; append-only streams
  (let [a (st/-append s "ledger" {:decision :reply :id 1})
        b (st/-append s "ledger" {:decision :archive :id 2})
        c (st/-append s "ledger" {:decision :snooze :id 3})]
    (check (< (:seq a) (:seq b) (:seq c)) "append assigns a strictly increasing :seq")
    (check (= [1 2 3] (mapv :id (st/-read s "ledger" 0))) "read from 0 returns all, in order")
    (check (= [2 3] (mapv :id (st/-read s "ledger" (:seq a)))) "read since a cursor skips consumed events")
    (check (= [] (st/-read s "ledger" (:seq c))) "read past the tip is empty")))
