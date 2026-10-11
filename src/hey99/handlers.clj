(ns hey99.handlers
  "HTTP surface for the Hey99 Game Master.

   - `/v1/gm/handle` — the ONLY route MoM calls. Everything protocol-shaped
     arrives through it and is dispatched against `merged-manifest`.
   - identity + visitor routes — served to a browser, proxying MoM. The logic is
     gm-lib's (`game.gm.identity`, `game.gm.visitor`), shared with the other GMs.
   - `/ui/*` — read-only projections for the site.

   Payments (Stripe checkout, Connect onboarding, webhooks) are not wired yet;
   they will be ported from agents-of-mind's marketplace/payments/stripe
   namespaces as their own step."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [compojure.core :refer [defroutes GET POST OPTIONS]]
            [compojure.route :as route]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [ring.middleware.json :as json-middleware]
            [ring.middleware.cookies :as cookies]
            [game.gm.config :as gm-config]
            [game.gm.dispatch :as gm-dispatch]
            [game.gm.identity :as gm-identity]
            [game.gm.runtime :as gm-runtime]
            [game.gm.visitor :as gm-visitor]
            [game.protocols.attention :as attention]
            [game.protocols.avatar :as avatar]
            [game.protocols.coseller :as coseller]
            [game.protocols.introspection :as introspection]
            [game.protocols.market :as market]
            [game.protocols.withdrawal :as withdrawal]
            [hey99.spec :as spec]
            [hey99.ui :as ui]))

;; --- Logging ---

(defn- log! [level msg & [data]]
  (if data
    (println (str " [" level "] " msg) (pr-str data))
    (println (str " [" level "] " msg))))

;; --- MoM plumbing ---

(defn- current-game-name []
  (some-> @gm-runtime/game-name-handle str))

(defn- submit-ui-event
  "Dispatch an event to MoM as the CALLER, forwarding their token so MoM
   re-verifies the human rather than trusting this backend."
  [request game-name seat]
  (let [body (or (:body request) {})
        roles (vec (or (:player-roles seat)
                       (some-> (:player-role seat) vector)
                       []))
        event-data (-> (or (:event/data body) {})
                       (dissoc :player-name :player-id :roles :caller :owner-id)
                       (assoc :player-name (:player-name seat))
                       (cond-> (seq roles)
                         (assoc :roles (mapv str roles))))
        payload {:event/type (str (:event/type body))
                 :event/data event-data
                 :game-name (str game-name)}]
    (http/post (str (gm-identity/mom-url nil) "/api/v1/action")
               (gm-identity/request-user-auth
                 request
                 {:content-type :json
                  :body (json/generate-string payload)
                  :as :json
                  :throw-exceptions false}))))

;; --- GM lifecycle handlers ---

(defn- handle-player-join
  "Acknowledges a join and records the member signup for referral attribution.
   If they arrived through a coseller's ?ref= link — including anonymously, via
   a visitor id later claimed — that first touch becomes the referral edge."
  [payload]
  (let [player-id   (get-in payload [:event/data :player-id])
        player-role (get-in payload [:event/data :player-role])
        signup      (coseller/handle-player-join payload)]
    (cond-> {:success true}
      player-id   (assoc :player-id (str player-id))
      player-role (assoc :player-role (str player-role))
      (:updated-state signup) (assoc :updated-state (:updated-state signup))
      (seq (:events signup))  (assoc :events (:events signup)))))

(def gm-manifest
  {:gm {:player-join
        {:handler handle-player-join
         :type :lifecycle
         :doc (str "Acknowledges a player join and records the member signup for "
                   "referral attribution — if they arrived via a coseller's ?ref= "
                   "link, that first touch becomes the referral edge.")
         :summary "Confirm the player role on join and record the signup."
         :when-to-use "Fired by MoM when a player claims a seat. Not called directly."
         :roles #{:any}
         :input [:map]
         :output [:map [:player-role {:optional true} :string]]
         :errors []
         :examples [{:request {:event/type "gm/player-join"
                               :event/data {:player-name "ari" :player-role "audience"}}
                     :response {:success true :player-role "audience"}}]}

        ;; MoM's GET /admin/games/<name>/state routes gm/query-state through the
        ;; GM; gm-lib supplies no handler, so without this that route 400s.
        :query-state
        {:handler (fn [payload] {:success true :data {:game-state (:game-state payload)}})
         :type :query
         :doc "Returns the full game-state snapshot exactly as provided in webhook context."
         :summary "Read current materialized state."
         :when-to-use "Diagnostics, UI hydration, external observer sync."
         :roles #{:any}
         :input [:map]
         :output [:map [:data [:map [:game-state :map]]]]
         :errors []
         :examples [{:request {:event/type "gm/query-state" :event/data {}}
                     :response {:success true :data {:game-state {}}}}]}}})

;; --- Manifest ---
;;
;; Every protocol Hey99 could dispatch. Only names in the spec's :protocols end
;; up in merged-manifest, so this may be wider than the spec — never narrower.
;; Register the shared protocols, with the preview commerce boundary below.

(def protocol-registry
  {"attention"     attention/manifest
   "market"        market/manifest
   "coseller"      coseller/manifest
   "withdrawal"    withdrawal/manifest
   "introspection" introspection/manifest
   "avatar"        avatar/manifest})

(def merged-manifest
  (let [names   (:protocols spec/game-spec)
        unknown (remove protocol-registry names)]
    (when (seq unknown)
      (println " [GM] WARNING: unknown protocols in game-spec:" (vec unknown)))
    (merge (apply merge (keep protocol-registry names))
           gm-manifest)))

;; --- Dispatcher ---

(def preview-allowed #{"coseller/register" "coseller/record-touch" "coseller/claim-visitor" "market/register-category" "market/register-hashtag"})
(defn commerce-blocked? [event]
  (let [k (keyword (str event)) p (some-> k namespace keyword)
        op (get-in merged-manifest [p (keyword (name k))])]
    (and (contains? #{:market :coseller :withdrawal} p)
         (not= :query (:type op))
         (not (contains? preview-allowed (str event))))))

(defn dispatch [payload]
  (let [event-type (:event/type payload)]
    (log! "INFO" "Event received" {:event/type event-type})
    (let [result (if (commerce-blocked? event-type)
                   {:success false :status 409 :error {:code "commerce-not-active" :message "Hey99 is preparing to launch. Checkout and payouts are not active."}}
                   (gm-dispatch/dispatch-gm-handler payload merged-manifest))]
      (if (:success result)
        (log! "INFO" "Event handled"
              {:event/type event-type
               :status (or (:status result) 200)
               :updated-state-keys (->> (keys (or (:updated-state result) {}))
                                        (map name) sort vec)})
        (log! "WARN" "Event failed"
              {:event/type event-type :error (:error result)}))
      result)))

(defn- mom-webhook-authorized?
  [request]
  (let [expected (gm-config/resolve-mom-api-token)
        presented (some-> (gm-identity/auth-header (:headers request))
                          (str/replace #"^Bearer " ""))]
    (and (not (str/blank? (str expected)))
         (= (str expected) (str presented)))))

;; --- Public read model ---

(defn- with-service-auth [req]
  (if-let [headers (gm-config/mom-auth-headers)]
    (assoc req :headers headers)
    req))

(defn- fetch-game-state
  "Durable game state from MoM. Service auth when the GM holds a credential,
   otherwise the caller's — an anonymous visitor still needs to see offers."
  [request game-name]
  (try
    (let [base {:as :json :throw-exceptions false}
          req (if (gm-config/resolve-mom-game-token)
                (with-service-auth base)
                (gm-identity/request-user-auth request base))
          resp (http/get (str (gm-identity/mom-url nil)
                              "/api/v1/storage/game/" game-name "/reload")
                         req)]
      (when (<= 200 (:status resp) 299) (:body resp)))
    (catch Exception e
      (log! "ERROR" "UI state reload failed" {:error (.getMessage e)})
      nil)))

(defn overlay-state
  "Merge durable state onto the seeded initial state so a sparse or
   freshly-created game still renders its seeded content. nil in the durable
   state means 'missing', not 'empty'."
  [seeded durable]
  (cond
    (nil? durable) seeded
    (and (map? seeded) (map? durable))
    (reduce-kv (fn [m k v] (assoc m k (overlay-state (get seeded k) v)))
               seeded durable)
    :else durable))

(defn- effective-state
  [request game-name]
  (let [state (fetch-game-state request game-name)
        payload (if (and (map? state) (:success state) (contains? state :state))
                  (:state state)
                  state)]
    (when (and (map? payload) (not= false (:success payload)))
      (overlay-state (:initial-state spec/game-spec) payload))))

(defn public-game-state
  "The anonymous read model: published content and the offer catalog only.
   Never referral policy, signups, touches, orders or audit trails — cosellers
   and partners get scoped views, not the whole state (§15)."
  [effective]
  {:attention (select-keys (:attention effective) [:brand-bible :feed])
   :market {:capabilities (into {}
                               (map (fn [[id capability]]
                                      [id (select-keys capability
                                                       [:id :seller :title :description :kind
                                                        :external-ref :terms :commerce
                                                        :entitlements :category-ids
                                                        :hashtag-ids :images :checkout
                                                        :status :display-order
                                                        :created-at :updated-at])]))
                               (get-in effective [:market :capabilities] {}))
            :categories (get-in effective [:market :categories] {})
            :hashtags (get-in effective [:market :hashtags] {})}})

;; --- Identity ---

(defn- identity-opts []
  {:game-id (some-> @gm-runtime/game-id str)
   :game-name (current-game-name)
   :app-base-url (gm-config/resolve-app-base-url)
   ;; Attention role names, not display labels: strategist = local operator.
   :gated-roles #{"strategist" "creator"}
   ;; Required because there is no :ensure-membership-fn: without it a person
   ;; who owns exactly one seat gets selectedSeat nil and is asked to claim a
   ;; handle they already have.
   :select-sole-seat? true})
   ;; DELIBERATELY no :ensure-membership-fn. It would enrol a signed-in visitor
   ;; under a generated handle, and MoM has no rename — player-name is the join
   ;; key across protocol state and the ledger. /join/claim lets them choose.

(defn- seat-roles [seat]
  (set (map str (or (:player-roles seat)
                    (some-> (:player-role seat) vector)
                    []))))

(defn- result-body
  [response]
  (let [body (:body response)]
    (or (:gm-result body) (get body "gm-result") body)))

(defn- activation-response
  "Opt a seated person into Coselling: claim the coseller role if they lack it,
   then register them with the Coseller Protocol to mint their ref token."
  [request context seat]
  (let [roles (seat-roles seat)
        role-response (when-not (contains? roles "coseller")
                        (gm-identity/claim-membership-response
                         (assoc request :body {:player-name (:player-name seat)
                                               :roles ["coseller"]})
                         (:game-name context)))
        role-ok? (or (nil? role-response)
                     (<= 200 (:status role-response) 299))]
    (if-not role-ok?
      role-response
      (let [coseller-seat (assoc seat
                                 :player-roles (vec (conj roles "coseller"))
                                 :player-role "coseller")
            registration (submit-ui-event
                          (assoc request :body {:event/type "coseller/register"
                                                :event/data {}})
                          (:game-name context)
                          coseller-seat)
            result (result-body registration)]
        (if (and (<= 200 (:status registration) 299)
                 (true? (or (:success result) (get result "success"))))
          {:status 200
           :body {:status "active"
                  :player-name (str (:player-name seat))
                  :ref-token (or (get-in result [:data :ref-token])
                                 (get-in result ["data" "ref-token"]))}}
          {:status (or (:status registration) 502)
           :body result})))))

;; --- The site shell ---
;;
;; Share cards need ABSOLUTE og:image / og:url values; crawlers do not resolve
;; relative ones. index.html carries __BASE_URL__ placeholders, filled from
;; APP_BASE_URL when set (the canonical public origin), else from the request.

(defn- base-url [request]
  (str/replace (or (not-empty (str (gm-config/resolve-app-base-url)))
                   (gm-identity/request-base-url request))
               #"/+$" ""))

(defn render-shell
  "The SPA document with __BASE_URL__ filled in, or nil when it is missing."
  [base]
  (some-> (io/resource "ui/index.html") slurp (str/replace "__BASE_URL__" base)))

(defn- shell-response [request]
  (if-let [html (render-shell (base-url request))]
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8"
               ;; No-store: a cached shell strands people on an old build.
               "Cache-Control" "no-store, max-age=0"}
     :body html}
    {:status 404 :body "UI not available"}))

;; --- Coseller self-view ---

(defn- state-entry
  "Case-insensitive lookup: game-state JSON keywordizes dynamic record keys."
  [m id]
  (some (fn [[k v]] (when (= (str/lower-case (name k)) (str/lower-case (str id))) v))
        (or m {})))

(defn coseller-self
  "What a coseller may see about their own standing: their ref token and the
   published policy. Nothing about anyone else."
  [effective player-name]
  (let [coseller (state-entry (get-in effective [:coseller :cosellers]) player-name)
        policy (get-in effective [:coseller :policy])]
    {:status (if coseller "active" "needs-registration")
     :player-name (str player-name)
     :ref-token (:ref-token coseller)
     :policy (select-keys policy [:algorithm :window-days :payout-hold-days
                                  :pool-share-bps :referral-override-bps])}))

(defn- session-context [request]
  (gm-identity/session-context request (identity-opts)))

(def ^:private cors-json-post
  {"Access-Control-Allow-Origin" "*"
   "Access-Control-Allow-Headers" "Content-Type, Authorization, X-Selected-Seat"
   "Access-Control-Allow-Methods" "POST, OPTIONS"})

(def browser-events
  {"attention/create-task" #{"strategist"}
   "attention/assign-task" #{"strategist"}
   "attention/review-task" #{"strategist"}
   "attention/start-piece" #{"creator" "contributor"}
   "attention/claim-task" #{"creator"}
   "attention/submit-draft" #{"creator" "contributor"}})

(defn event-response [request]
  (let [{:keys [context seat response]} (gm-identity/require-selected-seat request (identity-opts))
        event (get-in request [:body :event/type])
        allowed (get browser-events event)]
    (or response
        (if-not (some (seat-roles seat) allowed)
          {:status 403 :body {:error {:code "event-not-permitted" :message "This action is not available for your role."}}}
          (let [resp (submit-ui-event request (:game-name context) seat)]
            {:status (:status resp) :body (:body resp)})))))

(defn studio-response [request]
  (let [{:keys [context seat response]} (gm-identity/require-selected-seat request (identity-opts))
        roles (seat-roles seat)]
    (or response
        (if-not (some roles ["strategist" "creator" "contributor"])
          {:status 403 :body {:error {:message "The studio is for invited writers and editors. Tell us how you’d like to contribute on the join page."}}}
          (if-let [state (effective-state request (:game-name context))]
            {:status 200 :body {:tasks (->> (vals (get-in state [:attention :tasks]))
                                           (filter #(or (roles "strategist") (= (:assignee %) (:player-name seat))))
                                           vec)}}
            {:status 503 :body {:error {:message "The studio is temporarily unavailable. Your drafts remain saved."}}})))))

(defn player-token-response [request]
  (let [{:keys [context seat response]} (gm-identity/require-selected-seat request (identity-opts))
        roles (seat-roles seat)
        result (or response
                   (if-not (some roles ["strategist" "creator" "contributor"])
                     {:status 403 :body {:error {:code "studio-role-required"
                                                 :message "Connect your AI is available to invited writers and editors."}}}
                     (try
                       (let [upstream (http/post (str (gm-identity/mom-url nil) "/auth/player-token")
                                                 (gm-identity/request-user-auth
                                                   request
                                                   {:content-type :json
                                                    :body (json/generate-string
                                                            {:game-name (:game-name context)
                                                             :caller-player-name (:player-name seat)})
                                                    :as :json
                                                    :throw-exceptions false}))]
                         {:status (:status upstream) :body (:body upstream)})
                       (catch Exception e
                         (log! "ERROR" "Seat token exchange failed" {:error (.getMessage e)})
                         {:status 502 :body {:error {:code "token-service-unavailable"
                                                     :message "Could not connect to MoM. Try again shortly."}}}))))]
    (update result :headers (fnil assoc {}) "Cache-Control" "no-store")))

(defn story-response [request slug]
  (if-let [state (effective-state request (current-game-name))]
    (if-let [item (some #(when (= slug (:slug %)) %) (get-in state [:attention :feed]))]
      (ui/story-page item)
      (ui/not-found))
    (ui/unavailable)))

(defroutes gm-routes
  (GET "/health" [] {:status 200 :body {:ok true :game (current-game-name)}})

  (GET "/auth.json" [:as request]
    (gm-identity/auth-config-response request (identity-opts)))
  (GET "/auth/callback" [:as request]
    (gm-identity/ui-redirect-with-query request (identity-opts)))
  (GET "/auth/success" [:as request]
    (gm-identity/ui-redirect-with-query request (identity-opts)))
  (GET "/auth/failure" [:as request]
    (gm-identity/ui-redirect-with-query request (identity-opts)))

  ;; The hosted auth app POSTs to the GM's own origin, never to MoM directly.
  ;; Missing these shows up as "Failed to fetch (status=network)".
  (OPTIONS "/auth/login" []
    (gm-identity/cors-preflight-response))
  (POST "/auth/login" [:as request]
    (gm-identity/login-response request))
  (GET "/auth/me" [:as request]
    (gm-identity/auth-response request))

  ;; Enrol under a name the person chose; must run as them.
  (OPTIONS "/join/claim" []
    (gm-identity/cors-preflight-response))
  (POST "/join/claim" [:as request]
    (gm-identity/claim-membership-response request (current-game-name)))

  (GET "/session" [:as request]
    {:status 200 :body (gm-identity/session-response-body (session-context request))})

  (GET "/seats" [:as request]
    (let [{:keys [context response]} (gm-identity/require-game-context request (identity-opts))]
      (or response
          {:status 200
           :body {:gameId (:game-id context)
                  :selectedSeat (gm-identity/seat-summary (:selected-seat context))
                  :seats (mapv gm-identity/seat-summary (:seats context))}})))

  ;; --- Anonymous visitors ---
  ;;
  ;; Every page carrying ?ref= is an attributable surface; a shared offer link
  ;; records a visitor touch the person can claim after signing in.
  ;; `wrap-cookies` below is load-bearing: without it every visit mints a fresh
  ;; visitor id and nothing is ever claimable, while every call still returns 200.
  (OPTIONS "/public/touch" []
    {:status 204
     :headers {"Access-Control-Allow-Origin" "*"
               "Access-Control-Allow-Headers" "Content-Type"
               "Access-Control-Allow-Methods" "POST, OPTIONS"}})
  (POST "/public/touch" [:as request]
    (gm-visitor/record-visitor-touch request))
  (POST "/coseller/claim-visitor" [:as request]
    (let [{:keys [context seat response]} (gm-identity/require-selected-seat request (identity-opts))]
      (or response
          (gm-visitor/claim-visitor-touches request (:game-name context) seat
                                            {:submit-ui-event-fn submit-ui-event}))))

  (POST "/api/coseller/activate" [:as request]
    (let [{:keys [context seat response]}
          (gm-identity/require-selected-seat request (identity-opts))]
      (or response (activation-response request context seat))))

  (GET "/api/coseller/me" [:as request]
    (let [{:keys [context seat response]} (gm-identity/require-selected-seat request (identity-opts))]
      (cond
        response response
        (not (contains? (seat-roles seat) "coseller"))
        {:status 403 :body {:error {:code "coseller-role-required"
                                    :message "Activate recommending to get your link."}}}
        :else
        (if-let [state (effective-state request (:game-name context))]
          {:status 200 :body (coseller-self state (:player-name seat))}
          {:status 502 :body {:error {:code "state-unavailable"}}}))))

  (GET "/api/studio" [:as request] (studio-response request))
  (POST "/api/player-token" [:as request] (player-token-response request))
  (POST "/events" [:as request] (event-response request))

  (POST "/v1/gm/handle" [:as request]
    (if-not (mom-webhook-authorized? request)
      {:status 401
       :body {:success false
              :error {:code "untrusted_mom_envelope"
                      :message "The GM webhook requires MoM authentication."}}}
      (let [body (:body request)
            result (dispatch (if (map? body) body {}))]
        {:status (or (:status result) 200)
         :body (dissoc result :status)})))

  (GET "/ui/public-state" [:as request]
    (let [gname (current-game-name)
          gid (some-> @gm-runtime/game-id str)]
      (if (str/blank? (str gname))
        {:status 400 :body {:error {:code "missing-game-name"
                                    :message "GAME_NAME not set or registration incomplete"}}}
        (if-let [state (effective-state request gname)]
          {:status 200 :body {:gameId gid :gameState (public-game-state state)}}
          {:status 502 :body {:error {:code "state-unavailable"
                                      :message "Failed to load durable game state from MoM"}}}))))

  (GET "/ready" []
    (if @gm-runtime/game-id {:status 200 :body {:ready true}} {:status 503 :body {:ready false}}))
  (GET "/styles.css" [] (ui/asset "styles.css" "text/css"))
  (GET "/game.js" [] (ui/asset "game.js" "text/javascript"))
  (GET "/game.css" [] (ui/asset "game.css" "text/css"))
  (route/resources "/assets" {:root "ui/assets"})
  (GET "/" [] (ui/static-page "index.html"))
  (GET "/join/" [] (ui/static-page "join/index.html"))
  (GET "/market/" [] (ui/static-page "market/index.html"))
  (GET "/economics/" [] (ui/static-page "economics/index.html"))
  (GET "/account/" [] (ui/workspace "Your place in Hey99." "account"))
  (GET "/share/" [] (ui/workspace "Help good things find their people." "share"))
  (GET "/studio/" [] (ui/workspace "Tell the story of your community." "studio"))
  (GET "/stories/" [:as request]
    (if-let [state (effective-state request (current-game-name))]
      (ui/stories-page (get-in state [:attention :feed]))
      (ui/unavailable)))
  (GET "/stories/:slug/" [slug :as request] (story-response request slug))
  (GET "/privacy/" [] (ui/privacy-page))
  (GET "/terms/" [] (ui/terms-page))
  (GET "*" [:as request]
    (let [path (:uri request)]
      (if (contains? #{"/join" "/market" "/economics" "/account" "/share" "/studio" "/stories" "/privacy" "/terms"} path)
        {:status 308 :headers {"Location" (str path "/" (when-let [q (:query-string request)] (str "?" q)))}}
        (ui/not-found)))))

(defn wrap-site [handler]
  (fn [request]
    (if (= "www.hey99.online" (get-in request [:headers "host"]))
      {:status 308 :headers {"Location" (str "https://hey99.online" (:uri request) (when-let [q (:query-string request)] (str "?" q)))}}
      (-> (handler request)
          (assoc-in [:headers "X-Content-Type-Options"] "nosniff")
          (assoc-in [:headers "Referrer-Policy"] "strict-origin-when-cross-origin")
          (assoc-in [:headers "X-Frame-Options"] "DENY")))))

(def app
  (-> gm-routes
      (json-middleware/wrap-json-body {:keywords? true})
      ;; Anonymous visitor ids ride in a cookie so ?ref= history survives the
      ;; full navigation to the auth app, which destroys page state.
      (cookies/wrap-cookies)
      (json-middleware/wrap-json-response)
      wrap-site))
