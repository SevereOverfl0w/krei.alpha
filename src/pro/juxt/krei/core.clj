(ns pro.juxt.krei.core
  (:require
    [figwheel-sidecar.repl-api :as repl-api]
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
  ;; figwheel can't handle the deleting of this directory, and just blows up,
  ;; so leave stale files hanging around, it'll be fine, he says.
  ;; (fs/delete-dir "./target/public/css/")
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
  ;; TODO: Watch krei files & reconfigure figwheel on changes.
  []
  (let [krei-files (read-krei-files)

        classpath-dirs (classpath/classpath-directories)

        krei-builders (mapv
                        (fn [path]
                          (dirwatch/watch-dir (fn [p] (println p) (build))
                                              (io/file path)))
                        (classpath/classpath-directories))]
    ;; TODO: Update default config with target location
    (repl-api/start-figwheel!
      {:figwheel-options {:css-dirs ["target"]}
       :all-builds (doto (into []
                               (comp (map :krei.figwheel/builds)
                                     cat
                                     (map #(assoc % :source-paths (map str classpath-dirs)))
                                     (map #(update % :compiler merge {:optimizations :none})))
                               krei-files)
                     prn)})
    (fn []
      (run! dirwatch/close-watcher krei-builders)
      (repl-api/stop-figwheel!))))
