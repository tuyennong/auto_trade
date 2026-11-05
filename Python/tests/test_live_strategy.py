"""Unit tests for the broker-independent strategy and risk maths."""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from live.strategy import (  # noqa: E402
    BUY,
    SELL,
    StrategyConfig,
    atr,
    ema,
    evaluate,
    position_size,
    trades_to_double,
    validate_risk,
)


def flat_history(bars: int = 260) -> tuple[list[float], list[float], list[float]]:
    """Quiet range followed by one breakout bar."""
    high: list[float] = []
    low: list[float] = []
    close: list[float] = []
    for index in range(bars):
        mid = 100.0 + (0.2 if index % 2 else -0.2)
        close.append(mid)
        high.append(mid + 1.0)
        low.append(mid - 1.0)
    # Final closed bar breaks out upward.
    close[-1] = 130.0
    high[-1] = 131.0
    low[-1] = 124.0
    return high, low, close


class IndicatorTests(unittest.TestCase):
    def test_ema_seeds_and_smooths(self) -> None:
        values = [1.0, 2.0, 3.0, 4.0, 5.0]
        result = ema(values, 3)
        self.assertNotEqual(result[1], result[1])  # NaN before the first window
        self.assertAlmostEqual(result[2], 2.0)
        self.assertAlmostEqual(result[3], 3.0)
        self.assertAlmostEqual(result[4], 4.0)

    def test_atr_of_constant_range_is_the_range(self) -> None:
        high = [10.0] * 30
        low = [9.0] * 30
        close = [9.5] * 30
        result = atr(high, low, close, 14)
        self.assertAlmostEqual(result[14], 1.0)
        self.assertAlmostEqual(result[-1], 1.0)

    def test_atr_rejects_mismatched_lengths(self) -> None:
        with self.assertRaises(ValueError):
            atr([1.0], [1.0, 2.0], [1.0], 2)


class SignalTests(unittest.TestCase):
    def test_upward_breakout_is_a_buy(self) -> None:
        high, low, close = flat_history()
        setup = evaluate(high, low, close, StrategyConfig(lookback=20, ema_period=50))
        self.assertIsNotNone(setup)
        assert setup is not None
        self.assertEqual(setup.side, BUY)
        stop, target = setup.levels(130.0)
        self.assertLess(stop, 130.0)
        self.assertGreater(target, 130.0)
        self.assertAlmostEqual(130.0 - stop, (target - 130.0) / 2.0)

    def test_downward_breakout_is_a_sell(self) -> None:
        high, low, close = flat_history()
        close[-1] = 70.0
        low[-1] = 69.0
        high[-1] = 76.0
        setup = evaluate(high, low, close, StrategyConfig(lookback=20, ema_period=50))
        self.assertIsNotNone(setup)
        assert setup is not None
        self.assertEqual(setup.side, SELL)
        stop, target = setup.levels(70.0)
        self.assertGreater(stop, 70.0)
        self.assertLess(target, 70.0)

    def test_short_history_has_no_signal(self) -> None:
        high, low, close = flat_history(bars=50)
        self.assertIsNone(evaluate(high, low, close, StrategyConfig(lookback=20, ema_period=200)))


class RiskTests(unittest.TestCase):
    def test_lot_size_matches_the_risk_budget(self) -> None:
        lots = position_size(
            balance=10_000.0,
            risk_percent=1.0,
            stop_distance=10.0,
            tick_value=1.0,
            tick_size=0.01,
            min_lot=0.01,
            max_lot=100.0,
            lot_step=0.01,
            contract_size=100.0,
            price=2_000.0,
            leverage=200.0,
            free_margin=10_000.0,
        )
        # 100 USD risk / (10 / 0.01 * 1) = 0.10 lots.
        self.assertAlmostEqual(lots, 0.10)

    def test_minimum_lot_above_budget_returns_zero(self) -> None:
        lots = position_size(
            balance=100.0,
            risk_percent=1.0,
            stop_distance=10.0,
            tick_value=1.0,
            tick_size=0.01,
            min_lot=1.0,
            max_lot=100.0,
            lot_step=0.01,
            contract_size=100.0,
            price=2_000.0,
            leverage=200.0,
            free_margin=100.0,
        )
        self.assertEqual(lots, 0.0)

    def test_lot_size_is_capped_by_max_lot(self) -> None:
        lots = position_size(
            balance=1_000_000.0,
            risk_percent=1.0,
            stop_distance=1.0,
            tick_value=1.0,
            tick_size=0.01,
            min_lot=0.01,
            max_lot=0.05,
            lot_step=0.01,
            contract_size=100.0,
            price=2_000.0,
            leverage=200.0,
            free_margin=1_000_000.0,
        )
        self.assertAlmostEqual(lots, 0.05)

    def test_risk_above_cap_is_rejected(self) -> None:
        with self.assertRaises(ValueError):
            validate_risk(2.01, 2.0)
        with self.assertRaises(ValueError):
            validate_risk(0.0, 2.0)
        validate_risk(0.5, 2.0)

    def test_doubling_estimate_uses_compounding(self) -> None:
        self.assertAlmostEqual(trades_to_double(0.29, 2.0), 119.5, delta=0.5)
        self.assertEqual(trades_to_double(-0.1, 2.0), float("inf"))


if __name__ == "__main__":
    unittest.main()
