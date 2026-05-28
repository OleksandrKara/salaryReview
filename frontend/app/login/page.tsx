'use client';

import { useRouter } from 'next/navigation';
import { useState } from 'react';

export default function LoginPage() {
  const router = useRouter();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError('');
    try {
      const res = await fetch('/api/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password }),
      });
      if (!res.ok) {
        setError(res.status === 401 ? 'Incorrect username or password.' : 'Login failed.');
        return;
      }
      const { role } = (await res.json().catch(() => ({}))) as { role?: string };
      router.replace(role === 'PROVIDER' ? '/me' : '/reports');
      router.refresh();
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="mx-auto flex min-h-screen max-w-sm flex-col justify-center p-8">
      <h1 className="mb-1 text-2xl font-semibold">Salary Review</h1>
      <p className="mb-6 text-sm text-zinc-500">Sign in to view reports.</p>
      <form onSubmit={submit} className="flex flex-col gap-3">
        <label className="text-sm">
          <span className="mb-1 block text-zinc-600">Username</span>
          <input
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            className="w-full rounded border border-zinc-300 px-3 py-2"
            autoComplete="username"
          />
        </label>
        <label className="text-sm">
          <span className="mb-1 block text-zinc-600">Password</span>
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            className="w-full rounded border border-zinc-300 px-3 py-2"
            autoComplete="current-password"
            autoFocus
          />
        </label>
        {error && <p className="text-sm text-red-600">{error}</p>}
        <button
          type="submit"
          disabled={busy}
          className="mt-2 rounded bg-zinc-900 px-4 py-2 font-medium text-white disabled:opacity-50"
        >
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
    </main>
  );
}
