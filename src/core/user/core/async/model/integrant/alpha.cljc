(ns user.core.async.model.integrant.alpha
  (:require
   [clojure.core.async :as async]
   [integrant.core :as ig]
   [user.core.async.alpha :as user.async]
   #?(:clj
      [user.java.lang.runtime :as java.runtime])
   ))


(defmethod ig/init-key ::mult
  [_ chan]
  (async/mult chan))


(defmethod ig/halt-key! ::mult
  [_ mult]
  (async/untap-all mult)
  (async/close! (async/muxch* mult)))


(defmethod ig/init-key ::mult-putter
  [_ mult]
  #(async/put! (async/muxch* mult) %))


(defmethod ig/halt-key! ::mult-putter
  [_ _mult])


(defmethod ig/init-key ::pub
  [_ident {:keys [chan topic-fn buf-fn] :or {buf-fn (constantly nil)}}]
  {:pre [(ifn? topic-fn) (ifn? buf-fn)]}
  (async/pub chan topic-fn buf-fn))


(defmethod ig/halt-key! ::pub
  [_ident _pub])


;;


(defn tap--default-ex-handler [_ident] (fn [_]))
(defn tap--default-on-close [_ident] (fn []))
(defn tap--default-on-init [_ident] (fn []))


(defmethod ig/prep-key ::tap
  [ident config]
  (-> config
    (update :ex-handler #(or % (tap--default-ex-handler ident)))
    (update :on-close #(or % (tap--default-on-close ident)))
    (update :on-init #(or % (tap--default-on-init ident)))))


(defmethod ig/init-key ::tap
  [_ident {:keys [mult tap-ch consume ex-handler on-close on-init]}]
  (let [tap-proc (user.async/tap-proc mult tap-ch consume ex-handler on-close)]
    (on-init)
    tap-proc))


(defmethod ig/halt-key! ::tap
  [_ident tap-proc]
  (async/close! tap-proc))


(defn subscription--default-ex-handler [_ident] (fn [_]))
(defn subscription--default-on-close [_ident])
(defn subscription--default-on-init [_ident])


(defmethod ig/prep-key ::subscription
  [ident config]
  (-> config
    (update :ex-handler #(or % (subscription--default-ex-handler ident)))
    (update :on-close #(or % (subscription--default-on-close ident)))
    (update :on-init #(or % (subscription--default-on-init ident)))))


(defmethod ig/init-key ::subscription
  [_ident {:keys [pub topic sub-ch consume ex-handler on-close on-init]}]
  (let [sub-proc (user.async/subscription-proc pub topic sub-ch consume ex-handler on-close)]
    (on-init)
    sub-proc))


(defmethod ig/halt-key! ::subscription
  [_ident sub-proc]
  (async/close! sub-proc))


;;


(defn pipelined-subscription--default-ex-handler [_ident] (fn [_]))
(defn pipelined-subscription--default-on-close [_ident] (fn []))
(defn pipelined-subscription--default-on-init [_ident] (fn []))


#?(:clj
   (defmethod ig/prep-key ::pipelined-subscription
     [ident config]
     (-> config
       (update :ex-handler #(or % (pipelined-subscription--default-ex-handler ident)))
       (update :on-close #(or % (pipelined-subscription--default-on-close ident)))
       (update :on-init #(or % (pipelined-subscription--default-on-init ident)))))
   )


#?(:clj
   (defmethod ig/init-key ::pipelined-subscription
     [_ident {:keys [pub topic sub-ch consume xf ex-handler n out on-close on-init]
              :or   {xf  identity
                     n   (java.runtime/available-processors)
                     out (async/chan)}}]
     (let [sub-proc (user.async/pipelined-subscription-proc pub topic sub-ch consume xf ex-handler n out on-close)]
       (on-init)
       sub-proc)))


#?(:clj
   (defmethod ig/halt-key! ::pipelined-subscription
     [_ident sub-proc]
     (async/close! sub-proc)))


;;


(defmethod ig/init-key ::sticky-batch-proc
  [ident {:keys [on-ig-init on-ig-halt]
          :or   {on-ig-init (fn [_])
                 on-ig-halt (fn [_])}
          :as   opts}]
  (let [proc (user.async/sticky-batch-proc
               (-> opts
                 (dissoc :on-ig-init :on-ig-halt)
                 (update :on-exit
                   (fn [on-exit]
                     (fn []
                       (on-exit)
                       (on-ig-halt ident))))))]
    (on-ig-init ident)
    proc))


(defmethod ig/halt-key! ::sticky-batch-proc
  [_ident proc]
  (async/close! proc))


(defmethod ig/init-key ::lazy-batch-proc
  [ident {:keys [on-ig-init on-ig-halt]
          :or   {on-ig-init (fn [_])
                 on-ig-halt (fn [_])}
          :as   opts}]
  (let [proc (user.async/lazy-batch-proc
               (-> opts
                 (dissoc :on-ig-init :on-ig-halt)
                 (update :on-exit
                   (fn [on-exit]
                     (fn []
                       (on-exit)
                       (on-ig-halt ident))))))]
    (on-ig-init ident)
    proc))


(defmethod ig/halt-key! ::lazy-batch-proc
  [_ident proc]
  (async/close! proc))
