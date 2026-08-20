(ns kotobase.recovery-qualification-test
  (:require [clojure.test :refer [deftest is]]
            [kotobase.recovery-qualification :as recovery]))

(def policy (recovery/read-policy))

;; Valid verification proof for testing
(def valid-verification
  {:algorithm "SHA-256"
   :expected-digest "a1b2c3d4e5f6"
   :actual-digest "a1b2c3d4e5f6"
   :verified-at 1723000000000
   :verified-by "backup-verifier-service-v1.2.3"
   :key-id "verification-key-2026-q3"
   :signature "deadbeef"}) ;; placeholder - signature verification is mocked

(def complete
  {:backups [{:backup/site :region-a :backup/encrypted? true
              :backup/immutable? true :backup/digest-verified? true
              :backup/verification valid-verification}
             {:backup/site :region-b :backup/encrypted? true
              :backup/immutable? true :backup/digest-verified? true
              :backup/verification valid-verification}]
   :restore-receipt {:restore-drill/status :passed
                     :restore-drill/destructive? true
                     :restore-drill/digest-verified? true
                     :restore-drill/sites #{:region-a :region-b}
                     :restore-drill/rto-ms 42 :restore-drill/rpo-ms 50}
   :corruption-repair {:detected? true :isolated? true :rebuilt? true
                       :digest-verified? true}
   :lost-key-exercise {:failed-closed? true :recovery-path-exercised? true}})

(deftest complete-multi-region-recovery-evidence-is-ready
  (let [result (recovery/evaluate policy complete)]
    (is (:recovery/ready? result) (pr-str result))
    (is (= #{:region-a :region-b} (:recovery/sites result)))))

(deftest every-recovery-evidence-class-fails-closed-independently
  (doseq [[input expected]
          [[(assoc-in complete [:backups 0 :backup/immutable?] false)
            :recovery/backups]
           [(assoc-in complete [:restore-receipt :restore-drill/rto-ms]
                      700000)
            :recovery/restore]
           [(assoc-in complete [:corruption-repair :rebuilt?] false)
            :recovery/corruption-repair]
           [(assoc-in complete [:lost-key-exercise :failed-closed?] false)
            :recovery/lost-key]
           [(assoc complete :backups [(first (:backups complete))])
            :recovery/regions]]]
    (let [result (recovery/evaluate policy input)]
      (is (false? (:recovery/ready? result)))
      (is (contains? (set (:recovery/errors result)) expected)))))

(deftest backup-verification-requires-proof
  (let [backup-no-proof {:backup/site :region-a :backup/encrypted? true
                         :backup/immutable? true :backup/digest-verified? true}  ;; 証跡なし
        backup-with-proof {:backup/site :region-a :backup/encrypted? true
                           :backup/immutable? true :backup/digest-verified? true
                           :backup/verification valid-verification}
        input-no-proof (assoc complete :backups [backup-no-proof backup-no-proof])
        input-with-proof (assoc complete :backups [backup-with-proof backup-with-proof])]
    (let [result-no-proof (recovery/evaluate policy input-no-proof)
          result-with-proof (recovery/evaluate policy input-with-proof)]
      (is (false? (:recovery/backup-ok? result-no-proof))
          "backup without verification proof fails")
      (is (contains? (set (:recovery/errors result-no-proof)) :recovery/backups))
      (is (:recovery/backup-ok? result-with-proof)
          "backup with valid proof passes"))))

(deftest backup-verification-fails-on-digest-mismatch
  (let [bad-verification (assoc valid-verification :actual-digest "different-digest")
        backup-bad {:backup/site :region-a :backup/encrypted? true
                    :backup/immutable? true :backup/digest-verified? true
                    :backup/verification bad-verification}
        input-bad (assoc complete :backups [backup-bad backup-bad])]
    (let [result (recovery/evaluate policy input-bad)]
      (is (false? (:recovery/backup-ok? result))
          "backup with digest mismatch fails")
      (is (contains? (set (:recovery/errors result)) :recovery/backups)))))

(deftest backup-verification-fails-on-unknown-algorithm
  (let [bad-verification (assoc valid-verification :algorithm "MD5")
        backup-bad {:backup/site :region-a :backup/encrypted? true
                    :backup/immutable? true :backup/digest-verified? true
                    :backup/verification bad-verification}
        input-bad (assoc complete :backups [backup-bad backup-bad])]
    (let [result (recovery/evaluate policy input-bad)]
      (is (false? (:recovery/backup-ok? result))
          "backup with unknown algorithm fails")
      (is (contains? (set (:recovery/errors result)) :recovery/backups)))))

(deftest backup-verification-fails-on-unknown-key
  (let [bad-verification (assoc valid-verification :key-id "unknown-key-2026")
        backup-bad {:backup/site :region-a :backup/encrypted? true
                    :backup/immutable? true :backup/digest-verified? true
                    :backup/verification bad-verification}
        input-bad (assoc complete :backups [backup-bad backup-bad])]
    (let [result (recovery/evaluate policy input-bad)]
      (is (false? (:recovery/backup-ok? result))
          "backup with unknown key fails")
      (is (contains? (set (:recovery/errors result)) :recovery/backups)))))

(deftest backup-verification-fails-on-missing-fields
  (let [incomplete-verification (dissoc valid-verification :verified-at)
        backup-bad {:backup/site :region-a :backup/encrypted? true
                    :backup/immutable? true :backup/digest-verified? true
                    :backup/verification incomplete-verification}
        input-bad (assoc complete :backups [backup-bad backup-bad])]
    (let [result (recovery/evaluate policy input-bad)]
      (is (false? (:recovery/backup-ok? result))
          "backup with incomplete verification fails")
      (is (contains? (set (:recovery/errors result)) :recovery/backups)))))