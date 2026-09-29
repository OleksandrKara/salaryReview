import { forwardToBackend } from '../../../../../lib/proxyBackend';

export async function GET(): Promise<Response> {
  return forwardToBackend('/api/owner/automations/activity/provider-schedule-changes', 'GET');
}
