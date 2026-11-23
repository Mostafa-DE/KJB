import { createFileRoute } from "@tanstack/react-router"
import { z } from "zod"
import { listen, readInbox } from "@/data-access/messages"
import { apiRoute, eachMailboxOnce, enforceRate, idSchema, json, messageIdSchema } from "@/lib/api"
import { LIMITS } from "@/lib/limits"

/**
 * Reads the asked mailboxes. With `wait` (ms) and nothing waiting, the read is held until a message is sent into one of
 * them, `wait` passes (`LIMITS.inboxWaitMs` at most) or the phone goes; then read again and answered. Without it, the
 * read is answered at once, as before.
 */
export const handleInbox = apiRoute(
  z.object({
    mailboxes: z
      .array(z.object({ mailbox: idSchema, after: messageIdSchema.optional() }))
      .min(1)
      .max(LIMITS.inboxMailboxes)
      .refine(eachMailboxOnce),
    wait: z.number().int().min(0).optional(),
  }),
  async ({ mailboxes, wait = 0 }, ip, signal) => {
    const until = Date.now() + Math.min(wait, LIMITS.inboxWaitMs)
    let holds = wait > 0
    try {
      if (holds) enforceRate(`waits:${ip}`, LIMITS.rate.inboxWaits)
    } catch {
      // Past its own window: answered at once, as without `wait`.
      holds = false
    }
    let landed = false
    let wake = () => {}
    // Listening starts before the first read, so a message sent while it runs ends the wait too. Null past the server's
    // cap of held reads: answered at once.
    const stop = holds
      ? listen(
          mailboxes.map((entry) => entry.mailbox),
          () => {
            landed = true
            wake()
          },
        )
      : null
    try {
      for (;;) {
        const now = new Date()
        const read = await readInbox(mailboxes, now)
        const left = until - Date.now()
        if (!stop || left <= 0 || signal.aborted || read.some((entry) => entry.messages.length > 0 || entry.more)) {
          return json(200, { now: now.getTime(), mailboxes: read })
        }
        // Woken by a send, the time up or the phone gone; a play or a now another read took first means a read again.
        if (!landed) {
          await new Promise<void>((resolve) => {
            const timer = setTimeout(done, left)
            signal.addEventListener("abort", done)
            wake = done
            function done() {
              clearTimeout(timer)
              signal.removeEventListener("abort", done)
              resolve()
            }
          })
        }
        landed = false
      }
    } finally {
      stop?.()
    }
  },
)

export const Route = createFileRoute("/api/v1/messages/inbox")({
  server: {
    handlers: {
      POST: ({ request }) => handleInbox(request),
    },
  },
})
