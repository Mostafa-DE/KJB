import { createFileRoute } from "@tanstack/react-router"
import { z } from "zod"
import { sendMessage } from "@/data-access/messages"
import { storeIsFull } from "@/data-access/store"
import { storageKey } from "@/db"
import { messageKinds } from "@/db/schema"
import { ApiError, apiRoute, dataSchema, dataSize, enforceRate, idSchema, json } from "@/lib/api"
import { LIMITS } from "@/lib/limits"

export const handleSendMessage = apiRoute(
  z.object({ mailbox: idSchema, kind: z.enum(messageKinds), data: dataSchema }),
  async ({ mailbox, kind, data }, ip) => {
    const size = dataSize(data)
    if (size > LIMITS.dataBytes[kind]) throw new ApiError(413, "too_large")
    // Keyed by the hash, so the rate counters never hold a mailbox id either.
    enforceRate(`sends:${storageKey(mailbox)}`, LIMITS.rate.mailboxSends)
    enforceRate(`bytes:${ip}`, LIMITS.rate.ipBytes, size)
    if (await storeIsFull()) throw new ApiError(503, "storage_full")

    const sent = await sendMessage(mailbox, kind, data, size)
    if (sent.outcome === "mailbox_full") throw new ApiError(409, "mailbox_full")
    return json(201, { id: String(sent.id), sentAt: sent.sentAt.getTime(), expiresAt: sent.expiresAt.getTime() })
  },
)

export const Route = createFileRoute("/api/v1/messages/send")({
  server: {
    handlers: {
      POST: ({ request }) => handleSendMessage(request),
    },
  },
})
