(ns hey99.game-test
  (:require [clojure.test :refer :all] [clojure.string :as str]
            [hey99.handlers :as h] [hey99.ui :as ui] [hey99.spec :as spec]
            [game.gm.identity :as identity] [game.gm.config :as cfg]
            [game.protocols.attention :as attention]))

(def article {:id "article-1" :slug "your-local-connections" :author "maya" :byline-kind "guest" :published-at 1700000000000
              :payload {:title "Your local connections" :summary "A community story." :body "A recipe can begin a journey.\n\n<script>alert('unsafe')</script>"}})
(defn req [path] {:request-method :get :uri path :scheme :https :headers {"host" "hey99.online"}})

(deftest preserved-pages-and-new-public-routes
  (doseq [path ["/" "/join/" "/market/" "/economics/" "/account/" "/share/" "/studio/" "/privacy/" "/terms/"]]
    (let [r (h/app (req path))]
      (is (= 200 (:status r)) path)
      (is (str/includes? (:body r) "/game.js") path)
      (is (str/includes? (:body r) "og-card-v3.png") path)))
  (is (= 404 (:status (h/app (req "/.envrc")))))
  (is (= 308 (:status (h/app (assoc (req "/economics") :query-string "ref=one")))))
  (is (= "/economics/?ref=one" (get-in (h/app (assoc (req "/economics") :query-string "ref=one")) [:headers "Location"]))))

(deftest server-rendered-article-escaping-and-metadata
  (let [r (ui/story-page article) body (:body r)]
    (is (= 200 (:status r)))
    (is (str/includes? body "<title>Hey99 — Your local connections</title>"))
    (is (str/includes? body "https://hey99.online/stories/your-local-connections/"))
    (is (str/includes? body "By maya"))
    (is (str/includes? body "&lt;script&gt;"))
    (is (not (str/includes? body "<script>alert")))
    (is (str/includes? body "data-share"))))

(deftest public-view-excludes-private-game-state
  (let [public (h/public-game-state {:attention {:feed [article] :tasks {:private {:draft "secret"}}}
                                    :coseller {:signups {:person "private"} :touches [1]}
                                    :market {:orders {:private "order"}}})]
    (is (= [article] (get-in public [:attention :feed])))
    (is (nil? (get-in public [:attention :tasks])))
    (is (nil? (:coseller public)))
    (is (nil? (get-in public [:market :orders])))))

(deftest preview-boundary-and-webhook-authentication
  (doseq [event ["market/accept-offer" "coseller/settle-order" "withdrawal/request" "market/record-payment"]]
    (is (= "commerce-not-active" (get-in (h/dispatch {:event/type event}) [:error :code]))))
  (doseq [event ["coseller/register" "coseller/record-touch" "coseller/claim-visitor" "market/register-category" "market/register-hashtag"]]
    (is (not (h/commerce-blocked? event))))
  (with-redefs [cfg/resolve-mom-api-token (constantly nil)]
    (is (= 401 (:status (h/app {:request-method :post :uri "/v1/gm/handle" :headers {} :body {:event/type "gm/query-state"}}))))))

(deftest browser-events-check-real-seat-roles
  (with-redefs [identity/require-selected-seat (constantly {:context {:game-name "hey99"} :seat {:player-name "neighbor" :player-roles ["audience"]}})]
    (doseq [event ["attention/review-task" "market/record-payment" "attention/start-piece"]]
      (is (= 403 (:status (h/event-response {:body {:event/type event :event/data {:roles ["strategist"] :player-name "editor"}}})))))))

(deftest studio-does-not-leak-other-writers-drafts
  (with-redefs [identity/require-selected-seat (constantly {:context {:game-name "hey99"} :seat {:player-name "maya" :player-roles ["contributor"]}})
                h/effective-state (constantly {:attention {:tasks {"one" {:id "one" :assignee "maya"} "two" {:id "two" :assignee "other"}}}})]
    (is (= ["one"] (map :id (get-in (h/studio-response {}) [:body :tasks]))))))

(deftest player-token-requires-an-invited-selected-seat
  (with-redefs [identity/require-selected-seat (constantly {:response {:status 401 :body {:error "Authorization required"}}})]
    (is (= 401 (:status (h/player-token-response {})))))
  (with-redefs [identity/require-selected-seat (constantly {:response {:status 400 :body {:error "Seat required"}}})]
    (is (= 400 (:status (h/player-token-response {})))))
  (with-redefs [identity/require-selected-seat (constantly {:context {:game-name "hey99"} :seat {:player-name "reader" :player-roles ["audience"]}})]
    (let [resp (h/player-token-response {})]
      (is (= 403 (:status resp)))
      (is (= "no-store" (get-in resp [:headers "Cache-Control"]))))))

(deftest player-token-forwards-only-the-selected-user-seat
  (let [request {:headers {"authorization" "Bearer user-token" "x-selected-seat" "writer"}}
        exchange (atom nil)]
    (with-redefs [identity/require-selected-seat (constantly {:context {:game-name "hey99"} :seat {:player-name "writer" :player-roles ["contributor"]}})
                  clj-http.client/post (fn [url opts] (reset! exchange [url opts]) {:status 200 :body {:token "seat-token" :expires-in 3600}})]
      (let [resp (h/player-token-response request)]
        (is (= 200 (:status resp)))
        (is (= "seat-token" (get-in resp [:body :token])))
        (is (= "no-store" (get-in resp [:headers "Cache-Control"])))
        (is (str/ends-with? (first @exchange) "/auth/player-token"))
        (is (= "Bearer user-token" (get-in @exchange [1 :headers "Authorization"])))
        (is (= {:game-name "hey99" :caller-player-name "writer"}
               (cheshire.core/parse-string (get-in @exchange [1 :body]) true)))))))

(deftest player-token-route-and-upstream-failures
  (with-redefs [identity/require-selected-seat (constantly {:response {:status 401 :body {:error "Authorization required"}}})]
    (let [resp (h/app {:request-method :post :uri "/api/player-token" :headers {}})]
      (is (= 401 (:status resp)))
      (is (= "no-store" (get-in resp [:headers "Cache-Control"])))))
  (with-redefs [identity/require-selected-seat (constantly {:context {:game-name "hey99"} :seat {:player-name "writer" :player-roles ["creator"]}})
                clj-http.client/post (fn [_ _] {:status 503 :body {:error "MoM unavailable"}})]
    (is (= 503 (:status (h/player-token-response {})))))
  (with-redefs [identity/require-selected-seat (constantly {:context {:game-name "hey99"} :seat {:player-name "writer" :player-roles ["strategist"]}})
                clj-http.client/post (fn [_ _] (throw (Exception. "network error")))]
    (let [resp (h/player-token-response {})]
      (is (= 502 (:status resp)))
      (is (= "no-store" (get-in resp [:headers "Cache-Control"]))))))

(deftest attention-review-produces-addressed-credited-story
  (let [start (attention/handle-start-piece {:event/data {:player-name "maya" :roles ["contributor"] :spec {:title "Your local connections"}} :game-state {}})
        id (get-in start [:data :task-id])
        submit (attention/handle-submit-draft {:event/data {:player-name "maya" :roles ["contributor"] :task-id id :content {:title "Your local connections" :body "A recipe can begin a journey."}} :game-state (:updated-state start)})
        reviewed (attention/handle-review-task {:event/data {:player-name "editor" :task-id id :decision "approve" :slug "your-local-connections"} :game-state (:updated-state submit)})
        published (first (get-in reviewed [:updated-state :attention :feed]))]
    (is (:success submit))
    (is (:success reviewed))
    (is (= "your-local-connections" (:slug published)))
    (is (= "maya" (:author published)))
    (is (= "guest" (:byline-kind published)))))
