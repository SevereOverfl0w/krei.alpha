;; Copyright © 2018, JUXT LTD.

(ns juxt.kick.alpha.core
  (:require
   [clojure.java.classpath :as classpath]
   [clojure.tools.logging :as log]
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [juxt.dirwatch :as dirwatch]
   [juxt.kick.alpha.impl.util :refer [deleting-tmp-dir]]
   [juxt.kick.alpha.impl.debounce :as kick.debounce]
   [me.raynes.fs :as fs]))

(defn key->provider
  "A composite key begins with the keyword of the provider, followed by
  another 'instance' keyword. In the case of singletons, only the
  provider keyword is required."
  [k]
  (if (vector? k) (first k) k))

(defmulti init!
  "Initialise a provider that will respond to file-system events."
  {:arglists '([key value opts])}
  (fn [key value opts] key))

(defmulti halt!
  "Halt a provider."
  {:arglists '([key value])}
  (fn [key value] key))

(defmulti notify!
  "Notify a provider that file-system events have occurred."
  {:arglists '([key events init-result])}
  (fn [key events init-result] key))

(defmulti oneshot!
  {:arglists '([key value opts])}
  (fn [key value opts] key))

(defmethod oneshot! :default [_ _ _])

(defn- add-classpath-dirs [builder-config]
  (assoc
    builder-config
    :kick.builder/classpath-dirs
    (remove
      ;; Filter out build directory, as it's on the classpath in dev
      #(= (.toAbsolutePath (.toPath %))
          (.toAbsolutePath (:kick.builder/target builder-config)))
      (classpath/classpath-directories))))

(defn builder-config [config]
  (->
    (reduce-kv
      (fn [acc k v]
        (cond-> acc
          (= "kick.builder" (namespace k)) (assoc k v)))
      #:kick.builder{:target "target"}  ; defaults
      config)
    (update :kick.builder/target (comp #(.toPath %) io/file))
    (add-classpath-dirs)))

(def ignorable? (some-fn
                  #(re-matches #"\.#.*" %)))

(defn watch
  "Starts watching. Returns a function which will stop the watcher"
  [config]
  (let [builder-config (builder-config config)

        debounce-a (agent nil
                          :error-handler (fn [a ex]
                                           (send a (constantly nil))
                                           (log/error ex "Error in kick agent")))

        init-results
        (reduce-kv
          (fn [acc k v]
            (cond-> acc
              ;; Entries with keys in the kick.builder ns
              ;; are ignored, they're reserved config.
              (not= "kick.builder" (namespace k))
              (assoc k
                     (merge builder-config
                            v
                            (if-let [init! (get-method init! k)]
                              (do
                                (log/infof "Calling init! on %s" k)
                                (or
                                  (init! k v
                                         ;; Select only :kick.builder keys from this config:
                                         builder-config) {}))
                              {})))))
          {} config)

        receiver (kick.debounce/receiver
                   (kick.debounce/schedule
                     debounce-a
                     (fn [events]
                       (doseq [k (keys init-results)]
                         (log/debugf "Calling notify! on %s, events are %s" k events)
                         (when-let [notify! (get-method notify! k)]
                           (try
                             (notify! k events (get init-results k))
                             (catch Exception e
                               (log/errorf e "Error during notification of %s" k))))))
                     50))

        watchers (mapv
                   (fn [path]
                     (dirwatch/watch-dir
                       (fn [event]
                         (when-not (ignorable? (.getName (:file event)))
                           (send debounce-a receiver event)))
                       (io/file path)))
                   (:kick.builder/classpath-dirs builder-config))]

    (fn []
      (run! dirwatch/close-watcher watchers)
      (doseq [[k v] init-results]
        (when-let [halt! (get-method halt! k)]
          (log/debugf "Calling halt! on %s" k)
          (halt! k v))))))

(defn build-once
  [config]
  (let [builder-config (builder-config config)]
    (doseq [[k v] config]
      (oneshot! k v builder-config))))
