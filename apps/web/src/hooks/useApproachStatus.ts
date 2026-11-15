import { useEffect, useState } from "react";
import { APPROACHES, MAIN_APPROACH_ID, type Approach } from "../api/approaches";

/** Asks each comparison backend's /health once when the tool-calling mode is shown: id -> answering. */
export function useApproachStatus(enabled: boolean, approaches: Approach[] = APPROACHES): Record<string, boolean> {
  const [status, setStatus] = useState<Record<string, boolean>>({});

  useEffect(() => {
    if (!enabled) return;
    let cancelled = false;
    for (const approach of approaches) {
      if (approach.id === MAIN_APPROACH_ID) continue;
      fetch(`${approach.baseUrl}/health`, { signal: AbortSignal.timeout(2000) })
        .then((response) => response.ok)
        .catch(() => false)
        .then((ok) => { if (!cancelled) setStatus((current) => ({ ...current, [approach.id]: ok })); });
    }
    return () => { cancelled = true; };
  }, [enabled, approaches]);

  return status;
}
