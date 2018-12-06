;; Copyright © 2018, JUXT LTD.

;; Suggested dependencies:
;; figwheel-main {:mvn/version "0.1.9"}

(ns juxt.kick.alpha.providers.figwheel-main
  (:require
   [clojure.string :as string]
   [clojure.tools.logging :as log]
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [juxt.kick.alpha.core :as kick]
   [juxt.kick.alpha.impl.util :refer [deleting-tmp-dir when-ns]]))

(defn- update-contains
  [m k & args]
  (if (contains? m k)
    (apply update m k args)
    m))

(defn- filter-lib-dirs
  "Remove directories from the classpath which are from libraries."
  [classpath-dirs]
  (if-let [lib (some-> (System/getProperty "clojure.libfile")
                       slurp
                       edn/read-string)]
    (reduce
      (fn [project-dirs v]
        (filter #(= (.getCanonicalPath %) v) project-dirs))
      classpath-dirs
      (eduction
        (comp
          (map :paths)
          cat
          (map io/file)
          (filter (memfn isDirectory))
          (map (memfn getCanonicalPath)))
        (vals lib)))
    classpath-dirs))

(def pack-paths
  (some-> (System/getProperty "clojure.libfile")
          slurp
          edn/read-string
          (get 'juxt/kick.alpha)
          :paths
          set))

(defn pack?
  [file]
  (when pack-paths
    (pack-paths (str (.getAbsolutePath file)))))

(when-ns figwheel.main.api
  ;; Figwheel
  (require
    '[cljs.build.api :as cljs.build]
    '[figwheel.main.api :as figwheel.api]
    '[figwheel.main :refer [build-registry]])

  (defn- target-relative
    [relpath target]
    (str (.resolve target relpath)))

  (defmethod kick/oneshot! :kick/figwheel-main
    [_ {:keys [builds]} {:keys [classpath-dirs kick.builder/target]}]
    (let [tmp-dir (deleting-tmp-dir "figwheel")]
      (doseq [build builds]
        (cljs.build/build
          (mapv str classpath-dirs)
          (-> build
              (dissoc :id)
              (update-contains :output-dir target-relative tmp-dir)
              (update-contains :output-to target-relative target))))))

  (defmethod kick/init! :kick/figwheel-main
    [_ {:keys [builds figwheel-config]} {:kick.builder/keys [target classpath-dirs]}]

    (let [target-relative #(when % (target-relative % target))]

      (apply figwheel.api/start
             (doto
              (-> figwheel-config
                   (update :css-dirs conj (str target))
                   (update :css-dirs concat (map str classpath-dirs))
                   ;; watch-dirs is used to compile source code, despite
                   ;; :build-inputs below.  This is problematic due to kick
                   ;; containing figwheel integrations for sidecar, which don't
                   ;; work under main.

                   ;; A bit of a bad solution is to only filter out kick, but
                   ;; it is probably the only library I've encountered so far
                   ;; with this problem.
                   (update :watch-dirs concat (map str (remove pack? classpath-dirs)))
                   ;; watch-dirs above fails a spec due to lack of cljs source
                   ;; files on the whole classpath, but @bhauman said it's more
                   ;; of a warning.
                   (assoc :validate-config false)
                   (assoc :build-inputs (concat [:main]
                                                ;; This is because libs may
                                                ;; contain uncompilable code,
                                                ;; which figwheel will actively
                                                ;; try and compile.  In
                                                ;; particular, kick's sidecar
                                                ;; injector is incompatible with
                                                ;; figwheel main.
                                                (filter-lib-dirs classpath-dirs)))
                   (assoc :mode :serve)
                   ;; Default to false, assuming that most users are using kick
                   ;; within a context with other servers
                   (assoc :open-url (:open-url figwheel-config false)))
               clojure.pprint/pprint)
             (map (fn [build]
                    {:id (:id build)
                     :options (-> build
                                  (dissoc :id)
                                  (update-contains :output-dir target-relative)
                                  (update-contains :output-to target-relative))})
                  builds))))

  (defmethod kick/notify! :kick/figwheel-main [_ events _])

  (defmethod kick/halt! :kick/figwheel-main [_ {:keys [builds]}]
    (doseq [{:keys [id]} builds
            :when (get @build-registry id)]
      (figwheel.api/stop id))))
