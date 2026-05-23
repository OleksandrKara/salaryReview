import { api } from './lib/api';
import CreatePeriodForm from './CreatePeriodForm';
import PeriodRow from './PeriodRow';

export default async function HomePage() {
  const periods = await api.listPeriods();

  return (
    <main className="mx-auto max-w-3xl p-8">
      <div className="mb-2 flex items-baseline justify-between">
        <h1 className="text-3xl font-semibold">Salary Review</h1>
        <a href="/providers" className="text-sm text-zinc-500 hover:text-zinc-900 underline">
          Manage providers →
        </a>
      </div>
      <p className="mb-8 text-zinc-600">Pick a pay period to enter numbers and generate settlements.</p>

      <section className="mb-10">
        <h2 className="mb-3 text-xl font-medium">Pay periods</h2>
        {periods.length === 0 ? (
          <p className="text-zinc-500">None yet — create one below.</p>
        ) : (
          <ul className="divide-y divide-zinc-200 rounded border border-zinc-200">
            {periods.map((p) => (
              <PeriodRow key={p.id} period={p} />
            ))}
          </ul>
        )}
      </section>

      <section>
        <h2 className="mb-3 text-xl font-medium">Create a new period</h2>
        <CreatePeriodForm />
      </section>
    </main>
  );
}
