(require '[hey99.handlers :as h] '[ring.adapter.jetty :as jetty])
(def fixture {:attention {:feed [{:id "qa-story" :slug "a-recipe-can-start-a-journey" :author "maya" :byline-kind "guest" :published-at 1700000000000 :payload {:title "A recipe can start a journey." :summary "Everyday connections help good things find their people." :body "A useful recommendation can be the beginning of something good.\n\nShare what you enjoy. You don’t need to close a sale to contribute."}}]}})
(alter-var-root #'h/effective-state (constantly (fn [_ _] fixture)))
(jetty/run-jetty h/app {:port 8899 :join? true})
