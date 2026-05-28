'use client';

import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { api } from '../../lib/api';
import type { AppUser, Provider, Role } from '../../lib/types';

const ROLES: Role[] = ['OWNER', 'MANAGER', 'PROVIDER'];

export default function UsersManager({
  initialUsers,
  providers,
}: {
  initialUsers: AppUser[];
  providers: Provider[];
}) {
  const router = useRouter();
  const [users, setUsers] = useState(initialUsers);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [role, setRole] = useState<Role>('MANAGER');
  const [providerId, setProviderId] = useState<number | ''>('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  // A provider can only be linked once; hide those already taken (except keep all for clarity).
  const linkedProviderIds = new Set(users.map((u) => u.providerId).filter(Boolean));
  const providerName = (id: number | null) =>
    id == null ? '—' : providers.find((p) => p.id === id)?.displayName ?? `#${id}`;

  async function refresh() {
    router.refresh();
  }

  async function create(e: React.FormEvent) {
    e.preventDefault();
    setError('');
    setBusy(true);
    try {
      const created = await api.createUser({
        username,
        password,
        role,
        providerId: role === 'PROVIDER' ? Number(providerId) : null,
      });
      setUsers((prev) => [...prev, created].sort((a, b) => a.username.localeCompare(b.username)));
      setUsername('');
      setPassword('');
      setProviderId('');
      setRole('MANAGER');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to create user');
    } finally {
      setBusy(false);
    }
  }

  async function toggleActive(u: AppUser) {
    const updated = await api.updateUser(u.id, { active: !u.active });
    setUsers((prev) => prev.map((x) => (x.id === u.id ? updated : x)));
  }

  async function remove(u: AppUser) {
    if (!confirm(`Delete ${u.username}? This cannot be undone.`)) return;
    try {
      await api.deleteUser(u.id);
      setUsers((prev) => prev.filter((x) => x.id !== u.id));
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to delete');
    }
  }

  const needsProvider = role === 'PROVIDER';

  return (
    <div className="flex flex-col gap-8">
      <form onSubmit={create} className="flex flex-wrap items-end gap-3 rounded-lg ring-1 ring-zinc-200 p-4">
        <label className="text-sm">
          <span className="mb-1 block text-zinc-600">Username</span>
          <input value={username} onChange={(e) => setUsername(e.target.value)} required
            className="w-40 rounded border border-zinc-300 px-2 py-1.5" autoComplete="off" />
        </label>
        <label className="text-sm">
          <span className="mb-1 block text-zinc-600">Temp password</span>
          <input value={password} onChange={(e) => setPassword(e.target.value)} required
            className="w-40 rounded border border-zinc-300 px-2 py-1.5" autoComplete="new-password" />
        </label>
        <label className="text-sm">
          <span className="mb-1 block text-zinc-600">Role</span>
          <select value={role} onChange={(e) => setRole(e.target.value as Role)}
            className="rounded border border-zinc-300 px-2 py-1.5">
            {ROLES.map((r) => <option key={r} value={r}>{r}</option>)}
          </select>
        </label>
        {needsProvider && (
          <label className="text-sm">
            <span className="mb-1 block text-zinc-600">Provider</span>
            <select value={providerId} onChange={(e) => setProviderId(Number(e.target.value) || '')} required
              className="rounded border border-zinc-300 px-2 py-1.5">
              <option value="">Select…</option>
              {providers.map((p) => (
                <option key={p.id} value={p.id} disabled={linkedProviderIds.has(p.id)}>
                  {p.displayName}{linkedProviderIds.has(p.id) ? ' (linked)' : ''}
                </option>
              ))}
            </select>
          </label>
        )}
        <button type="submit" disabled={busy}
          className="rounded bg-zinc-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-50">
          {busy ? 'Adding…' : 'Add user'}
        </button>
      </form>

      {error && <p className="text-sm text-red-600">{error}</p>}

      <div className="overflow-x-auto rounded-lg ring-1 ring-zinc-200">
        <table className="w-full text-sm">
          <thead className="bg-zinc-50 text-left text-xs uppercase tracking-wide text-zinc-500">
            <tr>
              <th className="px-3 py-2">Username</th>
              <th className="px-3 py-2">Role</th>
              <th className="px-3 py-2">Provider</th>
              <th className="px-3 py-2">Status</th>
              <th className="px-3 py-2 text-right">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-zinc-100">
            {users.map((u) => (
              <tr key={u.id} className="hover:bg-zinc-50">
                <td className="px-3 py-2 font-medium">{u.username}</td>
                <td className="px-3 py-2">{u.role}</td>
                <td className="px-3 py-2 text-zinc-600">{providerName(u.providerId)}</td>
                <td className="px-3 py-2">
                  {u.active
                    ? <span className="text-green-700">active</span>
                    : <span className="text-zinc-400">disabled</span>}
                </td>
                <td className="px-3 py-2 text-right">
                  <button onClick={() => toggleActive(u)} className="mr-3 text-xs text-zinc-500 hover:text-zinc-800">
                    {u.active ? 'Disable' : 'Enable'}
                  </button>
                  <button onClick={() => remove(u)} className="text-xs text-red-500 hover:text-red-700">Delete</button>
                </td>
              </tr>
            ))}
            {users.length === 0 && (
              <tr><td colSpan={5} className="px-3 py-6 text-center text-zinc-400">No users yet.</td></tr>
            )}
          </tbody>
        </table>
      </div>
      <p className="text-xs text-zinc-400">
        Providers self-view their own numbers at <code>/me</code> and can approve or request a
        correction. <button onClick={refresh} className="underline">Refresh</button>
      </p>
    </div>
  );
}
