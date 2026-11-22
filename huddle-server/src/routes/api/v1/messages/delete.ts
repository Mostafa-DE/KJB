import { createFileRoute } from "@tanstack/react-router"
import { z } from "zod"
import { deleteMessages } from "@/data-access/messages"
import { apiRoute, idSchema, json, messageIdSchema } from "@/lib/api"
import { LIMITS } from "@/lib/limits"

export const handleDeleteMessages = apiRoute(
  z.object({ mailbox: idSchema, ids: z.array(messageIdSchema).min(1).max(LIMITS.deleteIds) }),
  async ({ mailbox, ids }) => json(200, { deleted: await deleteMessages(mailbox, ids) }),
)

export const Route = createFileRoute("/api/v1/messages/delete")({
  server: {
    handlers: {
      POST: ({ request }) => handleDeleteMessages(request),
    },
  },
})
