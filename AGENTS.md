## Release status

- The compatibility baseline is the first public release, 0.1.0. Earlier development versions do not require compatibility support.
- Treat released save formats, item IDs, configuration, commands, and network protocols as compatibility contracts. Preserve existing user data. When a breaking change is necessary, provide appropriate migration or explicit version rejection and document the change.
- Update affected code, tests, and documentation together. Verify upgrades from released formats and ensure existing functionality remains correct.

## Verification

- See [docs/testing.md](docs/testing.md) for test commands, opt-in checks, and CI.
- Run Gradle builds and client game tests one at a time in a checkout; a concurrent compile can break a running game test.

## Changelog

- Record user-visible changes in `CHANGELOG.md` under **Unreleased** with the implementation.
- At release time, move those entries into a section with the version and release date, link the release, and start a fresh **Unreleased** section. Keep published entries as the history of what each version shipped.
