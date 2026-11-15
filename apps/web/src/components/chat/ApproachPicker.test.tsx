import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ApproachPicker } from "./ApproachPicker";
import type { Approach } from "../../api/approaches";

const approaches: Approach[] = [
  { id: "main", label: "The application", baseUrl: "http://localhost:8001" },
  { id: "handWritten", label: "handWritten (plain Java)", baseUrl: "http://localhost:18101" },
  { id: "withSpringAi", label: "withSpringAi", baseUrl: "http://localhost:18102" },
];

describe("ApproachPicker", () => {
  it("selects the application when nothing was chosen", () => {
    render(<ApproachPicker approaches={approaches} value="" status={{}} onChange={() => {}} />);
    expect(screen.getByLabelText(/The application/)).toBeChecked();
    expect(screen.getByLabelText(/handWritten/)).not.toBeChecked();
  });

  it("disables a backend that did not answer and says so", () => {
    render(<ApproachPicker approaches={approaches} value="" status={{ handWritten: true, withSpringAi: false }} onChange={() => {}} />);
    expect(screen.getByLabelText(/handWritten/)).toBeEnabled();
    expect(screen.getByLabelText(/withSpringAi/)).toBeDisabled();
    expect(screen.getByText("(not running)")).toBeInTheDocument();
  });

  it("never disables the application itself, even before any check", () => {
    render(<ApproachPicker approaches={approaches} value="" status={{}} onChange={() => {}} />);
    expect(screen.getByLabelText(/The application/)).toBeEnabled();
    expect(screen.getAllByText("(checking)")).toHaveLength(2);
  });

  it("reports the chosen approach's id", () => {
    const onChange = vi.fn();
    render(<ApproachPicker approaches={approaches} value="" status={{ handWritten: true }} onChange={onChange} />);
    fireEvent.click(screen.getByLabelText(/handWritten/));
    expect(onChange).toHaveBeenCalledWith("handWritten");
  });
});
