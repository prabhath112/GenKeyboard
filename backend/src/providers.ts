/**
 * Strategy pattern over OpenAI-compatible chat completion APIs.
 * xAI, OpenAI, Gemini, Anthropic and OpenRouter all speak this wire format,
 * so one HTTP implementation covers every vendor. ProviderChain adds fallback.
 */
import type { ChatMessage } from "./actions";

export interface ChatProvider {
  readonly name: string;
  complete(messages: ChatMessage[], signal?: AbortSignal): Promise<string>;
}

export class ProviderError extends Error {
  constructor(
    readonly provider: string,
    readonly status: number | undefined,
    message: string,
  ) {
    super(`${provider}: ${message}`);
  }
}

export interface OpenAiCompatOptions {
  name: string;
  baseUrl: string;
  apiKey: string;
  model: string;
  fetchFn?: typeof fetch;
  extraHeaders?: Record<string, string>;
  extraBody?: Record<string, unknown>;
}

export class OpenAiCompatProvider implements ChatProvider {
  readonly name: string;
  private readonly fetchFn: typeof fetch;

  constructor(private readonly opts: OpenAiCompatOptions) {
    this.name = opts.name;
    // Wrap global fetch: calling it via a class field binds `this` to the provider, which workerd rejects.
    this.fetchFn = opts.fetchFn ?? ((input, init) => fetch(input, init));
  }

  async complete(messages: ChatMessage[], signal?: AbortSignal): Promise<string> {
    let res: Response;
    try {
      res = await this.fetchFn(`${this.opts.baseUrl}/chat/completions`, {
        method: "POST",
        signal,
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${this.opts.apiKey}`,
          ...this.opts.extraHeaders,
        },
        body: JSON.stringify({
          model: this.opts.model,
          messages,
          temperature: 0.4,
          // Cap output: OpenRouter bills/reserves the model max (65k) without it and rejects free-tier keys with 402.
          max_tokens: 1024,
          ...this.opts.extraBody,
        }),
      });
    } catch (e) {
      throw new ProviderError(this.name, undefined, (e as Error).message);
    }

    if (!res.ok) {
      const body = (await res.text()).slice(0, 300);
      throw new ProviderError(this.name, res.status, `HTTP ${res.status} ${body}`);
    }

    const json = (await res.json()) as {
      choices?: Array<{ message?: { content?: string | null } }>;
    };
    const content = json.choices?.[0]?.message?.content;
    if (typeof content !== "string" || content.trim().length === 0) {
      throw new ProviderError(this.name, res.status, "empty completion");
    }
    return content;
  }
}

export interface ChainResult {
  text: string;
  provider: string;
}

/** Tries providers in order; returns the first success. Throws the last error if all fail. */
export class ProviderChain implements ChatProvider {
  readonly name = "chain";

  constructor(
    private readonly providers: ChatProvider[],
    private readonly onFailure: (err: ProviderError) => void = () => {},
    private readonly perProviderTimeoutMs?: number,
  ) {
    if (providers.length === 0) throw new Error("ProviderChain requires at least one provider");
  }

  async complete(messages: ChatMessage[], signal?: AbortSignal): Promise<string> {
    return (await this.completeWithSource(messages, signal)).text;
  }

  async completeWithSource(messages: ChatMessage[], signal?: AbortSignal): Promise<ChainResult> {
    let lastError: unknown;
    for (const p of this.providers) {
      try {
        const perProvider = this.perProviderTimeoutMs
          ? AbortSignal.any([...(signal ? [signal] : []), AbortSignal.timeout(this.perProviderTimeoutMs)])
          : signal;
        const text = await p.complete(messages, perProvider);
        return { text, provider: p.name };
      } catch (e) {
        lastError = e;
        if (e instanceof ProviderError) this.onFailure(e);
        if (signal?.aborted) break;
      }
    }
    throw lastError;
  }
}

export interface ProviderEnv {
  PROVIDER_ORDER?: string;
  XAI_API_KEY?: string;
  XAI_MODEL?: string;
  OPENAI_API_KEY?: string;
  OPENAI_MODEL?: string;
  GEMINI_API_KEY?: string;
  GEMINI_MODEL?: string;
  GEMINI_FALLBACK_MODEL?: string;
  ANTHROPIC_API_KEY?: string;
  ANTHROPIC_MODEL?: string;
  OPENROUTER_API_KEY?: string;
  OPENROUTER_MODEL?: string;
}

interface VendorSpec {
  baseUrl: string;
  keyVar: keyof ProviderEnv;
  modelVar: keyof ProviderEnv;
  extraHeaders?: Record<string, string>;
  extraBody?: Record<string, unknown>;
}

const VENDORS: Record<string, VendorSpec> = {
  xai: { baseUrl: "https://api.x.ai/v1", keyVar: "XAI_API_KEY", modelVar: "XAI_MODEL" },
  openai: { baseUrl: "https://api.openai.com/v1", keyVar: "OPENAI_API_KEY", modelVar: "OPENAI_MODEL" },
  gemini: {
    baseUrl: "https://generativelanguage.googleapis.com/v1beta/openai",
    keyVar: "GEMINI_API_KEY",
    modelVar: "GEMINI_MODEL",
    // Gemini 3.x thinks before answering; low effort keeps a keyboard round trip in the ~2s range.
    extraBody: { extra_body: { google: { thinking_config: { thinking_level: "minimal" } } } },
  },
  // Same key, second model. Free tier caps each model per day, so a second Gemini model doubles headroom
  // before we fall through to a paid vendor.
  gemini_lite: {
    baseUrl: "https://generativelanguage.googleapis.com/v1beta/openai",
    keyVar: "GEMINI_API_KEY",
    modelVar: "GEMINI_FALLBACK_MODEL",
    extraBody: { extra_body: { google: { thinking_config: { thinking_level: "minimal" } } } },
  },
  anthropic: { baseUrl: "https://api.anthropic.com/v1", keyVar: "ANTHROPIC_API_KEY", modelVar: "ANTHROPIC_MODEL" },
  openrouter: {
    baseUrl: "https://openrouter.ai/api/v1",
    keyVar: "OPENROUTER_API_KEY",
    modelVar: "OPENROUTER_MODEL",
    extraHeaders: { "X-Title": "GenKeyboard" },
  },
};

/** Builds providers from env in PROVIDER_ORDER. Vendors with no key are skipped silently. */
export function providersFromEnv(env: ProviderEnv, fetchFn?: typeof fetch): ChatProvider[] {
  const order = (env.PROVIDER_ORDER ?? Object.keys(VENDORS).join(","))
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
  const out: ChatProvider[] = [];
  for (const name of order) {
    const spec = VENDORS[name];
    if (!spec) continue;
    const apiKey = env[spec.keyVar];
    const model = env[spec.modelVar];
    if (!apiKey || !model) continue;
    out.push(
      new OpenAiCompatProvider({ name, baseUrl: spec.baseUrl, apiKey, model, fetchFn, extraHeaders: spec.extraHeaders, extraBody: spec.extraBody }),
    );
  }
  return out;
}
