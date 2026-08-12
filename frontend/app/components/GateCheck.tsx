import AssistantWidget from "./AssistantWidget";
import LanguagePrompt from "./LanguagePrompt";
import OnboardingGate from "./OnboardingGate";
import SopAckGate from "./SopAckGate";
import { loadOnboardingGate } from "../lib/serverGate";

// Split out of the root layout so the gate check's own await can sit inside a Suspense boundary
// (see layout.tsx) instead of blocking the entire <html>/<body> shell from streaming. Decides
// onboarding on the server so a blocked manager/provider gets ONLY the gate in the initial HTML —
// the app is never rendered or sent, so it can't flash before the gate appears. Passes through
// untouched for owners/anonymous visitors, for whom loadOnboardingGate resolves null.
export default async function GateCheck({ children }: { children: React.ReactNode }) {
  const gate = await loadOnboardingGate();

  return gate?.blocked ? (
    // Hard gate: language, then SOPs. The app below is intentionally not rendered until cleared.
    <OnboardingGate me={gate.me} pending={gate.pending} />
  ) : (
    <>
      {children}
      {/* Global assistant — self-gates to OWNER/MANAGER, renders nothing otherwise. */}
      <AssistantWidget />
      {/* One-time language setup for owners who haven't chosen yet (z-60, sits above). */}
      <LanguagePrompt />
      {/* Safety net: catches SOPs published mid-session, when the layout doesn't re-run. */}
      <SopAckGate />
    </>
  );
}
