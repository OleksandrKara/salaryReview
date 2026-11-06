'use client';

import { useState } from 'react';
import { api } from '../../lib/api';
import type { PermissionsOverview } from '../../lib/types';

const LABELS: Record<string, { title: string; hint: string }> = {
  SMS_INBOX: {
    title: 'SMS inbox',
    hint: 'See and send client text messages for this business (all clients, same as a manager).',
  },
  LEADS_ALL: {
    title: 'CRM: all leads',
    hint: 'Open the Leads CRM and manage every lead of this business (statuses, notes, callbacks).',
  },
  LEADS_ACADEMY: {
    title: 'CRM: academy leads only',
    hint: 'Open the Leads CRM and see and manage only academy leads. Other leads stay hidden.',
  },
};

export default function PermissionsManager({ initial }: { initial: PermissionsOverview }) {
  const [users, setUsers] = useState(initial.users);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState('');

  const LEADS_PAIR: Record<string, string> = { LEADS_ALL: 'LEADS_ACADEMY', LEADS_ACADEMY: 'LEADS_ALL' };

  async function toggle(userId: number, permission: string, granted: boolean) {
    const key = `${userId}:${permission}`;
    setBusy(key);
    setError('');
    try {
      // "all leads" and "academy only" exclude each other, so granting one drops the other.
      const other = LEADS_PAIR[permission];
      const dropOther = granted && other && users.find((u) => u.userId === userId)?.granted.includes(other);
      if (dropOther) await api.setUserPermission({ userId, permission: other, granted: false });
      await api.setUserPermission({ userId, permission, granted });
      setUsers((prev) =>
        prev.map((u) => {
          if (u.userId !== userId) return u;
          let next = u.granted.filter((p) => p !== permission && !(dropOther && p === other));
          if (granted) next = [...next, permission];
          return { ...u, granted: next };
        }),
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not save');
    } finally {
      setBusy(null);
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <p className="text-sm text-zinc-600">
        Extra access for individual people in this business. Owners and managers already have everything that applies to
        their role.
      </p>
      {error ? <p className="text-sm text-red-600">{error}</p> : null}
      <ul className="flex flex-col divide-y divide-zinc-200 rounded-lg ring-1 ring-zinc-200">
        {users.map((u) => (
          <li key={u.userId} className="flex flex-col gap-2 p-4">
            <div className="flex items-baseline gap-2">
              <span className="font-medium">{u.displayName || u.username}</span>
              <span className="text-xs text-zinc-500">{u.role}</span>
            </div>
            {initial.permissions.filter((perm) => !(perm === 'LEADS_ACADEMY' && u.implied.includes('LEADS_ALL'))).map((perm) => {
              const implied = u.implied.includes(perm);
              const checked = implied || u.granted.includes(perm);
              const info = LABELS[perm] ?? { title: perm, hint: '' };
              return (
                <label key={perm} className="flex items-start gap-3 text-sm">
                  <input
                    type="checkbox"
                    className="mt-1 h-4 w-4"
                    checked={checked}
                    disabled={implied || busy === `${u.userId}:${perm}`}
                    onChange={(e) => toggle(u.userId, perm, e.target.checked)}
                  />
                  <span>
                    <span className="font-medium">{info.title}</span>
                    {implied ? <span className="ml-2 text-xs text-zinc-500">included in role</span> : null}
                    <span className="block text-xs text-zinc-500">{info.hint}</span>
                  </span>
                </label>
              );
            })}
          </li>
        ))}
        {users.length === 0 ? <li className="p-4 text-sm text-zinc-500">No users.</li> : null}
      </ul>
    </div>
  );
}
