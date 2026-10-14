"""
Сервер выдачи кофе для Raspberry Pi — заготовка для команды железа.

Планшет отправляет:
    POST /open  {"side": "left" | "right", "slot": 1..10, "request_id": "<uuid>"}
    (заголовок X-Token — если токен задан в админке планшета и в TOKEN ниже)

Ответ:
    200 {"status": "ok"}                 — ячейка открыта, выдача засчитана
    любой другой код/статус             — ошибка; человек не попадёт в базу,
                                           ячейка на планшете останется «полной»

Запуск:
    pip install -r requirements.txt
    python server.py
Проверка с ноутбука:
    curl -X POST http://<ip-pi>:5000/open -H "Content-Type: application/json" \
         -d '{"side":"left","slot":1,"request_id":"test-1"}'
"""

import os
import threading
from collections import OrderedDict

from flask import Flask, jsonify, request

PORT = int(os.environ.get("PORT", "5000"))
TOKEN = os.environ.get("COFFEE_TOKEN", "")  # пусто — без проверки
SLOTS_PER_SIDE = 10

app = Flask(__name__)
_lock = threading.Lock()
# Ответы на уже обработанные request_id: повтор запроса (таймаут на планшете) не выдаёт второй кофе.
_done: "OrderedDict[str, dict]" = OrderedDict()


def open_cell(side: str, slot: int) -> bool:
    """
    TODO (команда железа): открыть замок ячейки и вернуть True, если открылась.
    Если ячейка оказалась пустой или замок не сработал — вернуть False.
    """
    print(f"[STUB] открыть ячейку: {side} №{slot}")
    return True


@app.post("/open")
def open_endpoint():
    if TOKEN and request.headers.get("X-Token") != TOKEN:
        return jsonify(status="error", message="bad token"), 401

    data = request.get_json(silent=True) or {}
    side = data.get("side")
    slot = data.get("slot")
    request_id = data.get("request_id")
    if side not in ("left", "right") or not isinstance(slot, int) or not 1 <= slot <= SLOTS_PER_SIDE or not request_id:
        return jsonify(status="error", message="bad request"), 400

    with _lock:
        if request_id in _done:
            return jsonify(_done[request_id])
        ok = open_cell(side, slot)
        result = {"status": "ok"} if ok else {"status": "error", "message": "cell did not open"}
        _done[request_id] = result
        while len(_done) > 1000:
            _done.popitem(last=False)

    return jsonify(result), (200 if ok else 500)


@app.get("/health")
def health():
    return jsonify(status="ok")


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=PORT)
