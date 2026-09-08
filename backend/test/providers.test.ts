import { describe, expect, it } from "vitest";
import { OpenAiCompatProvider, ProviderChain, ProviderError, providersFromEnv } from "../src/providers";

const messages = [{ role: "user" as const, content: "hi" }];

function fakeFetch(handler: (url: string) => Response | Promise<Response>): typeof fetch {
  return (async (input: RequestInfo | URL) => handler(String(input))) as typeof fetch;
}

const okBody = (text: string) => JSON.stringify({ choices: [{ message: { content: text } }] });

describe("OpenAiCompatProvider", () => {
  it("posts to /chat/completions and returns content", async () => {
    let seenUrl = "";
    const p = new OpenAiCompatProvider({
      name: "t", baseUrl: "https://x/v1", apiKey: "k", model: "m",
      fetchFn: fakeFetch((url) => { seenUrl = url; return new Response(okBody("yo")); }),
    });
    expect(await p.complete(messages)).toBe("yo");
    expect(seenUrl).toBe("https://x/v1/chat/completions");
  });

  it("throws ProviderError on non-2xx", async () => {
    const p = new OpenAiCompatProvider({
      name: "t", baseUrl: "https://x/v1", apiKey: "k", model: "m",
      fetchFn: fakeFetch(() => new Response("rate limited", { status: 429 })),
    });
    await expect(p.complete(messages)).rejects.toBeInstanceOf(ProviderError);
  });
});

describe("ProviderChain", () => {
  it("falls back to the next provider and reports the source", async () => {
    const failing = { name: "a", complete: async () => { throw new ProviderError("a", 500, "boom"); } };
    const working = { name: "b", complete: async () => "ok" };
    const failures: string[] = [];
    const chain = new ProviderChain([failing, working], (e) => failures.push(e.provider));
    expect(await chain.completeWithSource(messages)).toEqual({ text: "ok", provider: "b" });
    expect(failures).toEqual(["a"]);
  });

  it("rethrows the last error when every provider fails", async () => {
    const failing = (n: string) => ({ name: n, complete: async () => { throw new ProviderError(n, 500, "x"); } });
    const chain = new ProviderChain([failing("a"), failing("b")]);
    await expect(chain.complete(messages)).rejects.toMatchObject({ provider: "b" });
  });
});

describe("providersFromEnv", () => {
  it("honours PROVIDER_ORDER and skips vendors without keys", () => {
    const ps = providersFromEnv({
      PROVIDER_ORDER: "xai,openai,gemini",
      XAI_API_KEY: "k", XAI_MODEL: "grok",
      GEMINI_API_KEY: "k", GEMINI_MODEL: "gem",
    });
    expect(ps.map((p) => p.name)).toEqual(["xai", "gemini"]);
  });

  it("expands a comma-separated model list into one provider per model", () => {
    const ps = providersFromEnv({
      PROVIDER_ORDER: "gemini,openrouter",
      GEMINI_API_KEY: "k", GEMINI_MODEL: "lite-a, lite-b,unlimited-c",
      OPENROUTER_API_KEY: "k", OPENROUTER_MODEL: "grok",
    });
    expect(ps.map((p) => p.name)).toEqual(["gemini:lite-a", "gemini:lite-b", "gemini:unlimited-c", "openrouter"]);
  });
});
