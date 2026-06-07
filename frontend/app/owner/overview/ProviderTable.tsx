import type { ProviderYtd } from '../../lib/types';

const usd = (n: number) =>
  n.toLocaleString('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });

export default function ProviderTable({ providers }: { providers: ProviderYtd[] }) {
  if (providers.length === 0) return null;

  return (
    <div className="overflow-x-auto rounded-lg ring-1 ring-zinc-200">
      <table className="w-full text-sm">
        <thead className="bg-zinc-50 text-left text-xs uppercase tracking-wide text-zinc-500">
          <tr>
            <th className="px-3 py-2">Provider</th>
            <th className="px-3 py-2 text-right">Gross</th>
            <th className="px-3 py-2 text-right">Payroll Cost</th>
            <th className="px-3 py-2 text-right">Payroll %</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-zinc-100">
          {providers.map((p) => (
            <tr key={p.providerId} className="hover:bg-zinc-50">
              <td className="px-3 py-2 font-medium">{p.name}</td>
              <td className="px-3 py-2 text-right tabular-nums">{usd(p.ytdGross)}</td>
              <td className="px-3 py-2 text-right tabular-nums">{usd(p.ytdPayroll)}</td>
              <td className="px-3 py-2 text-right tabular-nums text-zinc-500">
                {p.ytdPayrollPct != null ? `${p.ytdPayrollPct}%` : '—'}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
