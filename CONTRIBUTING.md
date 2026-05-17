# Contributing to DeCloud

Thanks for considering a contribution. DeCloud is built and maintained part-time, so clear bug reports and focused pull requests are deeply appreciated.

## Reporting bugs

Open a [GitHub issue](../../issues/new/choose) using the **Bug Report** template. Include:

- What you tried to do
- What actually happened
- Phone model + Android version (or PC OS for the desktop app)
- DeCloud version (Settings / About)
- Logs if available

If the bug touches user privacy or data security, **don't open a public issue** — see [SECURITY.md](./SECURITY.md).

## Suggesting features

Open a [GitHub issue](../../issues/new/choose) using the **Feature Request** template. Be specific about what you'd use it for — features grounded in real use cases get prioritized.

Note: features that conflict with the privacy promise (cloud uploads, telemetry, ads, account systems, anything that sends data off-device) **will not be accepted**, even as opt-in. That's the entire point of DeCloud.

## Pull requests

1. Fork the repo and create a branch from `main`.
2. Make your changes. Keep them focused — one PR per concern.
3. Match the existing code style (Kotlin idioms, no comments that just restate the code, no unrelated reformatting).
4. Test on a real device before opening the PR. Emulator-only changes are easy to break.
5. Open a PR using the template. Describe **what** changed and **why**.

For larger changes, please **open an issue first** to discuss the approach. It's frustrating to spend a weekend on a PR that needs to be redesigned.

## Code style — quick guidelines

- Kotlin: follow the standard Kotlin coding conventions.
- One screen of code per function when possible.
- Don't introduce abstractions unless there are two real users for them.
- No `TODO` / `FIXME` left in main — open a follow-up issue instead.
- No mocks for things you can run locally (cf. integration vs unit tests).
- UI strings: lower-case-first style for inline copy ("Send to PC" not "Send To PC").

## What I cannot accept

- Code under a non-GPL-compatible license
- Telemetry or analytics (even anonymous)
- Network requests to anything outside the user's local network
- Third-party advertising SDKs
- Background activity that runs without explicit user action

These constraints aren't negotiable — they define DeCloud.

## License

By contributing, you agree your contributions are licensed under the project's [GPL-3.0](./LICENSE).
