# Live MT5 auto-trading

> **Risk warning.** Trading leveraged products can lose more than you deposit.
> Nothing in this repository is financial advice, and no result here is a
> promise. Run every change on a demo account first, keep risk small, and
> never enable live trading on an account you cannot afford to lose.

## Why the Android app cannot log in to MT5

MetaTrader 5 has no public client API that an Android app can use to log in to
a broker, read quotes, and place orders. The terminal is closed software, and
the mobile app is not a remote control for it. The demo login in
`mobile/README.md` is a local simulation only: it stores a label, never
authenticates, and never sends anything over the network.

Real execution needs an endpoint that the broker already trusts. This
repository supports two:

| Path | Runs on | Real orders | Entry point |
| --- | --- | --- | --- |
| MQL5 Expert Advisor | MT5 terminal or VPS | Yes | [`Experts/SMC_Sample_EA.mq5`](../Experts/SMC_Sample_EA.mq5) |
| Python runner | Windows PC or VPS with MT5 installed | Yes, dry run by default | [`Python/live/runner.py`](../Python/live/runner.py) |

The Python runner attaches to a terminal that is already logged in. It can also
log in with credentials from environment variables, which are never written to
disk. The MQL5 EA is the more robust long-term option because it runs inside
the terminal itself; the Python runner is easier to inspect, test, and iterate.

## Python runner

### Requirements

- Windows with MetaTrader 5 installed and running.
- A broker account that allows algorithmic trading.
- Python 3.11 or newer and the MetaQuotes package:

```powershell
pip install MetaTrader5
```

### Dry run (default)

The runner never sends an order unless `--live` is present. Start with one
closed-bar evaluation:

```powershell
cd Python
python -m live.runner --symbol XAUUSDc --timeframe H1 --once
```

A dry run prints the setup and the exact lot, stop, and target it would use:

```text
INFO connected to Exness-MT5Real44 (REAL), balance 0.00 USC, leverage 1:2000
INFO 2026-11-05 03:00 DRY RUN BUY XAUUSDc lots=0.00 entry=... sl=... tp=...
```

If the account balance is zero, or the minimum lot would exceed the configured
risk, the runner logs a skip instead of rounding up. Rounding up to the minimum
lot is the usual reason small accounts take far more risk than intended.

### Live mode

Live orders require **all** of the following:

1. `--live`.
2. `MT5_LIVE_CONFIRM=YES` in the environment.
3. `--allow-real-account` when the terminal is on a real account.
4. A positive balance, no open position for the magic number, no kill switch,
   and a spread below the limit.

```powershell
$env:MT5_LOGIN = "12345678"          # optional: attach to a running session instead
$env:MT5_PASSWORD = "..."            # never commit or log this
$env:MT5_SERVER = "Broker-Server"
$env:MT5_LIVE_CONFIRM = "YES"

python -m live.runner --symbol XAUUSDc --timeframe H1 `
  --risk 0.5 --max-risk 2.0 --max-daily-loss 3.0 `
  --live --allow-real-account
```

Stop the runner at any time with `Ctrl+C`. To block new entries without
stopping the process, create a file named `STOP` in the working directory (or
point `MT5_KILL_SWITCH` at another path).

### Guards

| Guard | Default | Behaviour |
| --- | --- | --- |
| Risk per trade | 0.5% | Hard cap 2.0%; rejects the setting above it |
| Daily loss stop | 3.0% | No new entries after the limit |
| Open positions | 1 | One position per symbol and magic number |
| Trades per day | 1 | The rule is one closed-bar breakout per day |
| Spread | 0.50 price units | Skips wide-spread entries |
| Minimum lot | Broker value | Skips the trade if it would exceed the risk budget |
| Kill switch | `STOP` file | Blocks all new entries |

### Strategy

The measured rule is a Donchian breakout in the direction of a long EMA:

| Parameter | Value |
| --- | --- |
| Lookback | 70 closed H1 bars |
| Trend filter | EMA(200) |
| Stop | 2.0 × ATR(14) |
| Target | 2.0 R |
| Entry | Next tick after a closed bar |
| Filter | One position, spread limit, daily loss stop |

## What the data actually shows

The figures below were measured on **real Exness data** pulled from the locally
installed terminal (`XAUUSDc`, H1, 8,000 bars, current spread 0.24 USD,
2025–2026). They are in-sample and include only spread, not commission, swap,
slippage, or news gaps.

| Variant | Trades | Win rate | Avg R | Profit factor | First half R | Second half R |
| --- | --- | --- | --- | --- | --- | --- |
| Lookback 70, ATR 2.0, RR 2.0 | 146 | 43.2% | +0.287 | 1.50 | +0.347 | +0.227 |
| Lookback 40, ATR 2.0, RR 2.0 | 173 | 42.8% | +0.275 | 1.48 | +0.420 | +0.132 |
| Lookback 55, ATR 2.0, RR 2.0 | 160 | 42.5% | +0.267 | 1.46 | +0.415 | +0.119 |

The same sweep found **no edge** on `EURUSDc`, `GBPUSDc`, or `US30c` (average
−0.09 R to +0.00 R). Gold was the only symbol that held up in both halves, so
the runner defaults to gold rather than pretending the rule is universal.

These numbers are encouraging, not sufficient. Before risking money, add a
walk-forward split, commission and swap, a news filter, and at least one
additional market regime.

## The “double the account in 3–5 trades” target

This target is not a strategy. It is a bet on a short winning streak, and the
mathematics is unforgiving.

To double with a 1:2 reward/risk and **every trade winning**, the risk per
trade must be:

| Trades | Risk needed | Final equity |
| --- | --- | --- |
| 3 | 13.0% | 2.00× |
| 4 | 9.5% | 2.00× |
| 5 | 7.4% | 2.00× |

At the measured 42.5% win rate, a Monte Carlo simulation of 200,000 paths gives:

| Risk per trade | P(2× within 3) | P(2× within 4) | P(2× within 5) | P(−50% within 5) |
| --- | --- | --- | --- | --- |
| 2% | 0.0% | 0.0% | 0.0% | 0.0% |
| 5% | 0.0% | 0.0% | 0.0% | 0.0% |
| 7.5% | 0.0% | 0.0% | 1.4% | 0.0% |
| 10% | 0.0% | 3.2% | 3.2% | 0.0% |
| 13% | 7.6% | 7.5% | 13.3% | 6.3% |
| 20% | 7.6% | 20.9% | 20.9% | 6.3% |

The measured sample also contained losing streaks of **7** trades (lookback 70)
and **11** trades (lookback 20). At 10% risk, seven losses is a 52% drawdown.

With the measured +0.287 R expectancy, deterministic compounding needs roughly:

| Risk per trade | Trades to double |
| --- | --- |
| 0.5% | ~478 |
| 1.0% | ~239 |
| 2.0% | ~121 |
| 5.0% | ~48 |
| 10.0% | ~24 |

**Recommendation:** risk 0.5–2.0% per trade and accept that doubling takes tens
to hundreds of trades. Any plan that requires 7–13% per trade to double in a
handful of trades has a realistic chance of losing half the account first.

## Going-live checklist

1. Compile and attach the EA, or run the Python runner in dry mode, on a
   **demo** account for at least two to four weeks.
2. Compare every logged signal with the backtest and with the chart.
3. Confirm commission, swap, and spread are included in your expectations.
4. Start live with the smallest lot the broker allows, at ≤0.5% risk.
5. Keep the kill switch and the daily loss stop enabled.
6. Run on a VPS close to the broker, with a stable connection and a UPS.
7. Review the log daily; stop after the first unexpected fill.

## Known limitations

- The live order path was not exercised in the development environment because
  the connected account had a zero balance. Only dry runs and refusal paths
  were verified.
- The backtest is in-sample, single-symbol, and roughly one year long.
- Commission, swap, slippage, news gaps, partial fills, and requotes are not
  modelled.
- There is no position recovery, trailing stop, or multi-symbol portfolio.
- The MQL5 sample EA is provided as an example and has not been compiled in
  this environment either; validate it in MetaEditor before use.
