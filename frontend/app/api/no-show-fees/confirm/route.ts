import { forwardToBackend } from '../../../lib/proxyBackend';

// Credit a provider for a fee collected off-signal (cash / quick-sale / paid > 2 months later).
export async function POST(req: Request): Promise<Response> {
  return forwardToBackend('/api/no-show-fees/confirm', 'POST', await req.text());
}
