"""Stand-in for the Minecraft side: prints HostState and (with --follow) echoes it back as McState.

  python fake_mc.py            # watch what Portal sends
  python fake_mc.py --follow   # also take over the player and walk it in a slow circle
  python fake_mc.py --cmd "map testchmb_a_00"   # run a console command in Portal and exit
  python fake_mc.py --instance 1 ...            # talk to the second pair (hl2.exe -pcinstance 1)

Reproducing what the player did (Portal started with -portalcraftdev; it keeps the last 15 minutes):
  python fake_mc.py --dump-replay bug1          # write them to portal\\addons\\replay-bug1.txt
  python fake_mc.py --replay <that file> --replay-check   # play them back, then say how far it parted
"""
import argparse, math, os, socket, struct, time

HOST_PORT, MC_PORT = 27515, 27516  # instance 0; each further instance is 10 higher
HOST = ("127.0.0.1", HOST_PORT)
MC = ("127.0.0.1", MC_PORT)
DEV_INPUT = struct.Struct("<4s32sBBbBffII")  # "PCK2", pcproto::DevInput
assert DEV_INPUT.size == 56
HOST_STATE = struct.Struct("<4sII64sff3f3fI3f3f32sB3x" + "I3f3f" * 2 + "ff" + "II9f3f" + "I" + "3f" + "I" + "I3f")
MC_STATE = struct.Struct("<4sIII3f3fBBBB3f3fIfII4s" + "I3f")  # ... then the mover Steve is on (0: none) and where Minecraft has it
assert HOST_STATE.size == 328 and MC_STATE.size == 104


def parse(data):
    v = HOST_STATE.unpack(data)
    s = dict(seq=v[1], flags=v[2], map=v[3].split(b"\0")[0].decode(), yaw=v[4], pitch=v[5],
             origin=v[6:9], vel=v[9:12], tp=v[12], tpOrigin=v[13:16], tpVel=v[16:19], keys=v[19], mouse=v[20])
    p = v[21:]
    s["mover"], s["moverOrigin"] = v[-4], v[-3:]  # the mover under Portal's own player (0: none)
    s["portals"] = [dict(flags=p[i * 7], origin=p[i * 7 + 1:i * 7 + 4], angles=p[i * 7 + 4:i * 7 + 7]) for i in range(2)]
    s["pressed"] = [i for i in range(256) if s["keys"][i >> 3] & (1 << (i & 7))]
    return s


# ---- replays: the plugin's recording of the last 15 minutes, dumped as text and fed back ----------

INT_COLUMNS = ("tick", "mouse", "wheel", "hostflags", "shots", "mcbits", "frame")


def load_replay(path):
    """A dump the plugin wrote: its header, the portals' changes, and a dict per tick (by column name)."""
    rec = dict(path=path, map="", tick_ms=15.0, portal={}, changes=[], ticks=[])
    columns = None
    with open(path) as f:
        if not f.readline().startswith("portalcraft-replay"):
            raise SystemExit(f"{path} is not a replay dump")
        for line in f:
            w = line.split()
            if not w or w[0].startswith("#"):
                continue
            if w[0] == "map":
                rec["map"] = w[1] if len(w) > 1 else ""
            elif w[0] == "tick_ms":
                rec["tick_ms"] = float(w[1])
            elif w[0] == "portal":
                rec["portal"][w[1]] = dict(flags=int(w[2], 0), origin=tuple(map(float, w[3:6])), angles=tuple(map(float, w[6:9])))
            elif w[0] == "P":
                rec["changes"].append(dict(tick=int(w[1]), colour=w[2], flags=int(w[3], 0), origin=tuple(map(float, w[4:7])),
                                           angles=tuple(map(float, w[7:10]))))
            elif w[0] == "columns":
                columns = w[1:]
            elif w[0] == "T" and columns:
                t = dict(zip(columns, w[1:]))
                for name in columns:
                    if name == "keys":
                        t[name] = [] if t[name] == "-" else [int(k) for k in t[name].split(",")]
                    elif name in INT_COLUMNS:
                        t[name] = int(t[name], 0)
                    else:
                        t[name] = float(t[name])
                t["pos"] = (t["x"], t["y"], t["z"])
                rec["ticks"].append(t)
    if not rec["ticks"]:
        raise SystemExit(f"{path} has no ticks in it")
    return rec


def replay_start(ticks, last):
    """Where to start: the whole recording, or its last N seconds, moved to a tick where the player
    stood still if there is one. A replay can put Steve in a place but can't give him a velocity, so
    one that starts in the middle of a jump or a fling has parted from the recording by its first tick."""
    def still(t):
        return math.sqrt(t["vx"] ** 2 + t["vy"] ** 2 + t["vz"] ** 2) < 1.0

    start = 0
    if last:
        cutoff = ticks[-1]["ms"] - last * 1000.0
        start = next(i for i, t in enumerate(ticks) if t["ms"] >= cutoff)
    for i in list(range(start, -1, -1)) + list(range(start + 1, len(ticks))):  # earlier first: more run-up, not less
        if still(ticks[i]):
            return i, True
    return start, False


def portals_at(rec, tick):
    """Each colour's portal as it was at a tick of the recording."""
    at = {}
    for c in rec["changes"]:
        if c["tick"] <= tick:
            at[c["colour"]] = c
    return at


def wait_until(due):
    # Windows sleeps in steps of a millisecond or more: sleep most of the way, spin the rest.
    while True:
        left = due - time.perf_counter()
        if left <= 0:
            return
        if left > 0.003:
            time.sleep(left - 0.002)


def run_replay(sock, rec, start, settle):
    ticks = rec["ticks"]
    first = ticks[start]
    sock.sendto(b"PCD1" + struct.pack("<3f", *first["pos"]), MC)  # Minecraft moves Steve; Portal follows
    sock.sendto(b"PCV1" + struct.pack("<2f", first["pitch"], first["yaw"]), HOST)
    time.sleep(settle)
    due, previous, late, pauses = time.perf_counter(), first, 0, 0
    for t in ticks[start:]:
        gap = t["ms"] - previous["ms"]
        if gap > 250.0:  # the game was paused here: the recording skipped it, and so do we
            gap, pauses = rec["tick_ms"], pauses + 1
        due += gap / 1000.0
        if time.perf_counter() - due > 0.03:
            late += 1
        wait_until(due)
        bits = bytearray(32)
        for k in t["keys"]:
            bits[k >> 3] |= 1 << (k & 7)
        # Portal's own fire and use, which the player's clicks and E reached by way of its window.
        # Not while a Minecraft screen was open: Portal didn't see them then.
        buttons = 0 if t["mcbits"] & 8 else (t["mouse"] & 3) | (4 if 8 in t["keys"] else 0)
        wheel = (t["wheel"] - previous["wheel"] + 128) % 256 - 128
        sock.sendto(DEV_INPUT.pack(b"PCK2", bytes(bits), t["mouse"], 3, wheel, buttons, t["pitch"], t["yaw"], 250, t["tick"] + 1), HOST)
        previous = t
    sock.sendto(DEV_INPUT.pack(b"PCK2", bytes(32), 0, 2, 0, 0, 0.0, 0.0, 0, 0), HOST)  # let go of everything
    print(f"replayed {len(ticks) - start} ticks, {(ticks[-1]['ms'] - first['ms']) / 1000.0:.1f} s"
          + (f"; skipped {pauses} pause(s)" if pauses else "") + (f"; {late} ticks sent over 30 ms late" if late else ""))


def dump_replay(sock, name):
    """Asks the plugin to write its recording; returns the path it wrote, or None."""
    sock.sendto(b"PCQ1" + name.encode() + b"\0", HOST)
    sock.settimeout(3.0)
    try:
        data = sock.recv(1024)
    except OSError:  # a timeout, or Windows saying nobody listens on the port
        print("no answer from the plugin: is Portal running with -portalcraftdev, and in a level? (see portalcraft.log)")
        return None
    if data[:4] != b"PCQ1":
        return None
    return data[4:].split(b"\0")[0].decode(errors="replace")


def shots_fired(ticks):
    return sum(1 for a, b in zip(ticks, ticks[1:]) if a["shots"] != b["shots"])


def distance(a, b):
    return math.sqrt(sum((p - q) ** 2 for p, q in zip(a, b)))


def compare_replay(rec, check):
    """How far a replay parted from the recording it played. `check` is a dump taken after the replay:
    its frame column says which recorded tick was being fed in at each of its own ticks."""
    ticks, run = check["ticks"], []
    end = max((i for i, t in enumerate(ticks) if t["frame"]), default=-1)
    while end >= 0 and ticks[end]["frame"] and (not run or ticks[end]["frame"] <= run[-1]["frame"]):
        run.append(ticks[end])  # only the last replay in the dump, if an earlier one is still in it
        end -= 1
    run = [t for t in reversed(run) if t["frame"] <= len(rec["ticks"])]
    if not run:
        print("replay check: the dump has no replayed ticks in it (frame is 0 throughout)")
        return
    if check["map"] != rec["map"]:
        print(f"replay check: WRONG MAP: recorded on {rec['map']}, replayed on {check['map']}")
    pairs = [(t, rec["ticks"][t["frame"] - 1]) for t in run]
    apart = [distance(t["pos"], r["pos"]) for t, r in pairs]
    worst = max(range(len(apart)), key=apart.__getitem__)
    first, last = pairs[0][1], pairs[-1][1]
    seconds = lambda r: (r["ms"] - first["ms"]) / 1000.0
    fed = len({t["frame"] for t in run})
    print(f"replay check: recorded ticks {first['tick']}..{last['tick']} ({seconds(last):.1f} s) on {rec['map']}; "
          f"{fed} of {last['tick'] - first['tick'] + 1} reached a tick of their own")
    print(f"  apart at the start {apart[0]:.1f} units, at the end {apart[-1]:.1f}, at most {apart[worst]:.1f} "
          f"(tick {pairs[worst][1]['tick']}, {seconds(pairs[worst][1]):.1f} s in), on average {sum(apart) / len(apart):.1f}  [40 units = 1 block]")
    parted = next((i for i, d in enumerate(apart) if d > 16.0), None)
    if parted is None:
        print("  never more than 16 units apart")
    else:
        r, t = pairs[parted][1], pairs[parted][0]
        print(f"  first over 16 units apart at tick {r['tick']} ({seconds(r):.1f} s in): recorded ({r['x']:.1f} {r['y']:.1f} {r['z']:.1f}), "
              f"replayed ({t['x']:.1f} {t['y']:.1f} {t['z']:.1f})")
    recorded_shots = shots_fired(rec["ticks"][first["tick"]:last["tick"] + 1])
    print(f"  portal gun shots: {recorded_shots} recorded, {shots_fired(run)} replayed")
    then = portals_at(rec, last["tick"])
    for colour in ("blue", "orange"):
        a, b = then.get(colour), check["portal"].get(colour)
        if a and b and (a["flags"] & 2 or b["flags"] & 2):
            if (a["flags"] & 2) != (b["flags"] & 2):
                print(f"  {colour} portal: {'open' if a['flags'] & 2 else 'not open'} in the recording, {'open' if b['flags'] & 2 else 'not open'} after the replay")
            else:
                print(f"  {colour} portal: {distance(a['origin'], b['origin']):.1f} units from where the recording had it")


def replay_main(a, sock):
    rec = load_replay(a.replay)
    if a.replay_check:  # a dump taken earlier: compare only, nothing is sent to the game
        compare_replay(rec, load_replay(a.replay_check))
        return
    ticks = rec["ticks"]
    start, still = replay_start(ticks, a.replay_last)
    first = ticks[start]
    print(f"replaying {a.replay}: {rec['map']}, ticks {first['tick']}..{ticks[-1]['tick']} of {len(ticks)}, "
          f"{(ticks[-1]['ms'] - first['ms']) / 1000.0:.1f} s, from ({first['x']:.1f} {first['y']:.1f} {first['z']:.1f})")
    print(f"  Portal must already be on {rec['map']}: a replay doesn't load the map")
    if not still:
        print(f"  the player never stands still in it: starting at {first['vx']:.0f} {first['vy']:.0f} {first['vz']:.0f} units/s, which a replay can't give Steve")
    for colour, p in portals_at(rec, first["tick"]).items():
        if p["flags"] & 2:
            print(f"  the {colour} portal was already open at ({p['origin'][0]:.1f} {p['origin'][1]:.1f} {p['origin'][2]:.1f}), "
                  f"angles ({p['angles'][0]:.0f} {p['angles'][1]:.0f} {p['angles'][2]:.0f}): a replay doesn't put it there, place it first")
    run_replay(sock, rec, start, a.replay_settle)
    if a.replay_check is not None:
        time.sleep(0.3)  # the plugin lets go, and Steve's last step arrives
        name = os.path.splitext(os.path.basename(a.replay))[0]
        path = dump_replay(sock, ((name[7:] if name.startswith("replay-") else name) + "-check")[-60:])
        if path:
            print(f"replay check: the replay's own recording is {path}")
            compare_replay(rec, load_replay(path))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--follow", action="store_true")
    ap.add_argument("--cmd")
    ap.add_argument("--keys", help="dev: hold SDL scancodes in Portal, e.g. 26 for W, 26,225 for W+shift")
    ap.add_argument("--ms", type=int, default=1000)
    ap.add_argument("--goto", help="dev: move Minecraft's player to x,y,z (host units); Portal follows")
    ap.add_argument("--mouse", type=int, default=0, help="dev: with --keys, also hold mouse buttons (1 left, 2 right, 4 middle)")
    ap.add_argument("--view", help="dev: set Portal's camera to pitch,yaw")
    ap.add_argument("--give", help="dev: give Minecraft's player an item, e.g. minecraft:stone")
    ap.add_argument("--mc", action="append", help="dev: run a Minecraft command as the server (repeatable), e.g. \"give @p tnt 64\"")
    ap.add_argument("--lights", help="dev: Minecraft lights in Portal: exponent,units-per-level (e.g. 1,22)")
    ap.add_argument("--exposure", type=float, help="dev: how bright Portal's lighting makes Minecraft's blocks (0 = off)")
    ap.add_argument("--blast", help="test a Minecraft explosion in Portal: x,y,z,radius,damage (host units), e.g. --blast=0,0,64,320,120")
    ap.add_argument("--hit", help="test a Minecraft hit on a Portal entity: index,x,y,z,fx,fy,fz,damage")
    ap.add_argument("--trace", type=int, help="dev: log N server ticks of movement in the plugin log")
    ap.add_argument("--dump-replay", nargs="?", const="", metavar="NAME",
                    help="dev: write the plugin's recording of the last 15 minutes to portal\\addons\\replay-NAME.txt")
    ap.add_argument("--replay", metavar="FILE", help="dev: play a --dump-replay file back: Steve to its start, then its keys, buttons and view tick by tick")
    ap.add_argument("--replay-check", nargs="?", const="", metavar="DUMP",
                    help="with --replay: afterwards, dump the replay's own recording and print how far it parted from FILE. "
                         "Given a DUMP taken earlier, only compares the two (nothing is sent to the game)")
    ap.add_argument("--replay-last", type=float, metavar="SECONDS", help="with --replay: only the last SECONDS of the file")
    ap.add_argument("--replay-settle", type=float, default=1.5, metavar="SECONDS", help="with --replay: the wait after moving Steve to the start")
    ap.add_argument("--seconds", type=float, default=10)
    ap.add_argument("--instance", type=int, default=0, help="which Portal+Minecraft pair (hl2.exe -pcinstance N); 0 is the usual one")
    a = ap.parse_args()
    global HOST, MC  # the replay functions use them too
    HOST = ("127.0.0.1", HOST_PORT + 10 * a.instance)
    MC = ("127.0.0.1", MC_PORT + 10 * a.instance)
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    if a.replay:
        replay_main(a, sock)
        return
    if a.dump_replay is not None:
        path = dump_replay(sock, a.dump_replay or time.strftime("%Y%m%d-%H%M%S"))
        if path:
            print(path)
        return
    if a.view:
        p, y = (float(v) for v in a.view.split(","))
        sock.sendto(b"PCV1" + struct.pack("<2f", p, y), HOST)
        return
    if a.mc:
        for command in a.mc:
            sock.sendto(b"PCR1" + command.encode() + b"\0", MC)
        return
    if a.lights:
        e, r = (float(v) for v in a.lights.split(","))
        sock.sendto(b"PCX2" + struct.pack("<ff", e, r), HOST)
        return
    if a.exposure is not None:
        sock.sendto(b"PCX1" + struct.pack("<f", a.exposure), HOST)
        return
    if a.give:
        sock.sendto(b"PCG1" + a.give.encode() + b"\0", MC)
        return
    if a.goto:
        x, y, z = (float(v) for v in a.goto.split(","))
        sock.sendto(b"PCD1" + struct.pack("<3f", x, y, z), MC)
        return
    if a.trace:
        sock.sendto(b"PCT1" + struct.pack("<i", a.trace), HOST)
        return
    if a.blast:
        sock.sendto(b"PCB1" + struct.pack("<5f", *map(float, a.blast.split(","))), HOST)
        return
    if a.hit:
        v = a.hit.split(",")
        sock.sendto(b"PCI1" + struct.pack("<I7f", int(v[0]), *map(float, v[1:8])), HOST)
        return
    if a.keys is not None and a.keys != "":
        bits = bytearray(32)
        for k in a.keys.split(","):
            k = int(k)
            bits[k >> 3] |= 1 << (k & 7)
        sock.sendto(b"PCK1" + bytes(bits) + struct.pack("<I", a.ms) + (bytes((a.mouse,)) if a.mouse else b""), HOST)
        return
    if a.cmd:
        sock.sendto(b"PCC1" + a.cmd.encode() + b"\0", HOST)
        return
    sock.bind(MC)
    sock.settimeout(1.0)
    end, last_print, seq, ack, centre, t0 = time.time() + a.seconds, 0, 0, 0, None, time.time()
    while time.time() < end:
        try:
            s = parse(sock.recv(512))
        except socket.timeout:
            print("no HostState from Portal")
            continue
        if time.time() - last_print > 0.5:
            last_print = time.time()
            print(f"#{s['seq']} flags={s['flags']:#x} map={s['map']} yaw={s['yaw']:.1f} pitch={s['pitch']:.1f} "
                  f"pos=({s['origin'][0]:.0f},{s['origin'][1]:.0f},{s['origin'][2]:.0f}) tp={s['tp']} keys={s['pressed']} "
                  f"mouse={s['mouse']} portals={[ (p['flags'], tuple(round(c) for c in p['origin'])) for p in s['portals']]}")
        if a.follow:
            if s["tp"] != ack:
                ack, centre = s["tp"], s["tpOrigin"]
            if centre:
                t = time.time() - t0
                pos = (centre[0] + 64 * math.cos(t), centre[1] + 64 * math.sin(t), centre[2])
                seq += 1
                sock.sendto(MC_STATE.pack(b"PCM5", seq, 1, ack, *pos, 0, 0, 0, 1, 0, 0, 0, *pos, *pos, seq, 0.0, 0, 0, b"\xff" * 4, 0, 0.0, 0.0, 0.0), HOST)


if __name__ == "__main__":
    main()
