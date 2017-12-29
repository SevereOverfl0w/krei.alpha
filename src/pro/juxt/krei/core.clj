(ns pro.juxt.krei.core
  (:require
    [shadow.cljs.devtools.api :as shadow]
    [shadow.cljs.devtools.server :as shadow.server]
    [shadow.cljs.devtools.config :as shadow.config]
    [clojure.java.classpath :as classpath]
    [juxt.dirwatch :as dirwatch]
    [me.raynes.fs :as fs]
    [clojure.string :as string]
    [clojure.java.io :as io]
    [sass4clj.core :as sass]
    [clojure.edn :as edn]))

(defn make-jar
  [output files]
  (println "nop"))

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
       (pro.juxt.krei.core/find-krei-files)))

(defn build
  []
  (fs/delete-dir "./target/public/css/")
  (let [krei-files (read-krei-files)]
    (run!
      (fn [[input-file relative-path]]
        (sass/sass-compile-to-file
          input-file
          ;; TODO: How do I choose where to dump files?
          ;; TODO: (Maybe) take an option for the subpath to build CSS into?
          (io/file "./target/public/css"
                   (string/replace relative-path #"\.scss$" ".css"))
          ;; TODO: Take options
          {}))
      (eduction
        (map :krei.sass/files)
        cat
        (map (juxt (comp io/resource) identity))
        krei-files))))

(defn watch
  "Returns a function which will stop the watcher"
  ;; TODO: Watch krei files & reconfigure shadow on changes.
  []
  (let [krei-files (read-krei-files)

        krei-builders (mapv
                        (fn [path]
                          (dirwatch/watch-dir (fn [p] (println p) (build))
                                              (io/file path)))
                        (classpath/classpath-directories))
        shadow-builds (sequence
                        (comp (map :krei.shadow/builds)
                              cat)
                        krei-files)]
    ;; TODO: Update default config with target location
    (shadow.server/start! shadow.config/default-config)
    (run! shadow/watch shadow-builds)
    (fn []
      (run! dirwatch/close-watcher krei-builders)
      (run! shadow/stop-worker (map :build-id shadow-builds))
      (shadow.server/stop!))))
