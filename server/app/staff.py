"""Работа сотрудников ИС «Кадр».

Роли:
  • app_admin    — администратор приложения (сеть): кинотеатры, залы, сотрудники, поиск и блокировка пользователей,
                   журнал оплат и возвраты по всей сети;
  • cinema_admin — администратор кинотеатра: фильмы и постеры, расписание сеансов и цены, залы своего кинотеатра,
                   отчёты по продажам, журнал оплат и возвраты своего кинотеатра;
  • controller   — контролёр: проверка билета по QR-коду на входе.
"""
import base64
from datetime import datetime, timedelta

from . import security
from .services import ApiError, HALL_LAYOUTS, TICKET_TYPES, fmt, now

ROLES = {"viewer": "Зритель", "app_admin": "Администратор приложения", "cinema_admin": "Администратор кинотеатра",
         "controller": "Контролёр"}
STAFF_ROLES = ("cinema_admin", "controller")
CLEANING_MIN = 15          # перерыв на уборку зала между сеансами
ORDER_STATUS = {"paid": "Оплачен картой", "reserved": "Оплата на кассе", "refunded": "Возврат",
                "cancelled": "Отменён зрителем", "pending": "Не оплачен"}


# ---------------------------------------------------------------- права доступа

def require(user, *roles):
    if user["role"] not in roles:
        raise ApiError(403, "Недостаточно прав для этого действия")


def scope_cinema(user, cinema_id=None):
    """Кинотеатр, с которым работает сотрудник: свой — для сотрудников кинотеатра, любой — для админа сети."""
    if user["role"] == "app_admin":
        return int(cinema_id) if cinema_id else None
    if not user["cinema_id"]:
        raise ApiError(403, "Сотрудник не привязан к кинотеатру")
    if cinema_id and int(cinema_id) != user["cinema_id"]:
        raise ApiError(403, "Можно работать только со своим кинотеатром")
    return user["cinema_id"]


def _hall_of(db, user, hall_id):
    h = db.one("SELECT * FROM halls WHERE id = ?", (hall_id,))
    if not h:
        raise ApiError(404, "Зал не найден")
    scope_cinema(user, h["cinema_id"])
    return h


# ---------------------------------------------------------------- кинотеатры и залы (админ сети; залы — и админ кинотеатра)

def _check_text(value, field, limit=100):
    value = (value or "").strip()
    if not value:
        raise ApiError(400, f"Заполните поле «{field}»")
    if len(value) > limit:
        raise ApiError(400, f"Поле «{field}» — не длиннее {limit} символов")
    return value


def staff_cinemas(db, user):
    require(user, "app_admin", "cinema_admin", "controller")
    where, args = ("", ()) if user["role"] == "app_admin" else ("WHERE id = ?", (user["cinema_id"],))
    rows = db.all(f"SELECT * FROM cinemas {where} ORDER BY city, id", args)
    for c in rows:
        c["halls"] = [_hall_out(h) for h in db.all("SELECT * FROM halls WHERE cinema_id = ? ORDER BY id", (c["id"],))]
    return rows


def _hall_out(h):
    return {"id": h["id"], "cinema_id": h["cinema_id"], "name": h["name"], "rows": h["rows_count"],
            "seats_per_row": h["seats_per_row"], "model_url": h["model_url"]}


def save_cinema(db, user, cinema_id, name, city, address, status="active"):
    require(user, "app_admin")
    name, city, address = _check_text(name, "Название"), _check_text(city, "Город", 60), _check_text(address, "Адрес", 150)
    if status not in ("active", "closed"):
        raise ApiError(400, "Статус: active или closed")
    if cinema_id:
        if not db.one("SELECT id FROM cinemas WHERE id = ?", (cinema_id,)):
            raise ApiError(404, "Кинотеатр не найден")
        db.run("UPDATE cinemas SET name = ?, city = ?, address = ?, status = ? WHERE id = ?", (name, city, address, status, cinema_id))
    else:
        cinema_id = db.run("INSERT INTO cinemas (name, city, address, status) VALUES (?, ?, ?, ?)", (name, city, address, status))
    db.commit()
    return db.one("SELECT * FROM cinemas WHERE id = ?", (cinema_id,))


def save_hall(db, user, hall_id, cinema_id, name, rows, seats_per_row, model_url):
    require(user, "app_admin", "cinema_admin")
    name = _check_text(name, "Название зала", 40)
    rows, seats_per_row = int(rows), int(seats_per_row)
    if not (1 <= rows <= 40 and 1 <= seats_per_row <= 60):
        raise ApiError(400, "Рядов — от 1 до 40, мест в ряду — от 1 до 60")
    if model_url not in HALL_LAYOUTS:
        raise ApiError(400, "Неизвестная 3D-модель зала")
    model_size = HALL_LAYOUTS[model_url].get("size")
    if model_size and (rows, seats_per_row) != model_size:
        raise ApiError(400, f"Для этой 3D-модели: {model_size[0]} рядов по {model_size[1]} мест")
    if hall_id:
        h = _hall_of(db, user, hall_id)
        changed = (rows, seats_per_row) != (h["rows_count"], h["seats_per_row"])
        if changed and db.one(
            "SELECT t.id FROM tickets t JOIN orders o ON o.id = t.order_id JOIN sessions s ON s.id = t.session_id "
            "WHERE s.hall_id = ? AND s.starts_at >= ? AND o.status IN ('paid', 'reserved')", (hall_id, fmt(now())[:16])):
            raise ApiError(409, "На будущие сеансы в этом зале уже проданы билеты — схему мест менять нельзя")
        db.run("UPDATE halls SET name = ?, rows_count = ?, seats_per_row = ?, model_url = ? WHERE id = ?",
               (name, rows, seats_per_row, model_url, hall_id))
    else:
        cid = scope_cinema(user, cinema_id)
        if not cid or not db.one("SELECT id FROM cinemas WHERE id = ?", (cid,)):
            raise ApiError(404, "Кинотеатр не найден")
        hall_id = db.run("INSERT INTO halls (cinema_id, name, rows_count, seats_per_row, model_url) VALUES (?, ?, ?, ?, ?)",
                         (cid, name, rows, seats_per_row, model_url))
    db.commit()
    return _hall_out(db.one("SELECT * FROM halls WHERE id = ?", (hall_id,)))


# ---------------------------------------------------------------- пользователи и сотрудники (админ сети)

def _user_out(u, cinemas=None):
    return {"id": u["id"], "last_name": u["last_name"], "first_name": u["first_name"], "email": u["email"],
            "phone": u["phone"], "role": u["role"], "role_name": ROLES.get(u["role"], u["role"]),
            "cinema_id": u["cinema_id"], "cinema_name": (cinemas or {}).get(u["cinema_id"]),
            "bonus": u["bonus"], "blocked": bool(u["blocked"]), "failed_logins": u["failed_logins"]}


def search_users(db, user, q="", role=""):
    """Поиск без учёта регистра и по подстроке: фамилия, имя, почта, телефон."""
    require(user, "app_admin")
    rows = db.all("SELECT * FROM users ORDER BY last_name, first_name")
    cinemas = {c["id"]: c["name"] for c in db.all("SELECT id, name FROM cinemas")}
    q = (q or "").strip().lower()
    out = []
    for u in rows:
        if role and u["role"] != role:
            continue
        hay = " ".join([u["last_name"], u["first_name"], u["email"], u["phone"]]).lower()
        if q and q not in hay and q not in hay.replace("+7", "8"):
            continue
        out.append(_user_out(u, cinemas))
    return out[:200]


def create_staff(db, user, last_name, first_name, login, phone, password, role, cinema_id):
    require(user, "app_admin")
    last_name, first_name = _check_text(last_name, "Фамилия", 30), _check_text(first_name, "Имя", 30)
    login = _check_text(login, "Логин или почта", 50).lower()
    if role not in STAFF_ROLES:
        raise ApiError(400, "Сотрудник может быть администратором кинотеатра или контролёром")
    if not cinema_id or not db.one("SELECT id FROM cinemas WHERE id = ?", (cinema_id,)):
        raise ApiError(400, "Выберите кинотеатр сотрудника")
    if len(password or "") < 6:
        raise ApiError(400, "Пароль сотрудника — не короче 6 символов")
    norm_phone = security.normalize_phone(phone or "")
    if not norm_phone:
        raise ApiError(400, "Телефон в формате +7 ___-___-__-__")
    if db.one("SELECT id FROM users WHERE email = ?", (login,)):
        raise ApiError(409, "Такой логин уже занят")
    if db.one("SELECT id FROM users WHERE phone = ?", (norm_phone,)):
        raise ApiError(409, "Этот номер телефона уже используется")
    uid = db.run("INSERT INTO users (last_name, first_name, email, phone, password_hash, role, cinema_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                 (last_name, first_name, login, norm_phone, security.hash_password(password), role, int(cinema_id)))
    db.commit()
    return _user_out(db.one("SELECT * FROM users WHERE id = ?", (uid,)))


def update_user(db, user, uid, blocked=None, role=None, cinema_id=None, password=None):
    """Блокировка/разблокировка, смена роли и кинотеатра сотрудника, новый пароль сотруднику."""
    require(user, "app_admin")
    u = db.one("SELECT * FROM users WHERE id = ?", (uid,))
    if not u:
        raise ApiError(404, "Пользователь не найден")
    if u["id"] == user["id"] and (blocked or (role and role != "app_admin")):
        raise ApiError(400, "Нельзя заблокировать или понизить самого себя")
    if blocked is not None:
        db.run("UPDATE users SET blocked = ?, failed_logins = 0 WHERE id = ?", (1 if blocked else 0, uid))
        if blocked:
            db.run("DELETE FROM tokens WHERE user_id = ?", (uid,))
    if role is not None:
        if role not in ROLES:
            raise ApiError(400, "Неизвестная роль")
        if role in STAFF_ROLES and not (cinema_id or u["cinema_id"]):
            raise ApiError(400, "Выберите кинотеатр сотрудника")
        db.run("UPDATE users SET role = ?, cinema_id = ? WHERE id = ?",
               (role, int(cinema_id or u["cinema_id"]) if role in STAFF_ROLES else None, uid))
    elif cinema_id is not None:
        db.run("UPDATE users SET cinema_id = ? WHERE id = ?", (int(cinema_id), uid))
    if password:
        if u["role"] == "viewer":
            raise ApiError(400, "Зритель задаёт пароль сам")
        if len(password) < 6:
            raise ApiError(400, "Пароль сотрудника — не короче 6 символов")
        db.run("UPDATE users SET password_hash = ? WHERE id = ?", (security.hash_password(password), uid))
        db.run("DELETE FROM tokens WHERE user_id = ?", (uid,))
    db.commit()
    cinemas = {c["id"]: c["name"] for c in db.all("SELECT id, name FROM cinemas")}
    return _user_out(db.one("SELECT * FROM users WHERE id = ?", (uid,)), cinemas)


# ---------------------------------------------------------------- фильмы и постеры (админ кинотеатра, админ сети)

def _film_out(f):
    return {"id": f["id"], "title": f["title"], "age": f["age"], "genre": f["genre"], "duration_min": f["duration_min"],
            "description": f["description"], "color": f["color"], "active": bool(f["active"]),
            "has_poster": bool(f["poster"]), "poster_v": f["poster_v"]}


def staff_films(db, user):
    require(user, "app_admin", "cinema_admin")
    return [_film_out(f) for f in db.all("SELECT * FROM films ORDER BY active DESC, title")]


AGES = ("0+", "6+", "12+", "16+", "18+")


def save_film(db, user, film_id, title, age, genre, duration_min, description, color, active=True, poster=None):
    require(user, "app_admin", "cinema_admin")
    title, genre = _check_text(title, "Название", 80), _check_text(genre, "Жанр", 40)
    if age not in AGES:
        raise ApiError(400, "Возрастной рейтинг: " + ", ".join(AGES))
    duration_min = int(duration_min)
    if not 30 <= duration_min <= 300:
        raise ApiError(400, "Длительность — от 30 до 300 минут")
    color = (color or "#3A2E4F").strip()
    if len(color) != 7 or not color.startswith("#"):
        raise ApiError(400, "Цвет в формате #RRGGBB")
    description = (description or "").strip()[:1000]
    if film_id:
        if not db.one("SELECT id FROM films WHERE id = ?", (film_id,)):
            raise ApiError(404, "Фильм не найден")
        db.run("UPDATE films SET title = ?, age = ?, genre = ?, duration_min = ?, description = ?, color = ?, active = ? WHERE id = ?",
               (title, age, genre, duration_min, description, color, 1 if active else 0, film_id))
    else:
        film_id = db.run("INSERT INTO films (title, age, genre, duration_min, description, color, active) VALUES (?, ?, ?, ?, ?, ?, ?)",
                         (title, age, genre, duration_min, description, color, 1 if active else 0))
    if poster:
        _set_poster(db, film_id, poster)
    db.commit()
    return _film_out(db.one("SELECT * FROM films WHERE id = ?", (film_id,)))


def _set_poster(db, film_id, poster_b64):
    try:
        raw = base64.b64decode(poster_b64.split(",", 1)[-1], validate=False)
    except Exception:
        raise ApiError(400, "Не удалось прочитать картинку постера")
    if not (raw[:3] == b"\xff\xd8\xff" or raw[:8] == b"\x89PNG\r\n\x1a\n"):
        raise ApiError(400, "Постер должен быть картинкой JPEG или PNG")
    if len(raw) > 1_500_000:
        raise ApiError(400, "Постер слишком большой (до 1,5 МБ)")
    db.run("UPDATE films SET poster = ?, poster_v = poster_v + 1 WHERE id = ?", (base64.b64encode(raw).decode(), film_id))


def film_poster(db, film_id):
    f = db.one("SELECT poster FROM films WHERE id = ?", (film_id,))
    if not f or not f["poster"]:
        raise ApiError(404, "Постера нет")
    raw = base64.b64decode(f["poster"])
    return raw, ("image/png" if raw[:4] == b"\x89PNG" else "image/jpeg")


# ---------------------------------------------------------------- расписание сеансов и цены (админ кинотеатра)

def staff_sessions(db, user, cinema_id, day):
    require(user, "app_admin", "cinema_admin")
    cid = scope_cinema(user, cinema_id)
    if not cid:
        raise ApiError(400, "Выберите кинотеатр")
    rows = db.all(
        "SELECT s.id, s.hall_id, s.film_id, s.starts_at, s.format, s.price, h.name AS hall_name, h.rows_count, h.seats_per_row, "
        "f.title, f.duration_min, f.age FROM sessions s JOIN halls h ON h.id = s.hall_id JOIN films f ON f.id = s.film_id "
        "WHERE h.cinema_id = ? AND s.starts_at LIKE ? ORDER BY h.id, s.starts_at", (cid, day + "%"))
    for r in rows:
        r["sold"] = db.one("SELECT COUNT(*) AS n FROM tickets t JOIN orders o ON o.id = t.order_id "
                           "WHERE t.session_id = ? AND o.status IN ('paid', 'reserved')", (r["id"],))["n"]
        r["capacity"] = r.pop("rows_count") * r.pop("seats_per_row")
        r["ends_at"] = (datetime.strptime(r["starts_at"], "%Y-%m-%d %H:%M") + timedelta(minutes=r["duration_min"])).strftime("%H:%M")
    return rows


def _overlap(db, hall_id, starts_at, duration, skip_id=0):
    start = datetime.strptime(starts_at, "%Y-%m-%d %H:%M")
    end = start + timedelta(minutes=duration + CLEANING_MIN)
    day = starts_at[:10]
    prev_day = (start - timedelta(days=1)).strftime("%Y-%m-%d")
    for s in db.all("SELECT s.id, s.starts_at, f.duration_min, f.title FROM sessions s JOIN films f ON f.id = s.film_id "
                    "WHERE s.hall_id = ? AND (s.starts_at LIKE ? OR s.starts_at LIKE ?) AND s.id <> ?",
                    (hall_id, day + "%", prev_day + "%", skip_id)):
        s0 = datetime.strptime(s["starts_at"], "%Y-%m-%d %H:%M")
        s1 = s0 + timedelta(minutes=s["duration_min"] + CLEANING_MIN)
        if start < s1 and s0 < end:
            return f"Зал занят: «{s['title']}» в {s['starts_at'][11:]} (с учётом {CLEANING_MIN} минут на уборку)"
    return None


def save_session(db, user, session_id, hall_id, film_id, starts_at, fmt_name, price):
    require(user, "app_admin", "cinema_admin")
    try:
        start = datetime.strptime((starts_at or "").strip(), "%Y-%m-%d %H:%M")
    except ValueError:
        raise ApiError(400, "Дата и время сеанса в формате ГГГГ-ММ-ДД ЧЧ:ММ")
    if start < now() - timedelta(minutes=1):
        raise ApiError(400, "Нельзя назначить сеанс в прошлом")
    price = int(price)
    if not 100 <= price <= 5000:
        raise ApiError(400, "Цена билета — от 100 до 5000 ₽")
    fmt_name = _check_text(fmt_name, "Формат", 20)
    starts_at = start.strftime("%Y-%m-%d %H:%M")
    if session_id:
        s = db.one("SELECT * FROM sessions WHERE id = ?", (session_id,))
        if not s:
            raise ApiError(404, "Сеанс не найден")
        _hall_of(db, user, s["hall_id"])
        hall_id, film_id = s["hall_id"], s["film_id"]
        sold = db.one("SELECT COUNT(*) AS n FROM tickets t JOIN orders o ON o.id = t.order_id "
                      "WHERE t.session_id = ? AND o.status IN ('paid', 'reserved')", (session_id,))["n"]
        if sold and starts_at != s["starts_at"]:
            raise ApiError(409, "На сеанс уже проданы билеты — время менять нельзя (можно изменить цену для новых покупок)")
    else:
        _hall_of(db, user, hall_id)
    f = db.one("SELECT * FROM films WHERE id = ?", (film_id,))
    if not f:
        raise ApiError(404, "Фильм не найден")
    problem = _overlap(db, hall_id, starts_at, f["duration_min"], session_id or 0)
    if problem:
        raise ApiError(409, problem)
    if session_id:
        db.run("UPDATE sessions SET starts_at = ?, format = ?, price = ? WHERE id = ?", (starts_at, fmt_name, price, session_id))
    else:
        session_id = db.run("INSERT INTO sessions (hall_id, film_id, starts_at, format, price) VALUES (?, ?, ?, ?, ?)",
                            (hall_id, film_id, starts_at, fmt_name, price))
    db.commit()
    return db.one("SELECT * FROM sessions WHERE id = ?", (session_id,))


def delete_session(db, user, session_id):
    require(user, "app_admin", "cinema_admin")
    s = db.one("SELECT * FROM sessions WHERE id = ?", (session_id,))
    if not s:
        raise ApiError(404, "Сеанс не найден")
    _hall_of(db, user, s["hall_id"])
    if db.one("SELECT t.id FROM tickets t JOIN orders o ON o.id = t.order_id WHERE t.session_id = ? "
              "AND o.status IN ('paid', 'reserved', 'pending')", (session_id,)):
        raise ApiError(409, "На сеанс есть билеты — сначала оформите возвраты")
    db.run("DELETE FROM tickets WHERE session_id = ?", (session_id,))
    db.run("DELETE FROM orders WHERE session_id = ?", (session_id,))
    db.run("DELETE FROM sessions WHERE id = ?", (session_id,))
    db.commit()
    return {"ok": True}


# ---------------------------------------------------------------- отчёты по продажам

def _period(date_from, date_to):
    try:
        d0 = datetime.strptime(date_from, "%Y-%m-%d")
        d1 = datetime.strptime(date_to, "%Y-%m-%d")
    except (TypeError, ValueError):
        raise ApiError(400, "Период в формате ГГГГ-ММ-ДД")
    if d1 < d0:
        raise ApiError(400, "Дата окончания раньше даты начала")
    return d0.strftime("%Y-%m-%d"), (d1 + timedelta(days=1)).strftime("%Y-%m-%d")


def sales_report(db, user, cinema_id, date_from, date_to):
    """Продажи по дате сеанса: выручка, билеты, заполняемость — итого, по фильмам и по дням."""
    require(user, "app_admin", "cinema_admin")
    cid = scope_cinema(user, cinema_id)
    a, b = _period(date_from, date_to)
    where, args = "s.starts_at >= ? AND s.starts_at < ?", [a, b]
    if cid:
        where += " AND h.cinema_id = ?"
        args.append(cid)
    sessions = db.all(f"SELECT s.id, s.starts_at, s.film_id, f.title, h.rows_count * h.seats_per_row AS cap FROM sessions s "
                      f"JOIN halls h ON h.id = s.hall_id JOIN films f ON f.id = s.film_id WHERE {where}", tuple(args))
    orders = db.all(f"SELECT o.id, o.status, o.total, o.bonus_used, o.session_id, (SELECT COUNT(*) FROM tickets t WHERE t.order_id = o.id) AS n "
                    f"FROM orders o JOIN sessions s ON s.id = o.session_id JOIN halls h ON h.id = s.hall_id "
                    f"WHERE {where} AND o.status IN ('paid', 'reserved', 'refunded')", tuple(args))
    by_session = {s["id"]: s for s in sessions}
    tot = {"revenue": 0, "cash_reserved": 0, "tickets": 0, "orders": 0, "refunds": 0, "refund_sum": 0, "bonus_used": 0,
           "sessions": len(sessions), "capacity": sum(s["cap"] for s in sessions)}
    films, days = {}, {}
    for o in orders:
        s = by_session.get(o["session_id"])
        if not s:
            continue
        if o["status"] == "refunded":
            tot["refunds"] += 1
            tot["refund_sum"] += o["total"]
            continue
        f = films.setdefault(s["film_id"], {"film": s["title"], "tickets": 0, "revenue": 0})
        d = days.setdefault(s["starts_at"][:10], {"day": s["starts_at"][:10], "tickets": 0, "revenue": 0})
        tot["tickets"] += o["n"]; f["tickets"] += o["n"]; d["tickets"] += o["n"]
        tot["orders"] += 1
        if o["status"] == "paid":
            tot["revenue"] += o["total"]; f["revenue"] += o["total"]; d["revenue"] += o["total"]
            tot["bonus_used"] += o["bonus_used"]
        else:
            tot["cash_reserved"] += o["total"]
    tot["occupancy"] = round(100.0 * tot["tickets"] / tot["capacity"], 1) if tot["capacity"] else 0.0
    return {"from": date_from, "to": date_to, "cinema_id": cid, "total": tot,
            "films": sorted(films.values(), key=lambda f: -f["revenue"]), "days": sorted(days.values(), key=lambda d: d["day"])}


# ---------------------------------------------------------------- журнал оплат и возвраты

def payments(db, user, cinema_id=None, date_from=None, date_to=None, status="", q=""):
    """Все платёжные операции: дата, сумма, статус, пользователь, способ оплаты. Фильтры — период, статус, зритель."""
    require(user, "app_admin", "cinema_admin")
    cid = scope_cinema(user, cinema_id)
    where, args = ["o.pay_method IS NOT NULL"], []
    if cid:
        where.append("h.cinema_id = ?"); args.append(cid)
    if date_from and date_to:
        a, b = _period(date_from, date_to)
        where.append("o.created_at >= ? AND o.created_at < ?"); args += [a, b]
    if status:
        if status not in ORDER_STATUS:
            raise ApiError(400, "Неизвестный статус")
        where.append("o.status = ?"); args.append(status)
    rows = db.all(
        "SELECT o.*, u.last_name, u.first_name, u.email, u.phone, s.starts_at, f.title, h.name AS hall_name, c.name AS cinema_name, "
        "(SELECT COUNT(*) FROM tickets t WHERE t.order_id = o.id) AS n "
        "FROM orders o JOIN users u ON u.id = o.user_id JOIN sessions s ON s.id = o.session_id JOIN films f ON f.id = s.film_id "
        "JOIN halls h ON h.id = s.hall_id JOIN cinemas c ON c.id = h.cinema_id WHERE " + " AND ".join(where) +
        " ORDER BY o.id DESC", tuple(args))
    q = (q or "").strip().lower()
    out = []
    for r in rows:
        who = f"{r['last_name']} {r['first_name']}"
        if q and q not in (who + " " + r["email"] + " " + r["phone"]).lower():
            continue
        out.append({"id": r["id"], "created_at": r["created_at"], "paid_at": r["paid_at"], "user": who, "email": r["email"],
                    "phone": r["phone"], "film": r["title"], "cinema": r["cinema_name"], "hall": r["hall_name"],
                    "starts_at": r["starts_at"], "tickets": r["n"], "sum_total": r["sum_total"], "bonus_used": r["bonus_used"],
                    "total": r["total"], "pay_method": r["pay_method"], "status": r["status"],
                    "status_name": ORDER_STATUS.get(r["status"], r["status"]), "refund_reason": r["refund_reason"],
                    "refunded_at": r["refunded_at"]})
        if len(out) >= 300:
            break
    return out


def refund(db, user, order_id, reason):
    """Возврат через администратора: с подтверждением и причиной. Бонусы возвращаются/списываются обратно."""
    require(user, "app_admin", "cinema_admin")
    reason = _check_text(reason, "Причина возврата", 200)
    o = db.one("SELECT o.*, h.cinema_id FROM orders o JOIN sessions s ON s.id = o.session_id JOIN halls h ON h.id = s.hall_id "
               "WHERE o.id = ?", (order_id,))
    if not o:
        raise ApiError(404, "Заказ не найден")
    scope_cinema(user, o["cinema_id"])
    if o["status"] not in ("paid", "reserved"):
        raise ApiError(400, "Возврат возможен только для оплаченного или забронированного заказа")
    if db.one("SELECT id FROM tickets WHERE order_id = ? AND used_at IS NOT NULL", (order_id,)):
        raise ApiError(400, "По билету уже прошли в зал — возврат невозможен")
    stamp = fmt(now())
    if o["status"] == "paid":
        delta = o["bonus_used"] - o["bonus_earned"]
        if delta:
            db.run("INSERT INTO bonus_ops (user_id, amount, reason, created_at) VALUES (?, ?, ?, ?)",
                   (o["user_id"], delta, "Возврат заказа №%d" % order_id, stamp))
            db.run("UPDATE users SET bonus = MAX(0, bonus + ?) WHERE id = ?" if db.kind == "sqlite"
                   else "UPDATE users SET bonus = GREATEST(0, bonus + ?) WHERE id = ?", (delta, o["user_id"]))
    db.run("UPDATE orders SET status = 'refunded', refund_reason = ?, refunded_at = ?, refunded_by = ? WHERE id = ?",
           (reason, stamp, user["id"], order_id))
    db.commit()
    return {"ok": True, "id": order_id, "status": "refunded"}


# ---------------------------------------------------------------- контролёр: проверка билета по QR-коду

def check_ticket(db, user, code, admit=False):
    require(user, "controller", "cinema_admin", "app_admin")
    code = (code or "").strip().upper()
    t = db.one(
        "SELECT t.*, o.status, o.user_id, s.starts_at, f.title, f.duration_min, f.age, h.name AS hall_name, h.cinema_id, "
        "c.name AS cinema_name, u.last_name, u.first_name FROM tickets t JOIN orders o ON o.id = t.order_id "
        "JOIN sessions s ON s.id = t.session_id JOIN films f ON f.id = s.film_id JOIN halls h ON h.id = s.hall_id "
        "JOIN cinemas c ON c.id = h.cinema_id JOIN users u ON u.id = o.user_id WHERE t.code = ?", (code,))
    if not t:
        return {"ok": False, "verdict": "Билет не найден", "code": code}
    info = {"code": code, "film": t["title"], "age": t["age"], "cinema": t["cinema_name"], "hall": t["hall_name"],
            "starts_at": t["starts_at"], "row": t["row_n"], "seat": t["seat_n"], "type_name": TICKET_TYPES[t["type"]][0],
            "viewer": f"{t['last_name']} {t['first_name']}", "used_at": t["used_at"]}
    if user["role"] != "app_admin" and t["cinema_id"] != user["cinema_id"]:
        return dict(info, ok=False, verdict=f"Билет в другой кинотеатр: {t['cinema_name']}")
    if t["status"] in ("cancelled", "refunded", "pending"):
        return dict(info, ok=False, verdict="Билет недействителен: " + ORDER_STATUS[t["status"]].lower())
    if t["status"] == "reserved":
        return dict(info, ok=False, verdict="Бронь не оплачена — направьте зрителя на кассу")
    if t["used_at"]:
        return dict(info, ok=False, verdict=f"По билету уже прошли в {t['used_at'][11:16]}")
    start = datetime.strptime(t["starts_at"], "%Y-%m-%d %H:%M")
    if now() < start - timedelta(hours=1):
        return dict(info, ok=False, verdict="Рано: вход открывается за час до начала сеанса")
    if now() > start + timedelta(minutes=t["duration_min"]):
        return dict(info, ok=False, verdict="Сеанс уже закончился")
    if admit:
        db.run("UPDATE tickets SET used_at = ? WHERE id = ?", (fmt(now()), t["id"]))
        db.commit()
        return dict(info, ok=True, admitted=True, verdict="Проход разрешён — билет погашен")
    return dict(info, ok=True, admitted=False, verdict="Билет действителен")
