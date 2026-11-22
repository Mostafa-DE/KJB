import { execFile } from "node:child_process"
import { promisify } from "node:util"
import { PGlite } from "@electric-sql/pglite"
import { PGLiteSocketServer } from "@electric-sql/pglite-socket"

let database: PGlite | undefined
let server: PGLiteSocketServer | undefined

// An in-memory Postgres (PGlite) behind a real socket, so the tests use the same `pg` driver and the same
// `drizzle-kit migrate` step as Railway, without Docker.
export async function setup() {
  database = await PGlite.create()
  server = new PGLiteSocketServer({ db: database, host: "127.0.0.1", port: 0, maxConnections: 10 })
  await server.start()
  const address = server.getServerConn()
  process.env.DATABASE_URL = `postgresql://postgres:postgres@${address}/postgres?sslmode=disable`

  // Async on purpose: the database answers from this same process, so a blocking exec would wait on itself.
  await promisify(execFile)("pnpm", ["--silent", "db:migrate"], { env: process.env })
}

export async function teardown() {
  await server?.stop()
  await database?.close()
}
