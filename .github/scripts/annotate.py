"""Put readable, bounded tails of failed CI logs in Actions annotations."""
import pathlib
import sys

lines = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace").splitlines()
# Loom can print one classpath longer than GitHub's entire annotation limit.
lines = [line if len(line) <= 420 else line[:250] + " ... [long line truncated] ... " + line[-100:] for line in lines]
if len(lines) > 110:
    lines = lines[:8] + ["... earlier log lines omitted ..."] + lines[-100:]
text = "\n".join(lines)[-18000:]
chunks = [text[i:i + 2000] for i in range(0, len(text), 2000)] or ["No output"]
for i, chunk in enumerate(chunks, 1):
    encoded = chunk.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    print(f"::error title={sys.argv[2]} ({i}/{len(chunks)})::{encoded}")
