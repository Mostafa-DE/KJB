import { createFileRoute } from "@tanstack/react-router"
import { json } from "@/lib/api"

// An API path no route has: JSON like every other API answer, never the page shell.
export const Route = createFileRoute("/api/$")({
  server: {
    handlers: {
      ANY: () => json(404, { error: "not_found" }),
    },
  },
})
