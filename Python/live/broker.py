"""Thin MetaTrader 5 broker adapter used by the auto-trade runner.

The adapter never stores credentials. If the terminal is already logged in it
attaches to that session; otherwise the runner passes credentials from the
environment. Every order goes through :meth:`Mt5Broker.open_market`, which
refuses to send anything while ``dry_run`` is true.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

try:  # The package is Windows-only; keep imports testable elsewhere.
    import MetaTrader5 as mt5
except ImportError:  # pragma: no cover - exercised only on unsupported hosts
    mt5 = None  # type: ignore[assignment]

BUY = 1
SELL = -1


@dataclass(frozen=True)
class AccountSnapshot:
    login: int
    server: str
    currency: str
    balance: float
    equity: float
    free_margin: float
    leverage: int
    is_demo: bool


@dataclass(frozen=True)
class SymbolSnapshot:
    name: str
    point: float
    digits: int
    tick_value: float
    tick_size: float
    contract_size: float
    volume_min: float
    volume_max: float
    volume_step: float
    stops_level: int
    spread: float


def _require_mt5() -> Any:
    if mt5 is None:
        raise RuntimeError(
            "MetaTrader5 package is not installed. Install it with "
            "'pip install MetaTrader5' on Windows and start the terminal."
        )
    return mt5


class Mt5Broker:
    """Attach to a running MT5 terminal and trade one symbol at a time."""

    def __init__(self, symbol: str, magic: int, deviation: int = 20, dry_run: bool = True) -> None:
        self.symbol = symbol
        self.magic = magic
        self.deviation = deviation
        self.dry_run = dry_run
        self._connected = False

    # -- session ---------------------------------------------------------
    def connect(
        self,
        login: int | None = None,
        password: str | None = None,
        server: str | None = None,
        path: str | None = None,
    ) -> None:
        api = _require_mt5()
        kwargs: dict[str, Any] = {}
        if login and password and server:
            kwargs = {"login": login, "password": password, "server": server}
        if path:
            kwargs["path"] = path
        if not api.initialize(**kwargs):
            raise RuntimeError(f"MT5 initialize failed: {api.last_error()}")

        terminal = api.terminal_info()
        if terminal is None or not terminal.connected:
            api.shutdown()
            raise RuntimeError("MT5 terminal is not connected to a broker")

        if not api.symbol_select(self.symbol, True):
            api.shutdown()
            raise RuntimeError(f"symbol {self.symbol!r} is not available")
        self._connected = True

    def shutdown(self) -> None:
        if mt5 is not None and self._connected:
            mt5.shutdown()
        self._connected = False

    # -- read-only helpers ----------------------------------------------
    def account(self) -> AccountSnapshot:
        api = _require_mt5()
        info = api.account_info()
        if info is None:
            raise RuntimeError(f"MT5 account_info failed: {api.last_error()}")
        return AccountSnapshot(
            login=int(info.login),
            server=str(info.server),
            currency=str(info.currency),
            balance=float(info.balance),
            equity=float(info.equity),
            free_margin=float(info.margin_free),
            leverage=int(info.leverage),
            is_demo=int(info.trade_mode) != 2,
        )

    def symbol_snapshot(self) -> SymbolSnapshot:
        api = _require_mt5()
        info = api.symbol_info(self.symbol)
        if info is None:
            raise RuntimeError(f"MT5 symbol_info failed: {api.last_error()}")
        return SymbolSnapshot(
            name=str(info.name),
            point=float(info.point),
            digits=int(info.digits),
            tick_value=float(info.trade_tick_value),
            tick_size=float(info.trade_tick_size),
            contract_size=float(info.trade_contract_size),
            volume_min=float(info.volume_min),
            volume_max=float(info.volume_max),
            volume_step=float(info.volume_step),
            stops_level=int(info.trade_stops_level),
            spread=float(info.spread) * float(info.point),
        )

    def rates(self, timeframe: int, count: int) -> dict[str, list[float] | list[int]]:
        api = _require_mt5()
        data = api.copy_rates_from_pos(self.symbol, timeframe, 0, count)
        if data is None or len(data) == 0:
            raise RuntimeError(f"MT5 copy_rates_from_pos failed: {api.last_error()}")
        return {
            "time": [int(row["time"]) for row in data],
            "open": [float(row["open"]) for row in data],
            "high": [float(row["high"]) for row in data],
            "low": [float(row["low"]) for row in data],
            "close": [float(row["close"]) for row in data],
        }

    def tick(self) -> tuple[float, float]:
        api = _require_mt5()
        tick = api.symbol_info_tick(self.symbol)
        if tick is None:
            raise RuntimeError(f"MT5 symbol_info_tick failed: {api.last_error()}")
        return float(tick.bid), float(tick.ask)

    def open_positions(self) -> list[Any]:
        api = _require_mt5()
        positions = api.positions_get(symbol=self.symbol) or ()
        return [p for p in positions if int(p.magic) == self.magic]

    # -- order entry -----------------------------------------------------
    def _filling_mode(self) -> int:
        api = _require_mt5()
        info = api.symbol_info(self.symbol)
        if info is None:
            return api.ORDER_FILLING_RETURN
        # SYMBOL_FILLING_FOK = 1, SYMBOL_FILLING_IOC = 2.
        if int(info.filling_mode) & 1:
            return api.ORDER_FILLING_FOK
        if int(info.filling_mode) & 2:
            return api.ORDER_FILLING_IOC
        return api.ORDER_FILLING_RETURN

    def open_market(
        self,
        side: int,
        lots: float,
        stop_loss: float,
        take_profit: float,
        comment: str = "SMC trend breakout",
    ) -> dict[str, Any]:
        """Send a market order, or describe it when ``dry_run`` is true."""
        api = _require_mt5()
        bid, ask = self.tick()
        price = ask if side == BUY else bid
        info = self.symbol_snapshot()

        minimum = info.stops_level * info.point
        if minimum > 0:
            if abs(price - stop_loss) < minimum or abs(price - take_profit) < minimum:
                return {"ok": False, "reason": "stop or target closer than broker stops level"}

        if self.dry_run:
            return {
                "ok": True,
                "dry_run": True,
                "side": side,
                "lots": lots,
                "price": price,
                "sl": stop_loss,
                "tp": take_profit,
            }

        request = {
            "action": api.TRADE_ACTION_DEAL,
            "symbol": self.symbol,
            "volume": float(lots),
            "type": api.ORDER_TYPE_BUY if side == BUY else api.ORDER_TYPE_SELL,
            "price": price,
            "sl": float(stop_loss),
            "tp": float(take_profit),
            "deviation": self.deviation,
            "magic": self.magic,
            "comment": comment,
            "type_time": api.ORDER_TIME_GTC,
            "type_filling": self._filling_mode(),
        }
        result = api.order_send(request)
        if result is None:
            return {"ok": False, "reason": f"order_send returned None: {api.last_error()}"}
        ok = result.retcode == api.TRADE_RETCODE_DONE
        return {
            "ok": ok,
            "retcode": int(result.retcode),
            "order": int(result.order),
            "price": float(result.price),
            "comment": str(result.comment),
        }
