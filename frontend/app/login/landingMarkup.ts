// Auto-generated from the Claude Design 'Landing Page.html' handoff bundle. The marketing
// markup is rendered as-is; interactivity and the real login are wired in page.tsx.
export const LANDING_MARKUP = `<!-- ============================ HEADER ============================ -->
<header class="site-header" id="header">
  <div class="wrap">
    <nav class="nav" id="nav" data-screen-label="Header">
      <a class="brand" href="#top" aria-label="AK.LUX.STUDIO home">
        <span class="mark">AK.LUX.STUDIO</span>
        <span class="by">for beauty studios</span>
      </a>
      <ul class="nav-links" id="navLinks">
        <li><a href="#advantages">Platform</a></li>
        <li><a href="#reporting">Reporting</a></li>
        <li><a href="#how">How it works</a></li>
        <li><a href="#reviews">Reviews</a></li>
        <li><a href="#pricing">Pricing</a></li>
        <li class="mob-signin"><a href="#" data-signin><span class="btn sm">Sign In</span></a></li>
      </ul>
      <div class="nav-right">
        <button class="btn sm" data-signin>Sign In</button>
        <button class="nav-toggle" id="navToggle" aria-label="Menu" aria-expanded="false">
          <span></span><span></span><span></span>
        </button>
      </div>
    </nav>
  </div>
</header>

<main id="top">

<!-- ============================ HERO ============================ -->
<section class="hero wrap" aria-label="Intro">
  <div class="hero-grid">
    <div class="hero-copy">
      <p class="eyebrow reveal">The operating system behind AK.LUX.NAILS</p>
      <h1 class="display reveal d1">Every dollar, <em>visible</em>. Every payout, earned.</h1>
      <p class="lede reveal d2">AK.LUX.STUDIO turns your Square sales into transparent pay and performance for beauty studios — clear numbers for owners, fair earnings for service providers, and not a single hidden fee.</p>
      <div class="hero-cta reveal d3">
        <button class="btn" data-signin>Get started <span class="arrow">&rarr;</span></button>
        <a class="btn ghost" href="#how">See how it works</a>
      </div>
      <div class="hero-note reveal d3">
        <span class="sq">
          <svg viewBox="0 0 24 24" fill="none"><rect x="3" y="3" width="18" height="18" rx="4" fill="currentColor"/><rect x="9" y="9" width="6" height="6" rx="1.4" fill="var(--paper)"/></svg>
          Integrates with Square
        </span>
        <span>·</span>
        <span>100% transparent · Fully managed</span>
      </div>
    </div>

    <div class="hero-media reveal d2">
      <div class="dash" id="dash">
        <div class="dash-bar">
          <div class="dl">
            <span class="title">AK.LUX.NAILS</span>
          </div>
          <span class="chip"><span class="dot"></span> Synced with Square</span>
        </div>
        <div class="dash-body">
          <div class="dash-kpis">
            <div class="kpi">
              <div class="k-label">Net revenue · Today</div>
              <div class="k-val">$6,480</div>
              <div class="k-delta">▲ 12.4% vs. last Fri</div>
            </div>
            <div class="kpi alt">
              <div class="k-label">Services</div>
              <div class="k-val">38</div>
            </div>
            <div class="kpi alt">
              <div class="k-label">Avg. ticket</div>
              <div class="k-val">$171</div>
            </div>
          </div>

          <div class="bars">
            <div class="bar-row"><span class="b-name">Services</span><span class="bar-track"><i class="bar-fill" data-w="82%"></i></span><span class="b-val">$5,310</span></div>
            <div class="bar-row"><span class="b-name">Tips</span><span class="bar-track"><i class="bar-fill" data-w="46%"></i></span><span class="b-val">$870</span></div>
            <div class="bar-row"><span class="b-name">Retail</span><span class="bar-track"><i class="bar-fill" data-w="18%"></i></span><span class="b-val">$300</span></div>
          </div>

          <div class="payout-head">Provider payouts · auto-calculated</div>
          <div class="payout">
            <span class="who"><span class="av">AK</span><span><span class="nm">Anna K.</span><br><span class="sv">14 svc · Russian mani</span></span></span>
            <span class="amt"><span class="big">$1,940</span><br><span class="pct">55% + tips</span></span>
          </div>
          <div class="payout">
            <span class="who"><span class="av">MS</span><span><span class="nm">Mia S.</span><br><span class="sv">11 svc · gel &amp; art</span></span></span>
            <span class="amt"><span class="big">$1,510</span><br><span class="pct">52% + tips</span></span>
          </div>
        </div>
        <div class="float-card">
          <div class="fc-label">Anna — this week</div>
          <div class="fc-val">$<span>2,940</span></div>
          <div class="fc-sub">$360 to your next tier</div>
          <div class="fc-prog"><i></i></div>
        </div>
      </div>
    </div>
  </div>
</section>

<!-- ============================ TRUST STRIP ============================ -->
<section class="strip" aria-label="Key numbers">
  <div class="wrap strip-inner">
    <div class="stat reveal"><div class="s-val">100<span>%</span></div><div class="s-label">Transparent — no hidden fees</div></div>
    <div class="stat reveal d1"><div class="s-val">&lt; 60<span>s</span></div><div class="s-label">From close to payout report</div></div>
    <div class="stat reveal d2"><div class="s-val">+23<span>%</span></div><div class="s-label">Avg. lift in provider output</div></div>
    <div class="stat reveal d3"><div class="s-val">0</div><div class="s-label">Spreadsheets to manage</div></div>
  </div>
</section>

<!-- ============================ ADVANTAGES ============================ -->
<section class="section-pad wrap" id="advantages" aria-label="Advantages" data-screen-label="Advantages">
  <div class="section-head reveal">
    <p class="eyebrow">Why studios switch</p>
    <h2 class="h2">Built for the two people who matter most — the owner and the artist.</h2>
  </div>

  <div class="adv-grid">
    <article class="adv reveal">
      <span class="num">01</span>
      <svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.3"><rect x="3" y="3" width="18" height="18" rx="4"/><rect x="9" y="9" width="6" height="6" rx="1.4"/></svg>
      <h3 class="h3">Square, connected</h3>
      <p>AK.LUX.STUDIO reads every sale, tip and refund straight from Square the moment it happens. Nothing to export, nothing to re-enter.</p>
    </article>
    <article class="adv reveal d1">
      <span class="num">02</span>
      <svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.3"><circle cx="12" cy="12" r="9"/><path d="M12 7v10M9.5 14.2c0 1.3 1.1 2 2.5 2s2.5-.6 2.5-1.9c0-2.6-4.8-1.6-4.8-4.1 0-1.2 1.1-1.9 2.3-1.9s2.3.7 2.3 1.8"/></svg>
      <h3 class="h3">No hidden numbers</h3>
      <p>Every percentage, deduction and bonus is written in plain sight. What the owner sees is exactly what the provider sees. 100% transparent.</p>
    </article>
    <article class="adv reveal d2">
      <span class="num">03</span>
      <svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.3"><path d="M3 17l5-5 4 3 8-8"/><path d="M21 7h-4M21 7v4"/></svg>
      <h3 class="h3">Providers, motivated</h3>
      <p>Each artist sees live earnings, tips and progress toward the next pay tier — so the best work and the best pay move in the same direction.</p>
    </article>
    <article class="adv reveal">
      <span class="num">04</span>
      <svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.3"><rect x="3" y="4" width="18" height="16" rx="2"/><path d="M3 9h18M8 13h5M8 16h8"/></svg>
      <h3 class="h3">Advanced cash reporting</h3>
      <p>Cash and card reconciled side by side, day by day. Drawer counts, owed payouts and take-home cash — all balanced before you lock up.</p>
    </article>
    <article class="adv reveal d1">
      <span class="num">05</span>
      <svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.3"><path d="M12 3l7 3v6c0 4-3 6.5-7 8-4-1.5-7-4-7-8V6l7-3z"/><path d="M9.5 12l1.8 1.8L15 10"/></svg>
      <h3 class="h3">Fully managed</h3>
      <p>We set up the rules, sync the data and watch the math. You read clean numbers; we handle everything underneath. No spreadsheets, ever.</p>
    </article>
    <article class="adv reveal d2">
      <span class="num">06</span>
      <svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.3"><circle cx="12" cy="8" r="3.4"/><path d="M5 20c0-3.6 3.1-5.6 7-5.6s7 2 7 5.6"/></svg>
      <h3 class="h3">Clear for everyone</h3>
      <p>Owners get the boardroom view. Providers get their personal view. One source of truth, two perspectives, zero arguments on payday.</p>
    </article>
  </div>
</section>

<!-- ============================ REPORTING SPLIT ============================ -->
<section class="section-pad wrap" id="reporting" aria-label="Cash reporting" data-screen-label="Cash reporting">
  <div class="split">
    <div class="split-copy reveal">
      <p class="eyebrow">Advanced cash reporting</p>
      <h2 class="h2">The whole studio, balanced to the dollar.</h2>
      <p class="lede" style="margin-top:1.2rem;">AK.LUX.STUDIO splits every day into the parts that actually matter — what came in, who earned it, and what's left for the house. Cash and card live in the same view, reconciled automatically.</p>
      <ul class="feat-list">
        <li><span class="tick"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4"><path d="M5 12l4 4L19 7"/></svg></span> Cash vs. card reconciliation with drawer counts</li>
        <li><span class="tick"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4"><path d="M5 12l4 4L19 7"/></svg></span> Daily, weekly and per-provider breakdowns</li>
        <li><span class="tick"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4"><path d="M5 12l4 4L19 7"/></svg></span> Owed payouts and house take, calculated live</li>
        <li><span class="tick"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4"><path d="M5 12l4 4L19 7"/></svg></span> Export-ready for your accountant, anytime</li>
      </ul>
    </div>
    <div class="split-media reveal d1">
      <div class="report" id="report">
        <div class="report-top">
          <div><div class="rt-label">This week · take-home</div><div class="rt-val">$31,940</div></div>
          <span class="chip"><span class="dot"></span> Reconciled</span>
        </div>
        <div class="report-seg">
          <span class="sg" style="background:var(--ink);width:54%"></span>
          <span class="sg" style="background:var(--accent);width:30%"></span>
          <span class="sg" style="background:var(--accent-ink);width:16%"></span>
        </div>
        <div class="report-legend">
          <span class="lg"><span class="sw" style="background:var(--ink)"></span> Provider payouts · $17,240</span>
          <span class="lg"><span class="sw" style="background:var(--accent)"></span> House · $9,580</span>
          <span class="lg"><span class="sw" style="background:var(--accent-ink)"></span> Tips · $5,120</span>
        </div>
        <div class="spark" id="spark">
          <span class="col" data-h="40%"></span>
          <span class="col" data-h="62%"></span>
          <span class="col" data-h="48%"></span>
          <span class="col" data-h="78%"></span>
          <span class="col hi" data-h="95%"></span>
          <span class="col" data-h="70%"></span>
          <span class="col" data-h="55%"></span>
        </div>
      </div>
    </div>
  </div>
</section>

<!-- ============================ PROVIDER SPLIT ============================ -->
<section class="section-pad wrap" aria-label="Provider motivation" data-screen-label="Provider earnings">
  <div class="split reverse">
    <div class="split-copy reveal">
      <p class="eyebrow">For service providers</p>
      <h2 class="h2">See exactly what you earned — the moment you earn it.</h2>
      <p class="lede" style="margin-top:1.2rem;">No more guessing on payday. Every artist gets their own live view of services, tips, splits and progress toward the next tier. Transparent pay turns into real motivation.</p>
      <ul class="feat-list">
        <li><span class="tick"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4"><path d="M5 12l4 4L19 7"/></svg></span> Live earnings, updated with every service</li>
        <li><span class="tick"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4"><path d="M5 12l4 4L19 7"/></svg></span> Tips and splits shown in full — never a mystery</li>
        <li><span class="tick"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4"><path d="M5 12l4 4L19 7"/></svg></span> Performance tiers that reward your best work</li>
      </ul>
    </div>
    <div class="split-media reveal d1">
      <div class="earn">
        <div class="e-top">
          <div class="e-who">
            <span class="e-av">AK</span>
            <span><span class="e-name">Anna K.</span><br><span class="e-role">Lead nail artist · AK.LUX.NAILS</span></span>
          </div>
          <span class="chip" style="background:rgba(184,151,90,.16);border-color:rgba(184,151,90,.4);color:var(--accent)"><span class="dot"></span> Live</span>
        </div>
        <div class="e-big">$2,940 <small>this week</small></div>
        <div class="e-cap">Across 31 services · updated 4 minutes ago</div>
        <div class="e-break">
          <div class="eb"><div class="v">$2,180</div><div class="l">Service split</div></div>
          <div class="eb"><div class="v">$610</div><div class="l">Tips</div></div>
          <div class="eb"><div class="v">$150</div><div class="l">Retail bonus</div></div>
        </div>
        <div class="e-tier">
          <div class="tt"><span>Progress to Tier 3 (60% split)</span><span>$360 to go</span></div>
          <div class="track"><i></i></div>
        </div>
      </div>
    </div>
  </div>
</section>

<!-- ============================ HOW IT WORKS ============================ -->
<section class="section-pad wrap" id="how" aria-label="How it works" data-screen-label="How it works">
  <div class="section-head reveal">
    <p class="eyebrow">How it works</p>
    <h2 class="h2">Live in a day. Managed for life.</h2>
  </div>
  <div class="steps">
    <div class="step reveal"><span class="s-no">01</span><span class="s-tag">Connect</span><h3 class="h3">Link Square</h3><p>One secure connection pulls in your sales, tips and team. No migration, no manual entry.</p></div>
    <div class="step reveal d1"><span class="s-no">02</span><span class="s-tag">Configure</span><h3 class="h3">Set your splits</h3><p>We build your commission rules, tiers and bonuses with you — exactly how your studio pays.</p></div>
    <div class="step reveal d2"><span class="s-no">03</span><span class="s-tag">Operate</span><h3 class="h3">Numbers go live</h3><p>Owners and providers each open their view. Every figure updates in real time, all day.</p></div>
    <div class="step reveal d3"><span class="s-no">04</span><span class="s-tag">Relax</span><h3 class="h3">We manage it</h3><p>Reconciliation, payout math and reporting run on their own. You just read the results.</p></div>
  </div>
</section>

<!-- ============================ REVIEWS ============================ -->
<section class="section-pad reviews" id="reviews" aria-label="Reviews" data-screen-label="Reviews">
  <div class="wrap">
    <div class="section-head center reveal">
      <p class="eyebrow center">Loved by studios &amp; their teams</p>
      <h2 class="h2">Rated where it counts.</h2>
      <div class="ghead">
        <img class="g-mark" style="width:24px;height:24px" src="data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 48 48'><path fill='%234285F4' d='M45 24c0-1.6-.1-2.8-.4-4H24v7.6h12c-.2 2-1.6 5-4.6 7l-.04.3 6.7 5.2.5.04C42.9 36.3 45 30.7 45 24z'/><path fill='%2334A853' d='M24 46c6 0 11-2 14.6-5.4l-7-5.4c-1.9 1.3-4.4 2.2-7.6 2.2-5.8 0-10.7-3.9-12.5-9.2l-.3.02-7 5.4-.1.3C7.7 41.1 15.2 46 24 46z'/><path fill='%23FBBC05' d='M11.5 28.2c-.5-1.4-.7-2.9-.7-4.2s.3-2.9.7-4.2l-.02-.3-7.1-5.5-.2.1A22 22 0 0 0 2 24c0 3.5.8 6.9 2.3 9.9l7.2-5.7z'/><path fill='%23EA4335' d='M24 9.5c4.1 0 6.9 1.8 8.5 3.3l6.2-6C34.9 3.2 30 1 24 1 15.2 1 7.7 6 4.3 14.1l7.2 5.7C13.3 14.5 18.2 9.5 24 9.5z'/></svg>" alt="Google">
        <span class="gscore">4.9</span>
        <span class="gstars">
          <svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 2l3 6.3 6.9.9-5 4.8 1.2 6.9L12 17.7 5.9 20.9 7.1 14 2 9.2l6.9-.9z"/></svg>
          <svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 2l3 6.3 6.9.9-5 4.8 1.2 6.9L12 17.7 5.9 20.9 7.1 14 2 9.2l6.9-.9z"/></svg>
          <svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 2l3 6.3 6.9.9-5 4.8 1.2 6.9L12 17.7 5.9 20.9 7.1 14 2 9.2l6.9-.9z"/></svg>
          <svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 2l3 6.3 6.9.9-5 4.8 1.2 6.9L12 17.7 5.9 20.9 7.1 14 2 9.2l6.9-.9z"/></svg>
          <svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 2l3 6.3 6.9.9-5 4.8 1.2 6.9L12 17.7 5.9 20.9 7.1 14 2 9.2l6.9-.9z"/></svg>
        </span>
        <span class="greviews-meta">· 101 Google reviews</span>
      </div>
    </div>

    <div class="rev-grid">
      <article class="rev reveal">
        <div class="r-top"><span class="r-stars">★★★★★</span><img class="g-mark" src="data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 48 48'><path fill='%234285F4' d='M45 24c0-1.6-.1-2.8-.4-4H24v7.6h12c-.2 2-1.6 5-4.6 7l-.04.3 6.7 5.2.5.04C42.9 36.3 45 30.7 45 24z'/><path fill='%2334A853' d='M24 46c6 0 11-2 14.6-5.4l-7-5.4c-1.9 1.3-4.4 2.2-7.6 2.2-5.8 0-10.7-3.9-12.5-9.2l-.3.02-7 5.4-.1.3C7.7 41.1 15.2 46 24 46z'/><path fill='%23FBBC05' d='M11.5 28.2c-.5-1.4-.7-2.9-.7-4.2s.3-2.9.7-4.2l-.02-.3-7.1-5.5-.2.1A22 22 0 0 0 2 24c0 3.5.8 6.9 2.3 9.9l7.2-5.7z'/><path fill='%23EA4335' d='M24 9.5c4.1 0 6.9 1.8 8.5 3.3l6.2-6C34.9 3.2 30 1 24 1 15.2 1 7.7 6 4.3 14.1l7.2 5.7C13.3 14.5 18.2 9.5 24 9.5z'/></svg>" alt="Google"></div>
        <p>"Payday used to be the most stressful hour of my week. Now my team sees their own numbers and the arguments are just… gone. Everything is out in the open."</p>
        <div class="r-by"><span class="r-av">OK</span><span><span class="r-name">Olga K.</span><div class="r-date">Studio owner · 2 weeks ago</div></span></div>
      </article>

      <article class="rev reveal d1">
        <div class="r-top"><span class="r-stars">★★★★★</span><img class="g-mark" src="data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 48 48'><path fill='%234285F4' d='M45 24c0-1.6-.1-2.8-.4-4H24v7.6h12c-.2 2-1.6 5-4.6 7l-.04.3 6.7 5.2.5.04C42.9 36.3 45 30.7 45 24z'/><path fill='%2334A853' d='M24 46c6 0 11-2 14.6-5.4l-7-5.4c-1.9 1.3-4.4 2.2-7.6 2.2-5.8 0-10.7-3.9-12.5-9.2l-.3.02-7 5.4-.1.3C7.7 41.1 15.2 46 24 46z'/><path fill='%23FBBC05' d='M11.5 28.2c-.5-1.4-.7-2.9-.7-4.2s.3-2.9.7-4.2l-.02-.3-7.1-5.5-.2.1A22 22 0 0 0 2 24c0 3.5.8 6.9 2.3 9.9l7.2-5.7z'/><path fill='%23EA4335' d='M24 9.5c4.1 0 6.9 1.8 8.5 3.3l6.2-6C34.9 3.2 30 1 24 1 15.2 1 7.7 6 4.3 14.1l7.2 5.7C13.3 14.5 18.2 9.5 24 9.5z'/></svg>" alt="Google"></div>
        <p>"As an artist I finally see my tips and splits in real time. Watching the tier bar move makes me want to take that extra client. It's honestly motivating."</p>
        <div class="r-by"><span class="r-av">MS</span><span><span class="r-name">Mia S.</span><div class="r-date">Nail artist · 1 month ago</div></span></div>
      </article>

      <article class="rev reveal d2">
        <div class="r-top"><span class="r-stars">★★★★★</span><img class="g-mark" src="data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 48 48'><path fill='%234285F4' d='M45 24c0-1.6-.1-2.8-.4-4H24v7.6h12c-.2 2-1.6 5-4.6 7l-.04.3 6.7 5.2.5.04C42.9 36.3 45 30.7 45 24z'/><path fill='%2334A853' d='M24 46c6 0 11-2 14.6-5.4l-7-5.4c-1.9 1.3-4.4 2.2-7.6 2.2-5.8 0-10.7-3.9-12.5-9.2l-.3.02-7 5.4-.1.3C7.7 41.1 15.2 46 24 46z'/><path fill='%23FBBC05' d='M11.5 28.2c-.5-1.4-.7-2.9-.7-4.2s.3-2.9.7-4.2l-.02-.3-7.1-5.5-.2.1A22 22 0 0 0 2 24c0 3.5.8 6.9 2.3 9.9l7.2-5.7z'/><path fill='%23EA4335' d='M24 9.5c4.1 0 6.9 1.8 8.5 3.3l6.2-6C34.9 3.2 30 1 24 1 15.2 1 7.7 6 4.3 14.1l7.2 5.7C13.3 14.5 18.2 9.5 24 9.5z'/></svg>" alt="Google"></div>
        <p>"The cash reporting alone paid for itself. Card and cash reconciled before I lock up, no late-night spreadsheet. My accountant actually thanked me."</p>
        <div class="r-by"><span class="r-av">DV</span><span><span class="r-name">Daniel V.</span><div class="r-date">Owner, 3 locations · 3 weeks ago</div></span></div>
      </article>

      <article class="rev reveal">
        <div class="r-top"><span class="r-stars">★★★★★</span><img class="g-mark" src="data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 48 48'><path fill='%234285F4' d='M45 24c0-1.6-.1-2.8-.4-4H24v7.6h12c-.2 2-1.6 5-4.6 7l-.04.3 6.7 5.2.5.04C42.9 36.3 45 30.7 45 24z'/><path fill='%2334A853' d='M24 46c6 0 11-2 14.6-5.4l-7-5.4c-1.9 1.3-4.4 2.2-7.6 2.2-5.8 0-10.7-3.9-12.5-9.2l-.3.02-7 5.4-.1.3C7.7 41.1 15.2 46 24 46z'/><path fill='%23FBBC05' d='M11.5 28.2c-.5-1.4-.7-2.9-.7-4.2s.3-2.9.7-4.2l-.02-.3-7.1-5.5-.2.1A22 22 0 0 0 2 24c0 3.5.8 6.9 2.3 9.9l7.2-5.7z'/><path fill='%23EA4335' d='M24 9.5c4.1 0 6.9 1.8 8.5 3.3l6.2-6C34.9 3.2 30 1 24 1 15.2 1 7.7 6 4.3 14.1l7.2 5.7C13.3 14.5 18.2 9.5 24 9.5z'/></svg>" alt="Google"></div>
        <p>"Set-up took an afternoon and they handle everything since. It connected to Square and just worked. No hidden fees — the price is the price."</p>
        <div class="r-by"><span class="r-av">RP</span><span><span class="r-name">Renata P.</span><div class="r-date">Salon owner · 5 days ago</div></span></div>
      </article>

      <article class="rev reveal d1">
        <div class="r-top"><span class="r-stars">★★★★★</span><img class="g-mark" src="data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 48 48'><path fill='%234285F4' d='M45 24c0-1.6-.1-2.8-.4-4H24v7.6h12c-.2 2-1.6 5-4.6 7l-.04.3 6.7 5.2.5.04C42.9 36.3 45 30.7 45 24z'/><path fill='%2334A853' d='M24 46c6 0 11-2 14.6-5.4l-7-5.4c-1.9 1.3-4.4 2.2-7.6 2.2-5.8 0-10.7-3.9-12.5-9.2l-.3.02-7 5.4-.1.3C7.7 41.1 15.2 46 24 46z'/><path fill='%23FBBC05' d='M11.5 28.2c-.5-1.4-.7-2.9-.7-4.2s.3-2.9.7-4.2l-.02-.3-7.1-5.5-.2.1A22 22 0 0 0 2 24c0 3.5.8 6.9 2.3 9.9l7.2-5.7z'/><path fill='%23EA4335' d='M24 9.5c4.1 0 6.9 1.8 8.5 3.3l6.2-6C34.9 3.2 30 1 24 1 15.2 1 7.7 6 4.3 14.1l7.2 5.7C13.3 14.5 18.2 9.5 24 9.5z'/></svg>" alt="Google"></div>
        <p>"What I love most is that my team trusts the numbers. They see the same screen I do. That trust changed the whole feeling of the studio."</p>
        <div class="r-by"><span class="r-av">AK</span><span><span class="r-name">Anna K.</span><div class="r-date">Founder, AK.LUX.NAILS · 1 week ago</div></span></div>
      </article>

      <article class="rev reveal d2">
        <div class="r-top"><span class="r-stars">★★★★★</span><img class="g-mark" src="data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 48 48'><path fill='%234285F4' d='M45 24c0-1.6-.1-2.8-.4-4H24v7.6h12c-.2 2-1.6 5-4.6 7l-.04.3 6.7 5.2.5.04C42.9 36.3 45 30.7 45 24z'/><path fill='%2334A853' d='M24 46c6 0 11-2 14.6-5.4l-7-5.4c-1.9 1.3-4.4 2.2-7.6 2.2-5.8 0-10.7-3.9-12.5-9.2l-.3.02-7 5.4-.1.3C7.7 41.1 15.2 46 24 46z'/><path fill='%23FBBC05' d='M11.5 28.2c-.5-1.4-.7-2.9-.7-4.2s.3-2.9.7-4.2l-.02-.3-7.1-5.5-.2.1A22 22 0 0 0 2 24c0 3.5.8 6.9 2.3 9.9l7.2-5.7z'/><path fill='%23EA4335' d='M24 9.5c4.1 0 6.9 1.8 8.5 3.3l6.2-6C34.9 3.2 30 1 24 1 15.2 1 7.7 6 4.3 14.1l7.2 5.7C13.3 14.5 18.2 9.5 24 9.5z'/></svg>" alt="Google"></div>
        <p>"I switched from juggling three spreadsheets. Now it's one clean dashboard and the payouts are done for me. Wish I'd found it two years ago."</p>
        <div class="r-by"><span class="r-av">TN</span><span><span class="r-name">Thuy N.</span><div class="r-date">Spa owner · 1 month ago</div></span></div>
      </article>
    </div>
  </div>
</section>

<!-- ============================ ORIGIN ============================ -->
<section class="section-pad origin" aria-label="AK.LUX.NAILS story" data-screen-label="Origin story">
  <div class="wrap">
    <div class="o-copy reveal">
      <div class="o-logo" style="font-family:var(--serif);font-size:2rem;font-weight:600;letter-spacing:.18em;text-transform:uppercase;color:var(--paper);width:auto;margin-bottom:1.8rem;filter:none">AK.LUX.NAILS</div>
      <p class="eyebrow">Born on a real salon floor</p>
      <p class="quote">"We built AK.LUX.STUDIO because our own team deserved to see exactly what they earned — to the cent, in real time."</p>
      <p class="q-by">Anna Kara · Founder, AK.LUX.NAILS</p>
      <p style="margin-top:1.6rem;max-width:46ch;">AK.LUX.STUDIO started inside AK.LUX.NAILS, a Russian-manicure studio obsessed with precision. The same care that goes into every set of nails now goes into every number — and it's ready for your studio.</p>
    </div>
    <div class="o-media reveal d1">
      <div class="o-video-wrap"><div class="ph tall" data-label="AK.LUX.NAILS"></div></div></div>
    </div>
  </div>
</section>

<!-- ============================ PRICING ============================ -->
<section class="section-pad wrap center" id="pricing" aria-label="Pricing" data-screen-label="Pricing">
  <div class="section-head center reveal">
    <p class="eyebrow center">Honest pricing</p>
    <h2 class="h2">One flat rate. Fully managed. No hidden fees.</h2>
  </div>
  <div class="price-card reveal d1">
    <div class="p-eyebrow">Per location, billed monthly</div>
    <div class="p-val">$149<span>/mo</span></div>
    <p class="p-sub">Unlimited providers. Every feature included. We set it up and manage it — you'll never see a surprise line item.</p>
    <ul class="price-incl">
      <li><span class="tick"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.6"><path d="M5 12l4 4L19 7"/></svg></span> Square integration</li>
      <li><span class="tick"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.6"><path d="M5 12l4 4L19 7"/></svg></span> Unlimited providers</li>
      <li><span class="tick"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.6"><path d="M5 12l4 4L19 7"/></svg></span> Advanced cash reporting</li>
      <li><span class="tick"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.6"><path d="M5 12l4 4L19 7"/></svg></span> Full setup &amp; management</li>
    </ul>
    <button class="btn" data-signin>Start free trial <span class="arrow">&rarr;</span></button>
  </div>
</section>

<!-- ============================ FINAL CTA ============================ -->
<section class="section-pad wrap final" aria-label="Get started" data-screen-label="Final CTA">
  <p class="eyebrow center reveal">Ready when you are</p>
  <h2 class="reveal d1">Give every number a <em>home</em>.</h2>
  <p class="lede reveal d2" style="margin:1.2rem auto 0;text-align:center;">Join the studios paying their teams transparently and reading clean numbers every single day.</p>
  <div class="final-cta reveal d2">
    <button class="btn" data-signin>Get started <span class="arrow">&rarr;</span></button>
    <a class="btn ghost" href="#how">Book a demo</a>
  </div>
</section>

</main>

<!-- ============================ FOOTER ============================ -->
<footer class="site-footer">
  <div class="wrap">
    <div class="foot-grid">
      <div class="foot-brand">
        <div class="mark">AK.LUX.STUDIO</div>
        <p>Transparent pay and performance for beauty studios. Built on a real salon floor.</p>
      </div>
      <div class="foot-col">
        <h4>Platform</h4>
        <ul><li><a href="#advantages">Overview</a></li><li><a href="#reporting">Cash reporting</a></li><li><a href="#how">How it works</a></li><li><a href="#pricing">Pricing</a></li></ul>
      </div>
      <div class="foot-col">
        <h4>Company</h4>
        <ul><li><a href="#reviews">Reviews</a></li><li><a href="#" data-signin>Sign in</a></li><li><a href="#">Contact</a></li><li><a href="#">Careers</a></li></ul>
      </div>
      <div class="foot-col">
        <h4>Legal</h4>
        <ul><li><a href="#">Privacy</a></li><li><a href="#">Terms</a></li><li><a href="#">Security</a></li></ul>
      </div>
    </div>
    <div class="foot-bottom">
      <span>© 2026 AK.LUX.STUDIO. A product of AK.LUX.NAILS.</span>
      <span>Integrated with Square · Made with care</span>
    </div>
  </div>
</footer>

<!-- ============================ SIGN-IN MODAL ============================ -->
<div class="modal-scrim" id="signinScrim" role="dialog" aria-modal="true" aria-label="Sign in">
  <div class="modal">
    <button class="m-close" id="modalClose" aria-label="Close">
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M6 6l12 12M18 6L6 18"/></svg>
    </button>
    <div id="modalForm">
      <div class="m-mark">AK.LUX.STUDIO</div>
      <h3>Welcome back</h3>
      <p class="m-sub">Sign in to your studio dashboard.</p>
      <form id="signinForm" novalidate>
        <div class="field">
          <label for="email">Email or username</label>
          <input type="text" id="email" placeholder="your username" autocomplete="username">
          <div class="msg" id="emailMsg"></div>
        </div>
        <div class="field">
          <label for="password">Password</label>
          <input type="password" id="password" placeholder="••••••••" autocomplete="current-password">
          <div class="msg" id="passMsg"></div>
        </div>
        <div class="m-row">
          <label class="checkbox"><input type="checkbox" checked> Remember me</label>
          <a href="#">Forgot password?</a>
        </div>
        <button type="submit" class="btn full">Sign in</button>
      </form>
      <div class="m-divider">or</div>
      <button class="m-square" id="squareBtn">
        <svg viewBox="0 0 24 24" fill="none"><rect x="3" y="3" width="18" height="18" rx="4" fill="currentColor"/><rect x="9" y="9" width="6" height="6" rx="1.4" fill="var(--paper)"/></svg>
        Continue with Square
      </button>
      <p class="m-foot">New to AK.LUX.STUDIO? <a href="#">Book a demo</a></p>
    </div>
    <div id="modalSuccess" style="display:none">
      <div class="m-success">
        <div class="ok"><svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M5 12l4 4L19 7"/></svg></div>
        <h3 style="margin-top:0">You're in</h3>
        <p class="m-sub">Taking you to your dashboard…</p>
      </div>
    </div>
  </div>
</div>`;
