;; Copyright © 2018, JUXT LTD.

;; Suggested dependencies:
;; figwheel-sidecar {:mvn/version "0.5.16"}
;; org.clojure/clojurescript {:mvn/version "1.9.946"}

(ns juxt.kick.alpha.providers.figwheel
  (:require
   [clojure.string :as string]
   [clojure.tools.logging :as log]
   [figwheel-sidecar.repl-api :as repl-api]
   [figwheel-sidecar.components.figwheel-server :as figwheel.server]
   [figwheel-sidecar.utils :as figwheel.utils]
   [juxt.kick.alpha.core :as kick]))

;; Figwheel

(defn- figwheel-notify
  [files figwheel-system]
  (when-let [files' (and repl-api/*repl-api-system*
                         (->> files
                              (map str)
                              (filter #(or
                                         (string/ends-with? % ".html")
                                         (string/ends-with? % ".adoc")))
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

(defmethod kick/init! :kick/figwheel
  [_ {:keys [builds server-port]} {:kick.builder/keys [target classpath-dirs]}]

  (let [target-relative #(when % (str (.resolve target %)))]

    (repl-api/start-figwheel!
      {:figwheel-options (merge
                           {:css-dirs [(str target)]}
                           (when server-port {:server-port server-port}))

       :build-ids (into [] (map :id builds))

       :all-builds
       (into []
             (comp
               (map #(assoc % :source-paths
                            (map str classpath-dirs)))
               (map #(update % :compiler
                             (fn [compiler] (merge {:optimizations :none} compiler))))
               (map #(update % :compiler
                             (fn [compiler]
                               (cond-> compiler
                                 (= (:optimizations compiler) :none)
                                 (update :preloads
                                         conj 'juxt.kick.alpha.providers.figwheel.injector)))))
               (map #(update-in % [:compiler :output-dir] target-relative))
               (map #(update-in % [:compiler :output-to] target-relative)))
             builds)})))

(defmethod kick/notify! :kick/figwheel [_ events _]
  (when repl-api/*repl-api-system*
    (figwheel-notify
      (map :file events)
      repl-api/*repl-api-system*)))

(defmethod kick/halt! :kick/figwheel [_ _]
  (repl-api/stop-figwheel!))
