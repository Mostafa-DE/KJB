import { afterAll, afterEach, beforeEach, vi } from "vitest"
import { db } from "@/db"
import { invites, messages } from "@/db/schema"

beforeEach(async () => {
  await db.delete(messages)
  await db.delete(invites)
})

afterEach(() => {
  vi.useRealTimers()
})

afterAll(async () => {
  await db.$client.end()
})
