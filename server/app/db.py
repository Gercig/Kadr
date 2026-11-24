"""Подключение к базе данных.

По умолчанию сервер работает с файлом SQLite (kadr.db) — так проще запускать
на ноутбуке. В Yandex Cloud задайте переменную окружения DATABASE_URL, например:
    mysql://kadr:пароль@127.0.0.1:3306/kadr
и сервер будет работать с MySQL, код запросов при этом не меняется.
"""
import os
import sqlite3
import threading
from urllib.parse import urlparse, unquote

DATABASE_URL = os.environ.get("DATABASE_URL", "sqlite:///kadr.db")
_lock = threading.RLock()


class Db:
    """Тонкая обёртка: запросы пишутся с плейсхолдером '?' для обеих СУБД."""

    def __init__(self, url: str = DATABASE_URL):
        self.url = url
        u = urlparse(url)
        self.kind = "mysql" if u.scheme.startswith("mysql") else "sqlite"
        if self.kind == "sqlite":
            path = url.split("sqlite:///", 1)[1] if "sqlite:///" in url else "kadr.db"
            self.conn = sqlite3.connect(path, check_same_thread=False)
            self.conn.row_factory = sqlite3.Row
            self.conn.execute("PRAGMA foreign_keys = ON")
        else:
            import time
            import pymysql  # устанавливается из requirements.txt
            last = None
            for _ in range(60):  # в Docker база может стартовать чуть позже сервера
                try:
                    self.conn = pymysql.connect(
                        host=u.hostname or "127.0.0.1", port=u.port or 3306,
                        user=unquote(u.username or ""), password=unquote(u.password or ""),
                        database=(u.path or "/kadr").lstrip("/"), charset="utf8mb4",
                        cursorclass=pymysql.cursors.DictCursor, autocommit=False,
                        init_command="SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED",
                    )
                    break
                except pymysql.err.OperationalError as e:
                    last = e
                    time.sleep(2)
            else:
                raise last

    def _sql(self, sql: str) -> str:
        return sql.replace("?", "%s") if self.kind == "mysql" else sql

    def _cursor(self):
        if self.kind == "mysql":
            self.conn.ping(reconnect=True)
        return self.conn.cursor()

    def all(self, sql, params=()):
        with _lock:
            cur = self._cursor()
            cur.execute(self._sql(sql), params)
            return [dict(r) for r in cur.fetchall()]

    def one(self, sql, params=()):
        rows = self.all(sql, params)
        return rows[0] if rows else None

    def run(self, sql, params=(), *, return_rowcount=False):
        """Выполняет запись; возвращает id новой строки или число затронутых строк."""
        with _lock:
            cur = self._cursor()
            cur.execute(self._sql(sql), params)
            return cur.rowcount if return_rowcount else cur.lastrowid

    def tx(self, fn, *args):
        """Выполняет одну операцию целиком: запросы разных пользователей не перемешиваются."""
        with _lock:
            try:
                result = fn(self, *args)
                self.commit()
                return result
            except Exception:
                self.rollback()
                raise

    def commit(self):
        with _lock:
            self.conn.commit()

    def rollback(self):
        with _lock:
            self.conn.rollback()

    def script(self, sql: str):
        with _lock:
            if self.kind == "sqlite":
                self.conn.executescript(sql)
            else:
                cur = self._cursor()
                for stmt in [s.strip() for s in sql.split(";") if s.strip()]:
                    cur.execute(stmt)
            self.conn.commit()
