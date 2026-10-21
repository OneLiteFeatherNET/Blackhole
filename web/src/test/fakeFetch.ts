export interface Recorded {
  url: string
  method: string
  headers: Headers
  body: string
}

/** A fetch that records requests and answers from a queue; no network, no timers. */
export function fakeFetch(responses: Array<{ status?: number; body?: unknown } | Error>) {
  const calls: Recorded[] = []
  const impl = async (input: Request | string | URL, init?: RequestInit): Promise<Response> => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), 'http://ui.test'), init)
    calls.push({
      url: request.url,
      method: request.method,
      headers: request.headers,
      body: await request.clone().text(),
    })
    const next = responses.shift()
    if (next instanceof Error) throw next
    return new Response(next?.body === undefined ? null : JSON.stringify(next.body), {
      status: next?.status ?? 200,
      headers: { 'Content-Type': 'application/json' },
    })
  }
  return { calls, impl: impl as unknown as typeof fetch }
}
