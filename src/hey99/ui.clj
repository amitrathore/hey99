(ns hey99.ui
  (:require [clojure.java.io :as io] [clojure.string :as str] [game.gm.config :as cfg])
  (:import [org.jsoup Jsoup] [org.jsoup.safety Safelist]))

(defn escape-html [x]
  (-> (str (or x "")) (str/replace "&" "&amp;") (str/replace "<" "&lt;")
      (str/replace ">" "&gt;") (str/replace "\"" "&quot;") (str/replace "'" "&#39;")))
(defn safe-image-url [value]
  (when (string? value)
    (let [v (str/trim value)]
      (when (and (not (re-find #"[\\\s\p{Cntrl}]" v))
                 (or (and (str/starts-with? v "/") (not (str/starts-with? v "//")))
                     (try (let [u (java.net.URI. v)]
                            (and (= "https" (.getScheme u)) (.getHost u) (nil? (.getUserInfo u))))
                          (catch Exception _ false)))) v))))
(defn article-body [payload]
  (if (= "html" (get-in payload [:metadata :body_format]))
    (let [allow (doto (Safelist.)
                  (.addTags (into-array String ["p" "br" "h2" "h3" "strong" "em" "blockquote" "ul" "ol" "li" "a" "figure" "img" "figcaption"]))
                  (.addAttributes "a" (into-array String ["href" "title"]))
                  (.addProtocols "a" "href" (into-array String ["http" "https" "mailto"]))
                  (.addAttributes "img" (into-array String ["src" "alt" "width" "height"]))
                  (.addProtocols "img" "src" (into-array String ["https"]))
                  (.preserveRelativeLinks true))
          doc (Jsoup/parseBodyFragment (Jsoup/clean (str (:body payload)) "https://hey99.online/" allow))]
      (doseq [img (.select doc "img")]
        (if-let [src (safe-image-url (.attr img "src"))]
          (do
            (doseq [a ["width" "height"]]
              (when-not (re-matches #"[1-9][0-9]{0,3}" (.attr img a)) (.removeAttr img a)))
            (doto img (.attr "src" src) (.attr "loading" "lazy") (.attr "decoding" "async")))
          (.remove img)))
      (.prettyPrint (.outputSettings doc) false)
      (.html (.body doc)))
    (apply str (map #(str "<p>" (str/replace (escape-html %) "\n" "<br />") "</p>")
                    (str/split (str (:body payload)) #"\n\s*\n")))))
(defn featured-image [payload class-name eager?]
  (when-let [src (safe-image-url (if eager? (:featured_image payload) (or (get-in payload [:metadata :featured_image_thumbnail]) (:featured_image payload))))]
    (str "<img class=\"" class-name "\" src=\"" (escape-html src)
         "\" alt=\"" (escape-html (get-in payload [:metadata :featured_image_alt]))
         "\" width=\"1200\" height=\"800\" loading=\"" (if eager? "eager" "lazy")
         "\" decoding=\"async\"" (when eager? " fetchpriority=\"high\"") " />")))
(defn origin [] (or (not-empty (str (cfg/resolve-app-base-url))) "https://hey99.online"))
(defn response [html] {:status 200 :headers {"Content-Type" "text/html; charset=utf-8" "Cache-Control" "no-store"} :body html})
(defn asset [path mime]
  (if-let [r (io/resource (str "ui/" path))]
    {:status 200 :headers {"Content-Type" mime} :body (io/input-stream r)}
    {:status 404 :body "Not found"}))
(defn static-page [path]
  (if-let [r (io/resource (str "ui/" path))] (response (slurp r)) {:status 404 :body "Not found"}))
(defn page [title description path body]
  (let [template (slurp (io/resource "ui/economics/index.html"))
        url (escape-html (str (origin) path))]
    (response
      (-> template
          (str/replace #"(?s)<main id=\"main\">.*?</main>" (fn [_] (str "<main id=\"main\">" body "</main>")))
          (str/replace #"<title>[^<]*</title>" (fn [_] (str "<title>Hey99 — " (escape-html title) "</title>")))
          (str/replace #"(<meta\s+(?:name=\"description\"|property=\"og:description\"|name=\"twitter:description\")\s+content=\")[^\"]*" (fn [[_ prefix]] (str prefix (escape-html description))))
          (str/replace #"(<meta\s+(?:property=\"og:title\"|name=\"twitter:title\")\s+content=\")[^\"]*" (fn [[_ prefix]] (str prefix "Hey99 — " (escape-html title))))
          (str/replace #"(<link\s+rel=\"canonical\"\s+href=\")[^\"]*" (fn [[_ prefix]] (str prefix url)))
          (str/replace #"(<meta\s+property=\"og:url\"\s+content=\")[^\"]*" (fn [[_ prefix]] (str prefix url)))
          (str/replace "aria-current=\"page\"" "")
          (str/replace "../assets/" "/assets/")
          (str/replace "../styles.css" "/styles.css")
          (str/replace "../join/" "/join/")
          (str/replace "../market/" "/market/")
          (str/replace "../#opportunity" "/#opportunity")
          (str/replace "href=\"../\"" "href=\"/\"")
          (str/replace "href=\"./\"" "href=\"/economics/\"")))))
(defn hero [title text]
  (str "<section class=\"economics-hero\"><div class=\"wrap\"><span class=\"eyebrow\">#hey99 · Local stories</span><h1>" (escape-html title) "</h1><p class=\"economics-lede\">" (escape-html text) "</p></div></section>"))
(defn story-card [item]
  (let [p (:payload item) path (str "/stories/" (:slug item) "/")]
    (str "<article>" (when (safe-image-url (:featured_image p)) (str "<a href=\"" (escape-html path) "\">" (featured-image p "story-thumb" false) "</a>")) "<span class=\"economics-index\">" (escape-html (:author item)) "</span><h3><a href=\"" (escape-html path) "\">" (escape-html (:title p)) "</a></h3><p>" (escape-html (or (:summary p) (subs (str (:body p)) 0 (min 180 (count (str (:body p))))))) "</p><a class=\"text-link\" href=\"" (escape-html path) "\">Read the story ↗</a></article>")))
(defn stories-page [items]
  (let [items (sort-by :published-at > (filter :slug items))]
    (page "Stories from your local economy." "Meet the ideas, people and connections building Hey99. Stories worth reading and sharing." "/stories/"
      (str (hero "Good stories bring people together." "Ideas, everyday discoveries and local voices. Read something useful, then help it find the people who might appreciate it.")
           "<section class=\"section\"><div class=\"wrap\">"
           (if (seq items) (str "<div class=\"economics-grid\">" (apply str (map story-card items)) "</div>") "<h2>The first stories are taking shape.</h2><p>Want to help tell the story of your community? Bring an idea, a local discovery or a useful perspective.</p>")
           "<div class=\"hero-actions\"><a class=\"button\" href=\"/join/\">Suggest a story ↗</a><a class=\"text-link\" href=\"/studio/\">Writer’s studio ↗</a></div></div></section>"))))
(defn story-page [item]
  (let [p (:payload item) title (:title p) desc (or (:summary p) title)
        date (when (:published-at item) (.toString (.toLocalDate (.atZone (java.time.Instant/ofEpochMilli (long (:published-at item))) (java.time.ZoneId/of "America/Los_Angeles")))))
        html (str (hero title desc) "<section class=\"section\"><article class=\"wrap article-body\"><p class=\"article-byline\">" (if (= "guest" (:byline-kind item)) "By " "From ") (escape-html (:author item)) " · " (escape-html date) "</p>"
                  (featured-image p "story-featured" true)
                  (article-body p)
                  "<div class=\"hero-actions\"><button class=\"button\" data-share>Share this story ↗</button><a class=\"text-link\" href=\"/economics/\">How rewards work ↗</a></div><p class=\"share-note\">Personal sharing links credit your contribution. Hey99 is preparing to launch; checkout and payouts are not active.</p><hr /><h2>Find your place in the community.</h2><p>Bring your business, a connection or an idea.</p><div class=\"hero-actions\"><a class=\"button\" href=\"/join/\">Join Hey99 ↗</a><a class=\"text-link\" href=\"/stories/\">More stories ↗</a></div></article></section>")]
    (let [rendered (page title desc (str "/stories/" (:slug item) "/") html)]
      (if-let [image (safe-image-url (:featured_image p))]
        (let [absolute (str (.resolve (java.net.URI. (str (origin) "/")) image))
              alt (get-in p [:metadata :featured_image_alt] "")]
          (update rendered :body #(-> %
            (str/replace #"(<meta\s+(?:property=\"og:image\"|name=\"twitter:image\")\s+content=\")[^\"]*" (fn [[_ prefix]] (str prefix (escape-html absolute))))
            (str/replace #"(<meta\s+(?:property=\"og:image:alt\"|name=\"twitter:image:alt\")\s+content=\")[^\"]*" (fn [[_ prefix]] (str prefix (escape-html alt))))
            (str/replace #"(<meta\s+property=\"og:image:height\"\s+content=\")[^\"]*" (fn [[_ prefix]] (str prefix "800")))
            (str/replace #"<meta property=\"og:image:type\" content=\"[^\"]*\" />" ""))))
        rendered))))
(defn workspace [title kind]
  (page title "Your connections, stories and participation in the Hey99 community." (str "/" kind "/")
    (str (hero title "Bring what you know. Help your local community grow.") "<section class=\"section\"><div class=\"wrap game-workspace\" id=\"game-workspace\" data-workspace=\"" kind "\"><p role=\"status\">Loading your place in Hey99…</p></div></section>")))
(defn not-found [] (assoc (page "This page is still undiscovered." "Explore Hey99." "/404/" (hero "Let’s find your way back." "Visit the Hey99 homepage or explore our local stories.")) :status 404))
(defn unavailable [] (assoc (page "Stories will be back shortly." "Hey99 stories are temporarily unavailable." "/stories/" (hero "We’ll be back shortly." "Stories are temporarily unavailable. Please try again in a moment.")) :status 503))
(defn privacy-page []
  (page "Your information, treated with care." "How Hey99 uses account, application and referral information." "/privacy/"
    (str (hero "Your information, treated with care." "Clear information about participating in Hey99.") "<section class=\"section\"><div class=\"wrap article-body\"><p>Browsing is public. When you create an account, Intergraph authentication connects your identity to the handle and roles you choose in Hey99.</p><p>Personal sharing links record the referral, page and time of a qualifying visit. A browser cookie connects those visits to your account when you sign in and claim your handle. This supports referral credit and future eligible rewards.</p><p>Draft stories stay within the authorized editorial workflow. Approved stories publish with their credited byline. Recruiting applications are collected through Tally for the Hey99 team to review and follow up.</p><p>Do not include sensitive personal information in public stories. For questions or requests about your information, contact <a href=\"/join/\">the Hey99 team through the application form</a>.</p></div></section>")))
(defn terms-page []
  (page "A good place to participate." "Hey99 participation and preview terms." "/terms/"
    (str (hero "A good place to participate." "Useful contributions. Honest recommendations. Respect for your community.") "<section class=\"section\"><div class=\"wrap article-body\"><p>Share honestly and respect other people’s work. When sharing a personal referral link, let people know you may earn from future eligible commerce. Share only content you have permission to publish.</p><p>Your account and handle identify your contributions. Writers submit work for editorial review; publication requires approval. Do not misrepresent a business, another contributor or the platform’s launch status.</p><p>Hey99 is a preview. It has no active checkout or payouts, and preview activity creates no rewards. Intended launch reward terms are explained on <a href=\"/economics/\">How rewards work</a>; each live offer will publish its applicable terms.</p><p>If you need help with participation, contact <a href=\"/join/\">the Hey99 team through the application form</a>.</p></div></section>")))

(defn about-page []
  (page "Meet Amit Rathore, founder of Hey99." "Meet Amit Rathore: a builder, writer and neighbor bringing his experience in commerce and publishing to a community-led local economy along Highway 99." "/about/"
    (str "<section class=\"economics-hero\"><div class=\"wrap\"><span class=\"eyebrow\">#hey99 · Meet the founder</span><h1>Hi, I’m Amit.</h1><p class=\"economics-lede\">Founder of Hey99. A builder, a writer, and a neighbor getting to know this place alongside you.</p></div></section>"
         "<section class=\"section\"><article class=\"wrap article-body founder-story\"><figure class=\"founder-portrait\"><img src=\"/assets/people/amit-rathore-v1.webp\" width=\"600\" height=\"600\" alt=\"Amit Rathore, founder of Hey99\" loading=\"eager\" decoding=\"async\" /><figcaption>Amit Rathore · Founder, Hey99</figcaption></figure>"
         "<h2>A new chapter, close to home.</h2><p>At the end of 2025, a change in life plans brought me from the Bay Area to the Highway 99 area. I’m still figuring out my longer-term plans, but the kindness I encountered here made an impression almost immediately.</p><p>I’ve come to love Ripon and Manteca, spending time between the two as I get to know the area. Perhaps I’ll settle in Ripon—there seem to be some lovely houses there! For now, I’m enjoying discovering the businesses, places and people that give both communities their character.</p><p>Hey99 grows out of that experience. I’m its founder, and I’m also one of the people I hope it will serve. I want us to build something we can feel part of: our place, our people, our economy.</p>"
         "<h2>Connecting the dots.</h2><p>I’ve spent much of my life building technology. What keeps drawing me back is what happens between people: a discovery, a conversation, an idea that becomes something useful when someone else picks it up.</p><p>At Runa, and later Staples, my work was close to commerce. When I cofounded Quintype in 2014, it was close to publishing: helping people get their stories into the world. Working across those spaces brought me to a question I’m still exploring: if someone’s story or recommendation helps create value, how can that person share in it?</p><p>That thread continued through PressCoin and, later, AwakeVC, Shoptype and Coselling.ai. The names changed, but I kept connecting the same dots: technology, people, the things we create, and the ways we help each other discover them. Intergraph is where I’m bringing those ideas together. Hey99 is a way to explore them with the people and businesses around me.</p>"
         "<h2>More room to be ourselves.</h2><p>In the age of AI, I’m interested in more than making work faster. I’m interested in what we might have more room to do: follow a curiosity, develop a craft, start something small, tell a story, or spend time on the connections that make life meaningful.</p><p>That’s what self-actualization means to me here: having more room to become ourselves and contribute in ways that feel natural. We don’t all want the same life. A good local economy should make space for different interests, abilities and ambitions.</p><p>Value co-creation sounds abstract, but we already do it. A shopkeeper offers something good. A neighbor recommends it. Someone shares a story that helps another person discover it. Each brings something, and together we make something possible.</p><p>I want technology to help us recognize those contributions and share in what they create. A side income can be part of that. So can belonging, confidence, and the feeling that what we care about has a place in our community.</p>"
         "<h2>Something we build together.</h2><p>I want a local merchant to feel just as welcome here as someone sharing a favorite recipe or recommending a shop to a friend. Each of us brings something: a business, a skill, a story, a connection, or simply an interest in the place we live.</p><p>And I want everyday people like us to have a chance to earn a side income from the commerce our contributions help inspire. No hard selling. No need to turn every conversation into a pitch. Just honest enthusiasm helping good things find their people.</p><p>I’m bringing my experience to this project, but the community will give it its character. I’d love to hear what would make Hey99 useful to you.</p>"
         "<div class=\"hero-actions\"><a class=\"button\" href=\"/join/\">Let’s build Hey99 together ↗</a><a class=\"text-link\" href=\"/stories/why-start-the-hey99-movement/\">Why start the Hey99 movement? ↗</a></div><p class=\"share-note\">Hey99 is preparing to launch. Checkout and payouts are not active, and preview activity does not create earnings.</p>"
         "<details class=\"founder-background\"><summary>More about my background</summary><p>For more of my work and writing, visit <a href=\"https://amitrathore.com/\">my personal website</a>. Background references: <a href=\"https://www.manning.com/books/clojure-in-action\">Manning’s author and book page</a>, <a href=\"https://blog.quintype.com/people/medianomy-interviews-chirdeep-shetty-ceo-quintype\">Quintype’s founding story</a>, and <a href=\"https://www.sec.gov/Archives/edgar/data/791519/000115752313005562/a50754459_ex991.htm\">Staples’ 2013 announcement</a>.</p></details></article></section>")))
