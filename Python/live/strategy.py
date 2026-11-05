"""Pure, broker-independent strategy and risk maths for the MT5 runner.

Nothing in this module talks to MetaTrader 5, so it can be unit tested on any
machine. It implements the trend-breakout rule measured in
``docs/LIVE_TRADING.md``: Donchian breakout in the direction of a long EMA,
ATR stop, fixed reward/risk target.
"""

from __future__ import annotations

from dataclasses import dataclass
from math import floor

BUY = 1
SELL = -1


@dataclass(frozen=True)
class StrategyConfig:
    """Parameters of the trend-breakout rule."""

    lookback: int = 70
    ema_period: int = 200
    atr_period: int = 14
    atr_mult: float = 2.0
    reward_risk: float = 2.0

    def validate(self) -> None:
        if self.lookback < 2:
            raise ValueError("lookback must be at least 2")
        if self.ema_period < 2:
            raise ValueError("ema_period must be at least 2")
        if self.atr_period < 2:
            raise ValueError("atr_period must be at least 2")
        if self.atr_mult <= 0:
            raise ValueError("atr_mult must be positive")
        if self.reward_risk <= 0:
            raise ValueError("reward_risk must be positive")


@dataclass(frozen=True)
class Setup:
    """A closed-bar breakout that the runner may trade on the next tick."""

    side: int
    atr: float
    upper: float
    lower: float
    trend: float
    close: float
    reward_risk: float = 2.0

    def levels(self, entry: float) -> tuple[float, float]:
        """Return ``(stop_loss, take_profit)`` for an actual entry price."""
        distance = self.atr
        if self.side == BUY:
            return entry - distance, entry + distance * self.reward_risk
        return entry + distance, entry - distance * self.reward_risk


def ema(values: list[float], period: int) -> list[float]:
    """Exponential moving average; entries before the first full window are NaN."""
    if period < 1:
        raise ValueError("period must be positive")
    out: list[float] = [float("nan")] * len(values)
    if len(values) < period:
        return out
    seed = sum(values[:period]) / period
    out[period - 1] = seed
    multiplier = 2.0 / (period + 1.0)
    for index in range(period, len(values)):
        out[index] = values[index] * multiplier + out[index - 1] * (1.0 - multiplier)
    return out


def atr(high: list[float], low: list[float], close: list[float], period: int = 14) -> list[float]:
    """Wilder's average true range, aligned with the input bars."""
    if not (len(high) == len(low) == len(close)):
        raise ValueError("high, low and close must have the same length")
    if period < 1:
        raise ValueError("period must be positive")

    out: list[float] = [float("nan")] * len(close)
    if len(close) <= period:
        return out

    true_ranges: list[float] = []
    for index in range(1, len(close)):
        true_ranges.append(
            max(
                high[index] - low[index],
                abs(high[index] - close[index - 1]),
                abs(low[index] - close[index - 1]),
            )
        )

    first = sum(true_ranges[:period]) / period
    out[period] = first
    for index in range(period + 1, len(close)):
        out[index] = (out[index - 1] * (period - 1) + true_ranges[index - 1]) / period
    return out


def donchian(high: list[float], low: list[float], index: int, lookback: int) -> tuple[float, float]:
    """Highest high and lowest low of the ``lookback`` bars before ``index``."""
    start = index - lookback
    if start < 0 or index > len(high):
        raise ValueError("not enough history for the requested lookback")
    upper = max(high[start:index])
    lower = min(low[start:index])
    return upper, lower


def evaluate(
    high: list[float],
    low: list[float],
    close: list[float],
    config: StrategyConfig | None = None,
) -> Setup | None:
    """Evaluate the most recent closed bar.

    Returns ``None`` unless price closes beyond the prior Donchian channel in
    the direction of the EMA trend.
    """
    config = config or StrategyConfig()
    config.validate()
    bars = len(close)
    needed = max(config.lookback, config.ema_period, config.atr_period) + 1
    if bars < needed:
        return None

    index = bars - 1
    upper, lower = donchian(high, low, index, config.lookback)
    trend = ema(close, config.ema_period)[index]
    average_range = atr(high, low, close, config.atr_period)[index]
    if trend != trend or average_range != average_range or average_range <= 0:
        return None

    if close[index] > upper and close[index] > trend:
        return Setup(BUY, average_range * config.atr_mult, upper, lower, trend, close[index], config.reward_risk)
    if close[index] < lower and close[index] < trend:
        return Setup(SELL, average_range * config.atr_mult, upper, lower, trend, close[index], config.reward_risk)
    return None


def position_size(
    *,
    balance: float,
    risk_percent: float,
    stop_distance: float,
    tick_value: float,
    tick_size: float,
    min_lot: float,
    max_lot: float,
    lot_step: float,
    contract_size: float,
    price: float,
    leverage: float,
    free_margin: float,
) -> float:
    """Lots that risk exactly ``risk_percent`` if the stop is hit.

    Returns ``0`` instead of rounding up to ``min_lot`` when the minimum lot
    would exceed the risk budget. Rounding up is the usual way small accounts
    silently take far more risk than configured.
    """
    if balance <= 0 or risk_percent <= 0 or stop_distance <= 0:
        return 0.0
    if tick_value <= 0 or tick_size <= 0 or lot_step <= 0 or contract_size <= 0 or price <= 0:
        return 0.0

    risk_amount = balance * risk_percent / 100.0
    money_per_lot = stop_distance / tick_size * tick_value
    if money_per_lot <= 0:
        return 0.0
    lots = risk_amount / money_per_lot

    if leverage > 0:
        margin_per_lot = contract_size * price / leverage
        if margin_per_lot > 0:
            lots = min(lots, free_margin / margin_per_lot)

    lots = min(lots, max_lot)
    lots = floor(lots / lot_step + 1e-9) * lot_step

    if lots + 1e-9 < min_lot:
        return 0.0
    return round(lots, 8)


def validate_risk(risk_percent: float, max_risk_percent: float = 2.0) -> None:
    """Reject a risk setting outside the supported hard cap."""
    if not (0 < risk_percent <= max_risk_percent):
        raise ValueError(
            f"risk_percent must be greater than 0 and at most {max_risk_percent}"
        )


def trades_to_double(expectancy_r: float, risk_percent: float) -> float:
    """Approximate number of trades needed to double at a fixed risk size.

    This is the deterministic compounding estimate, not a promise. It exists
    so the documentation can compare a 3-5 trade target with what an edge
    actually implies.
    """
    if expectancy_r <= 0 or risk_percent <= 0:
        return float("inf")
    return 0.6931471805599453 / (expectancy_r * risk_percent / 100.0)
