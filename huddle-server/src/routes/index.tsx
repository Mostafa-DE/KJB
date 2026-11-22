import { createFileRoute } from "@tanstack/react-router"
import { KJB_RELEASES_URL } from "@/lib/kjb"

export function HomePage() {
  return (
    <main className="page">
      <p className="brand">KJB</p>
      <h1>KJB Huddle</h1>
      <p>
        This server passes songs between friends on KJB. Everything it carries is locked on the phones, so it cannot
        read any of it.
      </p>
      <a className="button primary" href={KJB_RELEASES_URL}>
        Get KJB
      </a>
    </main>
  )
}

export const Route = createFileRoute("/")({
  component: HomePage,
})
