import { createFileRoute } from "@tanstack/react-router"
import { z } from "zod"
import { ackMessages } from "@/data-access/messages"
import { apiRoute, eachMailboxOnce, idSchema, json, messageIdSchema } from "@/lib/api"
import { LIMITS } from "@/lib/limits"

export const handleAck = apiRoute(
  z.object({
    acks: z
      .array(z.object({ mailbox: idSchema, upTo: messageIdSchema }))
      .min(1)
      .max(LIMITS.batchEntries)
      .refine(eachMailboxOnce),
  }),
  async ({ acks }) => json(200, { deleted: await ackMessages(acks) }),
)

export const Route = createFileRoute("/api/v1/messages/ack")({
  server: {
    handlers: {
      POST: ({ request }) => handleAck(request),
    },
  },
})
