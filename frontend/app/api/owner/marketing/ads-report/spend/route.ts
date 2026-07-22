import { forwardToBackend } from '../../../../../lib/proxyBackend';

// Ad-spend-entry create + list — edit/delete of an existing row live in ./[id]/route.ts. OWNER+
// ADS_MANAGER can both call these (the backend enforces that — see SecurityConfig).
export async function POST(req: Request): Promise<Response> {
  return forwardToBackend('/api/owner/marketing/ads-report/spend', 'POST', await req.text());
}

export const GET = (req: Request) => {
  const slug = new URL(req.url).searchParams.get('slug');
  const qs = slug ? `?slug=${encodeURIComponent(slug)}` : '';
  return forwardToBackend(`/api/owner/marketing/ads-report/spend${qs}`, 'GET');
};
