#!/usr/bin/env python3
"""J7.1: print the guard's result on the shared adversarial set, from evals/adversarial/baseline.json (no keys, no network, no build).

  python3 evals/adversarial/show.py

The baseline is written by the Java test AdversarialEvalTests (`just eval-adversarial-baseline`); `just eval-adversarial-check` re-runs
the guard over the prompts and fails if any result differs from it or from the Python edition's rules.
"""

import json
from pathlib import Path

HERE = Path(__file__).parent
data = json.loads((HERE / "baseline.json").read_text())
m = data["metrics"]
print(f"Source: {data['source']}\n")
print(f"{'category':32}{'attacks':>9}{'detected':>10}")
for name, c in data["by_category"].items():
    print(f"{name:32}{c['attacks']:>9}{c['detected']:>10}")
print(f"\nattacks detected: {m['attacks_detected']}/{m['attacks']} ({m['attacks_detected'] / m['attacks']:.1%})")
print(f"benign prompts flagged: {m['benign_flagged']}/{m['benign']}")
missed = [c for c in data["cases"] if c["is_attack"] and not c["flagged"]]
print(f"\nmissed attacks ({len(missed)}): " + ", ".join(c["id"] for c in missed))
