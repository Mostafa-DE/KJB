import { defineRailway, github, postgres, project, service } from "railway/iac"

// Railway Infrastructure as Code for Huddle (railway.json is deprecated). The user applies it with
// `railway config plan` and `railway config apply` from huddle-server/; see the deploy steps in README.md.
export default defineRailway(() => {
  const database = postgres("postgres")
  const huddle = service("huddle", {
    source: github("Mostafa-DE/KJB", { branch: "main", rootDirectory: "huddle-server" }),
    build: { builder: "RAILPACK", buildCommand: "pnpm build", watchPatterns: ["/huddle-server/**"] },
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
