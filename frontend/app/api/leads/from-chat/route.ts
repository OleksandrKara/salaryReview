import { forwardToBackend } from '../../../lib/proxyBackend';

export async function POST(req: Request): Promise<Response> {
  return forwardToBackend('/api/leads/from-chat', 'POST', await req.text());
}
