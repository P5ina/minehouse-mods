#!/usr/bin/env python3
"""Сверяет jar из вашей папки mods с jar, собранным из этих исходников.

    python3 verify.py ~/.minecraft/mods/minehouse-client.jar menu-mod/build/libs/minehouse-client-1.0.0.jar

Сначала сравнивает SHA-256 целиком (сборка воспроизводимая, обычно совпадает байт в байт).
Если байты разные, сравнивает содержимое каждого файла внутри jar, без дат в архиве.
"""
import hashlib
import sys
import zipfile


def sha256(path: str) -> str:
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


def entries(path: str) -> dict[str, bytes]:
    with zipfile.ZipFile(path) as z:
        return {i.filename: z.read(i) for i in z.infolist() if not i.is_dir()}


if len(sys.argv) != 3:
    sys.exit(__doc__)
a, b = sys.argv[1], sys.argv[2]
ha, hb = sha256(a), sha256(b)
print(f"{ha}  {a}\n{hb}  {b}")
if ha == hb:
    print("OK: файлы совпадают байт в байт.")
    sys.exit(0)
ea, eb = entries(a), entries(b)
diff = sorted(n for n in ea.keys() | eb.keys() if ea.get(n) != eb.get(n))
if diff:
    print("РАЗНИЦА в файлах внутри jar:")
    for n in diff:
        print("  " + n)
    sys.exit(1)
print("OK: содержимое совпадает, отличаются только даты в архиве.")
