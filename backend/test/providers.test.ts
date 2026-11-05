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

describe("ProviderChain racing (hedgeAfterMs)", () => {
  // A provider that answers after `ms`, or rejects the moment it is aborted. Records both.
  function timed(name: string, ms: number, text = name) {
    const state = { started: false, aborted: false };
    const provider = {
      name,
      complete: (_m: unknown, signal?: AbortSignal) => {
        state.started = true;
        return new Promise<string>((resolve, reject) => {
          const t = setTimeout(() => resolve(text), ms);
          signal?.addEventListener("abort", () => {
            state.aborted = true;
            clearTimeout(t);
            reject(new ProviderError(name, undefined, "aborted"));
          });
        });
      },
    };
    return { provider, state };
  }

  it("lets a fast second model win while the first is still slow, and cancels the first", async () => {
    const slow = timed("slow", 400);
    const fast = timed("fast", 10);
    const chain = new ProviderChain([slow.provider, fast.provider], undefined, undefined, undefined, 30);
    const t0 = Date.now();
    expect(await chain.completeWithSource(messages)).toEqual({ text: "fast", provider: "fast" });
    expect(Date.now() - t0).toBeLessThan(300);
    expect(slow.state.aborted).toBe(true);
    expect(chain.attempts.map((a) => `${a.provider}:${a.outcome}`)).toEqual(["fast:ok", "slow:cancelled"]);
  });

  it("does not start the second model when the first answers before the hedge delay", async () => {
    const first = timed("first", 5);
    const second = timed("second", 5);
    const chain = new ProviderChain([first.provider, second.provider], undefined, undefined, undefined, 100);
    expect((await chain.completeWithSource(messages)).provider).toBe("first");
    expect(second.state.started).toBe(false);
  });

  it("starts the next model at once when the previous one fails, without waiting for the hedge delay", async () => {
    const failing = { name: "bad", complete: async () => { throw new ProviderError("bad", 500, "x"); } };
    const good = timed("good", 5);
    const chain = new ProviderChain([failing, good.provider], undefined, undefined, undefined, 5000);
    const t0 = Date.now();
    expect((await chain.completeWithSource(messages)).provider).toBe("good");
    expect(Date.now() - t0).toBeLessThan(1000);
  });

  it("is strictly sequential when hedging is off", async () => {
    const first = timed("first", 60);
    const second = timed("second", 5);
    const chain = new ProviderChain([first.provider, second.provider]);
    expect((await chain.completeWithSource(messages)).provider).toBe("first");
    expect(second.state.started).toBe(false);
  });

  it("counts a cancelled model against its budget", async () => {
    const recorded: string[] = [];
    const gate = { allow: async () => true, record: async (n: string) => { recorded.push(n); } };
    const slow = timed("slow", 400);
    const fast = timed("fast", 10);
    const chain = new ProviderChain([slow.provider, fast.provider], undefined, undefined, gate, 30);
    await chain.completeWithSource(messages);
    expect(recorded.sort()).toEqual(["fast", "slow"]);
  });

  it("rethrows the last error when every raced model fails", async () => {
    const failing = (n: string) => ({ name: n, complete: async () => { throw new ProviderError(n, 500, "x"); } });
    const chain = new ProviderChain([failing("a"), failing("b")], undefined, undefined, undefined, 20);
    await expect(chain.complete(messages)).rejects.toMatchObject({ provider: "b" });
  });
});
