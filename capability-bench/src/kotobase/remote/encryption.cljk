(ns kotobase.remote.encryption
  "What client-side encryption costs a content-addressed store, measured on a
  real block set rather than argued.

  Three placements, and they differ in which property they give up:

  | placement | server can verify | dedup | equality leaks | tree stays canonical |
  |---|---|---|---|---|
  | none | yes | yes | n/a | yes |
  | convergent, ciphertext-addressed | yes | yes | **yes** | yes |
  | random nonce, ciphertext-addressed | yes | **no** | no | **no** |
  | any mode, plaintext-addressed | **no** | yes | yes | yes |

  The last row is the one people reach for first because it needs no changes
  to the tree, and it is the one that quietly removes server-side integrity:
  the store holds ciphertext under a plaintext CID it cannot recompute.

  Cross-dataset equality leakage is measured in `run-storage.cljs` rather than
  here: within one store the blocks are already deduplicated, so counting
  duplicates inside it can only ever return zero. The question that has an
  answer is how many block addresses two independent tenants share.

  The third row is worse than it looks. A fresh nonce per write means encoding
  the same logical node twice yields two different addresses, so two replicas
  building the same data disagree about the root. Content addressing stops
  being content addressing. That is measured here as the duplicate factor
  between two independent builds of identical data."
  (:require [multiformats.core :as mf]
            [kotobase.remote.profile :as profile]))

(defn- now [] #?(:cljs (js/performance.now) :clj (/ (System/nanoTime) 1e6)))

(defn analyse
  "`blocks` is a seq of plaintext byte payloads (one real block set).
  Returns per-mode block counts, bytes and cipher cost."
  [blocks]
  (let [n (count blocks)
        plain-bytes (reduce + 0 (map #(.-length ^js %) blocks))
        t0 (now)
        conv (mapv #(profile/encrypt :convergent %) blocks)
        t1 (now)
        conv2 (mapv #(profile/encrypt :convergent %) blocks)
        rand1 (mapv #(profile/encrypt :random %) blocks)
        t2 (now)
        rand2 (mapv #(profile/encrypt :random %) blocks)
        cid-set (fn [bs] (into #{} (map mf/cidv1-raw) bs))
        conv-unique (count (cid-set (concat conv conv2)))
        rand-unique (count (cid-set (concat rand1 rand2)))
        cipher-bytes (reduce + 0 (map #(.-length ^js %) conv))
        t3 (now)
        _ (dotimes [i (min 500 n)]
            (let [pt (nth blocks i)
                  k (.subarray (profile/sha256 pt) 0 32)]
              (profile/decrypt k (.-length ^js pt) (nth conv i))))
        t4 (now)]
    {:blocks n
     :plaintext-bytes plain-bytes
     :ciphertext-bytes cipher-bytes
     :overhead-bytes (- cipher-bytes plain-bytes)
     :overhead-per-block (/ (double (- cipher-bytes plain-bytes)) n)
     :overhead-pct (* 100.0 (/ (double (- cipher-bytes plain-bytes)) plain-bytes))
     :encrypt-ms-per-block (/ (- t1 t0) n)
     :decrypt-ms-per-block (/ (- t4 t3) (min 500 n))
     ;; two independent builds of the SAME data
     :convergent-unique-after-two-builds conv-unique
     :random-unique-after-two-builds rand-unique
     :convergent-duplicate-factor (/ (double conv-unique) n)
     :random-duplicate-factor (/ (double rand-unique) n)
     ;; from the FIRST convergent pass only, which is exactly `blocks` once.
     ;; An earlier version divided the span covering three passes by a
     ;; two-pass constant and understated this by 1.5x.
     :encrypt-throughput-mb-s (/ (/ plain-bytes 1048576.0) (/ (- t1 t0) 1000.0))}))
