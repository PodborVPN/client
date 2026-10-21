"""Текст поста о релизе для Телеги (HTML) из GitHub Release: версия, разделы, прямая ссылка на APK и на релиз."""
import html
import json
import re
import sys

release = json.load(sys.stdin)  # gh release view --json tagName,url,body,assets
lines = [f"<b>Podbor {html.escape(release['tagName'].lstrip('v'))}</b>"]
for raw in release["body"].splitlines():
    line = raw.strip()
    if line.startswith("### "):
        lines += ["", f"<b>{html.escape(line[4:])}</b>"]
    elif line.startswith("* "):
        # «* текст ([abc1234](ссылка))» → «• текст»: ссылка на коммит в посте лишняя.
        text = re.sub(r"\s*\(\[[0-9a-f]{7,}\]\([^)]*\)\)\s*$", "", line[2:])
        text = re.sub(r"\*\*([^*]+)\*\*", r"\1", text)
        lines.append(f"• {html.escape(text)}")
apk = next(a for a in release["assets"] if a["name"].endswith(".apk"))
lines += [
    "",
    f'Скачать: <a href="{html.escape(apk["url"])}">{html.escape(apk["name"])}</a>',
    f'Подпись и SHA-256: <a href="{html.escape(release["url"])}">страница релиза</a>',
]
print("\n".join(lines))
