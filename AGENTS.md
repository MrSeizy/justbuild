## Release status

- This mod is unreleased. Backward compatibility with earlier development versions is not a requirement.
- When changing commands, configuration, storage, or network protocols, make the intended change directly and update the affected code, tests, and documentation together. Do not add legacy aliases, compatibility layers, or migrations solely to support old development versions.
- Verify that the current behavior works and existing functionality remains correct. Revisit this policy when the mod has its first public release or the maintainer explicitly changes it.

## Verification

- See [docs/testing.md](docs/testing.md) for test commands, opt-in checks, and CI.
- Run Gradle builds and client game tests one at a time in a checkout; a concurrent compile can break a running game test.
