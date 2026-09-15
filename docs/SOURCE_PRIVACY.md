# Source repository and private configuration

This repository contains application source, tests, documentation and original synthetic fixtures. It must not contain a bank's internal endpoint, credentials, database dumps, customer records, uploaded evidence, saved investigations or model artifacts.

## Configure a private installation

Use environment variables or ignored local configuration to supply inquiry URLs, service identities and secrets. Example domains in the published contracts are reserved placeholders; they are not deployed bank endpoints. Keep TLS certificate validation enabled when configuring an actual endpoint.

The following remain local and are excluded by `.gitignore`:

- `runtime/` and `dbdata/`: runtime settings, databases, model state, evidence and saved answers.
- `.env` and other private environment files; `.env.example` contains only documented demo defaults.
- `outputs/` and generated build/export artifacts.
- `compose.override.yaml`: workstation-specific service overrides.
- Local `AGENTS.md` instructions and generated packaging receipts with workstation paths.
- Private keys, certificate containers, database files, models and compiled binaries.

Original synthetic test identifiers and local demo credentials are explicitly test data. They must not be used as production secrets. Saved private knowledge and payment evidence are not part of the synthetic fixture dataset.

## Before pushing a change

Review `git diff --cached` and the staged filename list. Confirm that examples use reserved domains, no private endpoint or credential is staged, and any new workbook/image contains only publishable data. Ignore rules do not remove files that were previously tracked; remove such files from the index before committing. If a secret has already been published, revoke it and handle history cleanup separately.

The initial source release was prepared from an empty repository after scanning staged content and reviewing the included workbook and screenshots. Later changes require the same review; this document is not a guarantee that arbitrary future files are safe to publish.
