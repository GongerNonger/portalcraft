"""Print the virtual-method slot order of a class in a Source SDK header (reference only)."""
import re
import sys


def slots(path, cls):
    s = open(path, encoding="latin-1").read()
    s = re.sub(r"//[^\n]*", "", s)
    s = re.sub(r"/\*.*?\*/", "", s, flags=re.S)
    m = re.search(r"(class|abstract_class|struct)\s+" + cls + r"\b[^;{]*\{", s)
    if not m:
        print("no class", cls, "in", path)
        return
    i, depth, body = m.end(), 1, []
    while depth and i < len(s):
        c = s[i]
        i += 1
        depth += (c == "{") - (c == "}")
        # keep only depth-1 text so inline bodies don't hide later virtuals
        if depth == 1 and c not in "{}":
            body.append(c)
        elif depth == 1 and c == "}":
            body.append(";")
    text = "".join(body)
    print(f"== {cls} ({path})")
    for n, v in enumerate(re.finditer(r"virtual\s+([^;]*?)\s*(=\s*0\s*)?;", text)):
        print(f"{n:3} {' '.join(v.group(1).split())}")


if __name__ == "__main__":
    for arg in sys.argv[1:]:
        path, cls = arg.split(":")
        slots(path, cls)
