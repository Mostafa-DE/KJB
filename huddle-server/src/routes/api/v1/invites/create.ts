import { createFileRoute } from "@tanstack/react-router"
import { z } from "zod"
import { createInvite } from "@/data-access/invites"
import { storeIsFull } from "@/data-access/store"
import { ApiError, apiRoute, enforceRate, idSchema, json } from "@/lib/api"
import { LIMITS } from "@/lib/limits"

export const handleCreateInvite = apiRoute(
  z.object({ invite: idSchema, owner: idSchema }),
  async ({ invite, owner }, ip) => {
    enforceRate(`invite-create:${ip}`, LIMITS.rate.inviteCreate)
    if (await storeIsFull()) throw new ApiError(503, "storage_full")

    const created = await createInvite(invite, owner)
    if (!created) throw new ApiError(409, "invite_exists")
    return json(201, { expiresAt: created.expiresAt.getTime() })
  },
)

export const Route = createFileRoute("/api/v1/invites/create")({
  server: {
    handlers: {
      POST: ({ request }) => handleCreateInvite(request),
    },
  },
})
