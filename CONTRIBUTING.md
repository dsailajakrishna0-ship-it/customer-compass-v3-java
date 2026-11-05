# Contributing

This is a personal learning repo, but changes follow a small, strict flow so history stays readable.

1. Work is planned in [`docs/roadmap.md`](docs/roadmap.md). Each sub-stage (for example `J0.1`) is one pull request.
2. Branch from `main`: `chore/J0.1-short-name`, `feat/J3.1-vector-store`, `docs/J0.2-readme`.
3. Open a pull request. `main` is protected: no direct pushes, squash merge only, linear history.
4. Keep PRs small and focused, with the sub-stage number in the title and its exit criteria in the description.
5. Record tool choices as short decision records in `docs/adr/`.
6. Before pushing, run the stage's tests (`./mvnw verify`); CI runs the same checks once added.
7. Never commit secrets or real personal data. Use synthetic data only.

Use the `dsailajakrishna0-ship-it` identity for commits and `gh` in this repo.
