import { forwardToBackend } from '../../../../../lib/proxyBackend';

// POST /api/owner/automations/activity/reply — a manager/owner's freeform reply, bypassing
// templates and automation/consent gating entirely (design.md D9).
export async function POST(req: Request): Promise<Response> {
  const body = await req.text();
  return forwardToBackend('/api/owner/automations/activity/reply', 'POST', body);
}
