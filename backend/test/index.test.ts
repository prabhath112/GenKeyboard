import { describe, expect, it } from "vitest";
import worker, { type Env } from "../src/index";

const DEVICE = "123e4567-e89b-12d3-a456-426614174000";
const ctx = { waitUntil() {}, passThroughOnException() {} } as unknown as ExecutionContext;
const env = {} as Env;

function post(body: string, headers: Record<string, string> = {}) {
  return worker.fetch(
    new Request("https://x/v1/transform", { method: "POST", body, headers: { "X-Device-Id": DEVICE, ...headers } }),
    env,
    ctx,
  );
}

describe("request body guards", () => {
  it("rejects a non-JSON content type", async () => {
    expect((await post("{}", { "Content-Type": "text/plain" })).status).toBe(415);
  });
  it("rejects an oversized body", async () => {
    const res = await post(JSON.stringify({ text: "a".repeat(70_000) }), { "Content-Type": "application/json" });
    expect(res.status).toBe(413);
  });
  it("rejects malformed JSON", async () => {
    expect((await post("{nope", { "Content-Type": "application/json" })).status).toBe(400);
  });
});
