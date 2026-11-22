import { createFileRoute } from "@tanstack/react-router"
import { z } from "zod"
import { deleteInvite } from "@/data-access/invites"
import { apiRoute, idSchema, json } from "@/lib/api"

export const handleDeleteInvite = apiRoute(z.object({ owner: idSchema }), async ({ owner }) =>
  json(200, { deleted: await deleteInvite(owner) }),
)

export const Route = createFileRoute("/api/v1/invites/delete")({
  server: {
    handlers: {
      POST: ({ request }) => handleDeleteInvite(request),
    },
  },
})
