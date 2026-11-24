"""Проверка работы сотрудников: python tests/test_staff.py (из папки server)"""
import base64
from datetime import date, datetime, timedelta

from app import services, staff
from app.services import ApiError
from tests.test_services import ADMIN_PASSWORD, VIEWER_PASSWORD, expect_error, fresh_db


def main():
    db = fresh_db()
    admin = services.user_by_token(db, services.login(db, "admin", ADMIN_PASSWORD)["token"])
    assert admin["role"] == "app_admin"
    viewer = services.user_by_token(db, services.login(db, "viewer@example.test", VIEWER_PASSWORD)["token"])
    expect_error(lambda: staff.search_users(db, viewer), 403)

    # админ сети: кинотеатр, зал, сотрудники, поиск, блокировка
    c = staff.save_cinema(db, admin, 0, "Кадр Тест", "Казань", "ул. Тестовая, 1")
    h = staff.save_hall(db, admin, 0, c["id"], "Зал 1", 8, 12, "vip")
    assert h["rows"] == 8
    boss = staff.create_staff(db, admin, "Петров", "Пётр", "boss@kadr.ru", "+79001112233", "boss123", "cinema_admin", c["id"])
    ctrl = staff.create_staff(db, admin, "Сидоров", "Сидор", "gate", "+79001112244", "gate12", "controller", c["id"])
    expect_error(lambda: staff.create_staff(db, admin, "А", "Б", "gate", "+79001112255", "123456", "controller", c["id"]), 409)
    found = staff.search_users(db, admin, "ПЕТР")
    assert any(u["email"] == "boss@kadr.ru" for u in found), found
    assert staff.search_users(db, admin, "112244")[0]["email"] == "gate"
    staff.update_user(db, admin, ctrl["id"], blocked=True)
    expect_error(lambda: services.login(db, "gate", "gate12"), 403)
    staff.update_user(db, admin, ctrl["id"], blocked=False, password="newgate")
    gate = services.user_by_token(db, services.login(db, "gate", "newgate")["token"])
    bossu = services.user_by_token(db, services.login(db, "boss@kadr.ru", "boss123")["token"])

    # админ кинотеатра: только свой кинотеатр
    expect_error(lambda: staff.save_hall(db, bossu, 0, 1, "Чужой", 5, 5, "vip"), 403)
    png = base64.b64encode(b"\x89PNG\r\n\x1a\n" + b"0" * 50).decode()
    jpg = base64.b64encode(b"\xff\xd8\xff" + b"0" * 50).decode()
    f = staff.save_film(db, bossu, 0, "Тестовый фильм", "12+", "Комедия", 100, "Описание", "#334455", True, jpg)
    assert f["has_poster"] and f["poster_v"] == 1
    raw, mime = staff.film_poster(db, f["id"])
    assert mime == "image/jpeg"
    expect_error(lambda: staff.save_film(db, bossu, 0, "Плохой", "13+", "Драма", 100, "", "#334455"), 400)
    day = (date.today() + timedelta(days=2)).isoformat()
    s1 = staff.save_session(db, bossu, 0, h["id"], f["id"], day + " 12:00", "2D", 350)
    # пересечение: фильм 100 мин + 15 уборка → следующий не раньше 13:55
    expect_error(lambda: staff.save_session(db, bossu, 0, h["id"], f["id"], day + " 13:30", "2D", 350), 409)
    s2 = staff.save_session(db, bossu, 0, h["id"], f["id"], day + " 14:00", "2D", 400)
    expect_error(lambda: staff.save_session(db, bossu, 0, h["id"], f["id"], "2020-01-01 10:00", "2D", 400), 400)
    lst = staff.staff_sessions(db, bossu, 0, day)
    assert [x["id"] for x in lst] == [s1["id"], s2["id"]] and lst[0]["ends_at"] == "13:40"

    # покупка зрителем, отчёт, журнал оплат, возврат
    o = services.create_order(db, viewer, s1["id"], [{"row": 1, "seat": 4}, {"row": 1, "seat": 5}])
    services.pay_order(db, viewer, o["id"], [], False, "card")
    o2 = services.create_order(db, viewer, s2["id"], [{"row": 1, "seat": 1}])
    services.pay_order(db, viewer, o2["id"], [], False, "cash")
    rep = staff.sales_report(db, bossu, 0, day, day)
    assert rep["total"]["tickets"] == 3 and rep["total"]["revenue"] == 700 and rep["total"]["cash_reserved"] == 400, rep["total"]
    assert rep["films"][0]["film"] == "Тестовый фильм"
    pays = staff.payments(db, bossu)
    assert len(pays) == 2 and {p["status"] for p in pays} == {"paid", "reserved"}
    assert staff.payments(db, bossu, status="paid")[0]["total"] == 700
    expect_error(lambda: staff.delete_session(db, bossu, s1["id"]), 409)
    expect_error(lambda: staff.refund(db, bossu, o["id"], ""), 400)
    bonus_before = db.one("SELECT bonus FROM users WHERE id = ?", (viewer["id"],))["bonus"]
    staff.refund(db, bossu, o["id"], "Зритель заболел")
    bonus_after = db.one("SELECT bonus FROM users WHERE id = ?", (viewer["id"],))["bonus"]
    assert bonus_after == bonus_before - 35, (bonus_before, bonus_after)   # начисленные 5% забрали обратно
    assert staff.payments(db, bossu, status="refunded")[0]["refund_reason"] == "Зритель заболел"
    assert (3, 4) not in services._taken(db, s1["id"])    # места освободились
    assert staff.sales_report(db, bossu, 0, day, day)["total"]["refunds"] == 1

    # контролёр: проверка и погашение билета (сеанс через 2 дня — ещё рано)
    t2 = db.one("SELECT code FROM tickets WHERE order_id = ?", (o2["id"],))["code"]
    r = staff.check_ticket(db, gate, t2)
    assert not r["ok"] and "кассу" in r["verdict"], r
    # билет на сеанс, который идёт сейчас
    soon = (datetime.now() + timedelta(minutes=20)).strftime("%Y-%m-%d %H:%M")
    s3 = staff.save_session(db, bossu, 0, staff.save_hall(db, bossu, 0, 0, "Зал 2", 5, 5, "vip")["id"], f["id"], soon, "2D", 300)
    o3 = services.create_order(db, viewer, s3["id"], [{"row": 2, "seat": 2}])
    services.pay_order(db, viewer, o3["id"], [], False, "card")
    code = db.one("SELECT code FROM tickets WHERE order_id = ?", (o3["id"],))["code"]
    assert staff.check_ticket(db, gate, code.lower())["verdict"] == "Билет действителен"
    assert staff.check_ticket(db, gate, code, admit=True)["admitted"]
    assert "уже прошли" in staff.check_ticket(db, gate, code)["verdict"]
    expect_error(lambda: staff.refund(db, bossu, o3["id"], "поздно"), 400)
    assert staff.check_ticket(db, gate, "KADR-000000000000")["verdict"] == "Билет не найден"
    other = db.one("SELECT t.code FROM tickets t JOIN orders o ON o.id = t.order_id JOIN sessions s ON s.id = t.session_id JOIN halls h ON h.id = s.hall_id WHERE h.cinema_id <> ? LIMIT 1", (c["id"],))
    if other:
        assert "другой кинотеатр" in staff.check_ticket(db, gate, other["code"])["verdict"]
    expect_error(lambda: staff.check_ticket(db, viewer, code), 403)
    print("Все проверки сотрудников пройдены")


if __name__ == "__main__":
    main()
