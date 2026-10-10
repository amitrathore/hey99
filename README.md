# Hey99

An Intergraph Game for the Highway 99 local economy, with the Hey99 website as its game UI. It uses the shared `ai.intergraph/game-master` runtime and production MoM for durable game state. Hey99 has its own game identity, player group and game credential.

## Website and participation

The original homepage, marketplace preview, economics explanation and Tally application remain available. Community comes first; side income is an opportunity explained within the site. Tally uses dynamic height so its embed does not require internal scrolling.

- `/account/`: sign in through Intergraph, choose a handle and manage participation.
- `/share/`: activate coseller participation and obtain personal links. Referral visits are recorded anonymously and claimed after sign-in.
- `/stories/`: published Attention Protocol articles, with individual server-rendered titles, descriptions, canonical URLs and author credit.
- `/studio/`: invited writers create and submit stories; strategists assign briefs, return feedback and approve publication. A writer sees only their assigned drafts; editors see the review queue.
- `/economics/`: the intended reward model, explained without leaving Hey99.

**Preview:** no active offers, checkout, payments, settlements or withdrawals. Those commands are rejected by the GM. Tracking and published stories do not create preview earnings. Accounts cannot self-claim editorial roles.

## Development

Java 21, Leiningen, Node and Playwright are required. Copy `.env.example` to `.env.local` and configure your own game credentials. Never commit secrets.

```sh
bash scripts/prepare-ui.sh
lein test
npm ci
npx playwright install chromium
npm test
bash scripts/run-local.sh
```

The browser suite runs the real Ring application with fixture game state on port 8899. It checks mobile layouts, article metadata, the Tally embed, account/activation flow, referral sharing and draft submission. Production MoM is not mutated by these tests.

Root HTML, CSS, JavaScript and assets are the canonical UI sources. `scripts/prepare-ui.sh` copies them into generated classpath resources before running or packaging the GM. Do not edit `resources/ui` directly.

## Deployment

`fly.toml` targets the `hey99` app in `sjc`, one always-running shared CPU machine with 1 GB RAM. The container uses Java 21, runs as a non-root user and listens on port 8888. `/health` checks the process; `/ready` remains unavailable until the shared runtime has registered the game and completed its startup steps.

Fly secrets: `MOM_GAME_TOKEN` (dedicated Hey99 credential), `MOM_GM_OUTBOUND_TOKEN` (production MoM webhook credential), `GM_OWNER_ID`, and `CLERK_PUBLISHABLE_KEY`. Public URLs and the unique player group are in `fly.toml`.

```sh
fly deploy --remote-only --ha=false
fly status --app hey99
```

GitHub Actions verifies the GM and browser flows before deploying. Its `FLY_API_TOKEN` repository secret should be restricted to this app. Secrets are excluded from Git and the Docker build context.

The custom domain is `hey99.online`. Switch its DNS from GitHub Pages only after verifying the Fly deployment and certificates. To roll back a release, deploy the previous verified commit; MoM retains the game's state independently of the container.

## Editorial workflow

Invite writers and strategists through Intergraph's authorized role workflow. Writers start a piece or receive an assigned brief, then submit their draft for review. An editor approves publication with an optional stable slug. Published stories retain the writer's byline. The three introductory pieces in `scripts/articles.json` are proposed drafts, not automatically published content.

The `/economics/` reference is the shared Community.Markets earnings model; keep percentage bases consistent when updating the explanation. The generic model includes reward features that remain launch intentions rather than active preview settlement rules.
