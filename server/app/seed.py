"""Тестовые данные: сеть «Кадр», фильмы, сеансы на неделю вперёд, тестовые пользователи."""
from datetime import date, timedelta
from os import getenv

from .security import hash_password, password_problem
from .services import BASE_PRICE_INCREASE, fmt, now

CINEMAS = [
    ("Кадр на Баумана", "Казань", "ул. Баумана, 44", 3),
    ("Кадр Победа", "Казань", "пр. Победы, 141", 4),
    ("Кадр Заречье", "Казань", "ул. Восстания, 100", 2),
    ("Кадр Таганка", "Москва", "ул. Марксистская, 3", 5),
    ("Кадр Сокол", "Москва", "Ленинградский пр-т, 76", 3),
    ("Кадр Центр", "Набережные Челны", "пр. Мира, 49", 2),
]

FILMS = [
    ("Рождение империи", "12+", "Историческая драма", 149, "Пётр I вступает в войну со Швецией за выход к Балтийскому морю.", "#3A2E4F"),
    ("Битва моторов", "12+", "Драма, приключения", 152, "Создатели «Руссо-Балта» проверяют свою машину на ралли в Монако.", "#23414A"),
    ("Дедмобиль", "12+", "Семейная комедия", 92, "Водитель Валера объединяется с умным такси, чтобы заработать на лечение любимого пса.", "#4A2F2B"),
    ("Девятая планета", "16+", "Научная фантастика", 111, "Автомеханик пытается выяснить, почему помнит события на далёкой планете, о которых не знают его сослуживцы.", "#2F3F2A"),
]
LEGACY_FILM_TITLES = ("Северный ветер", "Код Альфа", "Лисёнок и звёзды", "Тихая гавань")

def day_plan(day):
    """Слоты Кадра: индекс зала, номер фильма в FILMS, время, формат, цена."""
    imax = [
        (0, 1, "17:30", "IMAX", 450),
        (0, 1, "20:25", "IMAX", 550),
    ]
    if day.weekday() >= 5:
        imax = [(0, 1, "12:00", "IMAX", 400), (0, 1, "14:45", "IMAX", 450)] + imax
    return imax + [
        (1, 3, "10:00", "2D", VIP_SEAT_PRICE),
        (1, 4, "12:00", "2D", VIP_SEAT_PRICE),
        (1, 2, "14:15", "2D", VIP_SEAT_PRICE),
        (1, 1, "18:30", "2D", VIP_SEAT_PRICE),
        (2, 3, "10:00", "2D", 350),
        (2, 4, "11:45", "2D", 350),
        (2, 2, "13:50", "2D", 350),
        (2, 3, "16:35", "2D", 450),
        (2, 2, "18:20", "2D", 450),
        (2, 4, "21:05", "2D", 500),
    ]


def seed(db, days: int = 7):
    ensure_admin(db)
    if db.one("SELECT COUNT(*) AS n FROM cinemas")["n"]:
        upgrade_halls(db)
        upgrade_compact_imax(db)
        upgrade_vip_halls(db)
        ensure_sessions(db, days)
        return
    for name, city, address, halls in CINEMAS:
        cid = db.run("INSERT INTO cinemas (name, city, address) VALUES (?, ?, ?)", (name, city, address))
        for h in range(halls):
            db.run(
                "INSERT INTO halls (cinema_id, name, rows_count, seats_per_row, model_url) VALUES (?, ?, ?, ?, ?)",
                [(cid, "Зал 1 IMAX", 8, 43, "imax"), (cid, "Зал 2 VIP", 7, 6, "vip2")][h]
                if h < 2 else (cid, f"Зал {h + 1}", 10, 16, "vip"),
            )
    db.commit()
    ensure_sessions(db, days)


VIP_SEAT_PRICE = 1500


def update_film_catalog(db):
    film_ids = []
    for previous_title, film in zip(LEGACY_FILM_TITLES, FILMS):
        row = db.one("SELECT id FROM films WHERE title = ? ORDER BY id LIMIT 1", (film[0],))
        film_id = row["id"] if row else db.run(
            "INSERT INTO films (title, age, genre, duration_min, description, color) VALUES (?, ?, ?, ?, ?, ?)", film
        )
        film_ids.append(film_id)
        db.run(
            "UPDATE sessions SET film_id = ? WHERE starts_at >= ? "
            "AND film_id IN (SELECT id FROM films WHERE title = ?) "
            "AND NOT EXISTS (SELECT 1 FROM orders WHERE session_id = sessions.id)",
            (film_id, date.today().isoformat(), previous_title),
        )
    return film_ids


def ensure_sessions(db, days: int = 7):
    """Добавляет сеансы на ближайшие дни, если их ещё нет (в каждом кинотеатре одинаковое расписание)."""
    film_ids = update_film_catalog(db)
    halls = db.all("SELECT id, cinema_id, model_url FROM halls ORDER BY cinema_id, id")
    by_cinema = {}
    for h in halls:
        by_cinema.setdefault(h["cinema_id"], []).append(h)
    for d in range(days):
        plan_date = date.today() + timedelta(days=d)
        day = plan_date.isoformat()
        if db.one("SELECT COUNT(*) AS n FROM sessions WHERE starts_at LIKE ?", (day + "%",))["n"]:
            continue
        for cinema_halls in by_cinema.values():
            for idx, film, t, fmt, price in day_plan(plan_date):
                if idx < len(cinema_halls):
                    hall = cinema_halls[idx]
                    base_price = VIP_SEAT_PRICE if idx == 1 or hall["model_url"] == "vip2" else price + BASE_PRICE_INCREASE
                    db.run(
                        "INSERT INTO sessions (hall_id, film_id, starts_at, format, price) VALUES (?, ?, ?, ?, ?)",
                        (hall["id"], film_ids[film - 1], f"{day} {t}", fmt, base_price),
                    )
    db.commit()


def upgrade_halls(db):
    """Обновление уже заполненной базы: первый зал каждого кинотеатра — Зал 1 IMAX (24 ряда × 54 места)."""
    if not db.one("SELECT COUNT(*) AS n FROM halls WHERE name = 'Зал 1'")["n"]:
        return
    imax = [r["id"] for r in db.all("SELECT id FROM halls WHERE name = 'Зал 1'")]
    db.run("UPDATE halls SET name = 'Зал 1 IMAX', rows_count = 24, seats_per_row = 54, model_url = 'imax' WHERE name = 'Зал 1'")
    db.run("UPDATE halls SET model_url = 'vip' WHERE model_url IS NULL")
    for hid in imax:
        db.run("UPDATE sessions SET format = 'IMAX 3D' WHERE hall_id = ? AND format = '3D'", (hid,))
    db.commit()


def upgrade_compact_imax(db):
    """Shrink only legacy halls whose live tickets fit the compact model."""
    stamp = fmt(now())
    db.run(
        "UPDATE halls SET rows_count = 8, seats_per_row = 43 "
        "WHERE model_url = 'imax' AND rows_count = 24 AND seats_per_row = 54 "
        "AND NOT EXISTS (SELECT 1 FROM sessions s JOIN tickets t ON t.session_id = s.id "
        "JOIN orders o ON o.id = t.order_id WHERE s.hall_id = halls.id "
        "AND (t.row_n > 8 OR t.seat_n > 43) AND (o.status IN ('paid','reserved') "
        "OR (o.status = 'pending' AND o.expires_at > ?)))",
        (stamp,),
    )
    db.commit()


def upgrade_vip_halls(db):
    """Подключает новую схему для стандартных залов 2, сохраняя существующие билеты."""
    db.run(
        "UPDATE halls SET name = 'Зал 2 VIP', rows_count = 7, seats_per_row = 6, model_url = 'vip2' "
        "WHERE name = 'Зал 2' AND model_url = 'vip' AND rows_count = 10 AND seats_per_row = 16 "
        "AND NOT EXISTS (SELECT 1 FROM sessions s JOIN tickets t ON t.session_id = s.id "
        "WHERE s.hall_id = halls.id AND (t.row_n > 7 OR t.seat_n > 6))"
    )
    db.commit()

def ensure_admin(db):
    password = getenv("KADR_ADMIN_PASSWORD")
    if not password or db.one("SELECT id FROM users WHERE role = 'app_admin'"):
        return
    problem = password_problem(password)
    if problem:
        raise ValueError(f"KADR_ADMIN_PASSWORD: {problem}")
    db.run(
        "INSERT INTO users (last_name, first_name, email, phone, password_hash, role) VALUES (?, ?, ?, ?, ?, ?)",
        ("Администратор", "Кадр", "admin", "", hash_password(password), "app_admin"),
    )
    db.commit()
