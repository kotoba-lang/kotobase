(ns kotobase.recovery-qualification-test
  (:require [clojure.test :refer [deftest is]]
            [kotobase.recovery-qualification :as recovery]))

(def policy (recovery/read-policy))
(def complete
  {:backups [{:backup/site :region-a :backup/encrypted? true
              :backup/immutable? true :backup/digest-verified? true}
             {:backup/site :region-b :backup/encrypted? true
              :backup/immutable? true :backup/digest-verified? true}]
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
