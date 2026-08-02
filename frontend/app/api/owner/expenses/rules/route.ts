import { forwardToBackend } from '../../../../lib/proxyBackend';

// GET /api/owner/expenses/rules — every learned merchant rule (owner).
export async function GET(): Promise<Response> {
  return forwardToBackend('/api/owner/expenses/rules', 'GET');
}
