import { forwardToBackend } from '../../../../../lib/proxyBackend';

// Ad-spend-entry CRUD (no D, no U — a correction is a new row, see AdSpendResolver). OWNER+
// ADS_MANAGER can both call these (the backend enforces that — see SecurityConfig).
export async function POST(req: Request): Promise<Response> {
  return forwardToBackend('/api/owner/marketing/ads-report/spend', 'POST', await req.text());
}

export const GET = (req: Request) => {
  const slug = new URL(req.url).searchParams.get('slug');
  const qs = slug ? `?slug=${encodeURIComponent(slug)}` : '';
  return forwardToBackend(`/api/owner/marketing/ads-report/spend${qs}`, 'GET');
};
