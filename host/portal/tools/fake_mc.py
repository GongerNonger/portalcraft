"""Stand-in for the Minecraft side: prints HostState and (with --follow) echoes it back as McState.

  python fake_mc.py            # watch what Portal sends
  python fake_mc.py --follow   # also take over the player and walk it in a slow circle
  python fake_mc.py --cmd "map testchmb_a_00"   # run a console command in Portal and exit
"""
import argparse, math, socket, struct, time

HOST = ("127.0.0.1", 27515)
HOST_STATE = struct.Struct("<4sII64sff3f3fI3f3f32sB3x" + "I3f3f" * 2 + "ff")
MC_STATE = struct.Struct("<4sIII3f3fBBBB3f3fIf")
assert HOST_STATE.size == 236 and MC_STATE.size == 76


def parse(data):
    v = HOST_STATE.unpack(data)
    s = dict(seq=v[1], flags=v[2], map=v[3].split(b"\0")[0].decode(), yaw=v[4], pitch=v[5],
             origin=v[6:9], vel=v[9:12], tp=v[12], tpOrigin=v[13:16], tpVel=v[16:19], keys=v[19], mouse=v[20])
    p = v[21:]
    s["portals"] = [dict(flags=p[i * 7], origin=p[i * 7 + 1:i * 7 + 4], angles=p[i * 7 + 4:i * 7 + 7]) for i in range(2)]
    s["pressed"] = [i for i in range(256) if s["keys"][i >> 3] & (1 << (i & 7))]
    return s


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
    ap.add_argument("--seconds", type=float, default=10)
    a = ap.parse_args()
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    if a.view:
        p, y = (float(v) for v in a.view.split(","))
        sock.sendto(b"PCV1" + struct.pack("<2f", p, y), HOST)
        return
    if a.mc:
        for command in a.mc:
            sock.sendto(b"PCR1" + command.encode() + b"\0", ("127.0.0.1", 27516))
        return
    if a.lights:
        e, r = (float(v) for v in a.lights.split(","))
        sock.sendto(b"PCX2" + struct.pack("<ff", e, r), HOST)
        return
    if a.exposure is not None:
        sock.sendto(b"PCX1" + struct.pack("<f", a.exposure), HOST)
        return
    if a.give:
        sock.sendto(b"PCG1" + a.give.encode() + b"\0", ("127.0.0.1", 27516))
        return
    if a.goto:
        x, y, z = (float(v) for v in a.goto.split(","))
        sock.sendto(b"PCD1" + struct.pack("<3f", x, y, z), ("127.0.0.1", 27516))
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
    sock.bind(("127.0.0.1", 27516))
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
                sock.sendto(MC_STATE.pack(b"PCM3", seq, 1, ack, *pos, 0, 0, 0, 1, 0, 0, 0, *pos, *pos, seq, 0.0), HOST)


if __name__ == "__main__":
    main()
