import { serverApi } from '../../lib/serverApi';
import PayrollPeriods from '../PayrollPeriods';

export default async function Page() {
  const me = await serverApi.getMe();
  return <PayrollPeriods lang={me.preferredLanguage ?? 'EN'} owner={me.role === 'OWNER'} />;
}
