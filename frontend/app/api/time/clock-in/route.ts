import { forwardToBackend } from '../../../lib/proxyBackend';

export const POST = () => forwardToBackend('/api/time/clock-in', 'POST', '{}');
