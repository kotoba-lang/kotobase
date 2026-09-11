(ns kotobase.operations-qualification-test
  (:require [clojure.test :refer [deftest is]]
            [kotobase.operations-qualification :as operations]))

(def policy (operations/read-policy))
(def complete
  {:slo {:evidence/passed? true} :alert-routing {:evidence/passed? true}
   :on-call {:evidence/passed? true}
   :incident-playbook {:evidence/passed? true :evidence/exercised? true}
   :secret-scan {:evidence/passed? true :evidence/no-secrets? true}
   :rotation-drill {:evidence/passed? true :evidence/keys-rotated? true}})

(deftest complete-operations-evidence-is-ready
  (let [result (operations/evaluate policy complete)]
    (is (:operations/ready? result) (pr-str result))
    (is (= (:required-evidence policy) (:operations/present result)))))

(deftest operations-controls-fail-closed
  (doseq [[evidence error]
          [[(dissoc complete :on-call) :operations/missing-evidence]
           [(assoc-in complete [:secret-scan :evidence/no-secrets?] false)
            :operations/secret-scan]
           [(assoc-in complete [:rotation-drill :evidence/keys-rotated?] false)
            :operations/rotation-drill]
           [(assoc-in complete [:incident-playbook :evidence/exercised?] false)
            :operations/incident-exercise]]]
    (let [result (operations/evaluate policy evidence)]
      (is (false? (:operations/ready? result)))
      (is (contains? (set (:operations/errors result)) error)))))
