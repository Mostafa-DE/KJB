import { createFileRoute } from "@tanstack/react-router"
import { z } from "zod"
import { readInbox } from "@/data-access/messages"
import { apiRoute, eachMailboxOnce, idSchema, json, messageIdSchema } from "@/lib/api"
import { LIMITS } from "@/lib/limits"

export const handleInbox = apiRoute(
  z.object({
    mailboxes: z
      .array(z.object({ mailbox: idSchema, after: messageIdSchema.optional() }))
      .min(1)
      .max(LIMITS.inboxMailboxes)
      .refine(eachMailboxOnce),
  }),
  async ({ mailboxes }) => {
    const now = new Date()
    return json(200, { now: now.getTime(), mailboxes: await readInbox(mailboxes, now) })
  },
)

export const Route = createFileRoute("/api/v1/messages/inbox")({
  server: {
    handlers: {
      POST: ({ request }) => handleInbox(request),
    },
  },
})
