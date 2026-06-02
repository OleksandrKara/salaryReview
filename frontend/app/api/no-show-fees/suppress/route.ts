import { forwardToBackend } from '../../../lib/proxyBackend';

// Do not credit an auto-detected fee (false positive / disputed).
export async function POST(req: Request): Promise<Response> {
  const qs = new URL(req.url).searchParams.toString();
  return forwardToBackend(`/api/no-show-fees/suppress?${qs}`, 'POST');
}
