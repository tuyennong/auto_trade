# Changelog

Changes are recorded here before they are released. Entries describe completed work; planned features are tracked in issues and pull requests.

## Unreleased

### Added

- Typed MQL5 configuration and snapshot APIs with stable record IDs, source and confirmation times, lifecycle state, related records, and per-concept readiness diagnostics.
- Closed-candle Displacement, MSS, IFVG, BPR, previous-day/week levels, completed-session ranges, broker daily/weekly opening gaps, SMT divergence, and Power of Three detection.
- A versioned JSON contract and schema, UTF-8 snapshot exporter, shared fixtures, and a data-only MT5 export EA.
- Runnable JSON-reader examples in Python, TypeScript, C#, Go, Java, and Rust, with common filtering and output behavior.
- English documentation for installation, detection rules, snapshot access, data exchange, and local development; Japanese, Simplified Chinese, and Spanish README translations.
- Local commit validation covering Python, MQL5 static checks, MetaEditor compilation, detector runtime tests, language examples, and documentation links.
- Shared Git hooks, commit-bound `local/validation` status publication, and contribution rules for small commits, frequent pushes, PR review, and squash merges into protected `main`.
- Contributor conduct guidance, a private security reporting policy, and bug and feature request forms with reproducible environment and data requirements.
- An MT5-style demo login panel in the Android settings, with persisted local session identity, server/broker fields, and logout.
- Android backtest regression coverage for minimum and maximum history, every symbol, leverage, and invalid balance.

### Changed

- The manager shares closed-candle inputs, replays detection chronologically, and distinguishes unavailable history or failed evaluation from successful evaluation without detections.
- Configuration-based sessions use broker time; the existing GMT-offset initialization API retains its earlier meaning.
- English is the default documentation language. Existing Japanese reference guides remain available and are labeled in the documentation index.
- Python CSV processing, Windows terminal connectivity, ML training, and development dependencies are separated while existing entry points remain available.
- Validation runs locally instead of using GitHub hosted workflows. Merge checks are tied to the tested commit and current base.
- Library producer metadata identifies version `1.1.0`; the independent snapshot schema remains version `1.0`.

### Fixed

- The Android demo terminal's persisted repository layer, which a broad `data/` ignore rule had excluded from the repository; generated Android build outputs are now ignored as well.
- Android backtest input errors are reported instead of silently falling back, rendering failures no longer crash the screen, and zero-trade runs explain which filters to relax.
- Backtest equity and drawdown now mark open positions to market on every bar, take-profit gaps fill at the bar open, and the configured leverage caps position size.
- Local Android builds no longer reserve 3 GB for Gradle plus a separate Kotlin daemon, which caused out-of-memory failures on small machines.
- MQL5 compilation failures in the data-export script and sample EA, while preserving existing initialization, update, cleanup, and getter interfaces.
- Zone state loss, unreachable breaker detection, and repeated liquidity-touch counting during updates.
- Failure propagation, stale signal handling, resource cleanup, drawing-object ownership, and visualizer display settings.
- CSV volume typing, partial history retrieval, chronological output, encoding, and Python OHLCV input validation.
- The placeholder repository URL in the library header.

The project remains available under the [MIT License](LICENSE).
