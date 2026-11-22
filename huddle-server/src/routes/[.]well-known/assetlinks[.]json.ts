import { createFileRoute } from "@tanstack/react-router"
import { KJB_PACKAGE, KJB_RELEASE_KEY } from "@/lib/kjb"

// The same answer on every host this server is reached by (the Railway address and huddle.kjb.mostafade.com).
const ASSET_LINKS = [
  {
    relation: ["delegate_permission/common.handle_all_urls"],
    target: { namespace: "android_app", package_name: KJB_PACKAGE, sha256_cert_fingerprints: [KJB_RELEASE_KEY] },
  },
]

export function handleAssetLinks() {
  return Response.json(ASSET_LINKS, { headers: { "Cache-Control": "public, max-age=3600" } })
}

export const Route = createFileRoute("/.well-known/assetlinks.json")({
  server: {
    handlers: {
      GET: () => handleAssetLinks(),
    },
  },
})
