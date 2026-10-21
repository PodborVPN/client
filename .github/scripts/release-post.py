"""Текст поста о релизе для Телеги (HTML) из заметок GitHub Release: версия, разделы, ссылка на релиз."""
import html
import json
import re
import sys

release = json.load(sys.stdin)  # gh release view --json tagName,url,body
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
lines += ["", f'Скачать и проверить подпись: <a href="{html.escape(release["url"])}">страница релиза</a>']
print("\n".join(lines))
