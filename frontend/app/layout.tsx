import type { Metadata, Viewport } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import AssistantWidget from "./components/AssistantWidget";
import LanguagePrompt from "./components/LanguagePrompt";
import OnboardingGate from "./components/OnboardingGate";
import SopAckGate from "./components/SopAckGate";
import { loadOnboardingGate } from "./lib/serverGate";
import "./globals.css";
// The salon's AK.LUX.STUDIO theme (paper background, serif headings) — imported app-wide so /reports
// and /me share the landing's look, consistently on every load (not just when arriving from the landing).
import "./landing.css";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "Salary Review",
  description: "Square-sourced salon salary reports",
};

// interactiveWidget: 'resizes-content' — tells Chromium-based mobile browsers (Chrome/Android
// WebView 108+, so most in-app browsers built on it too, e.g. Telegram's Android webview) to
// shrink the actual layout viewport when the on-screen keyboard opens, not just the visual one.
// Without this, `100dvh` (see /admin/messages/page.tsx) only reliably tracks browser-chrome
// changes (address bar hide/show) — despite the "dynamic viewport" name, most engines don't tie
// it to the keyboard by default, which is why the messages composer stayed hidden behind the
// keyboard even after switching to dvh. iOS Safari ignores this meta value; MessagesView's own
// VisualViewport-driven --vvh custom property is the cross-browser fix that covers it too.
export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  interactiveWidget: "resizes-content",
};

export default async function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  // Decide onboarding on the server so a blocked manager/provider gets ONLY the gate in the initial
  // HTML — the app is never rendered or sent, so it can't flash before the gate appears. Null for
  // owners/anonymous visitors, who pass through untouched.
  const gate = await loadOnboardingGate();

  return (
    <html
      lang="en"
      className={`${geistSans.variable} ${geistMono.variable} h-full antialiased`}
    >
      <body className="min-h-full flex flex-col">
        {gate?.blocked ? (
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
        )}
      </body>
    </html>
  );
}
