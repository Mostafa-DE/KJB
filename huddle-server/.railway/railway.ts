import { defineRailway, postgres, project, service } from "railway/iac"

// Railway Infrastructure as Code for Huddle (railway.json is deprecated), applied with `railway config plan` and
// `railway config apply` from huddle-server/. The service has no repository: `railway up` from huddle-server/
// uploads the code and deploys it. README.md has the steps.
export default defineRailway(() => {
  const database = postgres("postgres")
  const huddle = service("huddle", {
    build: { builder: "RAILPACK", buildCommand: "pnpm build" },
    start: "pnpm start",
    preDeploy: "pnpm db:migrate",
    healthcheck: "/health",
    healthcheckTimeout: 120,
    // The rate limits are counted in the server's memory (src/lib/api.ts): one replica only.
    replicas: 1,
    deploy: { restartPolicyType: "ON_FAILURE", restartPolicyMaxRetries: 5, sleepApplication: false },
    env: { DATABASE_URL: database.env.DATABASE_URL },
  })
  return project("kjb-huddle", { resources: [database, huddle] })
})
