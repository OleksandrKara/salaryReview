import type { Metadata } from 'next';
import IntakeFormClient from './IntakeFormClient';

export const metadata: Metadata = {
  title: 'Client intake form',
  robots: { index: false, follow: false },
  referrer: 'no-referrer',
};

// Public, token-protected page opened from the SMS link. No login; the token is the only key.
export default async function IntakePage({ params }: { params: Promise<{ token: string }> }) {
  const { token } = await params;
  return <IntakeFormClient token={token} />;
}
