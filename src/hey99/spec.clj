(ns hey99.spec
  "Game Specification — the single source of truth for Hey99's identity,
   active protocols, player slots and seed state.

   The :protocols list does double duty: it decides which handlers are
   dispatchable (see handlers/protocol-registry) and what is advertised to MoM at
   game creation.

   :initial-state is NOT applied by MoM. MoM stores it on the game record and
   never reads it back; durable state starts empty and is written only as this
   GM processes events. The seed is overlaid by handlers/overlay-state on the UI
   read path only. Protocol handlers receive MoM's durable state unseeded and
   carry their own defaults — which is why every policy number below that has a
   gm-lib default is stated explicitly anyway.")

;; --- Market taxonomy ---
;;
;; Must be declared: gm-lib's market seeder otherwise imposes THE JS's band
;; taxonomy (Merch, Music, Collabs, #drop). See game.gm.market-seed/seed-market!.
;;
;; PLACEHOLDER. The overview (§17) starts with local services and leaves the
;; exact category mix undecided until merchant interviews. Categories are what a
;; resident needs; hashtags are the corridor's towns, so a provider's service
;; area can be tagged without inferring it from where they live (§2).

(def market-seed
  {:categories [{:slug "home-services" :label "Home services"         :icon "wrench"}
                {:slug "experiences"   :label "Experiences & outdoors" :icon "mountain"}
                {:slug "classes"       :label "Classes & workshops"    :icon "palette"}
                {:slug "food-drink"    :label "Food & drink"           :icon "utensils"}
                {:slug "wellness"      :label "Wellness"               :icon "heart"}
                {:slug "events"        :label "Events"                 :icon "calendar"}]
   :hashtags [{:slug "manteca" :label "#manteca"}
              {:slug "lathrop" :label "#lathrop"}
              {:slug "ripon" :label "#ripon"}
              {:slug "salida" :label "#salida"}
              {:slug "escalon" :label "#escalon"}]
   ;; No external commerce adapters yet. Offers transact through the market's
   ;; own checkout once the payments boundary is ported.
   :commerce-adapters []})

;; --- Brand bible ---
;;
;; The seed, not the canon: {:name :mission}, the-js's shape. The longer story
;; belongs in published content, not in a state field only strategists can edit.

(def brand-bible
  {:name "Hey99"
   :mission "Your place. Your people. Your local economy."})

;; --- Game spec ---

(def game-spec
  {:name "Hey99"
   :description "A local economy built by the people and businesses of the Highway 99 community."

   ;; The-js's full protocol set, verbatim. `attention` carries campaigns and
   ;; local content. Financial commands remain disabled during the preview.
   :protocols ["attention" "market" "coseller" "withdrawal" "introspection" "avatar"]

   ;; Residents join as audience and may opt into Coselling under the same
   ;; handle. Market buy ops are role :any, so `buyer` need not be claimable.
   ;; Merchants (`seller`) join by invitation (§16), never self-claimed.
   :self-claimable-roles ["audience" "coseller"]

   :market-seed market-seed

   ;; Role names are the protocols' interface, not display words. attention
   ;; gates its campaign ops on :strategist, drafting on :creator/:contributor,
   ;; comment/upvote on :audience; market gates listing on :seller; coseller
   ;; registration on :coseller. A seat named "operator" would validate in MoM
   ;; and then be refused by every op. The UI shows "Operator" for strategist.
   :player-slots [{:role "strategist"  :min 1 :max nil}  ; local operator (§16)
                  {:role "creator"     :min 0 :max nil}  ; local publishers / guides
                  {:role "contributor" :min 0 :max nil}  ; guest content, own byline
                  {:role "audience"    :min 0 :max nil}  ; resident / visitor, day-one seat
                  {:role "buyer"       :min 0 :max nil}
                  {:role "seller"      :min 0 :max nil}  ; merchants, by invitation
                  {:role "coseller"    :min 0 :max nil}
                  {:role "observer"    :min 0 :max nil}] ; partners, scoped reporting

   :initial-state
   {:attention
    {:brand-bible brand-bible
     :roster {}
     :tasks {}
     :archived-tasks {}
     :calendar []
     :feed []
     :votes {}
     :comments {}
     :research {:status nil :draft-content nil :history []}}

    :market
    {:capabilities {}
     :commerce-adapters {}
     :intents {}
     :offers {}
     :carts {}
     :orders {}
     :disputes {}
     :trust {}
     :categories {}
     :hashtags {}
     ;; One decision split across two protocols; gm-lib validates neither
     ;; against the other. The return window must never outlast the coseller
     ;; payout hold: once cosellers settle, the order's gross has left
     ;; @buyer-escrow and a refund lands as :blocked-manual with no ledger
     ;; effect. Both are gm-lib's defaults (30), stated so neither drifts alone.
     :returns-policy {:return-window-days 30
                      :return-response-days 5
                      :post-denial-grace-days 0}
     :audit-log []}

    :coseller
    {:cosellers {}
     :signups {}
     :touches []
     ;; The J's policy, stated explicitly rather than left to gm-lib's
     ;; default-policy merge.
     ;;
     ;; :referral-override-bps 500 is Intergraph policy for every game: each
     ;; coseller keeps 95% of a sale earning and 5% goes to their own recruiter;
     ;; every recruiter applies the same rule, so a shrinking share continues up
     ;; the chain (residual to @game). Overview §8.3 was rewritten to say so.
     :policy {:algorithm "30-days-linear"
              :pool-share-bps 8000
              :referral-override-bps 500
              :window-days 30
              :payout-hold-days 30
              :history []}
     :settlements {}
     :payouts {}
     :audit-log []}

    ;; No :withdrawal seed on purpose: withdrawal-state builds its own
    ;; :records map, and seeding the wrong shape is worse than seeding nothing.

    :avatar
    {:avatars {}
     :audit-log []}}})
