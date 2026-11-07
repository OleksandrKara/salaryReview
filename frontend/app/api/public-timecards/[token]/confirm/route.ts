const BACKEND = process.env.BACKEND_URL ?? 'http://localhost:8080';

export async function POST(req: Request, ctx: { params: Promise<{ token: string }> }): Promise<Response> {
  const { token } = await ctx.params;
  const body = (await req.text()) || '{}';
  const res = await fetch(`${BACKEND}/api/public/timecards/${encodeURIComponent(token)}/confirm`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body,
    cache: 'no-store',
  });
  return new Response(await res.text(), { status: res.status, headers: { 'Content-Type': 'application/json' } });
}
