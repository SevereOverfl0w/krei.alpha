;; Copyright © 2018, JUXT LTD.

;; Suggested dependencies: deraen/sass4clj {:mvn/version "0.3.1"}

(ns juxt.kick.alpha.providers.sass
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.tools.logging :as log]
   [juxt.kick.alpha.core :as kick]
   [sass4clj.core :as sass]))

;; Sass

(defn build-sass
  [{:keys [source target]} destdir]
  ;; figwheel can't handle the deleting of this directory, and just blows up,
  ;; so leave stale files hanging around, it'll be fine, he says.
  ;; (fs/delete-dir "./target/public/css/")
  (let [output (io/file destdir target)]
    (io/make-parents output)
    (log/debugf "Building SCSS file %s, output going to %s" source output)
    (if-let [input (io/resource source)]
      (sass/sass-compile-to-file
       input (io/file destdir target) {})
      (log/warnf "No resource found for %s on classpath" source))))

(defmethod kick/init! :kick/sass [_ value opts]
  (log/infof "Initializing Sass builder: value is %s" value)
  (doseq [build (:builds value)]
    (build-sass build (:kick.builder/target opts)))
  (merge value opts))

(defmethod kick/notify! :kick/sass [_ events init-result]
  ;; Could use spec here, not going to yet.
  (assert (:kick.builder/target init-result))
  (assert (:sources init-result))

  (when (some #(re-matches #".*\.s[ca]ss$" (.getName (:file %))) events)
    (build-sass (:sources init-result) (:kick.builder/target init-result))))

(defmethod kick/oneshot! :kick/sass [_ value opts]
  (doseq [build (:builds value)]
    (build-sass (:sources build) (:kick.builder/target opts))))
