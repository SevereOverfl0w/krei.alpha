;; Copyright © 2018, JUXT LTD.

(ns juxt.kick.alpha.core
  (:require
   [clojure.java.classpath :as classpath]
   [clojure.tools.logging :as log]
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [cljs.build.api]
   [juxt.dirwatch :as dirwatch]
   [juxt.kick.alpha.impl.util :refer [deleting-tmp-dir]]
   [juxt.kick.alpha.impl.debounce :as krei.debounce]
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
  {:arglists '([key events])}
  (fn [key events init-result] key))

(defn- add-classpath-dirs [builder-config]
  (assoc
    builder-config
    :kick.builder/classpath-dirs
    (remove
      ;; Filter out build directory, as it's on the classpath in dev
      #(= (.toPath %) (.toAbsolutePath (:kick.builder/target builder-config)))
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

(defn watch
  "Starts watching. Returns a function which will stop the watcher"
  ;; TODO: Watch krei files & reconfigure figwheel on changes.
  [config]
  (let [builder-config (builder-config config)

        debounce-a (agent nil)

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

        receiver (krei.debounce/receiver
                   (krei.debounce/schedule
                     debounce-a
                     (fn [events]
                       (doseq [k (keys init-results)]
                         (log/infof "Calling notify! on %s" k)
                         (when-let [notify! (get-method notify! k)]
                           (log/infof "(found fn for %s)" k)
                           (notify! k events (get init-results k)))))
                     50))



        watchers (mapv
                   (fn [path]
                     (dirwatch/watch-dir
                       #(send debounce-a receiver %)
                       (io/file path)))
                   (:kick.builder/classpath-dirs builder-config))]

    (fn []
      (run! dirwatch/close-watcher watchers)
      (doseq [[k v] init-results]
        (when-let [halt! (get-method halt! k)]
          (log/infof "Calling halt! on %s" k)
          (halt! k v))))))
