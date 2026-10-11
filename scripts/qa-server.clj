(require '[hey99.handlers :as h] '[ring.adapter.jetty :as jetty])
(def fixture {:attention {:feed [{:id "qa-story" :slug "a-recipe-can-start-a-journey" :author "maya" :byline-kind "guest" :published-at 1700000000000 :payload {:title "A recipe can start a journey." :summary "Everyday connections help good things find their people." :body "A useful recommendation can be the beginning of something good.\n\nShare what you enjoy. You don’t need to close a sale to contribute."}}]}})
(def illustrated {:id "qa-images" :slug "illustrated-community" :author "maya" :published-at 1700000000000
                  :payload {:title "An illustrated community" :summary "Neighbors, stories and local discoveries."
                            :featured_image "/assets/featured/why-start-hey99-v1.jpg"
                            :metadata {:body_format "html" :featured_image_alt "Our local community"}
                            :body (apply str (for [name ["finding-home-ripon" "market-together" "influence-travels" "movement-taking-root"]]
                                               (str "<h2>Our community</h2><p>A useful story brings neighbors together.</p><figure><img src='/assets/editorial/" name "-v1.webp' alt='Community illustration' width='1000' height='667'><figcaption>A local connection.</figcaption></figure>")))}})
(def fixture (update-in fixture [:attention :feed] conj illustrated))
(alter-var-root #'h/effective-state (constantly (fn [_ _] fixture)))
(jetty/run-jetty h/app {:port 8899 :join? true})
