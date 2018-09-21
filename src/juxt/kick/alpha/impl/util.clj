;; Copyright © 2018, JUXT LTD.

(ns juxt.kick.alpha.impl.util
  (:require
    [me.raynes.fs :as fs])
  (:import
    [java.nio.file Files]
    [java.nio.file.attribute FileAttribute]))

(defn deleting-tmp-dir
  [prefix]
  (let [tmp-path (Files/createTempDirectory prefix
                                            (into-array FileAttribute []))]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread.
                        (fn []
                          (fs/delete-dir (.toFile tmp-path)))))
    tmp-path))
