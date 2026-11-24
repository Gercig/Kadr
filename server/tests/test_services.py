"""Проверка бизнес-логики без запуска веб-сервера: python -m tests.test_services"""
import os
import secrets
from datetime import date, datetime, timedelta

from app import security, services
from app.db import Db
from app.schema import ddl, migrate
from app.seed import seed
from app.services import ApiError


ADMIN_PASSWORD = "Aa1!" + secrets.token_urlsafe(15)
VIEWER_PASSWORD = "Aa1!" + secrets.token_urlsafe(15)

def fresh_db():
    path = "test_kadr.db"
    if os.path.exists(path):
        os.remove(path)
    db = Db("sqlite:///" + path)
    db.script(ddl(db.kind))
    migrate(db)
    previous = os.environ.get("KADR_ADMIN_PASSWORD")
    os.environ["KADR_ADMIN_PASSWORD"] = ADMIN_PASSWORD
    try:
        seed(db)
    finally:
        if previous is None:
            os.environ.pop("KADR_ADMIN_PASSWORD")
        else:
            os.environ["KADR_ADMIN_PASSWORD"] = previous
    db.run(
        "INSERT INTO users (last_name, first_name, email, phone, password_hash, role, bonus) VALUES (?, ?, ?, ?, ?, ?, ?)",
        ("Зритель", "Тест", "viewer@example.test", "", security.hash_password(VIEWER_PASSWORD), "viewer", 420),
    )
    db.commit()
    return db


def expect_error(fn, status):
    try:
        fn()
    except ApiError as e:
        assert e.status == status, (e.status, e.message)
        return e.message
    raise AssertionError("ожидалась ошибка %s" % status)


def main():
    db = fresh_db()
    cins = services.cinemas(db)
    assert len(cins) == 6 and cins[0]["halls_count"] > 0, cins
    tomorrow = (date.today() + timedelta(days=1)).isoformat()
    sch = services.schedule(db, 1, tomorrow)
    assert len(sch["films"]) == 4, sch
    slots = [s for film in sch["films"] for s in film["sessions"]]
    sess = next(s for s in slots if s["hall_name"] == "Зал 3" and s["time"] == "13:50")
    imax = next(s for s in slots if s["hall_name"] == "Зал 1 IMAX" and s["time"] == "17:30")
    vip = next(s for s in slots if s["hall_name"] == "Зал 2 VIP")
    assert sess["free"] == 160 and sess["price"] == 450
    assert imax["price"] == 550 and vip["price"] == 1500
    seats_2d = services.session_info(db, sess["id"])["premium_seats"]
    seats_imax = services.session_info(db, imax["id"])["premium_seats"]
    assert seats_2d == [[r, c] for r in range(2, 8) for c in range(5, 13)]
    assert seats_imax == [[r, c] for r in range(2, 7) for c in range(15, 30)]
    assert services.session_info(db, vip["id"])["premium_seats"] == []

    # Повторный запуск миграции не прибавляет ещё 100 ₽.
    db.run("DELETE FROM app_migrations WHERE name = ?", ("raise_2d_imax_base_prices",))
    db.run("UPDATE sessions SET price = price - 100 WHERE id IN (?, ?)", (sess["id"], imax["id"]))
    db.commit()
    migrate(db)
    migrate(db)
    assert services.session_info(db, sess["id"])["price"] == 450
    assert services.session_info(db, vip["id"])["price"] == 1500
    assert services.session_info(db, imax["id"])["price"] == 550

    # регистрация и правила
    expect_error(lambda: services.register(db, "Ivanov", "Иван", "a@b.ru", "+79991112233", "Qwerty12!"), 400)
    expect_error(lambda: services.register(db, "Иванов", "Иван", "a@b.ru", "+79991112233", "qwerty"), 400)
    r = services.register(db, "иванов", "иван", "A@B.ru", "8 (999) 111-22-33", "Qwerty12!")
    assert r["user"]["last_name"] == "Иванов" and r["user"]["phone"] == "+79991112233"
    expect_error(lambda: services.register(db, "Петров", "Пётр", "a@b.ru", "+79990000000", "Qwerty12!"), 409)

    # вход и блокировка после 5 ошибок
    for _ in range(4):
        expect_error(lambda: services.login(db, "a@b.ru", "wrong"), 401)
    assert services.login(db, "a@b.ru", "Qwerty12!")["token"]
    for _ in range(4):
        expect_error(lambda: services.login(db, "a@b.ru", "wrong"), 401)
    expect_error(lambda: services.login(db, "a@b.ru", "wrong"), 403)

    demo = services.login(db, "viewer@example.test", VIEWER_PASSWORD)
    user = services.user_by_token(db, demo["token"])
    assert user["bonus"] == 420

    other = services.user_by_token(db, services.register(db, "Сидоров", "Олег", "o@k.ru", "+79995554433", "Qwerty12!")["token"])

    # бронь и конфликт мест
    o = services.create_order(db, user, sess["id"], [{"row": 6, "seat": 8}, {"row": 6, "seat": 9}])
    assert o["status"] == "pending" and o["sum_total"] == sess["price"] * 2 + 200 and o["seconds_left"] > 500
    assert [6, 8] in services.session_info(db, sess["id"])["taken"]
    expect_error(lambda: services.create_order(db, other, sess["id"], [{"row": 6, "seat": 9}]), 409)
    expect_error(lambda: services.create_order(db, other, sess["id"], [{"row": 11, "seat": 1}]), 400)

    # нельзя покупать через одно место
    msg = expect_error(lambda: services.create_order(db, other, sess["id"], [{"row": 3, "seat": 5}, {"row": 3, "seat": 7}]), 400)
    assert "одно свободное" in msg
    # одиночная дырка рядом с чужой бронью (6-9 занято демо-пользователем): 6-11 оставит свободным 6-10
    expect_error(lambda: services.create_order(db, other, sess["id"], [{"row": 6, "seat": 11}]), 400)
    assert services.single_gaps({(4, 4), (4, 5)}, set(), 16) == set()
    # проход в IMAX после 27-го места: 26 и 28 по разные стороны — 27 не «дырка»
    assert services.single_gaps({(5, 26)}, {(5, 28)}, 54, [27]) == set()
    assert services.single_gaps({(5, 26)}, {(5, 28)}, 54) == {(5, 27)}

    # оплата картой: льготный билет, бонусы до 30%
    t1, t2 = o["tickets"]
    paid = services.pay_order(db, user, o["id"], [{"id": t2["id"], "type": "reduced"}], True, "card")
    base = sess["price"]
    total = (base + 100) + (base - 100 + 100)
    assert paid["status"] == "paid" and paid["sum_total"] == total
    assert paid["bonus_used"] == min(int(total * 0.3), 420)
    assert paid["bonus_earned"] == int(paid["total"] * 0.05)
    assert paid["bonus_balance"] == 420 - paid["bonus_used"] + paid["bonus_earned"]
    assert len(services.my_orders(db, user)) == 1
    expect_error(lambda: services.pay_order(db, user, o["id"], [], False, "card"), 400)

    # истечение брони
    o2 = services.create_order(db, other, sess["id"], [{"row": 1, "seat": 1}])
    db.run("UPDATE orders SET expires_at = ? WHERE id = ?", ("2000-01-01 00:00:00", o2["id"]))
    db.commit()
    assert [1, 1] not in services.session_info(db, sess["id"])["taken"]
    expect_error(lambda: services.pay_order(db, other, o2["id"], [], False, "card"), 410)

    # оплата в кассе: без бонусов
    o3 = services.create_order(db, other, sess["id"], [{"row": 2, "seat": 2}])
    res = services.pay_order(db, other, o3["id"], [], True, "cash")
    assert res["status"] == "reserved" and res["bonus_used"] == 0 and res["total"] == base

    # отмена оплаченного заказа возвращает бонусы
    before = services.user_by_token(db, demo["token"])["bonus"]
    c = services.cancel_order(db, user, o["id"])
    assert c["status"] == "cancelled"
    assert c["bonus_balance"] == before + paid["bonus_used"] - paid["bonus_earned"]
    assert [6, 8] not in services.session_info(db, sess["id"])["taken"]

    # чужой заказ недоступен
    expect_error(lambda: services.order_info(db, o3["id"], user), 404)
    # Один заказ с обычным и центральным местом. Доплата остаётся после смены типа билета.
    mixed = services.create_order(db, user, sess["id"], [{"row": 2, "seat": 12}, {"row": 2, "seat": 13}])
    center, regular = mixed["tickets"]
    assert (center["price"], regular["price"]) == (base + 100, base)
    assert (center["seat_surcharge"], regular["seat_surcharge"]) == (100, 0)
    db.run("UPDATE sessions SET price = price + 50 WHERE id = ?", (sess["id"],))
    db.commit()
    mixed = services.pay_order(db, user, mixed["id"], [{"id": center["id"], "type": "reduced"}], False, "cash")
    assert mixed["sum_total"] == base * 2

    db.conn.close()
    os.remove("test_kadr.db")
    print("Все проверки пройдены")


if __name__ == "__main__":
    main()
