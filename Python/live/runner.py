"""Dry-run-first auto-trade runner for MetaTrader 5.

The default mode only logs what it would do. Sending a live order requires all
of the following:

* ``--live``
* ``MT5_LIVE_CONFIRM=YES`` in the environment
* for a real account, ``--allow-real-account``
* a positive account balance and no kill-switch file

Credentials are read from ``MT5_LOGIN`` / ``MT5_PASSWORD`` / ``MT5_SERVER`` and
are never written to disk.
"""

from __future__ import annotations

import argparse
import logging
import os
import sys
import time
from datetime import date, datetime, timezone
from pathlib import Path

from .broker import BUY, Mt5Broker
from .strategy import (
    StrategyConfig,
    evaluate,
    position_size,
    trades_to_double,
    validate_risk,
)

LOGGER = logging.getLogger("smc.live")

TIMEFRAMES = ("M5", "M15", "H1", "H4", "D1")


def timeframe_constant(name: str) -> int:
    import MetaTrader5 as mt5

    mapping = {
        "M5": mt5.TIMEFRAME_M5,
        "M15": mt5.TIMEFRAME_M15,
        "H1": mt5.TIMEFRAME_H1,
        "H4": mt5.TIMEFRAME_H4,
        "D1": mt5.TIMEFRAME_D1,
    }
    try:
        return mapping[name.upper()]
    except KeyError as error:
        raise ValueError(f"unsupported timeframe: {name}") from error


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--symbol", default="XAUUSDc", help="broker symbol name")
    parser.add_argument("--timeframe", default="H1", choices=TIMEFRAMES)
    parser.add_argument("--risk", type=float, default=0.5, help="risk per trade in percent")
    parser.add_argument("--max-risk", type=float, default=2.0, help="hard risk cap in percent")
    parser.add_argument("--max-daily-loss", type=float, default=3.0, help="stop after this daily loss in percent")
    parser.add_argument("--max-spread", type=float, default=0.50, help="maximum spread in price units")
    parser.add_argument("--max-lot", type=float, default=0.0, help="lot cap; 0 uses the broker maximum")
    parser.add_argument("--lookback", type=int, default=70)
    parser.add_argument("--ema", type=int, default=200)
    parser.add_argument("--atr-period", type=int, default=14)
    parser.add_argument("--atr-mult", type=float, default=2.0)
    parser.add_argument("--reward-risk", type=float, default=2.0)
    parser.add_argument("--magic", type=int, default=20261105)
    parser.add_argument("--deviation", type=int, default=20)
    parser.add_argument("--heartbeat", type=int, default=15, help="poll interval in seconds")
    parser.add_argument("--once", action="store_true", help="evaluate one closed bar and exit")
    parser.add_argument("--live", action="store_true", help="send real orders (default is dry run)")
    parser.add_argument(
        "--allow-real-account",
        action="store_true",
        help="required together with --live when the terminal is logged into a real account",
    )
    parser.add_argument("--log-file", type=Path, help="optional log file")
    return parser


class Runner:
    def __init__(self, args: argparse.Namespace) -> None:
        self.args = args
        self.config = StrategyConfig(
            lookback=args.lookback,
            ema_period=args.ema,
            atr_period=args.atr_period,
            atr_mult=args.atr_mult,
            reward_risk=args.reward_risk,
        )
        self.config.validate()
        validate_risk(args.risk, args.max_risk)

        self.broker = Mt5Broker(
            symbol=args.symbol,
            magic=args.magic,
            deviation=args.deviation,
            dry_run=not args.live,
        )
        self.day: date | None = None
        self.day_start_balance = 0.0
        self.last_bar_time = 0
        self.trades_today = 0

    # -- guards ----------------------------------------------------------
    def _kill_switch(self) -> bool:
        path = Path(os.environ.get("MT5_KILL_SWITCH", "STOP"))
        return path.exists()

    def _roll_day(self, balance: float) -> None:
        today = datetime.now(timezone.utc).date()
        if self.day != today:
            self.day = today
            self.day_start_balance = balance
            self.trades_today = 0
            LOGGER.info("new trading day %s, start balance %.2f", today, balance)

    def _guard(self) -> str | None:
        if self._kill_switch():
            return "kill-switch file present"
        account = self.broker.account()
        self._roll_day(account.balance)
        if account.balance <= 0:
            return "account balance is zero"
        if self.trades_today >= 1:
            return "already traded today"
        if self.broker.open_positions():
            return "a position is already open"
        if self.day_start_balance > 0:
            loss = (self.day_start_balance - account.equity) / self.day_start_balance * 100.0
            if loss >= self.args.max_daily_loss:
                return f"daily loss limit reached ({loss:.2f}%)"
        symbol = self.broker.symbol_snapshot()
        if symbol.spread > self.args.max_spread:
            return f"spread {symbol.spread:.3f} above limit {self.args.max_spread:.3f}"
        return None

    # -- one evaluation --------------------------------------------------
    def step(self) -> None:
        account = self.broker.account()
        self._roll_day(account.balance)

        needed = max(self.config.lookback, self.config.ema_period, self.config.atr_period) + 50
        data = self.broker.rates(timeframe_constant(self.args.timeframe), needed)
        # The last bar is still forming; only closed bars may produce signals.
        high, low, close = data["high"][:-1], data["low"][:-1], data["close"][:-1]
        bar_time = data["time"][-2]

        if not self.args.once and bar_time <= self.last_bar_time:
            return
        self.last_bar_time = bar_time

        setup = evaluate(high, low, close, self.config)
        stamp = datetime.fromtimestamp(bar_time, tz=timezone.utc).strftime("%Y-%m-%d %H:%M")
        if setup is None:
            LOGGER.info("%s no setup on the closed %s bar", stamp, self.args.timeframe)
            return

        blocked = self._guard()
        if blocked:
            LOGGER.warning("%s setup skipped: %s", stamp, blocked)
            return

        bid, ask = self.broker.tick()
        entry = ask if setup.side == BUY else bid
        stop, target = setup.levels(entry)
        symbol = self.broker.symbol_snapshot()

        lots = position_size(
            balance=account.balance,
            risk_percent=self.args.risk,
            stop_distance=setup.atr,
            tick_value=symbol.tick_value,
            tick_size=symbol.tick_size,
            min_lot=symbol.volume_min,
            max_lot=self.args.max_lot if self.args.max_lot > 0 else symbol.volume_max,
            lot_step=symbol.volume_step,
            contract_size=symbol.contract_size,
            price=entry,
            leverage=float(account.leverage),
            free_margin=account.free_margin,
        )
        if lots <= 0:
            LOGGER.warning(
                "%s setup skipped: minimum lot %.2f would exceed %.2f%% risk",
                stamp,
                symbol.volume_min,
                self.args.risk,
            )
            return

        side = "BUY" if setup.side == BUY else "SELL"
        result = self.broker.open_market(setup.side, lots, stop, target)
        if result.get("ok"):
            self.trades_today += 1
            mode = "DRY RUN" if result.get("dry_run") else "LIVE"
            LOGGER.info(
                "%s %s %s lots=%.2f entry=%.5f sl=%.5f tp=%.5f",
                mode,
                side,
                self.args.symbol,
                lots,
                entry,
                stop,
                target,
            )
        else:
            LOGGER.error("%s order rejected: %s", stamp, result.get("reason"))

    # -- loops -----------------------------------------------------------
    def run_once(self) -> None:
        self.step()

    def run_forever(self) -> None:
        LOGGER.info(
            "watching %s %s every %ss; %s",
            self.args.symbol,
            self.args.timeframe,
            self.args.heartbeat,
            "DRY RUN" if not self.args.live else "LIVE",
        )
        while True:
            try:
                self.step()
            except Exception as error:  # keep the loop alive, but log loudly
                LOGGER.exception("step failed: %s", error)
            time.sleep(max(1, self.args.heartbeat))


def configure_logging(log_file: Path | None) -> None:
    handlers: list[logging.Handler] = [logging.StreamHandler(sys.stdout)]
    if log_file:
        log_file.parent.mkdir(parents=True, exist_ok=True)
        handlers.append(logging.FileHandler(log_file, encoding="utf-8"))
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
        handlers=handlers,
    )


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    configure_logging(args.log_file)

    if args.live:
        if os.environ.get("MT5_LIVE_CONFIRM") != "YES":
            LOGGER.error("refusing --live without MT5_LIVE_CONFIRM=YES")
            return 2
        LOGGER.warning("LIVE MODE: real orders will be sent after the confirmation below")

    runner = Runner(args)
    try:
        runner.broker.connect(
            login=int(os.environ["MT5_LOGIN"]) if "MT5_LOGIN" in os.environ else None,
            password=os.environ.get("MT5_PASSWORD"),
            server=os.environ.get("MT5_SERVER"),
            path=os.environ.get("MT5_PATH"),
        )
        account = runner.broker.account()
        LOGGER.info(
            "connected to %s (%s), balance %.2f %s, leverage 1:%d",
            account.server,
            "DEMO" if account.is_demo else "REAL",
            account.balance,
            account.currency,
            account.leverage,
        )
        if args.live and not account.is_demo and not args.allow_real_account:
            LOGGER.error("real account requires --allow-real-account")
            return 3
        LOGGER.info(
            "expectancy note: %.0f trades to double at %.2f%% risk and +0.29R per trade",
            trades_to_double(0.29, args.risk),
            args.risk,
        )
        if args.once:
            runner.run_once()
        else:
            runner.run_forever()
    except KeyboardInterrupt:
        LOGGER.info("stopped by user")
    except Exception as error:
        LOGGER.error("runner failed: %s", error)
        return 1
    finally:
        runner.broker.shutdown()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
