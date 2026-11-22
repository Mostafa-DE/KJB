import { describe, expect, it } from "vitest"
import { handleAssetLinks } from "./assetlinks[.]json"

const KJB_RELEASE_KEY =
  "A8:79:79:2B:1F:5E:F0:2D:9E:2E:50:4B:5F:15:4F:95:4E:45:FD:72:B1:94:CB:0F:6F:8C:F9:5F:7B:C7:6E:6C"

describe("GET /.well-known/assetlinks.json", () => {
  it("lets KJB, signed with its release key, open Huddle links", async () => {
    const answer = handleAssetLinks()

    expect(answer.status).toBe(200)
    expect(answer.headers.get("Content-Type")).toBe("application/json")
    expect(await answer.json()).toEqual([
      {
        relation: ["delegate_permission/common.handle_all_urls"],
        target: {
          namespace: "android_app",
          package_name: "app.lyrify",
          sha256_cert_fingerprints: [KJB_RELEASE_KEY],
        },
      },
    ])
  })
})
