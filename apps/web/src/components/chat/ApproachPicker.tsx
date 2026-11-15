import { MAIN_APPROACH_ID, type Approach } from "../../api/approaches";

type ApproachPickerProps = {
  approaches: Approach[];
  /** the user's choice; empty means the application itself */
  value: string;
  /** id -> whether that comparison backend answered /health; the application itself is always available */
  status: Record<string, boolean>;
  onChange: (id: string) => void;
};

/** Radio buttons: which build of the tool-calling copilot answers (the application, or one comparison backend). */
export function ApproachPicker({ approaches, value, status, onChange }: ApproachPickerProps) {
  const selected = value || MAIN_APPROACH_ID;
  return (
    <fieldset className="mcp-picker approach-picker">
      <legend>Approach:</legend>
      {approaches.map((approach) => {
        const checking = approach.id !== MAIN_APPROACH_ID && status[approach.id] === undefined;
        const down = approach.id !== MAIN_APPROACH_ID && status[approach.id] === false;
        return (
          <label key={approach.id} className={down ? "mcp-option unavailable" : "mcp-option"}>
            <input
              type="radio"
              name="approach"
              value={approach.id}
              checked={selected === approach.id}
              disabled={down}
              onChange={() => onChange(approach.id)}
            />
            {approach.label}
            {down && <small> (not running)</small>}
            {checking && <small> (checking)</small>}
          </label>
        );
      })}
    </fieldset>
  );
}
