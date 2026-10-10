(ns hey99.core
  (:require [hey99.handlers :refer [app]]
            [hey99.spec :as spec]
            [game.gm.config :as gm-config]
            [game.gm.runtime :as gm-runtime])
  (:gen-class))

(defn -main [& args]
  (println "--- Starting Hey99 Game Master ---")
  (let [cfg (gm-config/build-config args)]
    (gm-runtime/start-gm!
      (assoc cfg :game-spec spec/game-spec :agent-name "Hey99 GM")
      app)))
