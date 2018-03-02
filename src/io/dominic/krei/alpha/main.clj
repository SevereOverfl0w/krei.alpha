(ns io.dominic.krei.alpha.main
  (:require
    [io.dominic.krei.alpha.core :as krei])
  (:import
    [java.nio.file Paths]))

(defn -main
  [env & args]
  (case env
    "production" (krei/prod-build
                   (Paths/get (first args)
                              (into-array String [])))))
