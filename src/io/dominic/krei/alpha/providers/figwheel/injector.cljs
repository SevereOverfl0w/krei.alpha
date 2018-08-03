(ns io.dominic.krei.alpha.providers.figwheel.injector
  "Adds support for html file reloading to figwheel"
  (:require
    [figwheel.client :as fig]))

(fig/add-message-watch
  :html-watcher
  (fn [{:keys [msg-name] :as msg}]
    (when (= msg-name :html-files-changed)
      (.reload js/window.location true)
      (println "Figwheel: HTML file(s) changed. Reloaded page."))))
