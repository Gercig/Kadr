"""Хэширование паролей и правила из ТЗ."""
import hashlib
import hmac
import re
import secrets

SPECIALS = r"~+$&#@%!?*_\-.=^"


def hash_password(password: str) -> str:
    salt = secrets.token_hex(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode(), salt.encode(), 200_000).hex()
    return f"pbkdf2${salt}${digest}"


def check_password(password: str, stored: str) -> bool:
    try:
        _, salt, digest = stored.split("$")
    except ValueError:
        return False
    test = hashlib.pbkdf2_hmac("sha256", password.encode(), salt.encode(), 200_000).hex()
    return hmac.compare_digest(test, digest)


def password_problem(password: str):
    """Не менее 8 символов: латинские буквы в обоих регистрах, цифра и спецсимвол."""
    if len(password) < 8:
        return "Пароль должен быть не короче 8 символов"
    if not re.search(r"[a-z]", password) or not re.search(r"[A-Z]", password):
        return "Нужны латинские буквы в верхнем и нижнем регистре"
    if not re.search(r"[0-9]", password):
        return "Нужна хотя бы одна цифра"
    if not re.search("[" + SPECIALS + "]", password):
        return "Нужен хотя бы один спецсимвол (~+$&#@% и др.)"
    return None


NAME_RE = re.compile(r"^[А-Яа-яЁё\-]{1,30}$")
EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")


def normalize_phone(phone: str):
    digits = re.sub(r"\D", "", phone)
    if len(digits) == 11 and digits[0] in "78":
        return "+7" + digits[1:]
    if len(digits) == 10:
        return "+7" + digits
    return None


def new_token() -> str:
    return secrets.token_urlsafe(32)


def ticket_code() -> str:
    return "KADR-" + secrets.token_hex(6).upper()
