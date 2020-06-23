(ns user.core.async.alpha
  (:require
   [clojure.core.async :as async]
   [clojure.core.async.impl.protocols :as async.impl]
   [clojure.core.async.impl.channels :as async.channels]
   [taoensso.timbre :as timbre]
   [user.timbre.alpha :as u.timbre]
   #?(:clj [user.java.lang.runtime :as java.runtime])
   )
  (:import
   #?@(:clj
       [clojure.core.async.impl.channels.ManyToManyChannel])
   ))


(defn chan?
  [x]
  (and (satisfies? async.impl/Channel x)
       (satisfies? async.impl/ReadPort x)
       (satisfies? async.impl/WritePort x)))


(defn tap-proc
  "return proc type of `async.impl/Channel`

  proc can be deleted with (async/close! proc)"
  {:style/indent [0]}
  ([mult ch consume]
   (tap-proc mult ch consume (fn [_]) (fn [])))
  ([mult ch consume ex-handler]
   (tap-proc mult ch consume ex-handler (fn [])))
  ([mult ch consume ex-handler on-close]
   {:pre [(chan? ch)
          (fn? consume)
          (fn? ex-handler)
          (fn? on-close)]}
   (async/go-loop []
     (if-let [v (async/<! ch)]
       (do
         (try
           (consume v)
           (catch #?(:clj Throwable :cljs js/Error) e (ex-handler e)))
         (recur))
       (do
         (u.timbre/debug-halt consume)
         (on-close))))
   (async/tap mult ch)
   (reify async.impl/Channel
     (close! [_]
       (async/untap mult ch)
       (async/close! ch)))))


(defn subscription-proc
  "return proc type of `async.impl/Channel`

  proc can be deleted with (async/close! proc)"
  {:style/indent [0]}
  ([pub topic sub-ch consume]
   (subscription-proc pub topic sub-ch consume (fn [_]) (fn [])))
  ([pub topic sub-ch consume ex-handler]
   (subscription-proc pub topic sub-ch consume ex-handler (fn [])))
  ([pub topic sub-ch consume ex-handler on-close]
   {:pre [(chan? sub-ch)
          (fn? consume) (fn? ex-handler) (fn? on-close)]}
   (async/go-loop []
     (if-let [v (async/<! sub-ch)]
       (do
         (try
           (consume v)
           (catch #?(:clj Throwable :cljs js/Error) e (ex-handler e)))
         (recur))
       (do
         (u.timbre/debug-halt consume)
         (on-close))))
   (async/sub pub topic sub-ch)
   (reify async.impl/Channel
     (close! [_]
       (async/unsub pub topic sub-ch)
       (async/close! sub-ch)))))


#?(:clj
   (defn pipelined-subscription-proc
     {:style/indent [0]}
     ([pub topic from consume]
      (pipelined-subscription-proc
        pub topic from consume identity (fn [_]) (+ (java.runtime/available-processors) 2) (async/chan) (fn [])))
     ([pub topic from consume xf]
      (pipelined-subscription-proc
        pub topic from consume xf (fn [_]) (+ (java.runtime/available-processors) 2) (async/chan) (fn [])))
     ([pub topic from consume xf ex-handler]
      (pipelined-subscription-proc
        pub topic from consume xf ex-handler (+ (java.runtime/available-processors) 2) (async/chan) (fn [])))
     ([pub topic from consume xf ex-handler n]
      (pipelined-subscription-proc pub topic from consume xf ex-handler n (async/chan) (fn [])))
     ([pub topic from consume xf ex-handler n out]
      (pipelined-subscription-proc pub topic from consume xf ex-handler n out (fn [])))
     ([pub topic from consume xf ex-handler n out on-close]
      {:pre [(chan? from)
             (chan? out)
             (fn? consume) (fn? ex-handler) (fn? on-close)]}
      (async/pipeline n out xf from true ex-handler)
      (async/go-loop []
        (if-let [v (async/<! out)]
          (do
            (consume v)
            (recur))
          (do
            (u.timbre/debug-halt consume)
            (on-close))))
      (async/sub pub topic from)
      (reify async.impl/Channel
        (close! [_]
          (async/unsub pub topic from)
          (async/close! from))))))


(defn sticky-batch-proc*
  "
- :sub-task fn has args which should be [acc v] and should returns acc

- :batch-task fn arg should be [acc]"
  {:style/indent [0]}
  [{:keys [init-acc sub-ch sub-task batch-timeout batch-task on-exit]
    :or   {init-acc []
           on-exit  (fn [])
           sub-task (fn [acc v] (conj acc v))}}]
  {:pre [(coll? init-acc)
         (number? batch-timeout)
         (fn? sub-task)
         (fn? batch-task)
         (fn? on-exit)]}
  (let [wait-ch (async/chan)]
    (async/go
      (loop [acc              init-acc
             batch-timeout-ch wait-ch]
        (async/alt!
          batch-timeout-ch
          (do
            (batch-task acc)
            (recur [] wait-ch))

          sub-ch
          ([v]
           (cond
             (nil? v)
             (do
               (on-exit)
               (async/close! wait-ch))

             (empty? acc)
             (recur (sub-task acc v) (async/timeout batch-timeout))

             :else
             (recur (sub-task acc v) batch-timeout-ch))))))
    (reify async.impl/Channel
      (close! [_]
        (async/close! sub-ch)
        (async/close! wait-ch)))))


(defn sticky-batch-proc
  {:style/indent [0]}
  [{:keys [batch-task on-exit] :as opts}]
  {:pre [(fn? batch-task)
         (fn? on-exit)]}
  (sticky-batch-proc*
    (-> opts
      (assoc
        :batch-task
        (fn [acc]
          (try
            (batch-task acc)
            (catch #?(:clj Throwable :cljs js/Error) e
              (timbre/error e))))
        :on-exit
        (fn []
          (try
            (on-exit)
            (catch #?(:clj Throwable :cljs js/Error) e
              (timbre/error e))))))))


(defn lazy-batch-proc*
  ":sub-task fn arg should be [acc v] :batch-task fn arg should be [acc]"
  {:style/indent [0]}
  [{:keys [init-acc sub-ch sub-task batch-timeout batch-task on-exit]
    :or   {init-acc []
           sub-task (fn [acc v] (conj acc v))
           on-exit  (fn [])}}]
  {:pre [(coll? init-acc)
         (number? batch-timeout)
         (fn? batch-task)
         (fn? sub-task)
         (fn? on-exit)]}
  (let [wait-ch (async/chan)]
    (async/go
      (loop [acc              init-acc
             batch-timeout-ch wait-ch]
        (async/alt!
          batch-timeout-ch
          (do
            (batch-task acc)
            (recur [] wait-ch))

          sub-ch
          ([v]
           (cond
             (nil? v)
             (do
               (on-exit)
               (async/close! wait-ch))

             :else
             (recur (sub-task acc v) (async/timeout batch-timeout)))))))
    (reify async.impl/Channel
      (close! [_]
        (async/close! sub-ch)
        (async/close! wait-ch)))))


(defn lazy-batch-proc
  {:style/indent [0]}
  [{:keys [batch-task on-exit] :as opts}]
  {:pre [(fn? batch-task)
         (fn? on-exit)]}
  (lazy-batch-proc*
    (-> opts
      (assoc
        :batch-task
        (fn [acc]
          (try
            (batch-task acc)
            (catch #?(:clj Throwable :cljs js/Error) e
              (timbre/error e))))
        :on-exit
        (fn []
          (try
            (on-exit)
            (catch #?(:clj Throwable :cljs js/Error) e
              (timbre/error e))))))))
