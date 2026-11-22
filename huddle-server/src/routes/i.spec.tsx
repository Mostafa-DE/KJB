import { renderToStaticMarkup } from "react-dom/server"
import { describe, expect, it } from "vitest"
import { InviteLanding } from "@/routes/i"

const RELEASES = "https://github.com/Mostafa-DE/KJB/releases/latest"

describe("GET /i (the invite landing page)", () => {
  it("shows the code to copy and opens KJB with the same link", () => {
    const html = renderToStaticMarkup(<InviteLanding code="AbC-123_x.YZ" host="kjb-huddle.up.railway.app" />)

    expect(html).toContain("AbC-123_x.YZ")
    expect(html).toContain(
      `href="intent://kjb-huddle.up.railway.app/i#AbC-123_x.YZ#Intent;scheme=https;package=app.lyrify;` +
        `S.browser_fallback_url=${encodeURIComponent(RELEASES)};end"`,
    )
    expect(html).toContain(`href="${RELEASES}"`)
    expect(html).toContain("Copy code")
    expect(html).toContain("Paste invite")
  })

  it.each(["", "abc#Intent;S.extra=1", "a b", "x".repeat(1025)])("refuses a broken code: %s", (code) => {
    const html = renderToStaticMarkup(<InviteLanding code={code} host="huddle.kjb.mostafade.com" />)

    expect(html).toContain("This invite link is incomplete")
    expect(html).not.toContain("intent://")
    expect(html).toContain(`href="${RELEASES}"`)
  })

  it("still offers the download before the code is read", () => {
    const html = renderToStaticMarkup(<InviteLanding code={undefined} host="huddle.kjb.mostafade.com" />)

    expect(html).toContain(`href="${RELEASES}"`)
    expect(html).not.toContain("intent://")
    expect(html).not.toContain("This invite link is incomplete")
  })
})
