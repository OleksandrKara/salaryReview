'use client';

import { useEffect, useRef, useState } from 'react';

interface View { signed: boolean; firstName: string | null; businessName: string; when: string; expired: boolean; consentText: string; privacyUrl: string | null }
type Answers = Record<string, string>;
type Screen = 'loading' | 'form' | 'notfound' | 'signed' | 'expired' | 'done' | 'error';

const YNU = [['NO', 'No'], ['YES', 'Yes'], ['NOT_SURE', 'Not sure']] as const;
const YN = [['NO', 'No'], ['YES', 'Yes']] as const;
const HEARD = [
  ['GOOGLE_SEARCH', 'Google search'], ['GOOGLE_MAPS', 'Google Maps'], ['YELP', 'Yelp'], ['INSTAGRAM', 'Instagram'],
  ['TIKTOK', 'TikTok'], ['FRIEND_FAMILY', 'Friend or family'], ['WALK_BY', 'Walked by'], ['CHATGPT', 'ChatGPT'],
  ['CLAUDE', 'Claude'], ['PERPLEXITY', 'Perplexity'], ['OTHER', 'Other'],
] as const;

function Choice({ name, legend, options, value, onChange, invalid, hint }: {
  name: string; legend: string; options: readonly (readonly [string, string])[]; value: string | undefined;
  onChange: (v: string) => void; invalid: boolean; hint?: string;
}) {
  return (
    <fieldset data-field={name} className={`rounded-2xl border bg-white p-4 ${invalid ? 'border-red-400 ring-1 ring-red-300' : 'border-stone-200'}`}>
      <legend className="float-left w-full text-[15px] font-semibold text-stone-900">{legend}</legend>
      {hint && <p className="clear-both mb-2 text-sm text-stone-500">{hint}</p>}
      <div className="clear-both flex flex-wrap gap-2 pt-2">
        {options.map(([v, label]) => (
          <label key={v} className={`flex min-h-11 cursor-pointer items-center rounded-xl px-4 text-[15px] ring-1 transition has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-stone-900 ${value === v ? 'bg-stone-900 text-white ring-stone-900' : 'bg-white text-stone-800 ring-stone-300 hover:bg-stone-50'}`}>
            <input type="radio" name={name} value={v} checked={value === v} onChange={() => onChange(v)} className="sr-only" />
            {label}
          </label>
        ))}
      </div>
      {invalid && <p role="alert" className="mt-2 text-sm text-red-700">Please choose an answer.</p>}
    </fieldset>
  );
}

function Text({ name, label, value, onChange, invalid, max, rows = 3, required }: {
  name: string; label: string; value: string; onChange: (v: string) => void; invalid: boolean; max: number; rows?: number; required?: boolean;
}) {
  return (
    <label data-field={name} className="block">
      <span className="mb-1 block text-sm font-medium text-stone-700">{label}{required ? '' : ' (optional)'}</span>
      <textarea value={value} onChange={(e) => onChange(e.target.value)} rows={rows} maxLength={max}
        className={`w-full rounded-xl border bg-white px-3 py-2 text-base text-stone-900 focus:outline-none focus:ring-2 focus:ring-stone-900 ${invalid ? 'border-red-400' : 'border-stone-300'}`} />
      {invalid && <span role="alert" className="mt-1 block text-sm text-red-700">Please fill this in.</span>}
    </label>
  );
}

function Shell({ children, name }: { children: React.ReactNode; name?: string }) {
  return (
    <main className="min-h-dvh bg-stone-50 px-4 py-8 text-stone-900 sm:py-12">
      <div className="mx-auto w-full max-w-xl">
        <p className="mb-6 text-center text-sm font-semibold uppercase tracking-[0.25em] text-stone-500">{name ?? ''}</p>
        {children}
      </div>
    </main>
  );
}

export default function IntakeFormClient({ token }: { token: string }) {
  const [screen, setScreen] = useState<Screen>('loading');
  const [view, setView] = useState<View | null>(null);
  const [a, setA] = useState<Answers>({});
  const [sig, setSig] = useState('');
  const [agreed, setAgreed] = useState(false);
  const [consent, setConsent] = useState(false);
  const [bad, setBad] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const form = useRef<HTMLFormElement>(null);
  const startedSent = useRef(false);

  useEffect(() => {
    let alive = true;
    fetch(`/api/public/intake/${encodeURIComponent(token)}`, { cache: 'no-store' })
      .then(async (r) => {
        if (!alive) return;
        if (r.status === 404) return setScreen('notfound');
        if (!r.ok) return setScreen('error');
        const v: View = await r.json();
        setView(v);
        setScreen(v.signed ? 'signed' : v.expired ? 'expired' : 'form');
      })
      .catch(() => alive && setScreen('error'));
    return () => { alive = false; };
  }, [token]);

  const markStarted = () => {
    if (startedSent.current) return;
    startedSent.current = true;
    fetch(`/api/public/intake/${encodeURIComponent(token)}/started`, { method: 'POST', keepalive: true }).catch(() => {});
  };
  const set = (k: string) => (v: string) => { markStarted(); setA((p) => ({ ...p, [k]: v })); setBad(null); };

  function firstMissing(): string | null {
    for (const k of ['service', 'fungal', 'skin', 'allergies', 'sensitivity', 'epilepsy', 'enhancements', 'heardFrom']) if (!a[k]) return k;
    if (a.allergies === 'YES' && !a.allergiesDetails?.trim()) return 'allergiesDetails';
    if (a.sensitivity === 'YES' && !a.sensitivityDetails?.trim()) return 'sensitivityDetails';
    if (a.heardFrom === 'OTHER' && !a.heardOther?.trim()) return 'heardOther';
    if (sig.trim().length < 2) return 'signature';
    if (!agreed) return 'agreed';
    if (!consent) return 'consent';
    return null;
  }

  function flag(field: string) {
    setBad(field);
    form.current?.querySelector<HTMLElement>(`[data-field="${field}"]`)?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const missing = firstMissing();
    if (missing) return flag(missing);
    setBusy(true);
    setProblem(null);
    try {
      const r = await fetch(`/api/public/intake/${encodeURIComponent(token)}`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ answers: a, signatureName: sig.trim(), agreed, consent }),
      });
      if (r.ok) return setScreen('done');
      if (r.status === 409) return setScreen('signed');
      if (r.status === 400) {
        const body = await r.json().catch(() => ({}));
        return flag(body.field === 'signature' ? 'signature' : body.field ?? 'service');
      }
      setProblem(r.status === 429 ? 'Too many attempts. Please wait a few minutes and try again.' : 'Something went wrong. Please try again.');
    } catch {
      setProblem('No connection. Please check your internet and try again.');
    } finally {
      setBusy(false);
    }
  }

  if (screen === 'loading') return <Shell name={view?.businessName}><p className="text-center text-stone-500">Loading</p></Shell>;
  if (screen === 'notfound') return <Shell name={view?.businessName}><Card title="This link is not valid" body="Please use the link from our text message, or call or text us and we will send a new one." /></Shell>;
  if (screen === 'error') return <Shell name={view?.businessName}><Card title="We could not open the form" body="Please try again in a moment." /></Shell>;
  if (screen === 'expired') return <Shell name={view?.businessName}><Card title="This form has expired" body="Your visit has passed. Text us and we will send a fresh link if you are booking again." /></Shell>;
  if (screen === 'signed') return <Shell name={view?.businessName}><Card title="Thank you, your form is already on file" body="Nothing else is needed. See you soon." /></Shell>;
  if (screen === 'done') return <Shell name={view?.businessName}><Card title="Thank you, your form is signed" body={view ? `We have everything we need for your visit on ${view.when}. See you soon.` : 'See you soon.'} /></Shell>;

  return (
    <Shell name={view?.businessName}>
      <h1 className="text-2xl font-semibold leading-tight">{view?.firstName ? `Welcome, ${view.firstName}` : 'Welcome'}</h1>
      <p className="mt-2 text-[15px] text-stone-600">Client intake and health form for your visit on {view?.when}. It takes about two minutes and helps your technician prepare.</p>
      <form ref={form} onSubmit={submit} onChange={markStarted} noValidate className="mt-6 space-y-4">
        <Choice name="service" legend="What are you booked for?" options={[['MANICURE', 'Manicure'], ['PEDICURE', 'Pedicure'], ['BOTH', 'Both']]} value={a.service} onChange={set('service')} invalid={bad === 'service'} />
        <Choice name="fungal" legend="Do you have a fungal or contagious condition of the nails or skin?" options={YNU} value={a.fungal} onChange={set('fungal')} invalid={bad === 'fungal'} />
        <Choice name="skin" legend="Any open cuts, sores or irritated skin on your hands or feet?" options={YNU} value={a.skin} onChange={set('skin')} invalid={bad === 'skin'} />
        <Choice name="allergies" legend="Do you have allergies or reactions to nail products?" options={YN} value={a.allergies} onChange={set('allergies')} invalid={bad === 'allergies'} />
        {a.allergies === 'YES' && <Text name="allergiesDetails" label="Please tell us what" value={a.allergiesDetails ?? ''} onChange={set('allergiesDetails')} invalid={bad === 'allergiesDetails'} max={500} required />}
        <Choice name="sensitivity" legend="Do you have sensitivity or pain during nail services?" options={YN} value={a.sensitivity} onChange={set('sensitivity')} invalid={bad === 'sensitivity'} />
        {a.sensitivity === 'YES' && <Text name="sensitivityDetails" label="Please tell us more" value={a.sensitivityDetails ?? ''} onChange={set('sensitivityDetails')} invalid={bad === 'sensitivityDetails'} max={500} required />}
        <Choice name="epilepsy" legend="Do you have epilepsy or seizures, including from flashing or bright light? (Nail lamps flash and glow.)" options={YNU} value={a.epilepsy} onChange={set('epilepsy')} invalid={bad === 'epilepsy'} />
        {(a.epilepsy === 'YES' || a.epilepsy === 'NOT_SURE') && <Text name="epilepsyDetails" label="Anything we should know?" value={a.epilepsyDetails ?? ''} onChange={set('epilepsyDetails')} invalid={false} max={500} rows={2} />}
        <Choice name="enhancements" legend="Do you currently wear gel, acrylic or other enhancements that may need removal?" options={YNU} value={a.enhancements} onChange={set('enhancements')} invalid={bad === 'enhancements'} />
        <Choice name="heardFrom" legend="How did you hear about us?" options={HEARD} value={a.heardFrom} onChange={set('heardFrom')} invalid={bad === 'heardFrom'} />
        {a.heardFrom === 'OTHER' && <Text name="heardOther" label="Please tell us how" value={a.heardOther ?? ''} onChange={set('heardOther')} invalid={bad === 'heardOther'} max={500} rows={2} required />}
        <Text name="notes" label="Anything else your technician should know?" value={a.notes ?? ''} onChange={set('notes')} invalid={false} max={1000} />

        <div className={`rounded-2xl border bg-white p-4 ${bad === 'signature' || bad === 'agreed' || bad === 'consent' ? 'border-red-400 ring-1 ring-red-300' : 'border-stone-200'}`}>
          <label data-field="agreed" className="flex cursor-pointer items-start gap-3 text-[15px] text-stone-800">
            <input type="checkbox" checked={agreed} onChange={(e) => { setAgreed(e.target.checked); setBad(null); }} className="mt-1 h-5 w-5 shrink-0 accent-stone-900" />
            <span>I confirm that my answers are accurate and I understand that nail services may not be suitable if I have a contagious condition or broken skin.</span>
          </label>
          <label data-field="consent" className="mt-4 flex cursor-pointer items-start gap-3 text-[15px] text-stone-800">
            <input type="checkbox" checked={consent} onChange={(e) => { setConsent(e.target.checked); setBad(null); }} className="mt-1 h-5 w-5 shrink-0 accent-stone-900" />
            <span>
              {view?.consentText}
              {view?.privacyUrl && (
                <> <a href={view.privacyUrl} target="_blank" rel="noopener noreferrer" className="underline underline-offset-2">Privacy policy</a></>
              )}
            </span>
          </label>
          <label data-field="signature" className="mt-4 block">
            <span className="mb-1 block text-sm font-medium text-stone-700">Type your full name to sign</span>
            <input value={sig} onChange={(e) => { setSig(e.target.value); setBad(null); }} autoComplete="name" maxLength={120}
              className="w-full rounded-xl border border-stone-300 bg-white px-3 py-2 text-base focus:outline-none focus:ring-2 focus:ring-stone-900" />
          </label>
          {(bad === 'signature' || bad === 'agreed' || bad === 'consent') && <p role="alert" className="mt-2 text-sm text-red-700">Please tick both boxes and type your full name.</p>}
        </div>

        {problem && <p role="alert" className="rounded-xl bg-red-50 px-3 py-2 text-sm text-red-800">{problem}</p>}
        <button type="submit" disabled={busy} className="min-h-12 w-full rounded-xl bg-stone-900 text-base font-semibold text-white disabled:opacity-60">
          {busy ? 'Sending' : 'Sign and send'}
        </button>
        <p className="pb-4 text-center text-xs text-stone-500">Your answers are private. Only your technician and our salon team can see them.</p>
      </form>
    </Shell>
  );
}

function Card({ title, body }: { title: string; body: string }) {
  return (
    <div className="rounded-2xl border border-stone-200 bg-white p-6 text-center">
      <h1 className="text-xl font-semibold">{title}</h1>
      <p className="mt-2 text-[15px] text-stone-600">{body}</p>
    </div>
  );
}
