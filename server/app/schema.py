"""Структура базы данных ИС «Кадр» (общая для SQLite и MySQL)."""

from .services import BASE_PRICE_INCREASE, has_premium_seats, now


def ddl(kind: str) -> str:
    pk = "INTEGER PRIMARY KEY AUTOINCREMENT" if kind == "sqlite" else "INT PRIMARY KEY AUTO_INCREMENT"
    txt = "TEXT" if kind == "sqlite" else "VARCHAR(255)"
    long = "TEXT"
    tail = "" if kind == "sqlite" else " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
    return f"""
CREATE TABLE IF NOT EXISTS app_migrations (
  name {txt} PRIMARY KEY
){tail};
CREATE TABLE IF NOT EXISTS cinemas (
  id {pk}, name {txt} NOT NULL, city {txt} NOT NULL, address {txt} NOT NULL,
  status {txt} NOT NULL DEFAULT 'active'
){tail};
CREATE TABLE IF NOT EXISTS halls (
  id {pk}, cinema_id INT NOT NULL, name {txt} NOT NULL,
  rows_count INT NOT NULL, seats_per_row INT NOT NULL,
  model_url {txt} NULL,
  FOREIGN KEY (cinema_id) REFERENCES cinemas(id)
){tail};
CREATE TABLE IF NOT EXISTS films (
  id {pk}, title {txt} NOT NULL, age {txt} NOT NULL, genre {txt} NOT NULL,
  duration_min INT NOT NULL, description {long}, color {txt} NOT NULL
){tail};
CREATE TABLE IF NOT EXISTS sessions (
  id {pk}, hall_id INT NOT NULL, film_id INT NOT NULL,
  starts_at {txt} NOT NULL, format {txt} NOT NULL, price INT NOT NULL,
  FOREIGN KEY (hall_id) REFERENCES halls(id), FOREIGN KEY (film_id) REFERENCES films(id)
){tail};
CREATE TABLE IF NOT EXISTS users (
  id {pk}, last_name {txt} NOT NULL, first_name {txt} NOT NULL,
  email {txt} NOT NULL UNIQUE, phone {txt} NOT NULL,
  password_hash {txt} NOT NULL, role {txt} NOT NULL DEFAULT 'viewer',
  cinema_id INT NULL, bonus INT NOT NULL DEFAULT 0,
  must_change_password INT NOT NULL DEFAULT 0,
  failed_logins INT NOT NULL DEFAULT 0, blocked INT NOT NULL DEFAULT 0
){tail};
CREATE TABLE IF NOT EXISTS tokens (
  token {txt} PRIMARY KEY, user_id INT NOT NULL, created_at {txt} NOT NULL,
  FOREIGN KEY (user_id) REFERENCES users(id)
){tail};
CREATE TABLE IF NOT EXISTS orders (
  id {pk}, user_id INT NOT NULL, session_id INT NOT NULL,
  status {txt} NOT NULL, created_at {txt} NOT NULL, expires_at {txt} NOT NULL,
  pay_method {txt} NULL, sum_total INT NOT NULL DEFAULT 0,
  bonus_used INT NOT NULL DEFAULT 0, total INT NOT NULL DEFAULT 0,
  bonus_earned INT NOT NULL DEFAULT 0,
  FOREIGN KEY (user_id) REFERENCES users(id), FOREIGN KEY (session_id) REFERENCES sessions(id)
){tail};
CREATE TABLE IF NOT EXISTS tickets (
  id {pk}, order_id INT NOT NULL, session_id INT NOT NULL,
  row_n INT NOT NULL, seat_n INT NOT NULL, type {txt} NOT NULL DEFAULT 'adult',
  price INT NOT NULL, code {txt} NOT NULL, used_at {txt} NULL,
  FOREIGN KEY (order_id) REFERENCES orders(id)
){tail};
CREATE TABLE IF NOT EXISTS bonus_ops (
  id {pk}, user_id INT NOT NULL, amount INT NOT NULL, reason {txt} NOT NULL,
  created_at {txt} NOT NULL,
  FOREIGN KEY (user_id) REFERENCES users(id)
){tail};
"""


# Новые столбцы для уже созданной базы (раздел сотрудников): добавляются, если их ещё нет
_EXTRA = [
    ("films", "poster", "{long_blob} NULL"),              # постер фильма (JPEG в base64)
    ("films", "poster_v", "INT NOT NULL DEFAULT 0"),       # версия постера — чтобы приложение обновило картинку
    ("films", "active", "INT NOT NULL DEFAULT 1"),         # фильм в прокате
    ("tickets", "seat_surcharge", "INT NOT NULL DEFAULT 0"),
    ("orders", "paid_at", "{txt} NULL"),
    ("orders", "refund_reason", "{txt} NULL"),
    ("orders", "refunded_at", "{txt} NULL"),
    ("orders", "refunded_by", "INT NULL"),
]


def _columns(db, table):
    if db.kind == "sqlite":
        return {r["name"] for r in db.all(f"PRAGMA table_info({table})")}
    return {r["Field"] for r in db.all(f"SHOW COLUMNS FROM {table}")}


def migrate(db):
    txt = "TEXT" if db.kind == "sqlite" else "VARCHAR(255)"
    long_blob = "TEXT" if db.kind == "sqlite" else "LONGTEXT"
    for table, col, decl in _EXTRA:
        if col not in _columns(db, table):
            db.run(f"ALTER TABLE {table} ADD COLUMN {col} " + decl.format(txt=txt, long_blob=long_blob))
    db.commit()
    db.tx(_raise_base_prices)


def _raise_base_prices(db):
    insert = "INSERT OR IGNORE" if db.kind == "sqlite" else "INSERT IGNORE"
    inserted = db.run(f"{insert} INTO app_migrations (name) VALUES (?)",
                      ("raise_2d_imax_base_prices",), return_rowcount=True)
    if not inserted:
        return
    sessions = db.all(
        "SELECT s.id, s.format, h.name AS hall_name, h.model_url FROM sessions s "
        "JOIN halls h ON h.id = s.hall_id WHERE s.starts_at > ?",
        (now().strftime("%Y-%m-%d %H:%M"),),
    )
    for session in sessions:
        if has_premium_seats(session["format"], session["hall_name"], session["model_url"]):
            db.run("UPDATE sessions SET price = price + ? WHERE id = ?", (BASE_PRICE_INCREASE, session["id"]))
