(ns io.dominic.krei.alpha.core
  (:require
    [figwheel-sidecar.repl-api :as repl-api]
    [figwheel-sidecar.components.figwheel-server :as figwheel.server]
    [figwheel-sidecar.utils :as figwheel.utils]
    [clojure.java.classpath :as classpath]
    [juxt.dirwatch :as dirwatch]
    [me.raynes.fs :as fs]
    [clojure.string :as string]
    [clojure.java.io :as io]
    [sass4clj.core :as sass]
    [clojure.edn :as edn]
    [cljs.build.api]
    [io.dominic.krei.alpha.impl.util :refer [deleting-tmp-dir]]
    [io.dominic.krei.alpha.impl.debounce :as krei.debounce]))

(defn- list-resources [file]
  (enumeration-seq
    (.getResources
      (.. Thread currentThread getContextClassLoader)
      file)))

(defn find-krei-files
  []
  (list-resources "krei-file.edn"))

(defn read-krei-files
  []
  (map (comp clojure.edn/read #(java.io.PushbackReader. %) io/reader)
       (io.dominic.krei.alpha.core/find-krei-files)))

(defn build-sass
  []
  ;; figwheel can't handle the deleting of this directory, and just blows up,
  ;; so leave stale files hanging around, it'll be fine, he says.
  ;; (fs/delete-dir "./target/public/css/")
  (let [krei-files (read-krei-files)]
    (run!
      (fn [[input-file relative-path]]
        (sass/sass-compile-to-file
          input-file
          (io/file "./target"
                   (string/replace relative-path #"\.scss$" ".css"))
          ;; TODO: Take options
          {}))
      (eduction
        (map :krei.sass/files)
        cat
        (map (juxt (comp io/resource) identity))
        krei-files))))

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

(defn watch
  "Returns a function which will stop the watcher"
  ;; TODO: Watch krei files & reconfigure figwheel on changes.
  []
  (let [target (.toPath (io/file "target"))
        target-relative #(.resolve target %)
        krei-files (read-krei-files)

        classpath-dirs (remove
                         ;; Filter out build directory, as it's on the classpath in dev
                         #(= (.toPath %) (.toAbsolutePath target))
                         (classpath/classpath-directories))

        debounce-a (agent nil)

        receiver (krei.debounce/receiver
                   (krei.debounce/schedule
                     debounce-a
                     (fn [events]
                       (when repl-api/*repl-api-system*
                         (figwheel-notify
                           (map :file events)
                           repl-api/*repl-api-system*))
                       (when (some #(re-matches #".*\.s[ca]ss$" (.getName (:file %)))
                                   events)
                         (build-sass)))
                     50))

        krei-builders (mapv
                        (fn [path]
                          (dirwatch/watch-dir
                            #(send debounce-a receiver %)
                            (io/file path)))
                        classpath-dirs)]
    ;; TODO: Update default config with target location
    (when (seq (into []
                     (comp (map :krei.figwheel/builds)
                           cat)
                     krei-files))
      (repl-api/start-figwheel!
        {:figwheel-options {:css-dirs [(str target)]}

         :build-ids (into []
                          (comp (map :krei.figwheel/builds)
                                cat
                                (map :id))
                          krei-files)
         :all-builds (into []
                           (comp (map :krei.figwheel/builds)
                                 cat
                                 (map #(dissoc % :self-hosted?))
                                 (map #(assoc % :source-paths (map str classpath-dirs)))
                                 (map #(update % :compiler merge {:optimizations :none}))
                                 (map #(update-in % [:compiler :preloads] conj 'io.dominic.krei.alpha.figwheel-injector))
                                 (map #(update-in % [:compiler :output-dir] (comp str target-relative)))
                                 (map #(update-in % [:compiler :output-to] (comp str target-relative))))
                           krei-files)}))
    (build-sass)
    (fn []
      (run! dirwatch/close-watcher krei-builders)
      (when (seq (into []
                       (comp (map :krei.figwheel/builds)
                             cat)
                       krei-files))
        (repl-api/stop-figwheel!)))))

(defn prod-build
  [classpath-output]
  (let [build-data (deleting-tmp-dir "prod-build-data")
        krei-files (read-krei-files)]
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
                  (map #(if (:self-hosted? %)
                          (update % :compiler
                                  assoc
                                  :optimizations :simple
                                  :optimize-constants false
                                  :static-fns true)
                          %))
                  (map :compiler)
                  (map #(assoc %
                               :source-map false
                               :closure-defines {'goog.DEBUG false}
                               :output-dir (str (.resolve build-data "cljs"))))
                  (map (fn [c] (update c
                                       :output-to
                                       #(str (.resolve classpath-output %))))))
            krei-files))))
