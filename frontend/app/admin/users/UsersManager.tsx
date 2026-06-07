'use client';

import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { api } from '../../lib/api';
import { Spinner } from '../../components/Spinner';
import type { AppUser, Provider, Role, SquareRosterEntry } from '../../lib/types';

const ROLES: Role[] = ['OWNER', 'MANAGER', 'PROVIDER'];

export default function UsersManager({
  initialUsers,
  providers,
  roster,
}: {
  initialUsers: AppUser[];
  providers: Provider[];
  roster: SquareRosterEntry[];
}) {
  const router = useRouter();
  const [users, setUsers] = useState(initialUsers);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [role, setRole] = useState<Role>('MANAGER');
  const [providerId, setProviderId] = useState<number | ''>('');
  const [email, setEmail] = useState('');
  const [squareId, setSquareId] = useState<string | null>(null);
  const [pickedName, setPickedName] = useState<string | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  // Prefill the form from a Square team member: username from the email local-part (or name),
  // plus suggested role, email, provider link, and the team-member id to store/provision.
  function importFromSquare(teamMemberId: string) {
    const entry = roster.find((r) => r.teamMemberId === teamMemberId);
    if (!entry) return;
    const suggestedUsername = (entry.email?.split('@')[0] ?? entry.name)
      .toLowerCase().replace(/[^a-z0-9._-]+/g, '');
    setUsername(suggestedUsername);
    setEmail(entry.email ?? '');
    setRole(entry.suggestedRole);
    setProviderId(entry.providerId ?? '');
    setSquareId(entry.teamMemberId);
    setPickedName(entry.name);
  }

  // A provider can only be linked once; hide those already taken (except keep all for clarity).
  const linkedProviderIds = new Set(users.map((u) => u.providerId).filter(Boolean));
  // Full name for any account: a provider's display name, else the Square team-member name
  // (owner/manager imported from Square), else their email.
  const personName = (u: AppUser): string => {
    if (u.providerId != null) return providers.find((p) => p.id === u.providerId)?.displayName ?? `#${u.providerId}`;
    if (u.squareTeamMemberId) {
      const r = roster.find((x) => x.teamMemberId === u.squareTeamMemberId);
      if (r?.name) return r.name;
    }
    return u.email ?? '—';
  };

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
        providerId: role === 'PROVIDER' && providerId !== '' ? Number(providerId) : null,
        squareTeamMemberId: squareId,
        email: email || null,
        name: pickedName,
      });
      setUsers((prev) => [...prev, created].sort((a, b) => a.username.localeCompare(b.username)));
      setUsername('');
      setPassword('');
      setProviderId('');
      setRole('MANAGER');
      setEmail('');
      setSquareId(null);
      setPickedName(null);
      router.refresh();
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
      <form onSubmit={create} data-testid="user-form" className="flex flex-wrap items-end gap-3 rounded-lg ring-1 ring-zinc-200 p-4">
        {roster.length > 0 && (
          <label className="text-sm">
            <span className="mb-1 block text-zinc-600">Import from Square</span>
            <select value={squareId ?? ''} onChange={(e) => e.target.value ? importFromSquare(e.target.value) : setSquareId(null)}
              className="w-56 rounded border border-zinc-300 px-2 py-1.5">
              <option value="">— manual entry —</option>
              {roster.map((r) => (
                <option key={r.teamMemberId} value={r.teamMemberId} disabled={r.hasAccount}>
                  {r.name} · {r.jobTitle ?? r.suggestedRole}{r.hasAccount ? ' (has account)' : ''}
                </option>
              ))}
            </select>
          </label>
        )}
        <label className="text-sm">
          <span className="mb-1 block text-zinc-600">Username</span>
          <input value={username} onChange={(e) => setUsername(e.target.value)} required
            className="w-40 rounded border border-zinc-300 px-2 py-1.5" autoComplete="off" />
        </label>
        <label className="text-sm">
          <span className="mb-1 block text-zinc-600">Email</span>
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)}
            className="w-48 rounded border border-zinc-300 px-2 py-1.5" autoComplete="off" />
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
            <select value={providerId} onChange={(e) => setProviderId(Number(e.target.value) || '')}
              required={!squareId}
              className="rounded border border-zinc-300 px-2 py-1.5">
              <option value="">{squareId ? 'Auto (from Square)' : 'Select…'}</option>
              {providers.map((p) => (
                <option key={p.id} value={p.id} disabled={linkedProviderIds.has(p.id)}>
                  {p.displayName}{linkedProviderIds.has(p.id) ? ' (linked)' : ''}
                </option>
              ))}
            </select>
          </label>
        )}
        <button type="submit" disabled={busy} data-testid="user-submit"
          className="inline-flex items-center gap-2 rounded bg-zinc-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-50">
          {busy && <Spinner className="h-4 w-4" />}
          {busy ? 'Adding…' : 'Add user'}
        </button>
      </form>

      {error && <p className="text-sm text-red-600">{error}</p>}

      <div data-testid="user-table" className="overflow-x-auto rounded-lg ring-1 ring-zinc-200">
        <table className="w-full text-sm">
          <thead className="bg-zinc-50 text-left text-xs uppercase tracking-wide text-zinc-500">
            <tr>
              <th className="px-3 py-2">Username</th>
              <th className="px-3 py-2">Role</th>
              <th className="px-3 py-2">Name</th>
              <th className="px-3 py-2">Status</th>
              <th className="px-3 py-2 text-right">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-zinc-100">
            {users.map((u) => (
              <tr key={u.id} data-testid={`user-row-${u.id}`} className="hover:bg-zinc-50">
                <td className="px-3 py-2 font-medium">{u.username}</td>
                <td className="px-3 py-2">{u.role}</td>
                <td className="px-3 py-2 text-zinc-600">{personName(u)}</td>
                <td className="px-3 py-2">
                  {u.active
                    ? <span className="text-green-700">active</span>
                    : <span className="text-zinc-400">disabled</span>}
                </td>
                <td className="px-3 py-2 text-right">
                  <button data-testid={`user-toggle-${u.id}`} onClick={() => toggleActive(u)} className="mr-3 text-xs text-zinc-500 hover:text-zinc-800">
                    {u.active ? 'Disable' : 'Enable'}
                  </button>
                  <button data-testid={`user-delete-${u.id}`} onClick={() => remove(u)} className="text-xs text-red-500 hover:text-red-700">Delete</button>
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
