import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import AssistantWidget from "./components/AssistantWidget";
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
        {children}
        {/* Global assistant — self-gates to OWNER/MANAGER, renders nothing otherwise. */}
        <AssistantWidget />
      </body>
    </html>
  );
}
