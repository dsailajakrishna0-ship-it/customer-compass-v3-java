/**
 * J8: the ways the tool-calling copilot can be built (see docs/roadmap.md, Stage J8). The first entry is the
 * application itself, on the address every other request uses; the others are the small comparison backends in
 * comparisons/tool-calling/, each started on its own port. Edit this list to add a backend or change a port.
 *
 * A comparison backend serves only the tool-calling route (POST /api/v1/chat/tools) and /health, so the choice
 * changes the address of that one route; everything else in the app keeps using the main address.
 */
import { API_BASE_URL } from "./client";

export type Approach = {
  id: string;
  label: string;
  baseUrl: string;
};

export const MAIN_APPROACH_ID = "main";

export const APPROACHES: Approach[] = [
  { id: MAIN_APPROACH_ID, label: "The application (Spring AI and our own gateway)", baseUrl: API_BASE_URL },
  { id: "handWritten", label: "handWritten (plain Java)", baseUrl: "http://localhost:18101" },
  { id: "withSpringAi", label: "withSpringAi", baseUrl: "http://localhost:18102" },
  { id: "withLangchain4j", label: "withLangchain4j", baseUrl: "http://localhost:18103" },
];

/** The address for an approach id; an empty or unknown id means the application itself. */
export function approachBaseUrl(id: string, approaches: Approach[] = APPROACHES): string {
  return approaches.find((a) => a.id === id)?.baseUrl ?? API_BASE_URL;
}

/** True for the application itself: nothing about the request changes. */
export function isMainApproach(id: string): boolean {
  return !id || id === MAIN_APPROACH_ID;
}
