import { createFileRoute } from "@tanstack/react-router"
import { z } from "zod"
import { deleteMailboxes } from "@/data-access/messages"
import { apiRoute, idSchema, json } from "@/lib/api"
import { LIMITS } from "@/lib/limits"

export const handleDeleteMailboxes = apiRoute(
  z.object({ mailboxes: z.array(idSchema).min(1).max(LIMITS.batchEntries) }),
  async ({ mailboxes }) => json(200, { deleted: await deleteMailboxes(mailboxes) }),
)

export const Route = createFileRoute("/api/v1/mailboxes/delete")({
  server: {
    handlers: {
      POST: ({ request }) => handleDeleteMailboxes(request),
    },
  },
})
