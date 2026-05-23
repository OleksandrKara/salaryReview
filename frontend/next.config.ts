import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Produces .next/standalone with a minimal Node server + only required
  // node_modules — keeps the runtime Docker image small.
  output: "standalone",
};

export default nextConfig;
