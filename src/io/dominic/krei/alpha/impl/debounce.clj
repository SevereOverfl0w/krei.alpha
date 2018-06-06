(ns io.dominic.krei.alpha.impl.debounce
  (:import
    [java.util.concurrent ScheduledThreadPoolExecutor TimeUnit]))

(def s (ScheduledThreadPoolExecutor. 1))

(defn- send!
  [state* f]
  (when (some? state*)
    (f state*))
  nil)

(defn schedule
  [a f ms]
  (fn []
    (.schedule s #(send a send! f) ms TimeUnit/MILLISECONDS)))

(defn receiver
  [schedule]
  (fn [state* new-item]
    (when (nil? state*)
      (schedule))
    (conj state* new-item)))
