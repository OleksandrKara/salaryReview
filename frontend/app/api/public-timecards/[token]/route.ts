const BACKEND = process.env.BACKEND_URL ?? 'http://localhost:8080';

// Public, token-gated: no session is forwarded. The signed token is the only credential.
export async function GET(_req: Request, ctx: { params: Promise<{ token: string }> }): Promise<Response> {
  const { token } = await ctx.params;
  const res = await fetch(`${BACKEND}/api/public/timecards/${encodeURIComponent(token)}`, { cache: 'no-store' });
  return new Response(await res.text(), { status: res.status, headers: { 'Content-Type': 'application/json' } });
}
