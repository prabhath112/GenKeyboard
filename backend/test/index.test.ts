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

describe("transform timing", () => {
  function memoryKv() {
    const m = new Map<string, string>();
    return { get: async (k: string) => m.get(k) ?? null, put: async (k: string, v: string) => { m.set(k, v); } };
  }

  it("answers with a Server-Timing header naming the model that answered", async () => {
    const realFetch = globalThis.fetch;
    globalThis.fetch = (async () =>
      new Response(JSON.stringify({ choices: [{ message: { content: "Fixed text." } }] }))) as typeof fetch;
    try {
      const res = await worker.fetch(
        new Request("https://x/v1/transform", {
          method: "POST",
          body: JSON.stringify({ action: "grammar", text: "i has went" }),
          headers: { "X-Device-Id": DEVICE, "Content-Type": "application/json" },
        }),
        { QUOTA: memoryKv(), GEMINI_API_KEY: "k", GEMINI_MODEL: "m", PROVIDER_ORDER: "gemini", HEDGE_AFTER_MS: "2500" } as unknown as Env,
        ctx,
      );
      expect(res.status).toBe(200);
      expect(await res.json()).toMatchObject({ text: "Fixed text.", provider: "gemini" });
      expect(res.headers.get("Server-Timing")).toMatch(/^total;dur=\d+, gemini;desc=ok;dur=\d+$/);
    } finally {
      globalThis.fetch = realFetch;
    }
  });
});
