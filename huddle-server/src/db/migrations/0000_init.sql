CREATE TABLE "invites" (
	"invite_hash" text PRIMARY KEY NOT NULL,
	"owner_hash" text NOT NULL,
	"data" text,
	"created_at" timestamp with time zone NOT NULL,
	"accepted_at" timestamp with time zone,
	"expires_at" timestamp with time zone NOT NULL,
	CONSTRAINT "invites_owner_hash_unique" UNIQUE("owner_hash")
);
--> statement-breakpoint
CREATE TABLE "messages" (
	"id" bigserial PRIMARY KEY NOT NULL,
	"mailbox_hash" text NOT NULL,
	"kind" text NOT NULL,
	"data" text NOT NULL,
	"size" integer NOT NULL,
	"sent_at" timestamp with time zone NOT NULL,
	"expires_at" timestamp with time zone NOT NULL,
	CONSTRAINT "messages_kind_check" CHECK ("messages"."kind" in ('card', 'play', 'profile'))
);
--> statement-breakpoint
CREATE INDEX "invites_expires_idx" ON "invites" USING btree ("expires_at");--> statement-breakpoint
CREATE INDEX "messages_mailbox_idx" ON "messages" USING btree ("mailbox_hash","id");--> statement-breakpoint
CREATE INDEX "messages_expires_idx" ON "messages" USING btree ("expires_at");