# Huddle server HTTP API (v1)

The Huddle server is a relay for KJB phones. It stores and hands out encrypted messages and never sees what is
inside them. There are no accounts, emails or phone numbers. The right to use a mailbox or an invite is knowing its
secret id (a capability).

Status: v1, agreed between lanes hs1 (server) and ha1 (app). The server code is the source of truth for the numbers
below; `src/lib/limits.ts` holds them all.

## Hosts

Both hosts serve the same server and the same database:

- the Railway address, `https://<service>.up.railway.app` (the name is chosen by the user when the service is created;
  suggested: `kjb-huddle.up.railway.app`);
- `https://huddle.kjb.mostafade.com` (added later; KJB lists both from the first Huddle version).

Plain `http` is not used. Every path below is the same on both hosts.

## Pages and files (GET)

| Path | What it is |
|---|---|
| `GET /health` | `200 {"ok":true}` when the server and its database answer; `503 {"ok":false}` otherwise. |
| `GET /.well-known/assetlinks.json` | Android App Links file for package `app.lyrify` (release key fingerprint), `200`, `Content-Type: application/json`, no redirect, on whatever host was asked. |
| `GET /i` | The invite landing page for a friend who does not have KJB yet (see "Invite links"). |
| `GET /` | A one-screen page saying what this server is, with the KJB download link. |

## Invite links

```
https://<host>/i#<code>
```

- `<code>` is made by the inviting phone and is opaque to the server. Its characters must be `A-Z a-z 0-9 - _ .`
  (no `#`, `/`, `?` or `%`). It sits after `#`, so browsers never send it to the server.
- KJB opens these links with an App Links intent filter: `VIEW`, `BROWSABLE`, scheme `https`, both hosts, path `/i`,
  `autoVerify="true"`.
- The landing page shows the code with a Copy button (for "Paste invite" in KJB), a download link to the latest KJB
  release (`https://github.com/Mostafa-DE/KJB/releases/latest`), and an "Open in KJB" button. That button is a Chrome
  intent link that hands KJB the same https URL, fragment included:
  `intent://<host>/i#<code>#Intent;scheme=https;package=app.lyrify;S.browser_fallback_url=<release page>;end`.
  Android's `Intent.parseUri` splits at the last `#`, so the data URI KJB receives is `https://<host>/i#<code>`
  (to confirm on a phone).

## Common rules for every `/api/v1` call

- Every call is `POST` with a JSON body and `Content-Type: application/json`. Secret ids travel only in the body,
  never in the URL, so they never appear in Railway's request logs.
- Request bodies over 98,304 bytes get `413`. A missing or other `Content-Type` gets `415`.
- **Id** = exactly 43 characters of `A-Z a-z 0-9 - _` (32 random bytes or an HKDF output, base64url without
  padding). The server stores only the SHA-256 of each id, never the id itself.
- **Data** = base64url without padding (`A-Z a-z 0-9 - _`), at least 1 byte decoded. The server never decodes the
  meaning; size limits are on the decoded bytes.
- **Message id** = a decimal string such as `"1734"`. Ids only grow within a mailbox. Treat them as opaque, except
  that `after` and `upTo` use them as cursors.
- **Times** are Unix epoch milliseconds (numbers).
- Responses are JSON with `Cache-Control: no-store`.

### Errors

Every error is `{"error": "<code>"}`. `bad_request` also names the first bad field, for example
`{"error":"bad_request","field":"messages.0.mailbox"}`.

| Status | `error` | When |
|---|---|---|
| 400 | `bad_request` | Body is not JSON, or a field is missing, too long, or of the wrong shape. |
| 404 | `not_found` | Invite unknown or expired, or an unknown `/api` path. (Never used for mailboxes: an unknown mailbox is just empty.) |
| 405 | `method_not_allowed` | Anything but `POST` on an `/api` path. The `Allow` header says `POST`. |
| 409 | `invite_exists` | `invites/create` with an invite or owner id already in use. |
| 409 | `already_accepted` | `invites/accept` on an invite someone already accepted. |
| 409 | `mailbox_full` | The friend's mailbox already holds 200 cards and profiles or 4 MiB of them. |
| 413 | `too_large` | Body over 98,304 bytes, or `data` over the limit for its kind. |
| 415 | `unsupported_media_type` | `Content-Type` is not `application/json`. |
| 429 | `rate_limited` | A rate limit was hit. The `Retry-After` header gives the seconds to wait. |
| 500 | `server_error` | Unexpected failure. Retry later. |
| 503 | `storage_full` | The server's storage guard is full. Writes are refused until expired rows are cleaned. |

Railway itself can answer `502` or `503` without a JSON body during a deploy or a restart: retry once after a few
seconds.

## Invites

An invite is a one-time slot. The inviting phone creates it with two ids:

- `invite`: the friend must be able to work it out from the link (for example SHA-256 of a domain string plus the
  token in the link). Knowing it allows exactly one accept.
- `owner`: random, only on the inviting phone, never in the link. Knowing it allows reading and deleting the invite.

So a stranger who sees the link can accept it (the inviter then sees who joined and can remove them), but can never
read or delete it.

### `POST /api/v1/invites/create`

```json
{ "invite": "<id>", "owner": "<id>" }
```

`201 {"expiresAt": 1764424800000}`. Unaccepted invites expire 7 days after creation. Errors: `400`, `409
invite_exists`, `429` (30 per hour per IP), `503 storage_full`.

### `POST /api/v1/invites/accept`

The friend's phone, after opening the link.

```json
{ "invite": "<id>", "data": "<base64url, up to 4,096 bytes>" }
```

`data` is whatever KJB needs to hand the inviter, for example the friend's public key and a sealed hello with the
display name. `200 {"acceptedAt": 1763820000000}`. Only the first accept wins. An accepted invite is kept for 7 more
days from the accept, until the owner deletes it. Errors: `400`, `404 not_found` (unknown or expired), `409
already_accepted`, `413 too_large`, `429` (30 per hour per IP), `503 storage_full`.

### `POST /api/v1/invites/status`

The inviting phone, while it waits.

```json
{ "owner": "<id>" }
```

`200`, one of:

```json
{ "state": "waiting", "expiresAt": 1764424800000 }
{ "state": "accepted", "data": "<base64url>", "acceptedAt": 1763820000000, "expiresAt": 1764424800000 }
```

Errors: `400`, `404 not_found` (unknown, expired, or deleted).

### `POST /api/v1/invites/delete`

```json
{ "owner": "<id>" }
```

`200 {"deleted": 1}` or `{"deleted": 0}` when there was nothing to delete (safe to retry). Delete as soon as the
accept has been read, and when the user cancels an invite.

## Mailboxes and messages

A mailbox is a 43-character id that both friends derive from their shared key, one per direction (A to B, B to A).
Knowing a mailbox id is the right to write to it, read it, and delete from it. Mailboxes are not created or
registered: writing to an id makes it exist, and an id nobody wrote to is simply empty. A wrong id never errors and
never returns anything.

Message kinds and how long the server keeps them:

| `kind` | What it carries | Max `data` | Kept |
|---|---|---|---|
| `card` | A song, album or playlist, with an optional note | 65,536 bytes | Until acknowledged or deleted; 30 days at most |
| `profile` | The sender's display name | 4,096 bytes | Until acknowledged; 30 days at most. A new profile replaces an unread older one in the same mailbox |
| `play` | One live listening event | 2,048 bytes | Until the first `inbox` call returns it (then deleted at once); 24 hours at most. A mailbox keeps its newest 500 plays; older ones are dropped |

A mailbox holds at most 200 cards and profiles together, and at most 4 MiB of their data. A send above either limit
gets `409 mailbox_full` (show "Your friend's inbox is full. They need to open KJB."). Plays never fill a mailbox.

Advice for KJB (not checked by the server): pad each kind to a few fixed sizes before sealing so sizes reveal
little, put a random message id and the send time inside the sealed data and drop duplicates, and compress long
album or playlist lists before sealing.

### `POST /api/v1/messages/send`

```json
{ "mailbox": "<id>", "kind": "card", "data": "<base64url>" }
```

`201 {"id": "1734", "sentAt": 1763820000000, "expiresAt": 1766412000000}`. Keep `id` and `mailbox` for "Undo".
Errors: `400`, `409 mailbox_full`, `413 too_large`, `429` (240 sends per hour into one mailbox; 16 MiB of data per
hour per IP), `503 storage_full`.

### `POST /api/v1/messages/inbox`

One call reads all of a phone's incoming mailboxes.

```json
{
  "mailboxes": [
    { "mailbox": "<id>", "after": "1730" },
    { "mailbox": "<id>" }
  ]
}
```

Up to 64 mailboxes, each at most once. `after` is optional: only messages with a larger id are returned; without it
every waiting message comes back. `200`:

```json
{
  "now": 1763820000123,
  "mailboxes": [
    {
      "mailbox": "<id>",
      "messages": [
        { "id": "1734", "kind": "card", "data": "<base64url>", "sentAt": 1763820000000 }
      ],
      "more": false
    },
    { "mailbox": "<id>", "messages": [], "more": false }
  ]
}
```

- Mailboxes come back in the order asked. Messages come oldest first.
- At most 100 messages per mailbox and about 1 MiB of data per response. `more: true` means there is more: call again
  with `after` set to the last id you got.
- Plays in the answer are deleted on the server as they are returned. Cards and profiles stay until `ack`.
- `now` is the server's clock, for showing "2 minutes ago" correctly on a phone whose clock is off.

### `POST /api/v1/messages/ack`

After the phone has stored what it read.

```json
{ "acks": [ { "mailbox": "<id>", "upTo": "1734" } ] }
```

Up to 64 entries, each mailbox at most once. Deletes every message in that mailbox with an id up to and including
`upTo`. `200 {"deleted": 3}` (the total over all entries; `0` is fine and safe to retry).

### `POST /api/v1/messages/delete`

"Undo" on the sender's phone, before the friend has acknowledged.

```json
{ "mailbox": "<id>", "ids": ["1734"] }
```

1 to 100 ids. `200 {"deleted": 1}`. `{"deleted": 0}` means the friend already has it (or it expired).

### `POST /api/v1/mailboxes/delete`

"Remove friend": empty both directions.

```json
{ "mailboxes": ["<id>", "<id>"] }
```

1 to 64 ids. `200 {"deleted": 12}` (messages removed).

## How the phones poll

- When KJB comes to the front: one `messages/inbox` call for every friend's incoming mailbox, then
  `invites/status` for each invite still waiting.
- While the Huddle page is open: the same every 30 seconds (this is what makes "now playing" live).
- While KJB is in front but the Huddle page is not open: the same every hour.
- KJB closed: no checks in v1 (no push, no background work).
- After `inbox`: store, then one `messages/ack` call for the mailboxes that returned cards or profiles. Plays need no
  ack.
- Sending: one `messages/send` per friend (each friend has their own mailbox and their own sealed copy). Plays go to
  the friends the user shares with, unless "Listen privately" is on.
- Failures: on a network error, `5xx`, or `502/503` from Railway, retry after 30 s, then 60, 120, and so on, up to the
  normal interval. On `429`, wait `Retry-After` seconds. Use a 15 s request timeout.

At 10 friends with the Huddle page open, a phone makes about 2 calls a minute, far under the limits below.

## Limits

| Limit | Value |
|---|---|
| Request body | 98,304 bytes |
| `card` / `profile` / `play` data | 65,536 / 4,096 / 2,048 bytes |
| `invites/accept` data | 4,096 bytes |
| Cards and profiles waiting in one mailbox | 200 messages and 4 MiB |
| Plays waiting in one mailbox | 500 (oldest dropped) |
| Kept: card, profile / play / waiting invite / accepted invite | 30 days / 24 h / 7 days / 7 days after accept |
| `inbox` | 64 mailboxes, 100 messages each, about 1 MiB of data per answer |
| `ack` / `mailboxes/delete` / `messages/delete` | 64 entries / 64 ids / 100 ids |
| All `/api/v1` calls and `/health`, per IP | 600 per 10 minutes |
| `invites/create`, per IP | 30 per hour |
| `invites/accept`, per IP | 30 per hour |
| Data sent (`messages/send` plus `invites/accept`), per IP | 16 MiB per hour |
| Sends into one mailbox | 240 per hour |
| Storage guard (all messages and invites) | 1 GiB by default (`HUDDLE_MAX_STORE_MB`); writes get `503 storage_full` above it |

Rate limits are counted in the server's memory (one Railway replica), in fixed windows, and reset on a redeploy.
Expired rows are never returned, and a cleanup deletes them when the server starts and then once a day.

## What the server can see

- Stored: SHA-256 hashes of mailbox, invite and owner ids; the kind, size, send time and expiry of each message; the
  encrypted data. No names, no song details, no keys, no IP addresses.
- While a request is live: the IP address (Railway sees it too), and which mailboxes one phone reads together in one
  `inbox` call, so the server could link one person's friendships while they poll.
- The server's own logs hold one line per request (method, URL, status, time; no path or query ever carries an id),
  and unexpected errors without request data. No ids, no data and no IPs.
