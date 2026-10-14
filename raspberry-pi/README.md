# Raspberry Pi: сервер выдачи кофе

Планшет на роботе решает, давать ли кофе и **какую ячейку открыть**, и отправляет команду
на Raspberry Pi по HTTP в локальной сети. Задача Pi: открыть замок нужной ячейки и ответить.

## Быстрый старт

```bash
pip install -r requirements.txt
python server.py            # слушает 0.0.0.0:5000
```

Вся логика железа помещается в одну функцию `open_cell(side, slot)` в [server.py](server.py).
Сейчас там заглушка, которая просто печатает номер ячейки.

## Протокол

```
POST http://<ip-pi>:5000/open
Content-Type: application/json
X-Token: <токен>                   # только если задан

{"side": "left", "slot": 3, "request_id": "0f6c2b1e-..."}
```

| Поле | Значение |
|---|---|
| `side` | `"left"` или `"right"`: колонка ячеек |
| `slot` | `1`…`10`: номер ячейки в колонке |
| `request_id` | уникален для каждой выдачи; повтор с тем же id **не должен** открывать ячейку второй раз |

Ответ:
- `200 {"status":"ok"}`: ячейка открыта. Планшет пометит её пустой и покажет ✓.
- Что угодно другое (код ≠ 200, `status` ≠ `ok`, нет ответа за 5 с): ошибка.
  Человек увидит «Ошибка выдачи», в базу лиц он не попадёт, ячейка останется «полной».

Таймауты на стороне планшета: подключение 3 с, ответ 5 с. Если замку нужно больше времени,
отвечайте сразу после подачи команды на открытие.

`GET /health` возвращает `{"status":"ok"}`, это удобно для проверки связи.

## Подключение к планшету

1. Pi и планшет в одной Wi-Fi сети. Pi лучше дать статический IP (или резерв DHCP на роутере).
2. На планшете откройте админку (5 быстрых нажатий в левый верхний угол, пароль) →
   «Выдача кофе» → «Raspberry Pi по HTTP» → адрес `http://<ip-pi>:5000` → «Сохранить».
3. Токен необязателен. Если нужен, задайте одинаковый в админке и в переменной `COFFEE_TOKEN` на Pi.

## Проверка без планшета

```bash
curl -X POST http://<ip-pi>:5000/open -H "Content-Type: application/json" \
     -d '{"side":"left","slot":1,"request_id":"test-1"}'
```

## Автозапуск (systemd)

```ini
# /etc/systemd/system/coffee.service
[Unit]
Description=Coffee dispenser server
After=network-online.target

[Service]
WorkingDirectory=/home/pi/CoffeFaceID/raspberry-pi
ExecStart=/usr/bin/python3 server.py
Environment=COFFEE_TOKEN=
Restart=always

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl enable --now coffee
```
