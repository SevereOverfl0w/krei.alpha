(ns io.dominic.krei.alpha.core
  (:require
   [clojure.java.classpath :as classpath]
   [clojure.string :as string]
   [clojure.tools.logging :as log]
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [cljs.build.api]
   [io.dominic.krei.alpha.impl.util :refer [deleting-tmp-dir]]
   [io.dominic.krei.alpha.impl.debounce :as krei.debounce]
   [figwheel-sidecar.repl-api :as repl-api]
   [figwheel-sidecar.components.figwheel-server :as figwheel.server]
   [figwheel-sidecar.utils :as figwheel.utils]
   [juxt.dirwatch :as dirwatch]
   [me.raynes.fs :as fs]
   [sass4clj.core :as sass]))

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

(defn- list-resources [file]
  (enumeration-seq
    (.getResources
      (.. Thread currentThread getContextClassLoader)
      file)))

(defn find-krei-files []
  (list-resources "krei-file.edn"))

(defn read-krei-file [f]
  (-> f io/reader java.io.PushbackReader. clojure.edn/read))

(defn composite-key? [k] (vector? k))

(defn parse-krei-resource
  "Parse a krei resource, returning a normalized map keyed by
  provider. Where values are maps, associate the resource URL and in
  the case of a composite key, also the instance id."
  [krei-res]
  (reduce-kv
    (fn [acc prov entries]
      (assoc
        acc prov
        (for [[k m] entries]
          (cond-> m
            ;; Tag the resource for debugging purposes
            (map? m)
            (assoc :krei/resource krei-res)
            ;; Tag the instance id if k is composite
            (and (map? m) (composite-key? k))
            (assoc :krei/id (second k))))))
    {}
    (group-by (comp key->provider first) (read-krei-file krei-res))))

(defn parse-krei-resources []
  (apply merge-with concat (map parse-krei-resource (find-krei-files))))

(defn watch
  "Returns a function which will stop the watcher"
  ;; TODO: Watch krei files & reconfigure figwheel on changes.
  []
  (let [target (.toPath (io/file "target"))

        classpath-dirs (remove
                         ;; Filter out build directory, as it's on the classpath in dev
                         #(= (.toPath %) (.toAbsolutePath target))
                         (classpath/classpath-directories))

        debounce-a (agent nil)

        krei-map (parse-krei-resources)

        init-results
        (reduce-kv
          (fn [acc k v]
            (log/infof "Calling init! on %s" k)
            (assoc acc k
                   (init! k v
                          #:krei{:target target
                                 :classpath-dirs classpath-dirs})))
          {}
          krei-map)

        receiver (krei.debounce/receiver
                   (krei.debounce/schedule
                     debounce-a
                     (fn [events]
                       (doseq [[k _] krei-map]
                         (log/infof "Calling notify! on %s" k)
                         (when-let [f (get-method notify! k)]
                           (log/infof "(found fn for %s)" k)
                           (f k events (get init-results k)))))
                     50))

        krei-builders (mapv
                        (fn [path]
                          (dirwatch/watch-dir
                            #(send debounce-a receiver %)
                            (io/file path)))
                        classpath-dirs)]

    ;; TODO: Update default config with target location
    ;; @dmc what's this? still something we need?

    (fn []
      (run! dirwatch/close-watcher krei-builders)
      (doseq [[k v] krei-map]
        (log/infof "Calling halt! on %s" k)
        (halt! k v)))))

(defn prod-build
  [classpath-output]
  (let [build-data (deleting-tmp-dir "prod-build-data")
        krei-files (map read-krei-file (find-krei-files))]
    (run!
      (fn [[input-file relative-path]]
        (sass/sass-compile-to-file
          input-file
          (-> classpath-output
              (.resolve (string/replace relative-path #"\.scss$" ".css"))
              (.toFile))
          ;; TODO: Take options
          {}))
      (eduction
        (map :krei.sass/files)
        cat
        (map (juxt io/resource identity))
        krei-files))
    (run!
      #(cljs.build.api/build (mapv str (classpath/classpath-directories)) %)
      (into []
            (comp (map :krei.figwheel/builds)
                  cat
                  (map :compiler)
                  (map #(assoc %
                               :optimizations :advanced
                               :source-map false
                               :closure-defines {'goog.DEBUG false}
                               :output-dir (str (.resolve build-data "cljs"))))
                  (map (fn [c] (update c
                                       :output-to
                                       #(str (.resolve classpath-output %))))))
            krei-files))))


;; Deprecated providers declared here for compatibility with existing users of krei.

;; Figwheel

(defn- figwheel-notify
  [files figwheel-system]
  (when-let [files' (and repl-api/*repl-api-system*
                         (->> files
                              (map str)
                              (filter #(string/ends-with? % ".html"))
                              seq))]
    (figwheel.server/send-message
      (:figwheel-system repl-api/*repl-api-system*)
      ::figwheel.server/broadcast
      {:msg-name :html-files-changed
       :files (map
                (fn [file]
                  [{:type :html
                    :file (figwheel.utils/remove-root-path file)}])
                files')})))

(defmethod init! :krei.figwheel/builds [_ coll-of-builds {:krei/keys [target classpath-dirs]}]

  (let [target-relative #(.resolve target %)]
    (repl-api/start-figwheel!
      {:figwheel-options {:css-dirs [(str target)]}

       :build-ids (into [] (comp cat (map :id)) coll-of-builds)

       :all-builds (into []
                         (comp cat
                               (map #(assoc % :source-paths (map str classpath-dirs)))
                               (map #(update % :compiler merge {:optimizations :none}))
                               (map #(update-in % [:compiler :preloads] conj 'io.dominic.krei.alpha.figwheel-injector))
                               (map #(update-in % [:compiler :output-dir] (comp str target-relative)))
                               (map #(update-in % [:compiler :output-to] (comp str target-relative))))
                         coll-of-builds)})))

(defmethod notify! :krei.figwheel/builds [_ events _]
  (when repl-api/*repl-api-system*
    (figwheel-notify
      (map :file events)
      repl-api/*repl-api-system*)))

(defmethod halt! :krei.figwheel/builds [_ _]
  (repl-api/stop-figwheel!))

;; Sass

(defmethod init! :krei.sass/files [_ files opts]
  opts)

(defn build-sass
  [target]
  ;; figwheel can't handle the deleting of this directory, and just blows up,
  ;; so leave stale files hanging around, it'll be fine, he says.
  ;; (fs/delete-dir "./target/public/css/")
  (log/info "Building Sass, output going to %s" target)
  (let [krei-files (map read-krei-file (find-krei-files))]
    (run!
      (fn [[input-file relative-path]]
        (sass/sass-compile-to-file
          input-file
          (io/file target
                   (string/replace relative-path #"\.scss$" ".css"))
          ;; TODO: Take options
          {}))
      (eduction
        (map :krei.sass/files)
        cat
        (map (juxt (comp io/resource) identity))
        krei-files))))

(defmethod notify! :krei.sass/files [_ events init-result]
  (assert (:krei/target init-result))
  (when (some #(re-matches #".*\.s[ca]ss$" (.getName (:file %))) events)
    (build-sass (:krei/target init-result))))
