"""Census of the entity classes in a Source game's maps: what Hammer's entity report would show, for every map at once.

    python tools/map_census.py <maps dir> [name prefix] [--detail class]

Prints, per class: how many, in how many maps, and what classes they are parented to.
"""
import collections, glob, os, re, struct, sys

def entities(path):
    b = open(path, "rb").read()
    ofs, length = struct.unpack_from("<ii", b, 8)
    text = b[ofs:ofs + length].decode("latin1")
    return [dict(re.findall(r'"([^"]+)"\s+"([^"]*)"', e)) for e in re.findall(r"\{[^{}]*\}", text)]

def main():
    maps_dir, prefix = sys.argv[1], (sys.argv[2] if len(sys.argv) > 2 and not sys.argv[2].startswith("--") else "")
    detail = sys.argv[sys.argv.index("--detail") + 1] if "--detail" in sys.argv else None
    count, maps, parents, version = collections.Counter(), collections.defaultdict(set), collections.defaultdict(collections.Counter), collections.Counter()
    n = 0
    for path in sorted(glob.glob(os.path.join(maps_dir, prefix + "*.bsp"))):
        name = os.path.basename(path)[:-4]
        version[struct.unpack_from("<i", open(path, "rb").read(8), 4)[0]] += 1
        ents = entities(path)
        n += 1
        by_name = {e.get("targetname"): e for e in ents if e.get("targetname")}
        for e in ents:
            c = e.get("classname", "?")
            count[c] += 1
            maps[c].add(name)
            p = by_name.get(e.get("parentname", "").split(",")[0])
            if p:
                parents[c][p.get("classname")] += 1
            if c == detail:
                print(name, {k: v for k, v in e.items() if not k.startswith("On") and k not in ("classname", "hammerid")})
    print("%d maps, BSP versions %s" % (n, dict(version)))
    for c, k in count.most_common():
        par = ("  <- on " + ", ".join("%s:%d" % x for x in parents[c].most_common(5))) if parents[c] else ""
        print("%-36s %6d  in %3d maps%s" % (c, k, len(maps[c]), par))

if __name__ == "__main__":
    main()
