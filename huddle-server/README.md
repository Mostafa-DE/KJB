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

Live at `https://kjb-huddle.up.railway.app`: Railway project `kjb-huddle`, environment `production`, services
`huddle` (this server) and `postgres`. [.railway/railway.ts](.railway/railway.ts) describes both (Railway
Infrastructure as Code, in beta): Railpack builds with `pnpm build`, `pnpm db:migrate` runs before each deploy,
`pnpm start` runs the server, `/health` is the health check, and `DATABASE_URL` refers to the database. One replica:
the rate limits are counted in the server's memory. The service has no repository; `railway up` uploads this folder.

Every command below runs from `huddle-server/` with Railway CLI 5.42.1 or newer. Link the folder first, once per
checkout, and check the link before anything else:

```bash
railway link --project kjb-huddle --environment production --service huddle
railway status   # must say Project: kjb-huddle
```

Deploy the code in this folder (build logs stream, then it returns; the deploy log shows the migration, then srvx
listening):

```bash
pnpm install && pnpm test && pnpm build   # locally first
railway up --service huddle --ci
```

Change the settings: edit `.railway/railway.ts`, then `railway config plan` (it must list only what you changed) and
`railway config apply`. A plan with nothing changed says "already up to date".

Redeploy the running build without uploading: `railway redeploy --service huddle`. Restart it without rebuilding:
`railway restart --service huddle`. Logs: `railway logs --service huddle --deployment --lines 100`, and
`--http` for Railway's request log.

Roll back: in the Railway dashboard, open the `huddle` service, Deployments, and choose Rollback on the earlier
deployment. From the command line, check out the earlier commit and `railway up --service huddle --ci` again.
`railway deployment list --service huddle` lists the deployments. A rollback never undoes a migration.

Check it after a deploy:

```bash
curl https://kjb-huddle.up.railway.app/health
# {"ok":true}
curl -i https://kjb-huddle.up.railway.app/.well-known/assetlinks.json
# 200, content-type application/json, package app.lyrify with the release key's fingerprint, no redirect
curl "https://digitalassetlinks.googleapis.com/v1/statements:list?source.web.site=https://kjb-huddle.up.railway.app&relation=delegate_permission/common.handle_all_urls"
# lists app.lyrify
```

Later, for `huddle.kjb.mostafade.com`: add `domains: ["huddle.kjb.mostafade.com"]` to the `huddle` service in
`.railway/railway.ts`, run `railway config plan` and `railway config apply`, then `railway domain status
huddle.kjb.mostafade.com` shows the DNS records to add where `mostafade.com` is managed. Once the certificate is
issued (within an hour of the DNS change), run the checks above on the new host too. To deploy from GitHub instead of
`railway up`, add `source: github("Mostafa-DE/KJB", { branch: "main", rootDirectory: "huddle-server" })` to the
service once `huddle-server/` is on `main`, then plan and apply.
