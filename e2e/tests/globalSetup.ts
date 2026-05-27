import { sweepTestProviders } from './cleanup';

/**
 * Runs once before any tests. Removes anything left behind by a previous
 * crashed run — keeps the DB clean even when the previous run died hard.
 */
export default async function globalSetup() {
  const removed = await sweepTestProviders();
  if (removed > 0) {
    console.log(`[globalSetup] swept ${removed} stale test provider(s)`);
  }
}
