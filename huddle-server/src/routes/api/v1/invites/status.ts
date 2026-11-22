import { createFileRoute } from "@tanstack/react-router"
import { z } from "zod"
import { findInvite } from "@/data-access/invites"
import { ApiError, apiRoute, idSchema, json } from "@/lib/api"

export const handleInviteStatus = apiRoute(z.object({ owner: idSchema }), async ({ owner }) => {
  const invite = await findInvite(owner)
  if (!invite) throw new ApiError(404, "not_found")
  if (!invite.acceptedAt) return json(200, { state: "waiting", expiresAt: invite.expiresAt.getTime() })
  return json(200, {
    state: "accepted",
    data: invite.data,
    acceptedAt: invite.acceptedAt.getTime(),
    expiresAt: invite.expiresAt.getTime(),
  })
})

export const Route = createFileRoute("/api/v1/invites/status")({
  server: {
    handlers: {
      POST: ({ request }) => handleInviteStatus(request),
    },
  },
})
