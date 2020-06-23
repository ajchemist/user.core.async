(ns user.core.async.model.integrant.alpha
  (:require
   [clojure.core.async :as async]
   [integrant.core :as ig]
   [taoensso.timbre :as timbre]
   [user.timbre.alpha :as u.timbre]
   [user.core.async.alpha :as user.async]
   [user.java.lang.runtime :as java.runtime]
   ))


(defmethod ig/init-key ::pub
  [ident {:keys [chan topic-fn buf-fn] :or {buf-fn (constantly nil)}}]
  {:pre [(ifn? topic-fn) (ifn? buf-fn)]}
  (async/pub chan topic-fn buf-fn))


(defmethod ig/halt-key! ::pub
  [ident pub])


;;


(defn subscription--default-ex-handler [ident] (fn [_]))
(defn subscription--default-on-close [ident] (fn [] (u.timbre/info-halt (u.timbre/ident ident))))
(defn subscription--default-init-callback [ident] (fn [] (u.timbre/info-init (u.timbre/ident ident))))


(defmethod ig/init-key ::subscription
  [ident {:keys [pub topic sub-ch consume ex-handler on-close
                 init-callback]
          :or   {ex-handler    (subscription--default-ex-handler ident)
                 on-close      (subscription--default-on-close ident)
                 init-callback (subscription--default-init-callback ident)}}]
  (let [sub-proc (user.async/subscription-proc pub topic sub-ch consume ex-handler on-close)]
    (init-callback)
    sub-proc))


(defmethod ig/halt-key! ::subscription
  [ident sub-proc]
  (async/close! sub-proc))


;;


(defn pipelined-subscription--default-ex-handler [ident] (fn [_]))
(defn pipelined-subscription--default-on-close [ident] (fn [] (u.timbre/info-halt (u.timbre/ident ident))))
(defn pipelined-subscription--default-init-callback [ident] (fn [] (u.timbre/info-init (u.timbre/ident ident))))


#?(:clj
   (defmethod ig/init-key ::pipelined-subscription
     [ident {:keys [pub topic sub-ch consume xf ex-handler n out on-close
                    init-callback]
             :or   {xf            identity
                    ex-handler    (pipelined-subscription--default-ex-handler ident)
                    n             (java.runtime/available-processors)
                    out           (async/chan)
                    on-close      (pipelined-subscription--default-on-close ident)
                    init-callback (pipelined-subscription--default-init-callback ident)}}]
     (let [sub-proc (pipelined-subscription-proc pub topic sub-ch consume xf ex-handler n out on-close)]
       (init-callback)
       sub-proc)))


#?(:clj
   (defmethod ig/halt-key! ::pipelined-subscription
     [ident sub-proc]
     (async/close! sub-proc)))


;;


(defmethod ig/init-key ::sticky-batch-proc
  [ident opts]
  (let [proc (user.async/sticky-batch-proc
               (update opts :on-exit
                 (fn [on-exit]
                   (fn []
                     (on-exit)
                     (timbre/info (u.timbre/halt-prefix) (u.timbre/ident ident))))))]
    (timbre/info (u.timbre/init-prefix) (u.timbre/ident ident))
    proc))


(defmethod ig/halt-key! ::sticky-batch-proc
  [ident proc]
  (async/close! proc))


(defmethod ig/init-key ::lazy-batch-proc
  [ident opts]
  (let [proc (user.async/lazy-batch-proc
               (update opts :on-exit
                 (fn [on-exit]
                   (fn []
                     (on-exit)
                     (timbre/info (u.timbre/halt-prefix) (u.timbre/ident ident))))))]
    (timbre/info (u.timbre/init-prefix) (u.timbre/ident ident))
    proc))


(defmethod ig/halt-key! ::lazy-batch-proc
  [ident proc]
  (async/close! proc))
