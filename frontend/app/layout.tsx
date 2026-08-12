import type { Metadata, Viewport } from "next";
import { Suspense } from "react";
import { Geist, Geist_Mono } from "next/font/google";
import GateCheck from "./components/GateCheck";
import { LoadingScreen } from "./components/Spinner";
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

// Not async, deliberately: GateCheck's own onboarding-gate fetch lives inside a Suspense boundary
// below rather than a top-level await here. An await right in the layout would block the entire
// <html>/<body> shell from streaming — on a slow/flaky backend round trip, the browser shows a
// completely blank tab (no spinner at all, since no route's own loading.tsx gets a chance to run)
// until it resolves, which is exactly the "occasionally just blank, then it loads" bug this fixes.
// Wrapping GateCheck in Suspense lets Next.js stream the shell + this fallback immediately instead.
export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html
      lang="en"
      className={`${geistSans.variable} ${geistMono.variable} h-full antialiased`}
    >
      <body className="min-h-full flex flex-col">
        <Suspense fallback={<LoadingScreen />}>
          <GateCheck>{children}</GateCheck>
        </Suspense>
      </body>
    </html>
  );
}
