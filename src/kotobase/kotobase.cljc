(ns kotobase.kotobase
  "KotobaseStore — the IStore that persists to **kotobase.net** (the kotoba PDS).
  It carries NO HTTP client: every op is forwarded through a host-injected `xrpc`
  function `(fn [method params] -> result)`. The injecting host wires `xrpc` to the
  kotoba XRPC endpoint — in a Cloudflare Worker that is a `fetch` to
  `https://kotobase.net/xrpc/<method>` carrying the actor's CACAO; in tests it is a
  mock over a LocalStore. So the same store record runs in a worker, a JVM service,
  or a WASM pod.

  The five methods map onto kotoba's `:db-api` verbs (`{:q :transact! :pull …}`):
  `:put`/`:append` are `transact!`-class writes to the actor's key-derived IPNS
  graph (depth-1 self-mint, no token), `:get`/`:list`/`:read` are `q`/`pull` reads.
  kotobase.net itself backs that graph onto external object storage (git-annex/B2,
  S3) — so 'kotoba external storage' is exactly this record pointed at the PDS."
  (:require [kotoba.security.abac :as abac]
            [kotoba.security.information-flow :as flow]
            [kotoba.security.transport :as transport]
            [kotobase.store :as st]))

(def write-methods #{:put :append :transact})

(defn- resource-id [method params]
  (case method
    :put (str (:coll params) "/" (:key params))
    :get (str (:coll params) "/" (:key params))
    :list (str (:coll params) "/*")
    :append (str (:stream params) "/*")
    :read (str (:stream params) "/*")
    :snapshot :kotobase/snapshot
    :transact :kotobase/transaction
    :kotobase/unknown))

(defn authorize-xrpc
  "Wrap the host transport with shared four-axis ABAC. The wrapper evaluates
  immediately before every remote operation and never invokes XRPC on deny."
  [xrpc {:keys [abac-policy abac-attributes audit!
                information-flow-context information-flow-audit!
                transport-profile transport-audit!]
         :or {audit! (fn [_] nil) information-flow-audit! (fn [_] nil)
              transport-audit! (fn [_] nil)}}]
  (fn [method params]
    (let [decision
          (abac/evaluate
           (-> abac-attributes
               (assoc :resource
                      (merge {:id (resource-id method params)}
                             (:resource abac-attributes)))
               (assoc :action
                      (merge {:id (if (write-methods method)
                                    :kotobase/write :kotobase/read)
                              :capabilities #{(keyword "kotobase" (name method))}}
                             (:action abac-attributes))))
           abac-policy)
          flow-decision (when (and (write-methods method)
                                   information-flow-context)
                          (flow/evaluate-egress information-flow-context))
          transport-decision (when transport-profile
                               (transport/evaluate transport-profile))]
      (when abac-policy (audit! decision))
      (when flow-decision (information-flow-audit! flow-decision))
      (when transport-decision (transport-audit! transport-decision))
      (cond
        (not (:abac/allowed? decision))
        (throw (ex-info "ABAC policy denies kotobase operation"
                        {:type :kotobase/abac-denied
                         :method method
                         :abac/policy-id (:abac/policy-id decision)
                         :abac/violations (:abac/violations decision)}))

        (and flow-decision (not (:information-flow/allowed? flow-decision)))
        (throw (ex-info "information-flow policy denies kotobase egress"
                        {:type :kotobase/information-flow-denied
                         :method method
                         :information-flow/violations
                         (:information-flow/violations flow-decision)}))

        (and transport-decision (not (:transport/allowed? transport-decision)))
        (throw (ex-info "transport profile denies kotobase XRPC"
                        {:type :kotobase/transport-denied
                         :method method
                         :transport/violations
                         (:transport/violations transport-decision)}))

        :else
        (xrpc method params)
        ))))

(deftype KotobaseStore [xrpc]
  st/IStore
  (-put  [_ coll k v] (xrpc :put  {:coll coll :key k :val v}))
  (-get  [_ coll k]   (xrpc :get  {:coll coll :key k}))
  (-list [_ coll]     (xrpc :list {:coll coll}))
  (-append [_ stream event] (xrpc :append {:stream stream :event event}))
  (-read   [_ stream since] (xrpc :read   {:stream stream :since (or since 0)})))

(deftype TransactionalKotobaseStore [xrpc]
  st/IStore
  (-put  [_ coll k v] (xrpc :put  {:coll coll :key k :val v}))
  (-get  [_ coll k]   (xrpc :get  {:coll coll :key k}))
  (-list [_ coll]     (xrpc :list {:coll coll}))
  (-append [_ stream event] (xrpc :append {:stream stream :event event}))
  (-read [_ stream since] (xrpc :read {:stream stream :since (or since 0)}))
  st/ITransactionalStore
  (-snapshot [_ scope] (xrpc :snapshot scope))
  (-transact [_ request] (xrpc :transact request)))

(defn kotobase-store
  "A store backed by kotobase.net through the injected `xrpc` fn
  `(fn [method params] -> result)`."
  ([xrpc] (->KotobaseStore xrpc))
  ([xrpc {:keys [transactional? abac-policy information-flow-context
                 transport-profile] :as options}]
   (let [xrpc (if (or abac-policy information-flow-context transport-profile)
                (authorize-xrpc xrpc options)
                xrpc)]
     (if transactional?
       (->TransactionalKotobaseStore xrpc)
       (->KotobaseStore xrpc)))))

(defn xrpc-method->path
  "kotoba XRPC URL path for a store `method` (the worker's `fetch` target under
  https://kotobase.net/xrpc/)."
  [method]
  (str "net.kotobase.store." (name method)))
