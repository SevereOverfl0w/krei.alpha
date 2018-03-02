(ns io.dominic.krei.impl.util
  (:require
    [me.raynes.fs :as fs])
  (:import
    [java.nio.file Files]
    [java.nio.file.attribute FileAttribute]))

(defn deleting-tmp-dir
  [prefix]
  (let [tmp-path (Files/createTempDirectory "fatjar-bootstrap"
                                            (into-array FileAttribute []))]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread.
                        (fn []
                          (fs/delete-dir (.toFile tmp-path)))))
    tmp-path))


