import { createFileRoute } from "@tanstack/react-router"
import { useEffect, useState } from "react"
import { KJB_PACKAGE, KJB_RELEASES_URL } from "@/lib/kjb"

// What KJB puts after the `#` (API.md, "Invite links"). Anything else is never placed in a link.
const INVITE_CODE = /^[A-Za-z0-9._-]{1,1024}$/

type InviteLandingProps = {
  /** The text after `#` in the invite link; undefined until the browser has read it. */
  code: string | undefined
  host: string
}

function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false)
  const copy = () => {
    navigator.clipboard.writeText(text).then(
      () => setCopied(true),
      () => setCopied(false),
    )
  }
  return (
    <button type="button" className="button" onClick={copy} aria-live="polite">
      {copied ? "Copied" : "Copy code"}
    </button>
  )
}

export function InviteLanding({ code, host }: InviteLandingProps) {
  const valid = code !== undefined && INVITE_CODE.test(code)
  // Chrome hands this to KJB as https://<host>/i#<code>; without KJB it opens the download page instead.
  const openInKjb =
    `intent://${host}/i#${code}#Intent;scheme=https;package=${KJB_PACKAGE};` +
    `S.browser_fallback_url=${encodeURIComponent(KJB_RELEASES_URL)};end`

  return (
    <main className="page">
      <p className="brand">KJB</p>
      <h1>A friend invited you to KJB</h1>
      <p>
        KJB is a music player. With Huddle, friends send each other songs and see what the others are playing. Only
        your phones can read what you share.
      </p>

      {code !== undefined && !valid && (
        <p className="notice" role="alert">
          This invite link is incomplete. Ask your friend to send it again.
        </p>
      )}

      {valid && (
        <section>
          <h2>Have KJB already?</h2>
          <a className="button primary" href={openInKjb}>
            Open in KJB
          </a>
        </section>
      )}

      <section>
        <h2>New to KJB?</h2>
        {valid && (
          <ol>
            <li>Download KJB and open the file to install it. Your phone may ask you to allow installs first.</li>
            <li>Come back to this page and tap Open in KJB.</li>
          </ol>
        )}
        <a className="button" href={KJB_RELEASES_URL}>
          Download KJB
        </a>
      </section>

      {valid && (
        <section>
          <h2>Link did not open KJB?</h2>
          <p>Copy this invite code, open KJB and tap Paste invite.</p>
          <code className="code">{code}</code>
          <CopyButton text={code} />
        </section>
      )}
    </main>
  )
}

function InvitePage() {
  const [invite, setInvite] = useState<{ code: string; host: string }>()
  useEffect(() => {
    // The code is in the fragment, which the browser never sends to the server: read it here.
    setInvite({ code: window.location.hash.slice(1), host: window.location.host })
  }, [])
  return <InviteLanding code={invite?.code} host={invite?.host ?? ""} />
}

export const Route = createFileRoute("/i")({
  head: () => ({ meta: [{ title: "A friend invited you to KJB" }] }),
  component: InvitePage,
})
