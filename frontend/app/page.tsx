import { cookies } from 'next/headers';
import { redirect } from 'next/navigation';
import Landing from './Landing';

// Homepage. Signed-in users are sent to their app (providers → /me, staff → /reports); everyone else
// sees the AK.LUX.STUDIO landing with its sign-in modal. There is no separate /login route.
export default async function Home() {
  const jar = await cookies();
  if (jar.get('sid')) {
    redirect(jar.get('role')?.value === 'PROVIDER' ? '/me' : '/reports');
  }
  return <Landing />;
}
