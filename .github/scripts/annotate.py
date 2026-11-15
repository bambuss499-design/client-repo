"""Put a bounded tail of a failed CI log in an Actions annotation."""
import pathlib
import sys

text = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace")[-24000:]
text = text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
print(f"::error title={sys.argv[2]}::{text}")
