import { renderToStaticMarkup } from "react-dom/server"
import { describe, expect, it } from "vitest"
import { HomePage } from "@/routes/index"

describe("GET /", () => {
  it("says what this server is and links to KJB", () => {
    const html = renderToStaticMarkup(<HomePage />)

    expect(html).toContain("KJB Huddle")
    expect(html).toContain(`href="https://github.com/Mostafa-DE/KJB/releases/latest"`)
  })
})
