# Huddle server

The relay behind KJB's Huddle: friends' phones leave each other sealed songs, plays and names here and pick them up
later. Everything it stores is encrypted on the phones; it keeps only hashes of the ids and the ciphertext, and has no
accounts. [API.md](API.md) is the contract the app is built against.

TanStack Start server routes on Node, Drizzle with Postgres, Zod, and srvx as the server. The pages are the invite
landing page (`/i`), a home page, `/health` and `/.well-known/assetlinks.json`.

## Work on it

Needs Node 26 and pnpm 10 (the versions are pinned in `package.json`).

```bash
pnpm install
pnpm test          # unit and route tests, on an in-memory Postgres (PGlite); no Docker
pnpm typecheck
pnpm build && pnpm test:e2e   # starts the built server under srvx, as Railway does, and calls it over HTTP
pnpm db:generate   # after a change to src/db/schema.ts: writes the next migration into src/db/migrations
```

`pnpm dev` needs `DATABASE_URL` pointing at a Postgres you run yourself.

Settings (Railway variables): `DATABASE_URL` (set by the IaC file) and, optionally, `HUDDLE_MAX_STORE_MB`, the
storage guard (1024 by default). `PORT` comes from Railway.

## Deploy on Railway

The project is described in [.railway/railway.ts](.railway/railway.ts) (Railway Infrastructure as Code, in beta):
a Postgres database and the `huddle` service, built from `huddle-server/` on the `main` branch of GitHub
`Mostafa-DE/KJB`, with `pnpm db:migrate` before each deploy and `/health` as its health check. One replica: the rate
limits are counted in the server's memory.

First time:

1. Make sure `huddle-server/` is on `main` on GitHub, and that Railway's GitHub app may read `Mostafa-DE/KJB`
   (Railway dashboard, Account Settings, Integrations or GitHub).
2. Install or update the Railway CLI (`npm i -g @railway/cli`, then `railway upgrade`). The IaC file needs CLI
   5.42.1 or newer: check with `railway --version`.
3. `railway login`
4. `cd huddle-server && pnpm install` (the CLI reads `.railway/railway.ts`, which imports the `railway` package).
5. `railway init` and name the project `kjb-huddle`. This creates an empty project and links this folder to it.
6. `railway config plan`. Expect two creates and nothing else: the Postgres database `postgres`, and the service
   `huddle` from `Mostafa-DE/KJB` (branch `main`, root `huddle-server`) with its build and start commands, the
   pre-deploy migration, the `/health` check and a `DATABASE_URL` variable that refers to the database.
7. `railway config apply` and confirm.
8. Give the service its address: `railway domain` (or the service's Settings, Networking, Generate Domain). If
   Railway lets you choose the name, choose `kjb-huddle`, for `https://kjb-huddle.up.railway.app`. KJB lists the
   exact host in its App Links, so pass it on before KJB's Huddle release.
9. If no deployment started, press Deploy on the service in the dashboard (or push a commit that changes
   `huddle-server/` to `main`). The deploy log should show the migration, then the server listening.

Check it (replace the host):

```bash
curl https://kjb-huddle.up.railway.app/health
# {"ok":true}
curl -i https://kjb-huddle.up.railway.app/.well-known/assetlinks.json
# 200, content-type application/json, package app.lyrify with the release key's fingerprint, no redirect
curl "https://digitalassetlinks.googleapis.com/v1/statements:list?source.web.site=https://kjb-huddle.up.railway.app&relation=delegate_permission/common.handle_all_urls"
# lists app.lyrify
```

Then open `https://kjb-huddle.up.railway.app/i#test-code` on a phone: the page shows `test-code` with Copy code.

Later, for `huddle.kjb.mostafade.com`: add `domains: ["huddle.kjb.mostafade.com"]` to the `huddle` service in
`.railway/railway.ts`, run `railway config plan` and `railway config apply`, then `railway domain status
huddle.kjb.mostafade.com` shows the DNS records to add where `mostafade.com` is managed. Once the certificate is
issued (within an hour of the DNS change), run the checks above on the new host too.
