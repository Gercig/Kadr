"""REST API ИС «Кадр». Запуск: uvicorn app.main:app --host 0.0.0.0 --port 8000"""
from datetime import date
from typing import List, Optional

from fastapi import Depends, FastAPI, Header, Request
from fastapi.responses import JSONResponse, Response
from pydantic import BaseModel

from . import services, staff
from .db import Db
from .schema import ddl, migrate
from .seed import seed
from .services import ApiError

db = Db()
db.script(ddl(db.kind))
migrate(db)
seed(db)

app = FastAPI(title="Кадр — API сети кинотеатров", version="1.0")


@app.exception_handler(ApiError)
def api_error(_: Request, exc: ApiError):
    return JSONResponse(status_code=exc.status, content={"detail": exc.message})


def current_user(authorization: Optional[str] = Header(default=None)):
    token = (authorization or "").removeprefix("Bearer ").strip()
    user = db.tx(services.user_by_token, token)
    if not user:
        raise ApiError(401, "Нужно войти в приложение")
    return user


# ---------------------------------------------------------------- модели запросов

class RegisterIn(BaseModel):
    last_name: str
    first_name: str
    email: str
    phone: str
    password: str


class LoginIn(BaseModel):
    email: str
    password: str


class SeatIn(BaseModel):
    row: int
    seat: int


class OrderIn(BaseModel):
    session_id: int
    seats: List[SeatIn]


class TicketTypeIn(BaseModel):
    id: int
    type: str


class PayIn(BaseModel):
    tickets: List[TicketTypeIn] = []
    use_bonus: bool = False
    pay_method: str = "card"


# ---------------------------------------------------------------- маршруты

@app.get("/api/health")
def health():
    return {"ok": True, "db": db.kind}


@app.post("/api/auth/register")
def register(body: RegisterIn):
    return db.tx(services.register, body.last_name, body.first_name, body.email, body.phone, body.password)


@app.post("/api/auth/login")
def login(body: LoginIn):
    return db.tx(services.login, body.email, body.password)


@app.post("/api/auth/logout")
def logout(authorization: Optional[str] = Header(default=None)):
    db.tx(services.logout, (authorization or "").removeprefix("Bearer ").strip())
    return {"ok": True}


@app.get("/api/me")
def me(user=Depends(current_user)):
    return services.public_user(user)


@app.get("/api/me/bonus")
def my_bonus(user=Depends(current_user)):
    return {"balance": user["bonus"], "history": db.tx(services.bonus_history, user)}


@app.get("/api/cinemas")
def cinemas():
    return db.tx(services.cinemas)


@app.get("/api/cinemas/{cinema_id}/schedule")
def schedule(cinema_id: int, day: Optional[str] = None):
    return db.tx(services.schedule, cinema_id, day or date.today().isoformat())


@app.get("/api/sessions/{session_id}")
def session(session_id: int):
    return db.tx(services.session_info, session_id)


@app.post("/api/orders")
def create_order(body: OrderIn, user=Depends(current_user)):
    return db.tx(services.create_order, user, body.session_id, [s.model_dump() for s in body.seats])


@app.get("/api/orders/{order_id}")
def get_order(order_id: int, user=Depends(current_user)):
    return db.tx(services.order_info, order_id, user)


@app.post("/api/orders/{order_id}/pay")
def pay(order_id: int, body: PayIn, user=Depends(current_user)):
    return db.tx(services.pay_order, user, order_id, [t.model_dump() for t in body.tickets], body.use_bonus, body.pay_method)


@app.post("/api/orders/{order_id}/cancel")
def cancel(order_id: int, user=Depends(current_user)):
    return db.tx(services.cancel_order, user, order_id)


@app.get("/api/tickets")
def tickets(user=Depends(current_user)):
    return db.tx(services.my_orders, user)


# ---------------------------------------------------------------- сотрудники: админ сети, админ кинотеатра, контролёр

class CinemaIn(BaseModel):
    name: str
    city: str
    address: str
    status: str = "active"


class HallIn(BaseModel):
    name: str
    rows: int
    seats_per_row: int
    model_url: str = "vip"


class StaffIn(BaseModel):
    last_name: str
    first_name: str
    login: str
    phone: str
    password: str
    role: str
    cinema_id: int


class UserPatch(BaseModel):
    blocked: Optional[bool] = None
    role: Optional[str] = None
    cinema_id: Optional[int] = None
    password: Optional[str] = None


class FilmIn(BaseModel):
    title: str
    age: str
    genre: str
    duration_min: int
    description: str = ""
    color: str = "#3A2E4F"
    active: bool = True
    poster: Optional[str] = None      # JPEG/PNG в base64


class SessionIn(BaseModel):
    hall_id: int = 0
    film_id: int = 0
    starts_at: str
    format: str = "2D"
    price: int


class RefundIn(BaseModel):
    reason: str


class CheckIn(BaseModel):
    code: str
    admit: bool = False


@app.get("/api/staff/cinemas")
def st_cinemas(user=Depends(current_user)):
    return db.tx(staff.staff_cinemas, user)


@app.post("/api/staff/cinemas")
def st_cinema_add(body: CinemaIn, user=Depends(current_user)):
    return db.tx(staff.save_cinema, user, 0, body.name, body.city, body.address, body.status)


@app.put("/api/staff/cinemas/{cinema_id}")
def st_cinema_edit(cinema_id: int, body: CinemaIn, user=Depends(current_user)):
    return db.tx(staff.save_cinema, user, cinema_id, body.name, body.city, body.address, body.status)


@app.post("/api/staff/cinemas/{cinema_id}/halls")
def st_hall_add(cinema_id: int, body: HallIn, user=Depends(current_user)):
    return db.tx(staff.save_hall, user, 0, cinema_id, body.name, body.rows, body.seats_per_row, body.model_url)


@app.put("/api/staff/halls/{hall_id}")
def st_hall_edit(hall_id: int, body: HallIn, user=Depends(current_user)):
    return db.tx(staff.save_hall, user, hall_id, 0, body.name, body.rows, body.seats_per_row, body.model_url)


@app.get("/api/staff/users")
def st_users(q: str = "", role: str = "", user=Depends(current_user)):
    return db.tx(staff.search_users, user, q, role)


@app.post("/api/staff/users")
def st_user_add(body: StaffIn, user=Depends(current_user)):
    return db.tx(staff.create_staff, user, body.last_name, body.first_name, body.login, body.phone, body.password, body.role, body.cinema_id)


@app.patch("/api/staff/users/{uid}")
def st_user_edit(uid: int, body: UserPatch, user=Depends(current_user)):
    return db.tx(staff.update_user, user, uid, body.blocked, body.role, body.cinema_id, body.password)


@app.get("/api/staff/films")
def st_films(user=Depends(current_user)):
    return db.tx(staff.staff_films, user)


@app.post("/api/staff/films")
def st_film_add(body: FilmIn, user=Depends(current_user)):
    return db.tx(staff.save_film, user, 0, body.title, body.age, body.genre, body.duration_min, body.description, body.color, body.active, body.poster)


@app.put("/api/staff/films/{film_id}")
def st_film_edit(film_id: int, body: FilmIn, user=Depends(current_user)):
    return db.tx(staff.save_film, user, film_id, body.title, body.age, body.genre, body.duration_min, body.description, body.color, body.active, body.poster)


@app.get("/api/films/{film_id}/poster")
def film_poster(film_id: int):
    raw, mime = db.tx(staff.film_poster, film_id)
    return Response(content=raw, media_type=mime, headers={"Cache-Control": "public, max-age=86400"})


@app.get("/api/staff/sessions")
def st_sessions(day: str, cinema_id: int = 0, user=Depends(current_user)):
    return db.tx(staff.staff_sessions, user, cinema_id, day)


@app.post("/api/staff/sessions")
def st_session_add(body: SessionIn, user=Depends(current_user)):
    return db.tx(staff.save_session, user, 0, body.hall_id, body.film_id, body.starts_at, body.format, body.price)


@app.put("/api/staff/sessions/{session_id}")
def st_session_edit(session_id: int, body: SessionIn, user=Depends(current_user)):
    return db.tx(staff.save_session, user, session_id, 0, 0, body.starts_at, body.format, body.price)


@app.delete("/api/staff/sessions/{session_id}")
def st_session_del(session_id: int, user=Depends(current_user)):
    return db.tx(staff.delete_session, user, session_id)


@app.get("/api/staff/report")
def st_report(date_from: str, date_to: str, cinema_id: int = 0, user=Depends(current_user)):
    return db.tx(staff.sales_report, user, cinema_id, date_from, date_to)


@app.get("/api/staff/payments")
def st_payments(cinema_id: int = 0, date_from: str = "", date_to: str = "", status: str = "", q: str = "", user=Depends(current_user)):
    return db.tx(staff.payments, user, cinema_id, date_from or None, date_to or None, status, q)


@app.post("/api/staff/orders/{order_id}/refund")
def st_refund(order_id: int, body: RefundIn, user=Depends(current_user)):
    return db.tx(staff.refund, user, order_id, body.reason)


@app.post("/api/staff/check")
def st_check(body: CheckIn, user=Depends(current_user)):
    return db.tx(staff.check_ticket, user, body.code, body.admit)
