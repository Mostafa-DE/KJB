import { createFileRoute } from "@tanstack/react-router"
import { z } from "zod"
import { acceptInvite } from "@/data-access/invites"
import { storeIsFull } from "@/data-access/store"
import { ApiError, apiRoute, dataSchema, dataSize, enforceRate, idSchema, json } from "@/lib/api"
import { LIMITS } from "@/lib/limits"

export const handleAcceptInvite = apiRoute(
  z.object({ invite: idSchema, data: dataSchema }),
  async ({ invite, data }, ip) => {
    enforceRate(`invite-accept:${ip}`, LIMITS.rate.inviteAccept)
    const size = dataSize(data)
    if (size > LIMITS.acceptBytes) throw new ApiError(413, "too_large")
    enforceRate(`bytes:${ip}`, LIMITS.rate.ipBytes, size)
    enforceRate(`bytes-day:${ip}`, LIMITS.rate.ipBytesDay, size)
    if (await storeIsFull()) throw new ApiError(503, "storage_full")

    const accepted = await acceptInvite(invite, data)
    if (accepted.outcome === "not_found") throw new ApiError(404, "not_found")
    if (accepted.outcome === "already_accepted") throw new ApiError(409, "already_accepted")
    return json(200, { acceptedAt: accepted.acceptedAt.getTime() })
  },
)

export const Route = createFileRoute("/api/v1/invites/accept")({
  server: {
    handlers: {
      POST: ({ request }) => handleAcceptInvite(request),
    },
  },
})
