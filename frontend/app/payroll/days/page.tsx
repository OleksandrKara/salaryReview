import { serverApi } from '../../lib/serverApi';
import PayrollDays from '../PayrollDays';

export default async function Page() {
  const me = await serverApi.getMe();
  return <PayrollDays lang={me.preferredLanguage ?? 'EN'} owner={me.role === 'OWNER'} />;
}
