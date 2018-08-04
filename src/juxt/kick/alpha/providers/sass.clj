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
  [files target]
  ;; figwheel can't handle the deleting of this directory, and just blows up,
  ;; so leave stale files hanging around, it'll be fine, he says.
  ;; (fs/delete-dir "./target/public/css/")
  (log/infof "Building Sass, output going to %s" target)
  (run!
    (fn [[input-file relative-path]]
      (sass/sass-compile-to-file
        input-file
        (io/file target
                 (string/replace relative-path #"\.scss$" ".css"))
        ;; TODO: Take options
        {}))
    (eduction
      (map (juxt (comp io/resource) identity))
      files)))

(defmethod kick/notify! :kick/sass [_ events init-result]
  (log/infof "init-result is %s" init-result)

  ;; Could use spec here, not going to yet.
  (assert (:kick.builder/target init-result))
  (assert (:sources init-result))

  (when (some #(re-matches #".*\.s[ca]ss$" (.getName (:file %))) events)
    (build-sass (:sources init-result) (:kick.builder/target init-result))))
