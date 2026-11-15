import { describe, expect, it } from "vitest";
import { API_BASE_URL } from "./client";
import { APPROACHES, approachBaseUrl, isMainApproach, MAIN_APPROACH_ID } from "./approaches";

describe("approaches", () => {
  it("starts with the application on the address every other request uses", () => {
    expect(APPROACHES[0]).toMatchObject({ id: MAIN_APPROACH_ID, baseUrl: API_BASE_URL });
  });

  it("gives every approach its own address", () => {
    const urls = APPROACHES.map((a) => a.baseUrl);
    expect(new Set(urls).size).toBe(urls.length);
  });

  it("falls back to the application for an empty or unknown id", () => {
    expect(approachBaseUrl("")).toBe(API_BASE_URL);
    expect(approachBaseUrl("nope")).toBe(API_BASE_URL);
    expect(approachBaseUrl("handWritten")).toBe("http://localhost:18101");
  });

  it("treats empty and 'main' as the application itself", () => {
    expect(isMainApproach("")).toBe(true);
    expect(isMainApproach("main")).toBe(true);
    expect(isMainApproach("handWritten")).toBe(false);
  });
});
