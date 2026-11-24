"""Бизнес-логика ИС «Кадр». Не зависит от веб-фреймворка, поэтому её легко проверять тестами."""
from datetime import datetime, timedelta

from . import security

BASE_PRICE_INCREASE = 100
PREMIUM_SEAT_SURCHARGE = 100

HOLD_MINUTES = 10          # бронь мест в корзине (ТЗ)
# Раскладка залов по 3D-модели: после какого места в ряду идёт проход
HALL_LAYOUTS = {"imax": {"aisles": [22], "size": (8, 43)}, "vip": {"aisles": []}, "vip2": {"aisles": [2, 4], "size": (7, 6)}}
BONUS_EARN = 0.05          # начисление 5% от оплаченной суммы
BONUS_MAX_SHARE = 0.30     # списание бонусами до 30% стоимости
MAX_FAILED_LOGINS = 5      # блокировка после 5 неудачных попыток входа
TICKET_TYPES = {"adult": ("Взрослый", 0), "reduced": ("Льготный", 100), "child": ("Детский", 150)}


class ApiError(Exception):
    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status
        self.message = message


def now() -> datetime:
    return datetime.now()


def fmt(dt: datetime) -> str:
    return dt.strftime("%Y-%m-%d %H:%M:%S")


# ---------------------------------------------------------------- пользователи

def public_user(u):
    return {
        "id": u["id"], "last_name": u["last_name"], "first_name": u["first_name"],
        "email": u["email"], "phone": u["phone"], "role": u["role"],
        "cinema_id": u["cinema_id"], "bonus": u["bonus"],
        "must_change_password": bool(u["must_change_password"]),
    }


def register(db, last_name, first_name, email, phone, password):
    last_name, first_name, email = last_name.strip(), first_name.strip(), email.strip().lower()
    for value in (last_name, first_name):
        if not security.NAME_RE.match(value):
            raise ApiError(400, "Ограничение до 30 символов. Ввод допустим только буквами русского алфавита")
    if len(email) > 50 or not security.EMAIL_RE.match(email):
        raise ApiError(400, "Неверный формат электронной почты")
    norm_phone = security.normalize_phone(phone)
    if not norm_phone:
        raise ApiError(400, "Телефон в формате +7 ___-___-__-__")
    problem = security.password_problem(password)
    if problem:
        raise ApiError(400, problem)
    if db.one("SELECT id FROM users WHERE email = ?", (email,)):
        raise ApiError(409, "Пользователь с такой почтой уже зарегистрирован")
    if db.one("SELECT id FROM users WHERE phone = ?", (norm_phone,)):
        raise ApiError(409, "Этот номер телефона уже используется")
    uid = db.run(
        "INSERT INTO users (last_name, first_name, email, phone, password_hash, role) VALUES (?, ?, ?, ?, ?, 'viewer')",
        (last_name[:1].upper() + last_name[1:], first_name[:1].upper() + first_name[1:], email, norm_phone,
         security.hash_password(password)),
    )
    db.commit()
    return issue_token(db, uid)


def login(db, email, password):
    u = db.one("SELECT * FROM users WHERE email = ?", (email.strip().lower(),))
    if not u:
        raise ApiError(401, "Неверная почта или пароль")
    if u["blocked"]:
        raise ApiError(403, "Учётная запись заблокирована. Обратитесь к администратору")
    if not security.check_password(password, u["password_hash"]):
        fails = u["failed_logins"] + 1
        blocked = 1 if fails >= MAX_FAILED_LOGINS else 0
        db.run("UPDATE users SET failed_logins = ?, blocked = ? WHERE id = ?", (fails, blocked, u["id"]))
        db.commit()
        left = MAX_FAILED_LOGINS - fails
        if blocked:
            raise ApiError(403, "Учётная запись заблокирована после 5 неудачных попыток")
        raise ApiError(401, f"Неверная почта или пароль. Осталось попыток: {left}")
    db.run("UPDATE users SET failed_logins = 0 WHERE id = ?", (u["id"],))
    db.commit()
    return issue_token(db, u["id"])


def issue_token(db, uid):
    token = security.new_token()
    db.run("INSERT INTO tokens (token, user_id, created_at) VALUES (?, ?, ?)", (token, uid, fmt(now())))
    db.commit()
    u = db.one("SELECT * FROM users WHERE id = ?", (uid,))
    return {"token": token, "user": public_user(u)}


def user_by_token(db, token):
    if not token:
        return None
    return db.one("SELECT u.* FROM tokens t JOIN users u ON u.id = t.user_id WHERE t.token = ? AND u.blocked = 0", (token,))


def logout(db, token):
    db.run("DELETE FROM tokens WHERE token = ?", (token,))
    db.commit()


# ---------------------------------------------------------------- афиша

def cinemas(db):
    rows = db.all(
        "SELECT c.id, c.name, c.city, c.address, COUNT(h.id) AS halls_count, "
        "COALESCE(SUM(h.rows_count * h.seats_per_row), 0) AS seats_count "
        "FROM cinemas c LEFT JOIN halls h ON h.cinema_id = c.id WHERE c.status = 'active' "
        "GROUP BY c.id, c.name, c.city, c.address ORDER BY c.city, c.id"
    )
    return [{k: (int(v) if k in ("halls_count", "seats_count") else v) for k, v in r.items()} for r in rows]


def _taken_count(db, session_id):
    t = _taken(db, session_id)
    return len(t)


def schedule(db, cinema_id, day):
    rows = db.all(
        "SELECT s.id, s.starts_at, s.format, s.price, h.name AS hall_name, h.rows_count, h.seats_per_row, "
        "f.id AS film_id, f.title, f.age, f.genre, f.duration_min, f.color, f.description, f.poster_v "
        "FROM sessions s JOIN halls h ON h.id = s.hall_id JOIN films f ON f.id = s.film_id "
        "WHERE h.cinema_id = ? AND s.starts_at LIKE ? ORDER BY f.id, s.starts_at",
        (cinema_id, day + "%"),
    )
    films = {}
    for r in rows:
        film = films.setdefault(r["film_id"], {
            "id": r["film_id"], "title": r["title"], "age": r["age"], "genre": r["genre"],
            "duration_min": r["duration_min"], "color": r["color"], "description": r["description"],
            "poster_v": r["poster_v"], "sessions": [],
        })
        capacity = r["rows_count"] * r["seats_per_row"]
        film["sessions"].append({
            "id": r["id"], "time": r["starts_at"][11:16], "starts_at": r["starts_at"], "format": r["format"],
            "price": r["price"], "hall_name": r["hall_name"], "free": capacity - _taken_count(db, r["id"]),
        })
    return {"date": day, "films": list(films.values())}


def _taken(db, session_id):
    rows = db.all(
        "SELECT t.row_n, t.seat_n FROM tickets t JOIN orders o ON o.id = t.order_id "
        "WHERE t.session_id = ? AND (o.status IN ('paid', 'reserved') OR (o.status = 'pending' AND o.expires_at > ?))",
        (session_id, fmt(now())),
    )
    return {(r["row_n"], r["seat_n"]) for r in rows}


def has_premium_seats(fmt_name, hall_name, model_url):
    hall_words = hall_name.upper().replace("-", " ").replace("·", " ").split()
    if model_url == "vip2" or "VIP" in hall_words:
        return False
    fmt_name = fmt_name.strip().upper()
    return model_url == "imax" or fmt_name == "2D" or fmt_name.startswith("IMAX")


def premium_seats(rows, cols):
    # Центральная зона расширена на два ряда к экрану и по два места влево и вправо.
    side = max(0, cols * 3 // 8 - 2)
    first_row = max(1, rows * 3 // 8 - 1)
    last_row = max(first_row, rows * 3 // 4)
    return {(r, c) for r in range(first_row, last_row + 1)
            for c in range(side + 1, cols - side + 1)}


def session_info(db, session_id):
    s = db.one(
        "SELECT s.*, h.name AS hall_name, h.rows_count, h.seats_per_row, h.model_url, h.cinema_id, "
        "c.name AS cinema_name, c.city, c.address, f.title, f.age, f.genre, f.duration_min, f.color, f.poster_v "
        "FROM sessions s JOIN halls h ON h.id = s.hall_id JOIN cinemas c ON c.id = h.cinema_id "
        "JOIN films f ON f.id = s.film_id WHERE s.id = ?",
        (session_id,),
    )
    if not s:
        raise ApiError(404, "Сеанс не найден")
    has_premium = has_premium_seats(s["format"], s["hall_name"], s["model_url"])
    seats = premium_seats(s["rows_count"], s["seats_per_row"]) if has_premium else set()
    return {
        "id": s["id"], "starts_at": s["starts_at"], "format": s["format"], "price": s["price"],
        "film": {"id": s["film_id"], "title": s["title"], "age": s["age"], "genre": s["genre"],
                 "duration_min": s["duration_min"], "color": s["color"], "poster_v": s["poster_v"]},
        "cinema": {"id": s["cinema_id"], "name": s["cinema_name"], "city": s["city"], "address": s["address"]},
        "hall": {"id": s["hall_id"], "name": s["hall_name"], "rows": s["rows_count"],
                 "seats_per_row": s["seats_per_row"], "model_url": s["model_url"],
                 "aisles": [27] if s["model_url"] == "imax" and s["seats_per_row"] == 54
                           else HALL_LAYOUTS.get(s["model_url"] or "", {}).get("aisles", [])},
        "taken": sorted([[r, c] for r, c in _taken(db, session_id)]),
        "premium_seats": [list(seat) for seat in sorted(seats)],
        "premium_surcharge": PREMIUM_SEAT_SURCHARGE if seats else 0,
    }


# ---------------------------------------------------------------- заказы

def ticket_price(base, ticket_type):
    if ticket_type not in TICKET_TYPES:
        raise ApiError(400, "Неизвестный тип билета")
    return max(200, base - TICKET_TYPES[ticket_type][1])


def create_order(db, user, session_id, seats):
    info = session_info(db, session_id)
    if info["starts_at"] <= now().strftime("%Y-%m-%d %H:%M"):
        raise ApiError(400, "Сеанс уже начался")
    if not seats:
        raise ApiError(400, "Выберите хотя бы одно место")
    if len(seats) > 10:
        raise ApiError(400, "За один раз можно купить не больше 10 билетов")
    hall = info["hall"]
    wanted = set()
    for s in seats:
        r, c = int(s["row"]), int(s["seat"])
        if not (1 <= r <= hall["rows"] and 1 <= c <= hall["seats_per_row"]):
            raise ApiError(400, "Такого места нет в зале")
        wanted.add((r, c))
    # отменяем прошлые неоплаченные брони этого пользователя на этот сеанс
    db.run("UPDATE orders SET status = 'cancelled' WHERE user_id = ? AND session_id = ? AND status = 'pending'",
           (user["id"], session_id))
    taken = _taken(db, session_id)
    if wanted & taken:
        db.rollback()
        raise ApiError(409, "Выбранное место уже занято. Пожалуйста, выберите другое")
    if single_gaps(wanted, taken, hall["seats_per_row"], hall["aisles"]):
        db.rollback()
        raise ApiError(400, "Нельзя оставлять одно свободное место между выбранными или занятыми местами. Выберите места рядом")
    created = now()
    oid = db.run(
        "INSERT INTO orders (user_id, session_id, status, created_at, expires_at) VALUES (?, ?, 'pending', ?, ?)",
        (user["id"], session_id, fmt(created), fmt(created + timedelta(minutes=HOLD_MINUTES))),
    )
    premium = {tuple(seat) for seat in info["premium_seats"]}
    for r, c in sorted(wanted):
        surcharge = info["premium_surcharge"] if (r, c) in premium else 0
        db.run(
            "INSERT INTO tickets (order_id, session_id, row_n, seat_n, type, price, code, seat_surcharge) "
            "VALUES (?, ?, ?, ?, 'adult', ?, ?, ?)",
            (oid, session_id, r, c, ticket_price(info["price"], "adult") + surcharge,
             security.ticket_code(), surcharge),
        )
    _recalc(db, oid, user, use_bonus=False)
    db.commit()
    return order_info(db, oid, user)


def single_gaps(wanted, taken, cols, aisles=()):
    """Места, которые остались бы одиночными «дырками» между выбранными/занятыми местами.

    Правило: нельзя покупать «через одно место» — между двумя занятыми местами,
    одно из которых выбрано покупателем, не должно оставаться ровно одно свободное.
    Места по разные стороны прохода (aisles: после какого места проход) соседями не считаются.
    """
    occupied = wanted | taken
    cut = set(aisles)
    gaps = set()
    for r in {r for r, _ in wanted}:
        for c in range(2, cols):
            if (r, c) in occupied or (c - 1) in cut or c in cut:
                continue
            left, right = (r, c - 1), (r, c + 1)
            if left in occupied and right in occupied and (left in wanted or right in wanted):
                gaps.add((r, c))
    return gaps


def _recalc(db, oid, user, use_bonus):
    o = db.one("SELECT * FROM orders WHERE id = ?", (oid,))
    tickets = db.all("SELECT price FROM tickets WHERE order_id = ?", (oid,))
    total = sum(t["price"] for t in tickets)
    bonus = 0
    if use_bonus:
        bonus = min(int(total * BONUS_MAX_SHARE), user["bonus"])
    db.run("UPDATE orders SET sum_total = ?, bonus_used = ?, total = ? WHERE id = ?", (total, bonus, total - bonus, oid))
    return o


def _own_order(db, oid, user):
    o = db.one("SELECT * FROM orders WHERE id = ?", (oid,))
    if not o or o["user_id"] != user["id"]:
        raise ApiError(404, "Заказ не найден")
    return o


def pay_order(db, user, oid, ticket_types, use_bonus, pay_method):
    o = _own_order(db, oid, user)
    if o["status"] != "pending":
        raise ApiError(400, "Заказ уже оформлен или отменён")
    if o["expires_at"] <= fmt(now()):
        db.run("UPDATE orders SET status = 'cancelled' WHERE id = ?", (oid,))
        db.commit()
        raise ApiError(410, "Время брони истекло, места освобождены. Выберите места заново")
    if pay_method not in ("card", "cash"):
        raise ApiError(400, "Неизвестный способ оплаты")
    types = {int(t["id"]): t["type"] for t in ticket_types or []}
    for t in db.all("SELECT id, type, price, seat_surcharge FROM tickets WHERE order_id = ?", (oid,)):
        tt = types.get(t["id"], t["type"])
        surcharge = t["seat_surcharge"]
        # В неоплаченном заказе уже сохранена цена взрослого билета на момент брони.
        price = ticket_price(t["price"] - surcharge, tt) + surcharge
        db.run("UPDATE tickets SET type = ?, price = ? WHERE id = ?", (tt, price, t["id"]))
    fresh_user = db.one("SELECT * FROM users WHERE id = ?", (user["id"],))
    # бонусы списываются и начисляются только при оплате картой онлайн
    _recalc(db, oid, fresh_user, use_bonus=use_bonus and pay_method == "card")
    o = db.one("SELECT * FROM orders WHERE id = ?", (oid,))
    stamp = fmt(now())
    if pay_method == "card":
        earned = int(o["total"] * BONUS_EARN)
        if o["bonus_used"]:
            db.run("INSERT INTO bonus_ops (user_id, amount, reason, created_at) VALUES (?, ?, ?, ?)",
                   (user["id"], -o["bonus_used"], "Оплата бонусами", stamp))
        if earned:
            db.run("INSERT INTO bonus_ops (user_id, amount, reason, created_at) VALUES (?, ?, ?, ?)",
                   (user["id"], earned, "Покупка билета", stamp))
        db.run("UPDATE users SET bonus = bonus - ? + ? WHERE id = ?", (o["bonus_used"], earned, user["id"]))
        db.run("UPDATE orders SET status = 'paid', pay_method = 'card', bonus_earned = ?, paid_at = ? WHERE id = ?", (earned, stamp, oid))
    else:
        db.run("UPDATE orders SET status = 'reserved', pay_method = 'cash', bonus_used = 0, total = sum_total WHERE id = ?", (oid,))
    db.commit()
    return order_info(db, oid, db.one("SELECT * FROM users WHERE id = ?", (user["id"],)))


def cancel_order(db, user, oid):
    o = _own_order(db, oid, user)
    if o["status"] == "cancelled":
        return order_info(db, oid, user)
    info = session_info(db, o["session_id"])
    limit = datetime.strptime(info["starts_at"], "%Y-%m-%d %H:%M") - timedelta(minutes=30)
    if o["status"] in ("paid", "reserved") and now() > limit:
        raise ApiError(400, "Отменить бронь можно не позднее чем за 30 минут до начала сеанса")
    if o["status"] == "paid":
        stamp = fmt(now())
        # возвращаем списанные бонусы и убираем начисленные за этот заказ
        delta = o["bonus_used"] - o["bonus_earned"]
        if delta:
            db.run("INSERT INTO bonus_ops (user_id, amount, reason, created_at) VALUES (?, ?, ?, ?)",
                   (user["id"], delta, "Отмена заказа", stamp))
            db.run("UPDATE users SET bonus = MAX(0, bonus + ?) WHERE id = ?" if db.kind == "sqlite"
                   else "UPDATE users SET bonus = GREATEST(0, bonus + ?) WHERE id = ?", (delta, user["id"]))
    db.run("UPDATE orders SET status = 'cancelled' WHERE id = ?", (oid,))
    db.commit()
    return order_info(db, oid, db.one("SELECT * FROM users WHERE id = ?", (user["id"],)))


def order_info(db, oid, user):
    o = _own_order(db, oid, user)
    s = session_info(db, o["session_id"])
    s.pop("taken", None)
    tickets = db.all("SELECT id, row_n, seat_n, type, price, code, used_at, seat_surcharge FROM tickets WHERE order_id = ? ORDER BY row_n, seat_n", (oid,))
    fresh = db.one("SELECT bonus FROM users WHERE id = ?", (user["id"],))
    return {
        "id": o["id"], "status": o["status"], "created_at": o["created_at"], "expires_at": o["expires_at"],
        "seconds_left": max(0, int((datetime.strptime(o["expires_at"], "%Y-%m-%d %H:%M:%S") - now()).total_seconds())),
        "pay_method": o["pay_method"], "sum_total": o["sum_total"], "bonus_used": o["bonus_used"],
        "total": o["total"], "bonus_earned": o["bonus_earned"], "bonus_balance": fresh["bonus"],
        "session": s,
        "tickets": [{"id": t["id"], "row": t["row_n"], "seat": t["seat_n"], "type": t["type"],
                     "type_name": TICKET_TYPES[t["type"]][0], "price": t["price"], "code": t["code"],
                     "used": t["used_at"] is not None, "seat_surcharge": t["seat_surcharge"]} for t in tickets],
    }


def my_orders(db, user):
    rows = db.all(
        "SELECT o.id FROM orders o JOIN sessions s ON s.id = o.session_id "
        "WHERE o.user_id = ? AND o.status IN ('paid', 'reserved') ORDER BY s.starts_at DESC",
        (user["id"],),
    )
    return [order_info(db, r["id"], user) for r in rows]


def bonus_history(db, user):
    return db.all("SELECT amount, reason, created_at FROM bonus_ops WHERE user_id = ? ORDER BY id DESC", (user["id"],))
