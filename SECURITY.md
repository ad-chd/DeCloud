# Security Policy

## Reporting a vulnerability

If you discover a security issue in DeCloud — anything that could affect user data, privacy, or device safety — **please do not open a public GitHub issue.**

Instead, email the developer directly:

**adityachaudhary703@gmail.com**

Use the subject line: `[SECURITY] DeCloud — short description`

Please include:
- A description of the issue
- Steps to reproduce
- The version of the app and the device / OS you tested on
- (Optional) A suggested fix or mitigation

You'll get a reply within 7 days. Once a fix ships, you're welcome to disclose the issue publicly with credit if you'd like.

## Scope

DeCloud's threat model is simple: **the app should never send your data anywhere except the device(s) you intend.** Any deviation from that — covert telemetry, accidental external requests, leaks via logs, third-party SDK behavior — is in scope and worth reporting.

Out of scope: anything that requires the user's device to already be physically compromised (e.g. malware installed by the user). DeCloud cannot defend against an attacker who already owns your phone.

## Thank you

Security researchers genuinely improve the project for everyone. If you take the time to find and report something responsibly, it is appreciated.
