// PortalCraft host plugin for Steam Portal (32-bit Source, SP 2013 branch).
//
// Loaded as a server plugin from portal\addons\portalcraft.vdf. Minecraft is authoritative for
// where the player is; Portal stays authoritative for where the player looks, for its portal
// gun, and for teleporting through portals. Each movement tick we let Portal run its own
// movement, then overwrite the result with Minecraft's position and velocity. When Portal moves
// the player itself (through a portal, at level start) we hand that move to Minecraft and stop
// overriding until Minecraft confirms it has applied it.

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <winsock2.h>
#include <windows.h>

#include <cmath>
#include <cstddef>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <vector>
#include <intrin.h>

#include "../../../protocol/portalcraft_protocol.h"
#include "camera.h"
#include "hooks.h"
#include "instance.h"
#include "launcher.h"
#include "raybox.h"
#include "overlay.h"
#include "worldrender.h"
#include "sdk.h"

using sdk::Vector;

namespace {

// ---- logging -------------------------------------------------------------------------

FILE* g_log = nullptr;
char g_logDir[MAX_PATH] = {}; // portal\addons\, with its trailing backslash: where the log is
using MsgFn = void (*)(const char*, ...);
MsgFn g_msg = nullptr;

void logf(const char* fmt, ...) {
	char buf[1024];
	va_list ap;
	va_start(ap, fmt);
	vsnprintf(buf, sizeof buf, fmt, ap);
	va_end(ap);
	if (g_log) {
		fprintf(g_log, "%s\n", buf);
		fflush(g_log);
	}
	if (g_msg) {
		g_msg("[PortalCraft] %s\n", buf);
	}
}

// ---- engine handles ------------------------------------------------------------------

HMODULE g_self = nullptr;
sdk::CreateInterfaceFn g_engineFactory = nullptr;
void* g_engineServer = nullptr;  // VEngineServer021
void* g_playerInfoMgr = nullptr; // PlayerInfoManager002
void* g_engineClient = nullptr;  // VEngineClient013 (lazy: engine.dll exposes it)
uint8_t* g_edicts = nullptr;     // edict array base, from ServerActivate
char g_map[64] = {};
bool g_inLevel = false;

void* engineInterface(const char* module, const char* name) {
	HMODULE m = GetModuleHandleA(module);
	if (!m) {
		return nullptr;
	}
	auto factory = reinterpret_cast<sdk::CreateInterfaceFn>(GetProcAddress(m, "CreateInterface"));
	return factory ? factory(name, nullptr) : nullptr;
}

void* edictAt(int index) {
	return g_edicts ? g_edicts + index * sdk::kEdictSize : nullptr;
}

bool edictInUse(void* e) {
	// m_fStateFlags & FL_EDICT_FREE (1 << 1)
	return e && !(*static_cast<int*>(e) & 2) && sdk::edictNetworkable(e) && sdk::edictUnknown(e);
}

float dist(const Vector& a, const Vector& b) {
	float dx = a.x - b.x, dy = a.y - b.y, dz = a.z - b.z;
	return std::sqrt(dx * dx + dy * dy + dz * dz);
}

Vector toVecP(const pcproto::Vec3& v) {
	return {v.x, v.y, v.z};
}

// ---- portal crossings, carried through --------------------------------------------------
// Portal teleports its player through a portal between two server ticks. Minecraft hears of it a
// tick or two later (teleportAck) and meanwhile sends steps from the old side. We used to stop
// driving until it answered: Portal's own movement took over, then Minecraft's first steps after
// the answer were stale, and an infinite fall stalled and jumped at every portal, losing its speed.
// Now each crossing is kept as the rigid move it is, p' = r p + t, and every Minecraft step from
// before it is carried through it. Minecraft gets the same move (HostState cross fields) and
// carries its own current position and velocity through, so both sides agree with no gap.
struct Xf {
	float r[9]; // row-major
	Vector t;
};

Xf xfIdentity() {
	return {{1, 0, 0, 0, 1, 0, 0, 0, 1}, {0, 0, 0}};
}

Vector xfDir(const Xf& x, const Vector& v) {
	return {x.r[0] * v.x + x.r[1] * v.y + x.r[2] * v.z, x.r[3] * v.x + x.r[4] * v.y + x.r[5] * v.z,
		x.r[6] * v.x + x.r[7] * v.y + x.r[8] * v.z};
}

Vector xfPoint(const Xf& x, const Vector& p) {
	Vector d = xfDir(x, p);
	return {d.x + x.t.x, d.y + x.t.y, d.z + x.t.z};
}

// `first`, then `second`.
Xf xfThen(const Xf& first, const Xf& second) {
	Xf out{};
	for (int i = 0; i < 3; i++) {
		for (int j = 0; j < 3; j++) {
			out.r[i * 3 + j] = second.r[i * 3] * first.r[j] + second.r[i * 3 + 1] * first.r[3 + j] + second.r[i * 3 + 2] * first.r[6 + j];
		}
	}
	out.t = xfPoint(second, first.t);
	return out;
}

// Source's AngleVectors (degrees): forward, right, up.
void angleBasis(const pcproto::Vec3& a, Vector* f, Vector* r, Vector* u) {
	const float k = 3.14159265f / 180.0f;
	float sp = std::sin(a.x * k), cp = std::cos(a.x * k), sy = std::sin(a.y * k), cy = std::cos(a.y * k);
	float sr = std::sin(a.z * k), cr = std::cos(a.z * k);
	*f = {cp * cy, cp * sy, -sp};
	*r = {-sr * sp * cy + cr * sy, -sr * sp * sy - cr * cy, -sr * cp};
	*u = {cr * sp * cy + sr * sy, cr * sp * sy - sr * cy, cr * cp};
}

// Two kinds, in the order they happened: a host move we handed to Minecraft (seq: Minecraft is past
// it once its teleportAck is), or Portal matching a crossing Minecraft made itself (match: past it
// once its crossMatchedEcho is). Exactly one of the two is set.
struct Crossing {
	uint32_t seq;
	uint32_t match;
	Xf xf;
};
constexpr int kCrossings = 8;
Crossing g_crossings[kCrossings];
int g_crossingCount = 0;
uint32_t g_opaqueSeq = 0;                 // the newest host move that isn't a crossing (a level start, a trigger_teleport)
uint32_t g_matched = 0;                   // Minecraft's own crossings (McState.crossCount) Portal has made too
pcproto::HostPortal g_portalsNow[2] = {}; // as last sent
DWORD g_portalsChangedAt = 0;             // when either last moved, opened, linked or closed

// A place on Minecraft's timeline: the host moves it has taken, and the matches it knows of.
struct McAt {
	uint32_t ack;
	uint32_t matched;
};

bool crossingAfter(const Crossing& c, McAt at) {
	return c.seq ? c.seq > at.ack : c.match > at.matched;
}

// The crossings after `from`, up to and including `to`, as one move; false if a host move among
// them isn't a crossing (then there's nothing to carry through: Minecraft has to take it first).
bool crossingsBetween(McAt from, McAt to, Xf* out) {
	if (g_opaqueSeq > from.ack && g_opaqueSeq <= to.ack) {
		return false;
	}
	Xf x = xfIdentity();
	for (int i = 0; i < g_crossingCount; i++) {
		if (crossingAfter(g_crossings[i], from) && !crossingAfter(g_crossings[i], to)) {
			x = xfThen(x, g_crossings[i].xf);
		}
	}
	*out = x;
	return true;
}

bool anyCrossingAfter(McAt from) {
	for (int i = 0; i < g_crossingCount; i++) {
		if (crossingAfter(g_crossings[i], from)) {
			return true;
		}
	}
	return false;
}

// Through linked portal `in` and out of the other one, the way Minecraft carries Steve
// (PlayerCrossings): his centre goes through, his hull stays upright.
bool geometricCrossing(int in, float halfHeight, Xf* out);

// Portal moved the player from `before` to `after` by itself: if that's a linked portal carrying it
// to the other one (the in-portal's frame turned half round about its long axis, onto the out's),
// the move as a crossing. Anchored on the two points, so Portal's own fix-ups (keeping the player
// upright out of a floor or ceiling) are in it exactly.
bool portalCrossing(const Vector& before, const Vector& after, Xf* out) {
	const pcproto::HostPortal* p = g_portalsNow;
	if (!(p[0].flags & pcproto::kPortalLinked) || !(p[1].flags & pcproto::kPortalLinked)) {
		return false;
	}
	float bestError = 128.0f;
	bool found = false;
	for (int i = 0; i < 2; i++) {
		const pcproto::HostPortal& in = p[i];
		const pcproto::HostPortal& to = p[1 - i];
		if (dist(before, toVecP(in.origin)) > 192.0f) {
			continue;
		}
		Vector fa, ra, ua, fb, rb, ub;
		angleBasis(in.angles, &fa, &ra, &ua);
		angleBasis(to.angles, &fb, &rb, &ub);
		const Vector* a[3] = {&fa, &ra, &ua};
		const Vector* b[3] = {&fb, &rb, &ub};
		const float sign[3] = {-1.0f, -1.0f, 1.0f}; // forward and right turn round, up stays
		Xf x{};
		for (int r = 0; r < 3; r++) {
			for (int c = 0; c < 3; c++) {
				float v = 0.0f;
				for (int k = 0; k < 3; k++) {
					v += sign[k] * (&b[k]->x)[r] * (&a[k]->x)[c];
				}
				x.r[r * 3 + c] = v;
			}
		}
		Vector d = xfDir(x, {before.x - in.origin.x, before.y - in.origin.y, before.z - in.origin.z});
		Vector predicted{to.origin.x + d.x, to.origin.y + d.y, to.origin.z + d.z};
		float error = dist(predicted, after);
		if (error < bestError) {
			bestError = error;
			Vector turned = xfDir(x, before);
			x.t = {after.x - turned.x, after.y - turned.y, after.z - turned.z};
			*out = x;
			found = true;
		}
	}
	return found;
}

bool geometricCrossing(int in, float halfHeight, Xf* out) {
	const pcproto::HostPortal& a = g_portalsNow[in];
	const pcproto::HostPortal& b = g_portalsNow[1 - in];
	if (!(a.flags & pcproto::kPortalLinked) || !(b.flags & pcproto::kPortalLinked)) {
		return false;
	}
	Vector fa, ra, ua, fb, rb, ub;
	angleBasis(a.angles, &fa, &ra, &ua);
	angleBasis(b.angles, &fb, &rb, &ub);
	const Vector* va[3] = {&fa, &ra, &ua};
	const Vector* vb[3] = {&fb, &rb, &ub};
	const float sign[3] = {-1.0f, -1.0f, 1.0f};
	Xf x{};
	for (int r = 0; r < 3; r++) {
		for (int c = 0; c < 3; c++) {
			float v = 0.0f;
			for (int k = 0; k < 3; k++) {
				v += sign[k] * (&vb[k]->x)[r] * (&va[k]->x)[c];
			}
			x.r[r * 3 + c] = v;
		}
	}
	// feet' = R (feet + h - A) + B - h
	Vector shifted = xfDir(x, {-a.origin.x, -a.origin.y, halfHeight - a.origin.z});
	x.t = {shifted.x + b.origin.x, shifted.y + b.origin.y, shifted.z + b.origin.z - halfHeight};
	*out = x;
	return true;
}

// ---- send-table field lookup (pattern-free, by name) ---------------------------------

int findProp(sdk::SendTable* table, const char* name, int base, int depth = 0) {
	if (!table || depth > 8) {
		return -1;
	}
	for (int i = 0; i < table->count; i++) {
		uint8_t* p = table->props + i * sdk::kSendPropSize;
		const char* var = *reinterpret_cast<const char**>(p + sdk::kSendPropVarName);
		int offset = *reinterpret_cast<int*>(p + sdk::kSendPropOffset);
		auto* sub = *reinterpret_cast<sdk::SendTable**>(p + sdk::kSendPropDataTable);
		if (var && std::strcmp(var, name) == 0) {
			return base + offset;
		}
		if (sub) {
			int found = findProp(sub, name, base + offset, depth + 1);
			if (found >= 0) {
				return found;
			}
		}
	}
	return -1;
}

struct PortalOffsets {
	bool ready = false;
	int origin = -1, angles = -1, activated = -1, linked = -1, isPortal2 = -1;
} g_po;

void resolvePortalOffsets(void* networkable) {
	auto* sc = static_cast<sdk::ServerClass*>(sdk::networkableServerClass(networkable));
	if (!sc || !sc->table) {
		return;
	}
	g_po.origin = findProp(sc->table, "m_vecOrigin", 0);
	g_po.angles = findProp(sc->table, "m_angRotation", 0);
	g_po.activated = findProp(sc->table, "m_bActivated", 0);
	g_po.linked = findProp(sc->table, "m_hLinkedPortal", 0);
	g_po.isPortal2 = findProp(sc->table, "m_bIsPortal2", 0);
	g_po.ready = true;
	logf("prop_portal (%s): origin %d angles %d activated %d linked %d isPortal2 %d", sc->name, g_po.origin, g_po.angles,
		g_po.activated, g_po.linked, g_po.isPortal2);
}

// ---- link ----------------------------------------------------------------------------

SOCKET g_sock = INVALID_SOCKET;
sockaddr_in g_mcAddr{};
pcproto::McState g_mc{};

// Half of Steve's hull in Minecraft, which carries his centre through portals: 72 units tall, 60 sneaking.
float steveHalfHeight() {
	if (g_mc.flags & pcproto::kMcGliding) {
		return 12.0f; // the elytra's hull: 0.6 blocks at Steve's scale
	}
	return g_mc.sneaking ? 30.0f : 36.0f;
}
void dropAppliedShoves(uint32_t ack); // the player puppet, below
// Minecraft's recent physics steps on a smoothed timeline (see interpolatedMinecraft).
struct TickSample {
	uint32_t seq;
	pcproto::Vec3 pos;
	uint32_t ack;     // Minecraft's teleportAck when it sent this step: which side of later crossings it's on
	uint32_t matched; // ... and its crossMatchedEcho
};
constexpr int kTickHistory = 8;
TickSample g_ticks[kTickHistory] = {};
uint32_t g_tickSeq = 0;
double g_tickOffset = 0.0; // seconds: our clock minus Minecraft's tick timeline (seq * 50 ms)
bool g_haveOffset = false;

double nowSeconds() {
	static LARGE_INTEGER frequency{};
	if (!frequency.QuadPart) {
		QueryPerformanceFrequency(&frequency);
	}
	LARGE_INTEGER now;
	QueryPerformanceCounter(&now);
	return double(now.QuadPart) / double(frequency.QuadPart);
}
DWORD g_mcTime = 0; // GetTickCount of the last McState
uint32_t g_hostSeq = 0;

void linkOpen() {
	WSADATA wsa;
	WSAStartup(MAKEWORD(2, 2), &wsa);
	g_sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
	u_long nonBlocking = 1;
	ioctlsocket(g_sock, FIONBIO, &nonBlocking);
	sockaddr_in addr{};
	addr.sin_family = AF_INET;
	addr.sin_port = htons(instance::hostPort());
	addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
	if (bind(g_sock, reinterpret_cast<sockaddr*>(&addr), sizeof addr) != 0) {
		logf("bind 127.0.0.1:%d failed (%d): is another Portal running?", instance::hostPort(), WSAGetLastError());
	}
	g_mcAddr.sin_family = AF_INET;
	g_mcAddr.sin_port = htons(instance::mcPort());
	g_mcAddr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
}

// Dev: keys injected by a "PCK1" packet (tools/fake_mc.py --keys), OR'd into the real ones until expiry.
uint8_t g_fakeKeys[32] = {};
uint8_t g_fakeMouse = 0;
DWORD g_fakeUntil = 0;

// Dev: per-tick movement trace for N server ticks ("PCT1", tools/fake_mc.py --trace N).
int g_traceTicks = 0;

bool mcReady() {
	return (g_mc.flags & pcproto::kMcReady) && GetTickCount() - g_mcTime < 500;
}

// ---- Minecraft's light in Portal ----------------------------------------------------------
// Lava, torches and glowstone light Portal's own walls: Minecraft sends its nearest emitters
// ("PCL1", see LightExporter.java) and each becomes one of the engine's dynamic lights
// (IVEfx::CL_AllocDlight, slot 4 of VEngineEffects001), the kind a muzzle flash makes, which
// light the world's brushes and its models alike.
struct McLight {
	float x, y, z;
	uint8_t r, g, b, level;
};
static_assert(sizeof(McLight) == 16, "PCL1 entry");
constexpr int kMaxMcLights = 16;
McLight g_mcLights[kMaxMcLights];
int g_mcLightCount = 0, g_dlightsUsed = 0;
float g_lightExponent = 3.0f, g_lightRadiusPerLevel = 22.0f;
void* g_effects = nullptr; // VEngineEffects001

void receiveLights(const char* buf, int n) {
	uint32_t count;
	std::memcpy(&count, buf + 4, 4);
	if (count > uint32_t(kMaxMcLights) || 8 + int(count) * 16 > n) {
		return;
	}
	std::memcpy(g_mcLights, buf + 8, size_t(count) * 16);
	g_mcLightCount = int(count);
}

// dlight_t (SP 2013): flags 0, origin 4, radius 16, color 20 (r, g, b, exponent), die 24, decay 28,
// minlight 32, key 36, style 40, direction 44, inner angle 56, outer angle 60.
void applyLights() {
	if (!g_effects && g_engineFactory) {
		g_effects = g_engineFactory("VEngineEffects001", nullptr);
	}
	if (!g_effects) {
		return;
	}
	int lit = mcReady() && g_inLevel ? g_mcLightCount : 0;
	int total = lit > g_dlightsUsed ? lit : g_dlightsUsed;
	for (int i = 0; i < total; i++) {
		auto* dl = sdk::vcall<uint8_t*>(g_effects, 4, 0x50430000 + i); // CL_AllocDlight(key): ours, reused by key
		if (!dl) {
			continue;
		}
		if (i >= lit) {
			*reinterpret_cast<float*>(dl + 16) = 0.0f; // radius 0, already dead: gone
			*reinterpret_cast<float*>(dl + 24) = 0.0f;
			continue;
		}
		const McLight& l = g_mcLights[i];
		*reinterpret_cast<int*>(dl + 0) = 0;
		*reinterpret_cast<Vector*>(dl + 4) = {l.x, l.y, l.z};
		*reinterpret_cast<float*>(dl + 16) = (float(l.level) + 1.0f) * g_lightRadiusPerLevel;
		dl[20] = l.r;
		dl[21] = l.g;
		dl[22] = l.b;
		dl[23] = uint8_t(int8_t(g_lightExponent));
		*reinterpret_cast<float*>(dl + 24) = 1e9f; // never expires; we take it away ourselves
		*reinterpret_cast<float*>(dl + 28) = 0.0f;
		*reinterpret_cast<float*>(dl + 32) = 0.0f;
	}
	static bool logged = false;
	if (!logged && lit > 0) {
		logged = true;
		logf("lights: %d of Minecraft's lights in Portal (first at %.0f %.0f %.0f, level %u, rgb %u %u %u)", lit, g_mcLights[0].x, g_mcLights[0].y,
			g_mcLights[0].z, g_mcLights[0].level, g_mcLights[0].r, g_mcLights[0].g, g_mcLights[0].b);
	}
	g_dlightsUsed = lit;
}

// Dev packets that drive the game (console commands, fake keys, the view) are only taken when Portal
// was started with -portalcraftdev: otherwise any program on this PC could run Portal's console.
bool devMode() {
	static int dev = -1;
	if (dev < 0) {
		const char* line = GetCommandLineA();
		dev = line && std::strstr(line, "-portalcraftdev") ? 1 : 0;
	}
	return dev == 1;
}

// ---- dev: the last 45 seconds, kept for a replay ---------------------------------------------
// When the player hits a bug, what he did to get there used to be a guess. In dev mode every
// server tick's input (the keys and buttons Minecraft was sent, the view) and where it left the
// player go into a ring: one small copy a tick, no file touched. "PCQ1" (tools/fake_mc.py
// --dump-replay) writes the ring out as text, a line per tick, and fake_mc.py --replay feeds the
// same input back a tick at a time ("PCK2").
struct ReplayTick {
	double time; // nowSeconds()
	uint8_t keys[32];
	uint8_t mouse, shots, hostFlags;
	uint8_t mcBits; // Minecraft's side: 1 on the ground, 2 sneaking, 4 holding the portal gun, 8 a screen is open
	int8_t wheel;
	float pitch, yaw;
	pcproto::Vec3 origin, velocity, mcOrigin;
	uint32_t frame; // the recorded tick a replay was feeding in here (PCK2), 0 when the input was live
	pcproto::HostPortal portals[2];
};
constexpr size_t kReplayTicks = 3000; // 45 seconds at Portal's 66.7 ticks a second
std::vector<ReplayTick> g_replay; // stays empty outside dev mode
size_t g_replayNext = 0, g_replayCount = 0;

// Dev: a replay's input ("PCK2"), on top of the fake keys above.
uint32_t g_fakeFrame = 0;        // the recorded tick being fed in, 0 when none
int g_fakeWheel = 0;             // wheel notches not yet added to HostState.wheel
uint8_t g_fakePortalButtons = 0; // DevPortalButtons the replay holds down in Portal itself

// Presses and releases Portal's own fire and use, as the player's clicks on its window did: a
// replay runs with Portal's window in the background, where it reads no mouse or keyboard.
void setPortalButtons(uint8_t wanted) {
	static const char* const kNames[] = {"attack", "attack2", "use"};
	for (int i = 0; i < 3; i++) {
		uint8_t bit = uint8_t(1u << i);
		if (((wanted ^ g_fakePortalButtons) & bit) && g_engineServer) {
			char cmd[16];
			snprintf(cmd, sizeof cmd, "%c%s\n", (wanted & bit) ? '+' : '-', kNames[i]);
			sdk::serverCommand(g_engineServer, cmd);
		}
	}
	g_fakePortalButtons = wanted;
}

void recordReplay(const pcproto::HostState& s) {
	if (!devMode() || !(s.flags & pcproto::kHostInGame)) {
		return;
	}
	// Paused (the menu, the console): nothing happens, and the wait mustn't push the bug out of the ring.
	if (g_engineClient && sdk::clientIsPaused(g_engineClient)) {
		return;
	}
	if (g_replay.empty()) {
		g_replay.resize(kReplayTicks);
	}
	ReplayTick& t = g_replay[g_replayNext];
	t.time = nowSeconds();
	std::memcpy(t.keys, s.keys, sizeof t.keys);
	t.mouse = s.mouse;
	t.shots = s.shots;
	t.hostFlags = uint8_t(s.flags);
	bool linked = mcReady();
	t.mcBits = uint8_t(!linked ? 0 : (g_mc.onGround ? 1 : 0) | (g_mc.sneaking ? 2 : 0) | (g_mc.holdingPortalGun ? 4 : 0) |
		((g_mc.flags & pcproto::kMcScreen) ? 8 : 0));
	t.wheel = s.wheel;
	t.pitch = s.pitch;
	t.yaw = s.yaw;
	t.origin = s.origin;
	t.velocity = s.velocity;
	t.mcOrigin = linked ? g_mc.origin : s.origin;
	t.frame = g_fakeFrame;
	std::memcpy(t.portals, s.portals, sizeof t.portals);
	g_replayNext = (g_replayNext + 1) % kReplayTicks;
	if (g_replayCount < kReplayTicks) {
		g_replayCount++;
	}
}

void writeReplayPortal(FILE* f, const char* lead, int which, const pcproto::HostPortal& p) {
	fprintf(f, "%s%s 0x%x %.3f %.3f %.3f %.3f %.3f %.3f\n", lead, which ? "orange" : "blue", p.flags, p.origin.x, p.origin.y, p.origin.z, p.angles.x,
		p.angles.y, p.angles.z);
}

// Writes the ring to addons\replay-<name>.txt and tells whoever asked where it went.
void dumpReplay(const char* name, const sockaddr_in& from) {
	char safe[64];
	size_t len = 0;
	for (const char* c = name; *c && len < sizeof safe - 1; c++) { // a file name, not a path
		bool plain = (*c >= 'a' && *c <= 'z') || (*c >= 'A' && *c <= 'Z') || (*c >= '0' && *c <= '9') || *c == '-' || *c == '_';
		safe[len++] = plain ? *c : '_';
	}
	safe[len] = 0;
	char path[MAX_PATH + 80];
	snprintf(path, sizeof path, "%sreplay-%s.txt", g_logDir, len ? safe : "dump");
	FILE* f = g_replayCount ? std::fopen(path, "w") : nullptr;
	if (!f) {
		logf(g_replayCount ? "replay: couldn't write %s" : "replay: nothing recorded yet (no level played since Portal started)", path);
		return;
	}
	size_t first = (g_replayNext + kReplayTicks - g_replayCount) % kReplayTicks;
	const ReplayTick& oldest = g_replay[first];
	const ReplayTick& newest = g_replay[(g_replayNext + kReplayTicks - 1) % kReplayTicks];
	// The length of a tick, measured: the gaps a pause left in the ring aren't ticks.
	double sum = 0.0;
	int gaps = 0;
	for (size_t i = 1; i < g_replayCount; i++) {
		double d = g_replay[(first + i) % kReplayTicks].time - g_replay[(first + i - 1) % kReplayTicks].time;
		if (d < 0.1) {
			sum += d;
			gaps++;
		}
	}
	fprintf(f, "portalcraft-replay 1\n");
	fprintf(f, "map %s\n", g_map);
	fprintf(f, "ticks %zu\n", g_replayCount);
	fprintf(f, "tick_ms %.3f\n", gaps ? sum * 1000.0 / gaps : 15.0);
	// The portals as they are now, at the end. Where they were before is in the P lines below.
	writeReplayPortal(f, "portal ", 0, newest.portals[0]);
	writeReplayPortal(f, "portal ", 1, newest.portals[1]);
	fprintf(f, "# P tick colour flags, origin x y z, angles pitch yaw roll: a portal as it was at tick 0, and whenever it changed\n");
	fprintf(f, "# keys: pressed SDL scancodes; mouse: 1 left 2 right 4 middle; hostflags: HostFlags; shots: HostState.shots\n");
	fprintf(f, "# mc x y z: where Minecraft had Steve; mcbits: 1 on ground, 2 sneaking, 4 holds the portal gun, 8 a screen is open\n");
	fprintf(f, "# frame: the recorded tick a replay was feeding in (0: live input)\n");
	fprintf(f, "columns tick ms keys mouse wheel pitch yaw x y z vx vy vz hostflags shots mcx mcy mcz mcbits frame\n");
	for (size_t i = 0; i < g_replayCount; i++) {
		const ReplayTick& t = g_replay[(first + i) % kReplayTicks];
		for (int p = 0; p < 2; p++) {
			if (i == 0 || std::memcmp(&t.portals[p], &g_replay[(first + i - 1) % kReplayTicks].portals[p], sizeof t.portals[p]) != 0) {
				char lead[24];
				snprintf(lead, sizeof lead, "P %zu ", i);
				writeReplayPortal(f, lead, p, t.portals[p]);
			}
		}
		fprintf(f, "T %zu %.1f ", i, (t.time - oldest.time) * 1000.0);
		bool any = false;
		for (int k = 0; k < 256; k++) {
			if (t.keys[k >> 3] & (1 << (k & 7))) {
				fprintf(f, any ? ",%d" : "%d", k);
				any = true;
			}
		}
		fprintf(f, "%s %u %d %.3f %.3f %.3f %.3f %.3f %.2f %.2f %.2f 0x%x %u %.3f %.3f %.3f %u %u\n", any ? "" : "-", t.mouse, t.wheel, t.pitch, t.yaw,
			t.origin.x, t.origin.y, t.origin.z, t.velocity.x, t.velocity.y, t.velocity.z, t.hostFlags, t.shots, t.mcOrigin.x, t.mcOrigin.y,
			t.mcOrigin.z, t.mcBits, t.frame);
	}
	std::fclose(f);
	logf("replay: wrote %zu ticks (%.1f s) of %s to %s", g_replayCount, newest.time - oldest.time, g_map, path);
	char reply[4 + sizeof path];
	std::memcpy(reply, "PCQ1", 4);
	std::strcpy(reply + 4, path);
	sendto(g_sock, reply, int(4 + std::strlen(path) + 1), 0, reinterpret_cast<const sockaddr*>(&from), sizeof from);
}

// ---- builds whose interface slots were checked --------------------------------------------
// The calls below go through vtable slots checked by disassembling these exact builds of Portal's
// DLLs (IServerTools 28-31 in server.dll, VPhysics in vphysics.dll, Con_NPrintf in engine.dll). A
// Steam update can move them, and a call through a moved slot crashes Portal. So each feature
// only runs on the build it was checked against (the DLL's link time stamp), unless
// portalcraft.ini says trust_unknown_build=1; on another build it stays off and says so.
struct CheckedBuild {
	const char* module;
	uint32_t stamp;
};
constexpr CheckedBuild kCheckedBuilds[] = {
	{"server.dll", 0x67578384u},
	{"vphysics.dll", 0x674532f1u},
	{"engine.dll", 0x675781e6u},
};

uint32_t moduleStamp(const char* name) {
	auto* base = reinterpret_cast<uint8_t*>(GetModuleHandleA(name));
	if (!base) {
		return 0;
	}
	auto* dos = reinterpret_cast<IMAGE_DOS_HEADER*>(base);
	auto* nt = reinterpret_cast<IMAGE_NT_HEADERS*>(base + dos->e_lfanew);
	return nt->FileHeader.TimeDateStamp;
}

// True if `module` is the build its slots were checked on (or the ini trusts any build).
bool checkedBuild(const char* module) {
	for (const CheckedBuild& b : kCheckedBuilds) {
		if (std::strcmp(b.module, module) != 0) {
			continue;
		}
		static bool logged[sizeof kCheckedBuilds / sizeof kCheckedBuilds[0]] = {};
		size_t i = size_t(&b - kCheckedBuilds);
		uint32_t stamp = moduleStamp(module);
		bool ok = stamp == b.stamp || launcher::trustUnknownBuild();
		if (!ok && !logged[i]) {
			logged[i] = true;
			logf("unknown %s build (stamp %08x, checked on %08x): the features using its slots stay off", module, stamp, b.stamp);
			launcher::notice("PortalCraft: Portal was updated since PortalCraft was checked against it; some features are off (see portalcraft.log)");
		}
		return ok;
	}
	return true;
}

// ---- Minecraft's blasts and hits on Portal's props ----------------------------------------
// Through IServerTools (VSERVERTOOLS002 in server.dll; slots checked in its vtable: 28
// ClearMultiDamage, 29 ApplyMultiDamage, 30 AddMultiDamage(info, entity), 31 RadiusDamage(info, src,
// radius, classIgnore, ignore)), the same calls Portal's own explosions and weapons make: physics
// props get the push, turrets tip over, and the player (whom Minecraft hurts itself) is left out.

// CTakeDamageInfo as SP2013's server.dll has it (92 bytes; zero padding past it is harmless).
struct DamageInfo {
	Vector force, position, reported;
	uint32_t inflictor, attacker, weapon; // EHANDLEs
	float damage, maxDamage, baseDamage;
	int32_t damageType, custom, stats, ammoType, damagedOtherPlayers, penetration;
	float bonus;
	bool forceFriendlyFire;
	uint8_t pad[35];
};
static_assert(offsetof(DamageInfo, damage) == 48 && offsetof(DamageInfo, forceFriendlyFire) == 88, "CTakeDamageInfo layout");

constexpr int DMG_CLUB = 1 << 7, DMG_BLAST = 1 << 6;
void* g_serverTools = nullptr;

// The player as attacker (Steve's doing) and as the entity a blast leaves out. Null between levels.
void* playerBase() {
	void* e = edictAt(1);
	return edictInUse(e) ? sdk::networkableBaseEntity(sdk::edictNetworkable(e)) : nullptr;
}

// Steve's doing (the player as attacker), or the world's (`fromWorld`: nobody to blame or react to).
DamageInfo damageInfo(float damage, int type, bool fromWorld = false) {
	DamageInfo info{};
	info.inflictor = info.attacker = info.weapon = 0xFFFFFFFFu; // INVALID_EHANDLE_INDEX
	int by = fromWorld ? 0 : 1;
	if (void* unknown = edictInUse(edictAt(by)) ? sdk::edictUnknown(edictAt(by)) : nullptr) {
		// IHandleEntity slot 2, GetRefEHandle: the player's own handle.
		info.inflictor = info.attacker = *sdk::vcall<const uint32_t*>(unknown, 2);
	}
	info.damage = info.maxDamage = info.baseDamage = damage;
	info.damageType = type;
	info.ammoType = -1;
	return info;
}

bool serverToolsReady() {
	if (!checkedBuild("server.dll")) {
		return false;
	}
	if (!g_serverTools) {
		g_serverTools = engineInterface("server.dll", "VSERVERTOOLS002");
		logf("server tools %p", g_serverTools);
	}
	return g_serverTools && g_inLevel && playerBase();
}

// ---- Minecraft owns the player's health (as SkyCraft does) ----------------------------------
// Whatever hurts Portal's player (turrets, energy balls, toxic water, crushers) is refunded every server
// frame and sent to Minecraft ("PCU1"), where Steve takes it, armor and all. A hit that kills
// Portal's player outright (toxic water, an energy ball) kills Steve too (flag 1). When Steve dies
// ("PCZ1": a fall, lava, TNT, or the turret damage above), Portal's player is killed here, so
// Portal's own death and checkpoint reload follow. Health is kept at Portal's own maximum, never
// above it, so nothing in Portal that clamps or regenerates health can look like a hit.
struct PlayerOffsets {
	bool ready = false;
	int health = -1, lifeState = -1, flags = -1, viewEntity = -1, activeWeapon = -1, groundEntity = -1, nextAttack = -1;
} g_pl;

// Portal's player entity, its send-table offsets looked up the first time. Null between levels.
uint8_t* playerFields() {
	void* e = edictAt(1);
	if (!g_inLevel || !edictInUse(e)) {
		return nullptr;
	}
	void* networkable = sdk::edictNetworkable(e);
	auto* base = static_cast<uint8_t*>(sdk::networkableBaseEntity(networkable));
	if (base && !g_pl.ready) {
		g_pl.ready = true;
		auto* sc = static_cast<sdk::ServerClass*>(sdk::networkableServerClass(networkable));
		if (sc && sc->table) {
			g_pl.health = findProp(sc->table, "m_iHealth", 0);
			g_pl.lifeState = findProp(sc->table, "m_lifeState", 0);
			g_pl.flags = findProp(sc->table, "m_fFlags", 0);
			g_pl.viewEntity = findProp(sc->table, "m_hViewEntity", 0);
			g_pl.activeWeapon = findProp(sc->table, "m_hActiveWeapon", 0);
			g_pl.groundEntity = findProp(sc->table, "m_hGroundEntity", 0);
			g_pl.nextAttack = findProp(sc->table, "m_flNextAttack", 0);
		}
		logf("player: %s m_iHealth %d m_lifeState %d m_fFlags %d m_hViewEntity %d m_hActiveWeapon %d m_hGroundEntity %d", sc ? sc->name : "?",
			g_pl.health, g_pl.lifeState, g_pl.flags, g_pl.viewEntity, g_pl.activeWeapon, g_pl.groundEntity);
	}
	return base;
}

// ---- scripted scenes ----------------------------------------------------------------------
// Portal takes its player for a scene now and then (as Skyrim does for furniture and scenes, which
// SkyCraft hands back to Skyrim): a point_viewcontrol camera (the chamber 00 wake-up, the escape
// ending), or a frozen player. Meanwhile Portal moves the player itself, and Minecraft follows
// it without input (kHostScripted).
constexpr int FL_FROZEN = 1 << 5, FL_ATCONTROLS = 1 << 6;
bool g_scripted = false;

void updateScripted() {
	uint8_t* base = playerFields();
	bool scripted = false;
	if (base && g_pl.flags >= 0) {
		int flags = *reinterpret_cast<int*>(base + g_pl.flags);
		scripted = (flags & (FL_FROZEN | FL_ATCONTROLS)) != 0;
	}
	if (base && g_pl.viewEntity >= 0 && !scripted) {
		uint32_t view = *reinterpret_cast<uint32_t*>(base + g_pl.viewEntity);
		uint32_t self = 0xFFFFFFFFu;
		if (void* unknown = sdk::edictUnknown(edictAt(1))) {
			self = *sdk::vcall<const uint32_t*>(unknown, 2); // IHandleEntity::GetRefEHandle
		}
		scripted = view != 0xFFFFFFFFu && view != 0 && view != self; // 0 would be the world: never a camera
	}
	if (scripted != g_scripted) {
		logf("scripted scene %s", scripted ? "started: Portal has the player" : "over: Minecraft drives again");
		g_scripted = scripted;
	}
}
// ---- riding lifts ---------------------------------------------------------------------------
// Standing on something that moves up or down (the elevators, lifts, moving platforms), Portal owns
// the player's height: its train pushes its player along exactly, while Minecraft's copy of the
// lift arrives ~16 times a second and Minecraft's position plays back a tick or two late, so Steve
// sagged, got pushed up, and bounced all the way up the elevator. While riding, the plugin keeps
// Portal's height (Minecraft still walks him around inside the lift) and Minecraft follows it
// (kHostRiding).
bool g_riding = false;
int g_rideEntity = -1;
float g_rideLastZ = 0.0f;
DWORD g_rideStill = 0; // when the lift last stopped moving
// A platform that carries the player sideways (the light-rail platforms). Portal moves its player
// along with it, and as he jumps off it Portal's velocity for him jumps too: read as impulses and
// shoves and handed to Minecraft, each one replaced Steve's own jump (seen: a jump on a moving
// platform cut to 18 units of 40, five hand-overs in six ticks, Steve thrown about). On one, and
// for a moment after leaving it, Portal's small moves and velocity changes are the platform's and
// are not handed over; Minecraft carries Steve on its own copy of the platform.
float g_rideLastX = 0.0f, g_rideLastY = 0.0f;
DWORD g_carriedAt = 0; // when the thing under Portal's player last moved sideways
// When Portal's player last went through a portal with Steve (a match, or one we made). Just out
// of a portal, close to its rim, Portal nudges its player's velocity tick after tick to work him
// clear of the wall around the opening. Each was handed to Minecraft as an impulse, and they add
// up there: dropped into a floor portal that opened under his feet, Steve came out of the ceiling
// one at its edge, was pushed up at 60 to 150 units a second each tick, rose back up through the
// ceiling portal and only then began to fall (his report, and the log: ten impulses, then a
// crossing upwards). For half a second after a crossing a small change of velocity is Portal's
// own business.
DWORD g_crossedAt = 0;

bool carriedLately() {
	return g_carriedAt != 0 && GetTickCount() - g_carriedAt < 1500;
}

bool collideableOrigin(int index, Vector* out);

bool g_onLooseProp = false; // Portal has its player standing on a loose prop (a cube)

void updateRiding() {
	uint8_t* base = playerFields();
	bool moving = false;
	if (base && g_pl.groundEntity >= 0) {
		uint32_t h = *reinterpret_cast<uint32_t*>(base + g_pl.groundEntity);
		int index = h == 0xFFFFFFFFu ? -1 : int(h & 0xFFF);
		Vector o;
		// Only what carries the player like a lift: not a loose prop he happens to stand on. A cube
		// settles under his weight, a hair up and down, and each twitch handed Steve's height to
		// Portal and back (standing on a cube flapped between the two).
		bool loose = false;
		if (index > 1) {
			void* e = edictAt(index);
			void* networkable = edictInUse(e) ? sdk::edictNetworkable(e) : nullptr;
			const char* cls = networkable ? sdk::networkableClassName(networkable) : nullptr;
			loose = cls && (std::strncmp(cls, "prop_physics", 12) == 0 || std::strncmp(cls, "npc_", 4) == 0);
		}
		g_onLooseProp = loose;
		if (index > 1 && !loose && collideableOrigin(index, &o)) { // 0 is the world, 1 the player
			if (index == g_rideEntity && std::fabs(o.z - g_rideLastZ) > 0.05f) {
				moving = true;
			}
			if (index == g_rideEntity && (std::fabs(o.x - g_rideLastX) > 0.01f || std::fabs(o.y - g_rideLastY) > 0.01f)) {
				g_carriedAt = GetTickCount();
			}
			g_rideEntity = index;
			g_rideLastZ = o.z;
			g_rideLastX = o.x;
			g_rideLastY = o.y;
		} else {
			g_rideEntity = -1;
		}
	}
	DWORD now = GetTickCount();
	if (moving) {
		g_rideStill = now;
	}
	// Stays on a moment after the lift stops, so a stop-start ride doesn't flap.
	bool riding = moving || (g_riding && g_rideEntity >= 0 && now - g_rideStill < 300);
	if (riding != g_riding) {
		logf("riding %s", riding ? "a moving lift: Portal keeps the player's height" : "over");
		g_riding = riding;
	}
}

constexpr int kFullHealth = 100;
float g_hurtPending = 0.0f;
DWORD g_hurtSentAt = 0;
bool g_wasAlive = false;
bool g_killedForMc = false; // Portal's player died because Steve did: don't kill Steve back
bool g_killPending = false;
uint32_t g_mcDeathSeq = 0;
DWORD g_deadSince = 0;
bool g_reloadSent = false;

bool following();

void sendHurt(float damage, uint32_t flags) {
	char packet[12];
	std::memcpy(packet, "PCU1", 4);
	std::memcpy(packet + 4, &damage, 4);
	std::memcpy(packet + 8, &flags, 4);
	sendto(g_sock, packet, sizeof packet, 0, reinterpret_cast<sockaddr*>(&g_mcAddr), sizeof g_mcAddr);
}

void killPortalPlayer(void* player) {
	if (!serverToolsReady()) {
		return;
	}
	DamageInfo info = damageInfo(100000.0f, 0 /* DMG_GENERIC */);
	__try {
		sdk::vcall<void>(g_serverTools, 28);
		sdk::vcall<void>(g_serverTools, 30, static_cast<const void*>(&info), player);
		sdk::vcall<void>(g_serverTools, 29);
	} __except (EXCEPTION_EXECUTE_HANDLER) {
		logf("health: killing Portal's player faulted");
	}
}

void bridgeHealth() {
	void* e = edictAt(1);
	uint8_t* base = playerFields();
	if (!base) {
		g_wasAlive = false;
		return;
	}
	if (g_pl.health < 0 || g_pl.lifeState < 0) {
		return;
	}
	int& health = *reinterpret_cast<int*>(base + g_pl.health);
	bool alive = base[g_pl.lifeState] == 0; // LIFE_ALIVE
	if (g_killPending) {
		g_killPending = false;
		if (alive) {
			g_killedForMc = true;
			logf("health: Steve died; killing Portal's player");
			killPortalPlayer(base);
			return;
		}
	}
	if (!alive) {
		if (g_wasAlive && !g_killedForMc && mcReady()) {
			logf("health: Portal's player died; so does Steve");
			sendHurt(0.0f, 1);
		}
		if (g_wasAlive) {
			g_deadSince = GetTickCount();
			g_reloadSent = false;
		}
		// Single-player Portal waits for a key before it reloads; Steve is already back on his feet
		// in Minecraft, so after a moment Portal goes back to its last checkpoint by itself.
		if (!g_reloadSent && mcReady() && GetTickCount() - g_deadSince > 4000) {
			g_reloadSent = true;
			logf("health: reloading Portal's last checkpoint");
			sdk::serverCommand(g_engineServer, "reload\n");
		}
		g_wasAlive = false;
		g_hurtPending = 0.0f;
		return;
	}
	bool fresh = !g_wasAlive; // a level start or a reload: whatever the save had isn't a new hit
	g_wasAlive = true;
	if (fresh) {
		g_killedForMc = false;
	}
	if (!mcReady()) {
		g_hurtPending = 0.0f;
		return;
	}
	if (health < kFullHealth) {
		if (!fresh) {
			g_hurtPending += float(kFullHealth - health);
		}
		health = kFullHealth;
		*static_cast<int*>(e) |= 1 | (1 << 8); // FL_EDICT_CHANGED | FL_FULL_EDICT_CHANGED
	}
	if (g_hurtPending > 0.0f && GetTickCount() - g_hurtSentAt >= 50) {
		static int logged = 0;
		if (logged++ < 5) {
			logf("health: Portal hurt the player by %.0f; Minecraft takes it", g_hurtPending);
		}
		sendHurt(g_hurtPending, 0);
		g_hurtPending = 0.0f;
		g_hurtSentAt = GetTickCount();
	}
}

// ---- Steve's blocks in Portal's physics --------------------------------------------------
// As SkyCraft makes Skyrim's NPCs stand on Minecraft's blocks, Portal's physics props (cubes,
// turrets, energy balls) collide with Steve's: Minecraft sends its blocks' collision boxes
// ("PCS1", BlockSolids.java) and each becomes a static VPhysics box owned by the world entity, so
// Portal treats it like its own brushes. Only boxes that changed are made or removed; a removed
// one gets a tiny blast at its spot, which wakes whatever was resting on it (VPhysics has no
// "wake what touches this", and a sleeping cube would hang in the air).
// Slots checked in vphysics.dll: IPhysics (VPhysics031) 7 GetActiveEnvironmentByIndex;
// IPhysicsCollision (VPhysicsCollision007) 29 BBoxToCollide(mins, maxs); IPhysicsEnvironment 8
// CreatePolyObjectStatic(collide, material, position, angles, params), 10 DestroyObject.
struct ObjectParams { // objectparams_t
	Vector* massCenterOverride;
	float mass, inertia, damping, rotdamping, rotInertiaLimit;
	const char* name;
	void* gameData;
	float volume, dragCoefficient;
	bool enableCollisions;
};
struct BlockBox {
	float box[6]; // mins xyz, maxs xyz (host units)
	void* object;
};
constexpr uint32_t kMaxBlockBoxes = 512;
void* g_physics = nullptr;
void* g_physCollision = nullptr;
void* g_blockEnv = nullptr; // the environment g_blockBoxes live in
std::vector<BlockBox> g_blockBoxes;
std::vector<float> g_blockIncoming;
bool g_blockDirty = false;

void updateBlockBounds();

void receiveSolids(const char* buf, int n) {
	uint32_t count;
	std::memcpy(&count, buf + 4, 4);
	if (count > kMaxBlockBoxes || n != int(8 + count * 24)) {
		return;
	}
	std::vector<float> boxes(count * 6);
	std::memcpy(boxes.data(), buf + 8, count * 24);
	if (boxes != g_blockIncoming) {
		g_blockIncoming.swap(boxes);
		g_blockDirty = true;
		updateBlockBounds();
	}
}

// A new level: the old boxes went with the old physics environment.
void forgetBlockBoxes() {
	g_blockBoxes.clear();
	g_blockEnv = nullptr;
	g_blockDirty = !g_blockIncoming.empty();
}

void* makeBlockBox(void* env, const float* b, void* world) {
	Vector half{(b[3] - b[0]) * 0.5f, (b[4] - b[1]) * 0.5f, (b[5] - b[2]) * 0.5f};
	Vector neg{-half.x, -half.y, -half.z};
	Vector centre{b[0] + half.x, b[1] + half.y, b[2] + half.z};
	Vector angles{0.0f, 0.0f, 0.0f};
	ObjectParams params{nullptr, 1.0f, 1.0f, 0.1f, 0.1f, 0.05f, "portalcraft block", world, 0.0f, 1.0f, true};
	__try {
		void* collide = sdk::vcall<void*>(g_physCollision, 29, static_cast<const void*>(&neg), static_cast<const void*>(&half));
		if (!collide) {
			return nullptr;
		}
		return sdk::vcall<void*>(env, 8, collide, 0, static_cast<const void*>(&centre), static_cast<const void*>(&angles),
			static_cast<void*>(&params));
	} __except (EXCEPTION_EXECUTE_HANDLER) {
		return nullptr;
	}
}

void destroyBlockBox(void* env, void* object) {
	__try {
		sdk::vcall<void>(env, 10, object);
	} __except (EXCEPTION_EXECUTE_HANDLER) {
	}
}

extern Vector g_origin; // the player's feet (below, with the movement hook)

void wakeAround(const float* b) {
	if (!serverToolsReady()) {
		return;
	}
	float hx = (b[3] - b[0]) * 0.5f, hy = (b[4] - b[1]) * 0.5f, hz = (b[5] - b[2]) * 0.5f;
	Vector at{b[0] + hx, b[1] + hy, b[2] + hz};
	// Only near Steve: a block he broke. Far ones only left Minecraft's scan around him as he walked.
	if (dist(at, g_origin) > 30.0f * 40.0f) {
		return;
	}
	float radius = std::sqrt(hx * hx + hy * hy + hz * hz) + 32.0f;
	DamageInfo info = damageInfo(0.5f, 0 /* DMG_GENERIC */, true);
	info.position = info.reported = at;
	__try {
		sdk::vcall<void>(g_serverTools, 31, static_cast<const void*>(&info), static_cast<const void*>(&at), radius, 0, playerBase());
	} __except (EXCEPTION_EXECUTE_HANDLER) {
	}
}

void* activeEnvironment() {
	__try {
		return sdk::vcall<void*>(g_physics, 7, 0);
	} __except (EXCEPTION_EXECUTE_HANDLER) {
		return nullptr;
	}
}

void updateBlockPhysics() {
	if (!g_inLevel || !g_blockDirty || !checkedBuild("vphysics.dll")) {
		return;
	}
	if (!g_physics) {
		g_physics = engineInterface("vphysics.dll", "VPhysics031");
		g_physCollision = engineInterface("vphysics.dll", "VPhysicsCollision007");
		logf("blocks: vphysics %p collision %p", g_physics, g_physCollision);
	}
	void* worldEdict = edictAt(0);
	void* world = edictInUse(worldEdict) ? sdk::networkableBaseEntity(sdk::edictNetworkable(worldEdict)) : nullptr;
	if (!g_physics || !g_physCollision || !world) {
		return;
	}
	void* env = activeEnvironment();
	if (!env) {
		return;
	}
	if (env != g_blockEnv) {
		g_blockBoxes.clear();
		g_blockEnv = env;
	}
	g_blockDirty = false;

	size_t count = g_blockIncoming.size() / 6;
	std::vector<bool> kept(g_blockBoxes.size(), false);
	std::vector<BlockBox> next;
	next.reserve(count);
	int made = 0;
	for (size_t i = 0; i < count; i++) {
		const float* b = &g_blockIncoming[i * 6];
		bool found = false;
		for (size_t k = 0; k < g_blockBoxes.size(); k++) {
			if (!kept[k] && std::memcmp(g_blockBoxes[k].box, b, sizeof(float) * 6) == 0) {
				kept[k] = true;
				next.push_back(g_blockBoxes[k]);
				found = true;
				break;
			}
		}
		if (!found) {
			BlockBox box;
			std::memcpy(box.box, b, sizeof box.box);
			box.object = makeBlockBox(env, b, world);
			if (box.object) {
				next.push_back(box);
				made++;
			}
		}
	}
	int removed = 0;
	for (size_t k = 0; k < g_blockBoxes.size(); k++) {
		if (!kept[k]) {
			destroyBlockBox(env, g_blockBoxes[k].object);
			if (removed++ < 16) {
				wakeAround(g_blockBoxes[k].box);
			}
		}
	}
	g_blockBoxes.swap(next);
	static int logged = 0;
	if ((made || removed) && logged++ < 20) {
		logf("blocks: %zu of Steve's block boxes in Portal's physics (+%d -%d)", g_blockBoxes.size(), made, removed);
	}
}

void receiveBlast(const char* buf) {
	pcproto::McBlast b;
	std::memcpy(&b, buf, sizeof b);
	if (!serverToolsReady() || !(b.radius > 0.0f && b.radius < 4096.0f) || !(b.damage > 0.0f && b.damage < 10000.0f)) {
		return;
	}
	DamageInfo info = damageInfo(b.damage, DMG_BLAST);
	Vector at{b.origin.x, b.origin.y, b.origin.z};
	info.position = info.reported = at;
	__try {
		sdk::vcall<void>(g_serverTools, 31, static_cast<const void*>(&info), static_cast<const void*>(&at), b.radius, 0, playerBase());
	} __except (EXCEPTION_EXECUTE_HANDLER) {
		logf("blast: RadiusDamage faulted");
		return;
	}
	static int logged = 0;
	if (logged++ < 3) {
		logf("blast: Minecraft explosion at %.0f %.0f %.0f, radius %.0f, damage %.0f", at.x, at.y, at.z, b.radius, b.damage);
	}
}

void receiveHit(const char* buf) {
	pcproto::McHit h;
	std::memcpy(&h, buf, sizeof h);
	if (!serverToolsReady() || h.index < 2 || h.index >= 2048 || !(h.damage >= 0.0f && h.damage < 1000.0f)) {
		return;
	}
	void* e = edictAt(int(h.index));
	void* entity = edictInUse(e) ? sdk::networkableBaseEntity(sdk::edictNetworkable(e)) : nullptr;
	if (!entity) {
		return;
	}
	DamageInfo info = damageInfo(h.damage, DMG_CLUB);
	info.force = {h.force.x, h.force.y, h.force.z};
	info.position = info.reported = {h.point.x, h.point.y, h.point.z};
	__try {
		sdk::vcall<void>(g_serverTools, 28);
		sdk::vcall<void>(g_serverTools, 30, static_cast<const void*>(&info), entity);
		sdk::vcall<void>(g_serverTools, 29);
	} __except (EXCEPTION_EXECUTE_HANDLER) {
		logf("hit: multi-damage faulted");
		return;
	}
	static int logged = 0;
	if (logged++ < 3) {
		logf("hit: entity #%u (%s) at %.0f %.0f %.0f, force %.0f %.0f %.0f", h.index, sdk::networkableClassName(sdk::edictNetworkable(e)),
			h.point.x, h.point.y, h.point.z, h.force.x, h.force.y, h.force.z);
	}
}

void linkPoll() {
	static char buf[16384]; // PCS1 carries up to 512 block boxes
	for (int i = 0; i < 64; i++) {
		sockaddr_in from{}; // who sent it: a replay dump answers them
		int fromSize = sizeof from;
		int n = recvfrom(g_sock, buf, sizeof buf, 0, reinterpret_cast<sockaddr*>(&from), &fromSize);
		if (n <= 0) {
			return;
		}
		if (n == sizeof(pcproto::McState) && std::memcmp(buf, "PCM4", 4) == 0) {
			bool wasReady = mcReady();
			uint32_t oldAck = g_mc.teleportAck, oldEcho = g_mc.crossMatchedEcho;
			std::memcpy(&g_mc, buf, sizeof g_mc);
			g_mcTime = GetTickCount();
			if (g_mc.crossCount < g_matched) {
				// Minecraft started counting again (a fresh link, a respawn): nothing of its own is pending.
				g_matched = g_mc.crossCount;
				int kept = 0;
				for (int i = 0; i < g_crossingCount; i++) {
					if (g_crossings[i].seq) {
						g_crossings[kept++] = g_crossings[i];
					}
				}
				g_crossingCount = kept;
			}
			if (g_mc.crossMatchedEcho > g_matched) {
				g_matched = g_mc.crossMatchedEcho; // it dropped its own (the host placed it)
			}
			McAt mcNow{g_mc.teleportAck, g_mc.crossMatchedEcho};
			if (g_mc.teleportAck != oldAck || g_mc.crossMatchedEcho != oldEcho) {
				if (g_mc.teleportAck != oldAck) {
					dropAppliedShoves(g_mc.teleportAck);
				}
				// Minecraft just applied a move: its earlier steps are from before it, and playing them
				// back (one step behind) would put the player back where he was (outside the map after a
				// Portal restart; a step back along a fling after a portal, the snap). Portal crossings
				// carry those steps through (Minecraft carried itself the same way); any other move keeps
				// the timeline but moves them to where Minecraft is now, so playback starts from there.
				for (TickSample& t : g_ticks) {
					if (t.seq == 0 || (t.ack == mcNow.ack && t.matched == mcNow.matched)) {
						continue;
					}
					McAt at{t.ack, t.matched};
					Xf x;
					if (anyCrossingAfter(at) && crossingsBetween(at, mcNow, &x)) {
						Vector p = xfPoint(x, toVecP(t.pos));
						t.pos = {p.x, p.y, p.z};
					} else if (t.ack != mcNow.ack) {
						t.pos = g_mc.origin;
					}
					t.ack = mcNow.ack;
					t.matched = mcNow.matched;
				}
				int kept = 0;
				for (int i = 0; i < g_crossingCount; i++) {
					if (crossingAfter(g_crossings[i], mcNow)) {
						g_crossings[kept++] = g_crossings[i];
					}
				}
				g_crossingCount = kept;
			}
			if (g_mc.tickSeq != g_tickSeq) {
				// A physics step just ended in Minecraft. Keep it, and fold its arrival time into a
				// slow average of where Minecraft's 20 Hz timeline sits on our clock: arrival jitter
				// averages out instead of jerking the camera.
				g_tickSeq = g_mc.tickSeq;
				g_ticks[g_tickSeq % kTickHistory] = {g_tickSeq, g_mc.tickCurrent, g_mc.teleportAck, g_mc.crossMatchedEcho};
				double sample = nowSeconds() - g_tickSeq * 0.05;
				if (!g_haveOffset || sample - g_tickOffset > 0.2 || sample - g_tickOffset < -0.2) {
					g_tickOffset = sample; // first step, or Minecraft stalled: start over
					g_haveOffset = true;
				} else {
					g_tickOffset += (sample - g_tickOffset) * 0.05;
				}
			}
			if (!wasReady && mcReady()) {
				logf("Minecraft linked");
			}
		} else if (n == 8 && std::memcmp(buf, "PCT1", 4) == 0) {
			std::memcpy(&g_traceTicks, buf + 4, 4);
			logf("trace on for %d ticks", g_traceTicks);
		} else if ((n == 4 + 32 + 4 || n == 4 + 32 + 4 + 1) && std::memcmp(buf, "PCK1", 4) == 0 && devMode()) {
			std::memcpy(g_fakeKeys, buf + 4, 32);
			uint32_t ms;
			std::memcpy(&ms, buf + 36, 4);
			g_fakeMouse = n == 41 ? uint8_t(buf[40]) : 0;
			g_fakeUntil = GetTickCount() + ms;
		} else if (n == sizeof(pcproto::DevInput) && std::memcmp(buf, "PCK2", 4) == 0 && devMode()) {
			// Dev: one tick of a replay (tools/fake_mc.py --replay): the keys, the buttons and the view
			// together, so a tick never goes out with one of them a packet behind the others.
			pcproto::DevInput in;
			std::memcpy(&in, buf, sizeof in);
			std::memcpy(g_fakeKeys, in.keys, 32);
			g_fakeMouse = in.mouse;
			g_fakeUntil = GetTickCount() + in.holdMs;
			g_fakeFrame = in.frame;
			g_fakeWheel += in.wheel;
			if ((in.flags & pcproto::kDevInputView) && g_engineClient) {
				sdk::QAngle view{in.pitch, in.yaw, 0.0f};
				sdk::clientSetViewAngles(g_engineClient, &view);
			}
			if (in.flags & pcproto::kDevInputPortalButtons) {
				setPortalButtons(in.portalButtons);
			}
		} else if (n >= 4 && n < 4 + 64 && std::memcmp(buf, "PCQ1", 4) == 0 && devMode()) {
			char name[64] = {};
			std::memcpy(name, buf + 4, size_t(n - 4));
			dumpReplay(name, from);
		} else if (n == 4 + 8 && std::memcmp(buf, "PCV1", 4) == 0 && g_engineClient && devMode()) {
			// Dev: point the camera (pitch, yaw), e.g. to aim at a floor for a placement test.
			float pitchYaw[2];
			std::memcpy(pitchYaw, buf + 4, 8);
			sdk::QAngle view{pitchYaw[0], pitchYaw[1], 0.0f};
			sdk::clientSetViewAngles(g_engineClient, &view);
		} else if (n >= 8 && std::memcmp(buf, "PCS1", 4) == 0) {
			receiveSolids(buf, n);
		} else if (n == 8 && std::memcmp(buf, "PCZ1", 4) == 0) {
			uint32_t seq;
			std::memcpy(&seq, buf + 4, 4);
			if (seq != g_mcDeathSeq) {
				g_mcDeathSeq = seq;
				g_killPending = true;
			}
		} else if (n == sizeof(pcproto::McBlast) && std::memcmp(buf, "PCB1", 4) == 0) {
			receiveBlast(buf);
		} else if (n == sizeof(pcproto::McHit) && std::memcmp(buf, "PCI1", 4) == 0) {
			receiveHit(buf);
		} else if (n >= 8 && std::memcmp(buf, "PCL1", 4) == 0) {
			receiveLights(buf, n);
		} else if (n == 12 && std::memcmp(buf, "PCX2", 4) == 0) {
			std::memcpy(&g_lightExponent, buf + 4, 4); // dev: dynamic light brightness (fake_mc.py --lights)
			std::memcpy(&g_lightRadiusPerLevel, buf + 8, 4);
			logf("lights: exponent %.1f, %.0f units per level", g_lightExponent, g_lightRadiusPerLevel);
		} else if (n == 8 && std::memcmp(buf, "PCX1", 4) == 0) {
			float exposure; // dev: lighting exposure (tools/fake_mc.py --exposure)
			std::memcpy(&exposure, buf + 4, 4);
			worldrender::setExposure(exposure);
		} else if (n > 4 && std::memcmp(buf, "PCC1", 4) == 0 && g_engineServer && devMode()) {
			char cmd[260];
			int len = n - 4 < 250 ? n - 4 : 250;
			std::memcpy(cmd, buf + 4, len);
			cmd[len] = 0;
			cmd[strnlen(cmd, len)] = 0;
			logf("command: %s", cmd);
			std::strcat(cmd, "\n");
			sdk::serverCommand(g_engineServer, cmd);
		}
	}
}

// ---- input ---------------------------------------------------------------------------

// Windows virtual key -> SDL scancode (USB HID usage), for the keys Minecraft cares about.
struct KeyMap {
	int vk;
	int sdl;
};
const KeyMap kKeys[] = {
	{'A', 4}, {'B', 5}, {'C', 6}, {'D', 7}, {'E', 8}, {'F', 9}, {'G', 10}, {'H', 11}, {'I', 12}, {'J', 13},
	{'K', 14}, {'L', 15}, {'M', 16}, {'N', 17}, {'O', 18}, {'P', 19}, {'Q', 20}, {'R', 21}, {'S', 22}, {'T', 23},
	{'U', 24}, {'V', 25}, {'W', 26}, {'X', 27}, {'Y', 28}, {'Z', 29},
	{'1', 30}, {'2', 31}, {'3', 32}, {'4', 33}, {'5', 34}, {'6', 35}, {'7', 36}, {'8', 37}, {'9', 38}, {'0', 39},
	{VK_RETURN, 40}, {VK_ESCAPE, 41}, {VK_BACK, 42}, {VK_TAB, 43}, {VK_SPACE, 44}, {VK_OEM_2, 56},
	{VK_HOME, 74}, {VK_DELETE, 76}, {VK_END, 77}, {VK_RIGHT, 79}, {VK_LEFT, 80}, {VK_DOWN, 81}, {VK_UP, 82},
	{VK_F1, 58}, {VK_F2, 59}, {VK_F3, 60}, {VK_F5, 62},
	{VK_LCONTROL, 224}, {VK_LSHIFT, 225}, {VK_LMENU, 226}, {VK_RCONTROL, 228}, {VK_RSHIFT, 229},
};

bool gameHasFocus() {
	HWND fg = GetForegroundWindow();
	DWORD pid = 0;
	GetWindowThreadProcessId(fg, &pid);
	if (pid != GetCurrentProcessId()) {
		return false;
	}
	if (g_engineClient && (sdk::clientConsoleVisible(g_engineClient) || sdk::clientIsPaused(g_engineClient))) {
		return false;
	}
	return true;
}

void readInput(pcproto::HostState& s) {
	for (const KeyMap& k : kKeys) {
		if (GetAsyncKeyState(k.vk) & 0x8000) {
			s.keys[k.sdl >> 3] |= uint8_t(1u << (k.sdl & 7));
		}
	}
	if (GetAsyncKeyState(VK_LBUTTON) & 0x8000) s.mouse |= 1;
	if (GetAsyncKeyState(VK_RBUTTON) & 0x8000) s.mouse |= 2;
	if (GetAsyncKeyState(VK_MBUTTON) & 0x8000) s.mouse |= 4;
}

// ---- the player puppet ---------------------------------------------------------------

uint32_t g_teleportSeq = 0;
Vector g_teleportOrigin{}, g_teleportVelocity{};
uint8_t g_teleportKind = pcproto::kMoveTeleport;
Vector g_lastSetVelocity{}; // the velocity we wrote last server tick
bool g_needSync = true;   // first tick of a level: hand Portal's spawn point to Minecraft
bool g_haveSet = false;   // we wrote the player's origin last server tick
Vector g_lastSet{};       // ... to this
bool g_drivingNow = false;
Vector g_origin{}, g_velocity{}; // player state after the last server movement tick
bool g_haveOrigin = false;       // a movement tick has run this level: g_origin is real

// Small shoves (Portal's physics pushing the player off a prop or a physics brush, a lift carrying
// it) are soft handoffs: Minecraft is told where the player went like any other move, but we keep
// driving meanwhile, writing Minecraft's position plus the shoves it hasn't applied yet. Before,
// every shove stopped the driving until Minecraft answered (2-4 server ticks), and Portal's own
// movement took over for that long: the sticky snap at buttons, lifts and some walls.
struct Shove {
	uint32_t seq;
	Vector delta;
};
constexpr int kShoves = 32;
Shove g_shoves[kShoves];
int g_shoveCount = 0;
bool g_hardPending = false; // a real teleport (portal, level start) waits for Minecraft's answer

Vector pendingShoves() {
	Vector sum{};
	for (int i = 0; i < g_shoveCount; i++) {
		if (g_shoves[i].seq > g_mc.teleportAck) {
			sum = {sum.x + g_shoves[i].delta.x, sum.y + g_shoves[i].delta.y, sum.z + g_shoves[i].delta.z};
		}
	}
	return sum;
}

// Minecraft answered up to `ack`: forget the shoves it has applied.
void dropAppliedShoves(uint32_t ack) {
	int kept = 0;
	for (int i = 0; i < g_shoveCount; i++) {
		if (g_shoves[i].seq > ack) {
			g_shoves[kept++] = g_shoves[i];
		}
	}
	g_shoveCount = kept;
	if (ack == g_teleportSeq) {
		g_hardPending = false;
	}
}

// Driving, unless a host move Minecraft must take first is on its way (a portal crossing isn't one).
bool following() {
	return mcReady() && (g_mc.teleportAck == g_teleportSeq || !g_hardPending || g_mc.teleportAck >= g_opaqueSeq);
}

Vector toVec(const pcproto::Vec3& v) {
	return {v.x, v.y, v.z};
}

// Portal's physics sometimes rests the player a few units above Minecraft's floor (its hull
// skin on a prop, a lip Minecraft's collision is a hair under). Fighting that every tick bobs;
// handing it to Minecraft loops (Minecraft falls back to its own floor). So we remember the lift
// here and add it to what we write, until the player walks away from where it was learned.
float g_zLift = 0.0f;
Vector g_zLiftAt{};
float g_zLiftFloor = 0.0f; // Minecraft's z (feet, on the ground) when the lift was learned
float g_lastMcZ = 0.0f;    // Minecraft's z we last wrote, before the lift

// Where Minecraft's player is "now": one physics step behind Minecraft's smoothed timeline,
// between the two samples around that moment (the way Minecraft itself renders one step behind).
// Evenly spaced every host tick, unlike sampling whatever position a render frame reported.
const TickSample* tickAt(uint32_t seq) {
	const TickSample& t = g_ticks[seq % kTickHistory];
	return t.seq == seq && seq != 0 ? &t : nullptr;
}

// A step of Minecraft's, carried through the crossings Minecraft hadn't taken when it sent it.
uint32_t g_dbgTagAck = 0, g_dbgTagMatched = 0; // the last step played back, for the trace
bool g_dbgCarried = false;

Vector stepNow(const TickSample& t) {
	Xf x;
	McAt at{t.ack, t.matched};
	g_dbgTagAck = t.ack;
	g_dbgTagMatched = t.matched;
	g_dbgCarried = anyCrossingAfter(at) && crossingsBetween(at, {g_teleportSeq, g_matched}, &x);
	return g_dbgCarried ? xfPoint(x, toVec(t.pos)) : toVec(t.pos);
}

// The clock Minecraft's steps are played back on: one even step per server tick. Portal runs its
// ticks in bursts between rendered frames (two frames apart, then three), so reading the wall clock
// at each tick moved the player 2.3 units, then 2.9, then 2.3: a judder at walking speed, since the
// client spreads ticks evenly when it draws. This advances by the tick's own length and only
// leans gently on the wall clock, to stay in step with Minecraft's.
double g_playClock = 0.0;

void advancePlayClock() {
	static double last = 0.0, tick = 0.015;
	double now = nowSeconds();
	if (g_playClock == 0.0 || now - g_playClock > 0.25 || g_playClock - now > 0.25) {
		g_playClock = now; // the first tick, or after a stall (a load, a pause)
	} else {
		double step = now - last;
		if (step > 0.001 && step < 0.1) {
			tick += (step - tick) * 0.01; // the tick's real length, averaged over about a hundred
		}
		g_playClock += tick;
		g_playClock += (now - g_playClock) * 0.03;
	}
	last = now;
}

Vector interpolatedMinecraft(Vector* velocity) {
	Xf now = xfIdentity();
	McAt mcAt{g_mc.teleportAck, g_mc.crossMatchedEcho};
	bool carried = anyCrossingAfter(mcAt) && crossingsBetween(mcAt, {g_teleportSeq, g_matched}, &now);
	*velocity = carried ? xfDir(now, toVec(g_mc.velocity)) : toVec(g_mc.velocity);
	if (!g_haveOffset) {
		return carried ? xfPoint(now, toVec(g_mc.origin)) : toVec(g_mc.origin);
	}
	double ticks = ((g_playClock != 0.0 ? g_playClock : nowSeconds()) - g_tickOffset) / 0.05 - 1.0;
	if (ticks > double(g_tickSeq)) {
		ticks = double(g_tickSeq); // ahead of the newest step (it's late): hold it
	}
	uint32_t k = ticks < 1.0 ? 1u : uint32_t(ticks);
	const TickSample* a = tickAt(k);
	const TickSample* b = tickAt(k + 1);
	if (!a) {
		const TickSample* newest = tickAt(g_tickSeq);
		return newest ? stepNow(*newest) : carried ? xfPoint(now, toVec(g_mc.origin)) : toVec(g_mc.origin);
	}
	Vector pa = stepNow(*a);
	if (!b) {
		return pa;
	}
	Vector pb = stepNow(*b);
	if (dist(pa, pb) > 64.0f) {
		// A teleport between the two steps: don't smear it across the map, and don't read it as
		// speed either (the velocity stays Minecraft's own, set above). As speed, a teleport beside
		// a cube was 25,640 units a second in Portal's player for a tick.
		return pb;
	}
	*velocity = {(pb.x - pa.x) * 20.0f, (pb.y - pa.y) * 20.0f, (pb.z - pa.z) * 20.0f};
	float f = float(ticks - double(k));
	f = f < 0.0f ? 0.0f : f > 1.0f ? 1.0f : f;
	return {pa.x + (pb.x - pa.x) * f, pa.y + (pb.y - pa.y) * f, pa.z + (pb.z - pa.z) * f};
}

void applyMinecraft(uint8_t* mv) {
	Vector velocity{};
	Vector o = interpolatedMinecraft(&velocity);
	// The lift belongs to a floor height, not a spot: Portal rests the player the same amount above
	// a whole floor, so forgetting it every 24 units walked (as this used to) re-learnt it with a
	// one-tick dip over and over, which felt like snagging. Drop it when Steve stands at another
	// height (a step, a platform, the button) or has gone far; keep it through jumps.
	if (g_zLift != 0.0f) {
		float dx = o.x - g_zLiftAt.x, dy = o.y - g_zLiftAt.y;
		bool otherFloor = g_mc.onGround && std::fabs(o.z - g_zLiftFloor) > 1.0f;
		if (otherFloor || dx * dx + dy * dy > 1024.0f * 1024.0f) {
			g_zLift = 0.0f;
		}
	}
	g_lastMcZ = o.z;
	o.z += g_zLift;
	Vector shoved = pendingShoves();
	o = {o.x + shoved.x, o.y + shoved.y, o.z + shoved.z};
	*reinterpret_cast<Vector*>(mv + sdk::kMvAbsOrigin) = o;
	*reinterpret_cast<Vector*>(mv + sdk::kMvVelocity) = velocity;
}

void quietPortalMovement(uint8_t* mv) {
	// Minecraft decides jumping, crouching and walking. Portal only crouches when Minecraft sneaks,
	// which lowers Portal's camera the way sneaking lowers Steve's.
	int& buttons = *reinterpret_cast<int*>(mv + sdk::kMvButtons);
	buttons &= ~(sdk::IN_JUMP | sdk::IN_DUCK);
	if (g_mc.sneaking) {
		buttons |= sdk::IN_DUCK;
	}
	// Portal's gun only fires while Steve holds the Minecraft portal gun; otherwise clicks are
	// Minecraft's. With a Minecraft screen open (the inventory) every click is the screen's.
	if (!g_mc.holdingPortalGun || (g_mc.flags & pcproto::kMcScreen)) {
		buttons &= ~(sdk::IN_ATTACK | sdk::IN_ATTACK2);
	}
	*reinterpret_cast<float*>(mv + sdk::kMvForwardMove) = 0;
	*reinterpret_cast<float*>(mv + sdk::kMvSideMove) = 0;
	*reinterpret_cast<float*>(mv + sdk::kMvUpMove) = 0;
}

// Portal's gun fires only while Steve holds Minecraft's portal gun and no Minecraft screen is open:
// otherwise the click is Minecraft's (placing a block, lighting TNT, the inventory). The player's
// m_flNextAttack is the time before which none of its weapons may act (CBasePlayer::ItemPostFrame),
// and it's networked, so the client's prediction holds fire as well. Clearing the attack buttons in
// the move data (quietPortalMovement) isn't enough: the weapon reads the player's own buttons.
bool g_heldFire = false;

void updateGunGate() {
	uint8_t* base = playerFields();
	if (!base || g_pl.nextAttack < 0) {
		return;
	}
	bool hold = mcReady() && (!g_mc.holdingPortalGun || (g_mc.flags & pcproto::kMcScreen));
	float& nextAttack = *reinterpret_cast<float*>(base + g_pl.nextAttack);
	if (hold) {
		nextAttack = 1.0e9f;
		g_heldFire = true;
	} else if (g_heldFire) {
		nextAttack = 0.0f; // any time now
		g_heldFire = false;
	}
}

// Portal's gun firing, for Minecraft's gun to flash and animate with it (HostState.shots). A shot
// shows as the active weapon's next-attack times jumping ahead; which button was down says which
// colour. Read through the weapon's own send table, so nothing here depends on the build.
uint8_t g_shots = 0;
uint32_t g_gunEffect = 0xFFFFFFFFu; // the gun's m_EffectState (2: holding an object), for Minecraft's grab animations
uint8_t g_gunFizzles = 0;            // times an emancipation grid has fizzled the gun (HostState.gunEffect, second byte)

void watchGunShots(int buttons) {
	static void* weaponTable = nullptr;
	static int nextPrimary = -1, nextSecondary = -1, effectState = -1, effectsMax1 = -1, effectsMax2 = -1;
	static uint32_t lastWeapon = 0xFFFFFFFFu;
	static float lastP = 0.0f, lastS = 0.0f;
	uint8_t* base = playerFields();
	if (!base || g_pl.activeWeapon < 0) {
		return;
	}
	uint32_t handle = *reinterpret_cast<uint32_t*>(base + g_pl.activeWeapon);
	if (handle == 0xFFFFFFFFu) {
		lastWeapon = handle;
		return;
	}
	void* e = edictAt(int(handle & 0xFFF));
	if (!edictInUse(e)) {
		return;
	}
	void* networkable = sdk::edictNetworkable(e);
	auto* weapon = networkable ? static_cast<uint8_t*>(sdk::networkableBaseEntity(networkable)) : nullptr;
	auto* sc = networkable ? static_cast<sdk::ServerClass*>(sdk::networkableServerClass(networkable)) : nullptr;
	if (!weapon || !sc || !sc->table) {
		return;
	}
	if (sc->table != weaponTable) {
		weaponTable = sc->table;
		nextPrimary = findProp(sc->table, "m_flNextPrimaryAttack", 0);
		nextSecondary = findProp(sc->table, "m_flNextSecondaryAttack", 0);
		effectState = findProp(sc->table, "m_EffectState", 0);
		effectsMax1 = findProp(sc->table, "m_fEffectsMaxSize1", 0);
		effectsMax2 = findProp(sc->table, "m_fEffectsMaxSize2", 0);
		logf("weapon: m_fEffectsMaxSize1 %d m_fEffectsMaxSize2 %d", effectsMax1, effectsMax2);
		logf("weapon: %s m_flNextPrimaryAttack %d m_flNextSecondaryAttack %d m_EffectState %d", sc->name, nextPrimary, nextSecondary, effectState);
	}
	// The gun's fizzle: an emancipation grid, as it takes the player's portals, pulses the gun's
	// glow (m_fEffectsMaxSize1/2 jump from 4 to 50 and shrink back) and plays its fizzle animation.
	// Nothing else does: a portal a door or a moving wall closes goes without either.
	if (effectsMax1 >= 0 && effectsMax2 >= 0) {
		static float lastMax = 0.0f;
		float m1 = *reinterpret_cast<float*>(weapon + effectsMax1), m2 = *reinterpret_cast<float*>(weapon + effectsMax2);
		float most = m1 > m2 ? m1 : m2;
		if (most > 30.0f && lastMax <= 30.0f && handle == lastWeapon) {
			g_gunFizzles++;
			logf("gun fizzle %u (glow %.0f %.0f)", unsigned(g_gunFizzles), m1, m2);
		}
		lastMax = most;
	}
	uint32_t effectBefore = g_gunEffect;
	if (effectState >= 0) {
		uint32_t effect = *reinterpret_cast<uint32_t*>(weapon + effectState);
		static int effectLogs = 0;
		if (effect != g_gunEffect && effectLogs++ < 40) {
			logf("gun effect state %u -> %u", g_gunEffect, effect);
		}
		g_gunEffect = effect;
	}
	if (nextPrimary < 0 || nextSecondary < 0) {
		return;
	}
	float p = *reinterpret_cast<float*>(weapon + nextPrimary), s = *reinterpret_cast<float*>(weapon + nextSecondary);
	// Only with a fire button down and the gun's state unchanged this tick: letting go of an object
	// pushes the same timers on by half a second (so does drawing the gun), and every cube Steve put
	// down flashed the gun and turned its light blue.
	// (This tick's buttons or the last's: the weapon fires after the move this is called from, so the
	// jump is seen a tick later, when a short click may already be over.)
	static int lastButtons = 0;
	bool firing = ((buttons | lastButtons) & (sdk::IN_ATTACK | sdk::IN_ATTACK2)) != 0 && g_gunEffect == effectBefore && g_gunEffect != 2;
	int shotButtons = (buttons & (sdk::IN_ATTACK | sdk::IN_ATTACK2)) ? buttons : lastButtons;
	lastButtons = buttons;
	if (handle == lastWeapon && firing && (p > lastP + 0.05f || s > lastS + 0.05f)) {
		bool orange = (shotButtons & sdk::IN_ATTACK2) && !(shotButtons & sdk::IN_ATTACK);
		g_shots = uint8_t(((g_shots + 1) & 0x7F) | (orange ? 0x80 : 0));
	}
	lastWeapon = handle;
	lastP = p;
	lastS = s;
}

void logSolidNear(const Vector& at);
void logHostSolid(const Vector& at, void* playerEntity);
bool clampToProps(const Vector& from, Vector* to);

using ProcessMovementFn = void(__thiscall*)(void* self, void* player, void* mv);
ProcessMovementFn g_serverOriginal = nullptr;
ProcessMovementFn g_clientOriginal = nullptr;
bool g_checkedLayout = false;

void __fastcall serverProcessMovement(void* self, void* /*edx*/, void* player, void* mvRaw) {
	auto* mv = static_cast<uint8_t*>(mvRaw);
	Vector& origin = *reinterpret_cast<Vector*>(mv + sdk::kMvAbsOrigin);
	updateGunGate();
	advancePlayClock();
	watchGunShots(*reinterpret_cast<int*>(mv + sdk::kMvButtons));

	if (!g_checkedLayout && g_playerInfoMgr) {
		g_checkedLayout = true;
		void* info = sdk::playerInfo(g_playerInfoMgr, edictAt(1));
		if (info) {
			Vector truth = sdk::playerAbsOrigin(info);
			logf("CMoveData check: mv origin (%.1f %.1f %.1f) vs player (%.1f %.1f %.1f) -> %s", origin.x, origin.y, origin.z,
				truth.x, truth.y, truth.z, dist(origin, truth) < 1.0f ? "layout OK" : "LAYOUT MISMATCH");
		}
	}

	static int pushLogs = 0;
	static DWORD lastPushLog = 0;
	if (g_haveSet && pushLogs < 300 && GetTickCount() - lastPushLog > 250) {
		float d = dist(origin, g_lastSet);
		if (d > 0.1f && d <= 24.0f) {
			pushLogs++;
			lastPushLog = GetTickCount();
			logf("push: Portal moved the player %.2f units between ticks (%.2f %.2f %.2f) -> (%.2f %.2f %.2f)", d, g_lastSet.x, g_lastSet.y,
				g_lastSet.z, origin.x, origin.y, origin.z);
			if (pushLogs == 1 || d > 0.5f) {
				logSolidNear(origin); // who did it (a handed-over shove, or the first nudge)
				logHostSolid(g_lastSet, player);
			}
		}
	}

	if (g_haveSet && !g_riding) {
		float dx = origin.x - g_lastSet.x, dy = origin.y - g_lastSet.y, dz = origin.z - g_lastSet.z;
		if (dx * dx + dy * dy < 0.25f && dz > 0.1f && g_zLift + dz <= 4.0f) {
			g_zLift += dz; // a small straight-up nudge: keep it (see applyMinecraft)
			g_zLiftAt = origin;
			g_zLiftFloor = g_lastMcZ;
			g_lastSet = origin;
		} else if (dx * dx + dy * dy < 0.25f && dz < -0.1f && dz > -0.5f && g_zLift > 0.0f) {
			g_zLift = g_zLift + dz > 0.0f ? g_zLift + dz : 0.0f; // nudged back down: we lifted too much
			g_lastSet = origin;
		}
	}

	// Something other than us moved the player since last tick: a portal, a trigger_teleport, a
	// fresh level, or Portal's physics shoving the player out of a prop. That move is Portal's;
	// give it to Minecraft and wait for the ack, rather than fighting it every tick (which bobs).
	// Portal changed the player's velocity since last tick (a trigger_push air current, an
	// env_physexplosion): an impulse, handed to Minecraft with the new velocity. We overwrite the
	// velocity every tick, so without this every push was lost.
	Vector& mvVelocity = *reinterpret_cast<Vector*>(mv + sdk::kMvVelocity);
	// There are no impulses. A change of Portal's velocity for its player with no change of place
	// used to be handed to Minecraft as one (for trigger_push air currents and explosions). But a
	// trigger_push sets the player's base velocity, which Portal's movement adds and takes out again
	// within the tick, so it never shows here; no explosion in the game pushes players; and what did
	// show was noise that this then acted on: a lift's own speed (Steve locked in place for the
	// ride), Portal working its player clear of a portal's rim (Steve rising back up through a
	// ceiling portal), the physics' kick on a cube (thrown 270 units up), a moving platform's
	// velocity under a jump. Each had its own exception here; the rule is gone instead.
	const bool impulse = false;

	// Portal just teleported its player through a portal Steve has already gone through in Minecraft
	// (PlayerCrossings): that's the match, nothing to hand over. From here on we carry Minecraft's
	// steps (unfolded until it hears of the match) through Portal's own crossing.
	bool matchedNow = false;
	static uint32_t tickNow = 0, lastMatchTick = 0;
	static uint8_t lastMatchIn = 0xFF; // the portal Steve went into on the newest matched crossing
	tickNow++;
	static int pendingTicks = 0; // ticks a crossing of Steve's has gone unmatched
	pendingTicks = g_mc.crossCount > g_matched ? pendingTicks + 1 : 0;
	if (!g_needSync && g_haveSet && !g_riding && g_mc.crossCount > g_matched && g_crossingCount < kCrossings &&
		dist(origin, g_lastSet) > 24.0f) {
		Xf x;
		if (portalCrossing(g_lastSet, origin, &x)) {
			// The crossing as Minecraft made it: the plain carry. (Portal's own result has its fix-up
			// in it: its player pushed 16 units out of and up a wall portal when it came out with its
			// head over the opening. Minecraft fits Steve its own way, and what it sends from here on,
			// carried plainly, is where he is; with Portal's fix-up in the carry the player jumped by
			// the difference a few ticks after every such crossing, when Minecraft heard of the match.)
			uint8_t in = g_mc.crossPortal[(g_matched + 1) & 3];
			Xf plain;
			if (in < 2 && geometricCrossing(in, steveHalfHeight(), &plain)) {
				x = plain;
			}
			g_matched++;
			g_crossings[g_crossingCount++] = {0, g_matched, x};
			matchedNow = true;
			pendingTicks = 0;
			lastMatchTick = tickNow;
			g_crossedAt = GetTickCount();
			lastMatchIn = in;
			static int matchLogs = 0;
			if (matchLogs++ < 60) {
				logf("Portal matched Steve's crossing %u: (%.1f %.1f %.1f) -> (%.1f %.1f %.1f)", g_matched, g_lastSet.x, g_lastSet.y, g_lastSet.z, origin.x,
					origin.y, origin.z);
			}
		}
	}
	// Steve went through in Minecraft, our playback took Portal's player in behind the portal, and
	// Portal didn't teleport it (its own rules said no): make the crossing ourselves, the way
	// Minecraft did, so the two don't part. Portal's camera jumps this once instead of turning smoothly.
	if (!matchedNow && !g_needSync && g_haveSet && !g_riding && g_mc.crossCount > g_matched && g_crossingCount < kCrossings &&
		dist(origin, g_lastSet) <= 0.5f) {
		uint8_t in = g_mc.crossPortal[(g_matched + 1) & 3];
		const float kHalf = steveHalfHeight();
		if (in < 2) {
			Vector f, r, u;
			angleBasis(g_portalsNow[in].angles, &f, &r, &u);
			const pcproto::Vec3& o = g_portalsNow[in].origin;
			Vector c{g_lastSet.x - o.x, g_lastSet.y - o.y, g_lastSet.z + kHalf - o.z};
			float behind = -(c.x * f.x + c.y * f.y + c.z * f.z);
			static int behindTicks = 0, justBehindTicks = 0;
			bool nearPortal = dist(g_lastSet, toVecP(o)) < 192.0f;
			behindTicks = behind > 4.0f && nearPortal ? behindTicks + 1 : 0;
			justBehindTicks = behind > 0.0f && nearPortal ? justBehindTicks + 1 : 0;
			Xf x;
			// Two ticks well behind with no teleport: Portal has had its chance (it teleports the tick
			// after the centre crosses at the latest). Or six ticks behind by anything at all: Steve
			// stepped (or crouch-walked) in so slowly that his centre only just crossed, where Portal
			// never takes its own player through, and it stood under the floor portal for good.
			// Or eight ticks with a crossing of Steve's unmatched, wherever the player is: a portal
			// re-placed while he fell through it left Portal unable to follow, Minecraft went on
			// looping on its own, and the player (played back as if before all those crossings) fell
			// out of the map.
			// Or at once, behind a portal that has only just opened or moved: Portal teleports only
			// what the portal "owns", and it takes ownership of its player as he first touches the space
			// in front of it with his centre in front of its plane. One that opens under his feet (or is
			// re-placed as he falls through) never owns him and never takes him; waiting the ticks above
			// for it, he hung in the opening and the two sides came apart (one such start in five bobbed).
			bool justOpened = behind > 0.0f && nearPortal && g_portalsChangedAt != 0 && GetTickCount() - g_portalsChangedAt < 600;
			if ((behindTicks >= 2 || justBehindTicks >= 6 || pendingTicks >= 8 || justOpened) && geometricCrossing(in, kHalf, &x)) {
				behindTicks = justBehindTicks = 0;
				pendingTicks = pendingTicks >= 8 ? 7 : 0; // still behind after this one: the next goes next tick
				g_matched++;
				g_crossings[g_crossingCount++] = {0, g_matched, x};
				lastMatchTick = tickNow;
			g_crossedAt = GetTickCount();
				lastMatchIn = in;
				static int forceLogs = 0;
				if (forceLogs++ < 30) {
					logf("Portal didn't take Steve's crossing %u (%.0f units behind portal %u): made it ourselves", g_matched, behind, in);
				}
			}
		}
	}

	// Portal took its player straight back in through the portal Steve has just come out of, and
	// Minecraft hasn't: after a slow crossing he stands with his centre a unit in front of it, and
	// Portal's own test (its ducked player's centre is lower than Steve's) flips. Not handed over:
	// the player goes back to where Minecraft has Steve, below. Handed over, he ping-ponged.
	bool bounced = false;
	if (!matchedNow && !g_needSync && g_haveSet && !g_riding && g_mc.crossCount == g_matched && lastMatchIn < 2 && tickNow - lastMatchTick < 20 &&
		dist(origin, g_lastSet) > 24.0f) {
		Xf x;
		const pcproto::Vec3& out = g_portalsNow[1 - lastMatchIn].origin;
		const pcproto::Vec3& in = g_portalsNow[lastMatchIn].origin;
		if (portalCrossing(g_lastSet, origin, &x) && dist(g_lastSet, toVecP(out)) < dist(g_lastSet, toVecP(in))) {
			bounced = true;
			static int bounceLogs = 0;
			if (bounceLogs++ < 30) {
				logf("Portal took its player back through portal %u right after Steve's crossing %u: not handed over", 1 - lastMatchIn, g_matched);
			}
		}
	}

	// (Riding a lift, the lift moving the player is the point: it's not handed over, see updateRiding.)
	// Standing on a cube, Portal drags its player back over it as he walks (its physics has him
	// stuck to the cube under his feet): handed to Minecraft, every step Steve took on a cube was
	// undone and he couldn't walk off one. Small moves of Portal's there are not handed over.
	bool draggedOnProp = g_onLooseProp && g_haveSet && !g_needSync && !impulse && dist(origin, g_lastSet) < 12.0f;
	// Nor is a small move straight up or down: Portal settling its player onto its own floor. On a
	// slope the two floors differ at every step (Portal's is the smooth ramp, Minecraft's a flight of
	// two-unit columns), each settling was handed over as a shove, pushed Steve into the next column
	// or off it, and walking over the wedges of a floor button shook hard.
	if (g_haveSet && !g_needSync && !impulse && std::fabs(origin.x - g_lastSet.x) < 0.5f && std::fabs(origin.y - g_lastSet.y) < 0.5f &&
		std::fabs(origin.z - g_lastSet.z) < 3.0f) {
		draggedOnProp = true;
	}
	if (carriedLately() && g_haveSet && !g_needSync && !impulse && dist(origin, g_lastSet) < 12.0f) {
		draggedOnProp = true; // carried by a moving platform (see g_carriedAt)
	}
	if (!matchedNow && !bounced && !draggedOnProp && (g_needSync || impulse || (g_haveSet && !g_riding && dist(origin, g_lastSet) > 0.5f))) {
		g_teleportSeq++;
		g_teleportOrigin = origin;
		g_teleportVelocity = mvVelocity;
		bool hard = g_needSync || !g_haveSet || dist(origin, g_lastSet) > 24.0f;
		g_teleportKind = hard ? pcproto::kMoveTeleport : impulse ? pcproto::kMoveImpulse : pcproto::kMoveShove;
		if (hard) {
			Xf x;
			if (!g_needSync && g_haveSet && g_crossingCount < kCrossings && portalCrossing(g_lastSet, origin, &x)) {
				g_crossings[g_crossingCount++] = {g_teleportSeq, 0, x};
			} else {
				g_opaqueSeq = g_teleportSeq;
			}
			g_hardPending = true;
			g_shoveCount = 0;
		} else {
			if (g_shoveCount == kShoves) { // Minecraft fell far behind: drop the oldest
				std::memmove(g_shoves, g_shoves + 1, sizeof(Shove) * (kShoves - 1));
				g_shoveCount--;
			}
			g_shoves[g_shoveCount++] = {g_teleportSeq, {origin.x - g_lastSet.x, origin.y - g_lastSet.y, origin.z - g_lastSet.z}};
		}
		if (hard) {
			logf("handing teleport %u to Minecraft: (%.1f %.1f %.1f)%s", g_teleportSeq, origin.x, origin.y, origin.z,
				g_needSync ? " [level start]" : g_opaqueSeq == g_teleportSeq ? "" : " [portal crossing, carried]");
		}
		g_needSync = false;
	}

	bool wasDriving = g_drivingNow;
	g_drivingNow = following() && !g_scripted;
	if (g_drivingNow) {
		quietPortalMovement(mv);
	}
	float zIn = origin.z;
	g_serverOriginal(self, player, mvRaw);
	float zPortal = origin.z;
	if (g_drivingNow) {
		Vector portalWent = origin;
		applyMinecraft(mv);
		// Portal's own answer to "can the player go there": its hull swept from where it was to where
		// Minecraft has Steve, against Portal's loose props where they are this tick. Minecraft stops
		// Steve at its copy of a cube, a tick old; placed a hair inside the real one, the player was
		// thrown clear by Portal's physics (up onto the cube, or shaking against it).
		if (!g_riding && g_haveSet && dist(g_lastSet, origin) < 24.0f) {
			Vector to = origin;
			if (clampToProps(g_lastSet, &to)) {
				// ... and Minecraft is told, as a shove back to where the player stopped: left to
				// itself Steve walked on (through where Minecraft thought the cube wasn't) and the two
				// stood 19 units apart, the player stuck behind the cube.
				// Only when they have really parted (8 units): told of every small stop, Minecraft was
				// shoved back a unit a tick all the while Steve pushed a cube, and he shuddered against it.
				if (dist(to, origin) > 8.0f) {
					g_teleportSeq++;
					g_teleportOrigin = to;
					g_teleportVelocity = *reinterpret_cast<Vector*>(mv + sdk::kMvVelocity);
					g_teleportKind = pcproto::kMoveShove;
					if (g_shoveCount == kShoves) {
						std::memmove(g_shoves, g_shoves + 1, sizeof(Shove) * (kShoves - 1));
						g_shoveCount--;
					}
					g_shoves[g_shoveCount++] = {g_teleportSeq, {to.x - origin.x, to.y - origin.y, to.z - origin.z}};
				}
				origin = to;
			}
		}
		if (g_riding && !(g_mc.flags & pcproto::kMcRideJump)) { // (not through a jump of Steve's: that height is his)
			origin.z = zPortal; // the lift carries the player; Minecraft follows (kHostRiding)
			g_zLift = 0.0f;
		}
		// Just took over again after a teleport: Portal moved the player by itself while Minecraft
		// caught up. Hand that difference over as a shove, so we write where Portal had it, not a
		// step back, and Minecraft is told.
		if (!wasDriving && g_haveOrigin && dist(portalWent, origin) > 0.5f && dist(portalWent, origin) < 64.0f) {
			g_teleportSeq++;
			g_teleportOrigin = portalWent;
			g_teleportVelocity = *reinterpret_cast<Vector*>(mv + sdk::kMvVelocity);
			g_teleportKind = pcproto::kMoveShove;
			if (g_shoveCount < kShoves) {
				g_shoves[g_shoveCount++] = {g_teleportSeq, {portalWent.x - origin.x, portalWent.y - origin.y, portalWent.z - origin.z}};
			}
			origin = portalWent;
		}
		g_lastSet = origin;
		g_lastSetVelocity = *reinterpret_cast<Vector*>(mv + sdk::kMvVelocity);
		g_haveSet = true;
	} else {
		g_haveSet = false;
	}
	if (g_needSync) {
		g_zLift = 0.0f;
	}
	if (g_traceTicks > 0 && g_log) {
		g_traceTicks--;
		fprintf(g_log, "S %lu z in %.3f portal %.3f out %.3f | mc z %.3f vz %.1f ground %d lift %.2f | xy (%.2f %.2f)"
			" | drive %d seq %u ack %u matched %u mc count %u echo %u | crossings %d step tag %u/%u carried %d",
			GetTickCount(), zIn, zPortal, origin.z, g_mc.origin.z, g_mc.velocity.z, g_mc.onGround, g_zLift, origin.x, origin.y, g_drivingNow,
			g_teleportSeq, g_mc.teleportAck, g_matched, g_mc.crossCount, g_mc.crossMatchedEcho, g_crossingCount, g_dbgTagAck, g_dbgTagMatched,
			g_dbgCarried);
		fputc(10, g_log); // newline
	}
	g_origin = origin;
	g_velocity = *reinterpret_cast<Vector*>(mv + sdk::kMvVelocity);
	g_haveOrigin = true;
}

void __fastcall clientProcessMovement(void* self, void* /*edx*/, void* player, void* mvRaw) {
	// Client prediction runs the same movement; give it the same answer so it never disagrees.
	auto* mv = static_cast<uint8_t*>(mvRaw);
	bool drive = following();
	if (drive) {
		quietPortalMovement(mv);
	}
	float zIn = reinterpret_cast<Vector*>(mv + sdk::kMvAbsOrigin)->z;
	g_clientOriginal(self, player, mvRaw);
	float zPortal = reinterpret_cast<Vector*>(mv + sdk::kMvAbsOrigin)->z;
	if (drive) {
		applyMinecraft(mv);
		Vector& predicted = *reinterpret_cast<Vector*>(mv + sdk::kMvAbsOrigin);
		if (!g_riding && g_haveSet && dist(g_lastSet, predicted) < 24.0f) {
			Vector to = predicted; // the same check as the server's, so the two draw the player in one place
			if (clampToProps(g_lastSet, &to)) {
				predicted = to;
			}
		}
		if (g_riding && !(g_mc.flags & pcproto::kMcRideJump)) {
			reinterpret_cast<Vector*>(mv + sdk::kMvAbsOrigin)->z = zPortal;
		}
	}
	if (g_traceTicks > 0 && g_log) {
		fprintf(g_log, "C %lu z in %.3f portal %.3f out %.3f", GetTickCount(), zIn, zPortal, reinterpret_cast<Vector*>(mv + sdk::kMvAbsOrigin)->z);
		fputc(10, g_log); // newline
	}
}

bool hookSlot(void* object, int slot, void* replacement, void** original) {
	return hooks::patch(*static_cast<void***>(object), slot, replacement, original);
}

// Ray_t (SP2013): start, delta, startOffset, extents (VectorAligned each), isRay, isSwept.
struct alignas(16) TraceRayArgs {
	float start[4], delta[4], startOffset[4], extents[4];
	bool isRay, isSwept;
};

// ---- Portal's traces see Steve's blocks ------------------------------------------------------
// Portal's gun, its turrets' line of sight and its bullets all trace through the engine
// (IEngineTrace::TraceRay, slot 4 of EngineTraceServer003), which knows nothing of Minecraft's
// blocks: a portal shot at a wall Steve built landed on the chamber wall behind it, and turrets saw
// and shot him through it. After Portal's own trace, a line trace (never a hull: Portal's player
// movement stays as it was) is clipped against Steve's block boxes (PCS1, the same ones Portal's
// physics gets); a nearer block is reported as world geometry where no portal can go
// (SURF_NOPORTAL), so a portal shot fizzles on it, turrets lose sight and bullets stop.
//
// trace_t (CGameTrace, SP2013): startpos 0, endpos 12, plane 24 (normal, dist 36, type 40, signbits
// 41), fraction 44, contents 48, dispFlags 52, allsolid 54, startsolid 55, fractionleftsolid 56,
// surface 60 (name, surfaceProps 64, flags 66), hitgroup 68, physicsbone 72, m_pEnt 76, hitbox 80.
constexpr unsigned kContentsSolid = 0x1;
constexpr unsigned short kSurfNoPortal = 0x0020;
using TraceRayFn = void(__thiscall*)(void* self, const TraceRayArgs* ray, unsigned mask, void* filter, uint8_t* trace);
TraceRayFn g_traceRayOriginal = nullptr;
float g_blockBounds[6] = {}; // all of Steve's boxes together, for a quick miss
bool g_haveBlockBounds = false;

void updateBlockBounds() {
	size_t n = g_blockIncoming.size() / 6;
	g_haveBlockBounds = n > 0;
	for (size_t i = 0; i < n; i++) {
		const float* b = &g_blockIncoming[i * 6];
		for (int k = 0; k < 3; k++) {
			g_blockBounds[k] = i == 0 || b[k] < g_blockBounds[k] ? b[k] : g_blockBounds[k];
			g_blockBounds[k + 3] = i == 0 || b[k + 3] > g_blockBounds[k + 3] ? b[k + 3] : g_blockBounds[k + 3];
		}
	}
}

void __fastcall hkTraceRay(void* self, void* /*edx*/, const TraceRayArgs* ray, unsigned mask, void* filter, uint8_t* trace) {
	g_traceRayOriginal(self, ray, mask, filter, trace);
	if (!ray->isRay || !(mask & kContentsSolid) || !g_haveBlockBounds || !g_inLevel) {
		return;
	}
	float& fraction = *reinterpret_cast<float*>(trace + 44);
	if (!(fraction > 0.0f) || *reinterpret_cast<bool*>(trace + 55)) {
		return; // it started in something solid already
	}
	const float* start = ray->start;
	const float* delta = ray->delta;
	float t, sign;
	int axis;
	if (!rayEnters(start, delta, g_blockBounds, fraction, &t, &axis, &sign) && !(start[0] > g_blockBounds[0] && start[0] < g_blockBounds[3] &&
			start[1] > g_blockBounds[1] && start[1] < g_blockBounds[4] && start[2] > g_blockBounds[2] && start[2] < g_blockBounds[5])) {
		return; // nowhere near any of Steve's blocks
	}
	float best = fraction;
	int bestAxis = -1;
	float bestSign = 0.0f;
	size_t n = g_blockIncoming.size() / 6;
	for (size_t i = 0; i < n; i++) {
		if (rayEnters(start, delta, &g_blockIncoming[i * 6], best, &t, &axis, &sign)) {
			best = t;
			bestAxis = axis;
			bestSign = sign;
		}
	}
	if (bestAxis < 0) {
		return;
	}
	void* worldEdict = edictAt(0);
	void* world = edictInUse(worldEdict) ? sdk::networkableBaseEntity(sdk::edictNetworkable(worldEdict)) : nullptr;
	if (!world) {
		return;
	}
	auto* end = reinterpret_cast<float*>(trace + 12);
	for (int k = 0; k < 3; k++) {
		end[k] = start[k] + delta[k] * best;
	}
	auto* normal = reinterpret_cast<float*>(trace + 24);
	normal[0] = normal[1] = normal[2] = 0.0f;
	normal[bestAxis] = bestSign;
	*reinterpret_cast<float*>(trace + 36) = bestSign * end[bestAxis]; // plane dist: normal . point
	trace[40] = uint8_t(bestAxis); // PLANE_X/Y/Z
	trace[41] = uint8_t(bestSign < 0.0f ? 1 << bestAxis : 0);
	fraction = best;
	*reinterpret_cast<int*>(trace + 48) = int(kContentsSolid);
	*reinterpret_cast<unsigned short*>(trace + 52) = 0;
	trace[54] = 0; // allsolid
	*reinterpret_cast<const char**>(trace + 60) = "PORTALCRAFT/BLOCK";
	*reinterpret_cast<short*>(trace + 64) = 0; // surfaceProps: default
	*reinterpret_cast<unsigned short*>(trace + 66) = kSurfNoPortal;
	*reinterpret_cast<int*>(trace + 68) = 0; // hitgroup
	*reinterpret_cast<short*>(trace + 72) = 0;
	*reinterpret_cast<void**>(trace + 76) = world;
	*reinterpret_cast<int*>(trace + 80) = 0; // hitbox (for the world: no static prop)
}

void hookServerTraces() {
	if (g_traceRayOriginal || !g_engineFactory || !checkedBuild("engine.dll")) {
		return;
	}
	void* trace = g_engineFactory("EngineTraceServer003", nullptr);
	if (trace && hookSlot(trace, 4, reinterpret_cast<void*>(&hkTraceRay), reinterpret_cast<void**>(&g_traceRayOriginal))) {
		logf("hooked EngineTraceServer003::TraceRay: Portal's traces see Steve's blocks");
	}
}

void hookClientMovement() {
	if (g_clientOriginal) {
		return;
	}
	void* gm = engineInterface("client.dll", "GameMovement001");
	if (gm && hookSlot(gm, sdk::kProcessMovementSlot, reinterpret_cast<void*>(&clientProcessMovement),
				  reinterpret_cast<void**>(&g_clientOriginal))) {
		logf("hooked client GameMovement001");
	}
}

// ---- portals -------------------------------------------------------------------------

int g_portalEdicts[16];
int g_portalCount = 0;
int g_scanCountdown = 0;

void scanPortals() {
	g_portalCount = 0;
	for (int i = 1; i < 2048 && g_portalCount < 16; i++) {
		void* e = edictAt(i);
		if (!edictInUse(e)) {
			continue;
		}
		void* n = sdk::edictNetworkable(e);
		const char* cls = sdk::networkableClassName(n);
		if (cls && std::strcmp(cls, "prop_portal") == 0) {
			if (!g_po.ready) {
				resolvePortalOffsets(n);
			}
			g_portalEdicts[g_portalCount++] = i;
		}
	}
}

void fillPortals(pcproto::HostState& s) {
	if (--g_scanCountdown <= 0) {
		g_scanCountdown = 15;
		scanPortals();
	}
	if (!g_po.ready || g_po.origin < 0 || g_po.angles < 0) {
		return;
	}
	float best[2] = {1e30f, 1e30f};
	for (int k = 0; k < g_portalCount; k++) {
		void* e = edictAt(g_portalEdicts[k]);
		if (!edictInUse(e)) {
			continue;
		}
		auto* ent = static_cast<uint8_t*>(sdk::networkableBaseEntity(sdk::edictNetworkable(e)));
		if (!ent) {
			continue;
		}
		bool active = g_po.activated >= 0 && ent[g_po.activated];
		bool second = g_po.isPortal2 >= 0 && ent[g_po.isPortal2];
		uint32_t linkHandle = g_po.linked >= 0 ? *reinterpret_cast<uint32_t*>(ent + g_po.linked) : 0xFFFFFFFFu;
		Vector o = *reinterpret_cast<Vector*>(ent + g_po.origin);
		Vector a = *reinterpret_cast<Vector*>(ent + g_po.angles);
		int slot = second ? 1 : 0;
		// Several pairs on a map: keep the active one nearest the player for each colour.
		float d = dist(o, g_origin) - (active ? 1e6f : 0.0f);
		if (d >= best[slot]) {
			continue;
		}
		best[slot] = d;
		pcproto::HostPortal& p = s.portals[slot];
		p.flags = pcproto::kPortalExists | (active ? pcproto::kPortalActive : 0) | (second ? pcproto::kPortalSecond : 0) |
			(active && linkHandle != 0xFFFFFFFFu ? pcproto::kPortalLinked : 0);
		p.origin = {o.x, o.y, o.z};
		p.angles = {a.x, a.y, a.z};
	}
}

// ---- solid entities (doors, buttons, lifts, cubes) -------------------------------------

void* g_modelInfo = nullptr; // VModelInfoServer00x: 1 GetModel(int), 3 GetModelName(const model_t*)

// ICollideable (SP 2013): 3 OBBMins, 4 OBBMaxs, 9 GetCollisionModel, 10 GetCollisionOrigin,
// 11 GetCollisionAngles, 13 GetSolid, 14 GetSolidFlags, 16 GetCollisionGroup. Checked on the
// player once per level before anything else uses them.
enum { kColMins = 3, kColMaxs = 4, kColModel = 9, kColOrigin = 10, kColAngles = 11, kColSolid = 13, kColSolidFlags = 14, kColGroup = 16 };
constexpr int FSOLID_NOT_SOLID = 0x4, FSOLID_TRIGGER = 0x8;
int g_colState = 0; // 0 unchecked, 1 verified, -1 mismatch (entity streaming off)

void* collideableOf(void* edict) {
	void* unknown = sdk::edictUnknown(edict);
	return unknown ? sdk::vcall<void*>(unknown, 3) : nullptr; // IServerUnknown::GetCollideable
}

bool guardedColRead(void* col, sdk::Vector* origin, int* solid);

bool collideableOrigin(int index, Vector* out) {
	void* e = edictAt(index);
	void* col = edictInUse(e) ? collideableOf(e) : nullptr;
	int solid;
	return col && guardedColRead(col, out, &solid);
}

bool guardedColRead(void* col, sdk::Vector* origin, int* solid) {
	__try {
		*origin = *sdk::vcall<const sdk::Vector*>(col, kColOrigin);
		*solid = sdk::vcall<int>(col, kColSolid);
		return true;
	} __except (EXCEPTION_EXECUTE_HANDLER) {
		return false;
	}
}

void checkCollideableLayout() {
	if (g_colState != 0 || !g_playerInfoMgr || !g_edicts) {
		return;
	}
	void* info = sdk::playerInfo(g_playerInfoMgr, edictAt(1));
	void* col = edictInUse(edictAt(1)) ? collideableOf(edictAt(1)) : nullptr;
	if (!info || !col) {
		return; // the player isn't spawned yet: try again next frame
	}
	sdk::Vector origin{};
	int solid = -1;
	if (!guardedColRead(col, &origin, &solid)) {
		g_colState = -1;
		logf("ICollideable check: reading the player's collideable faulted -> entity collision off");
	} else {
		Vector truth = sdk::playerAbsOrigin(info);
		g_colState = dist(origin, truth) < 1.0f && solid == 2 ? 1 : -1;
		logf("ICollideable check: origin (%.1f %.1f %.1f) vs player (%.1f %.1f %.1f), solid %d -> %s", origin.x, origin.y, origin.z,
			truth.x, truth.y, truth.z, solid, g_colState == 1 ? "layout OK" : "LAYOUT MISMATCH, entity collision off");
	}
}

int g_entityEdicts[512];
int g_entityCount = 0;
int g_entityScanCountdown = 0;
uint32_t g_entitySeq = 0;
int g_entitySendCountdown = 0;
pcproto::Vec3 g_lastEntityOrigin[2048];

// Not Portal's shadow clones (physicsshadowclone): for every loose physics thing near a portal (a
// cube, a turret, the radio, its own player) Portal keeps a solid copy mirrored through the portal
// pair, usually in or behind the other portal's wall, and lets it collide only with what is in
// that portal's hole. Streamed to Minecraft a cube's clone was a second cube, standing in the
// mouth of the exit; the player's stopped Steve coming out of it.
// A loose physics thing the player can shove or carry: by class, not by model (the companion
// cube, the radio, a turret, GLaDOS's cores, the office chairs are all different models), and not
// one fixed to something else (a floor button has a physics prop inside it, parented to the button).
bool looseEntity(void* networkable, const char* cls) {
	if (!cls || (std::strncmp(cls, "prop_physics", 12) != 0 && std::strcmp(cls, "prop_glados_core") != 0 &&
		std::strcmp(cls, "npc_portal_turret_floor") != 0)) {
		return false;
	}
	auto* sc = static_cast<sdk::ServerClass*>(sdk::networkableServerClass(networkable));
	auto* entity = static_cast<uint8_t*>(sdk::networkableBaseEntity(networkable));
	if (!sc || !sc->table || !entity) {
		return false;
	}
	static void* table = nullptr;
	static int moveParent = -1;
	if (sc->table != table) {
		table = sc->table;
		moveParent = findProp(sc->table, "moveparent", 0);
	}
	return moveParent < 0 || *reinterpret_cast<uint32_t*>(entity + moveParent) == 0xFFFFFFFFu;
}

bool interestingClass(const char* cls) {
	return cls && std::strcmp(cls, "player") != 0 && std::strcmp(cls, "prop_portal") != 0 && std::strcmp(cls, "worldspawn") != 0 &&
		std::strcmp(cls, "physicsshadowclone") != 0 && std::strcmp(cls, "portalsimulator_collisionentity") != 0 &&
		std::strcmp(cls, "func_clip_vphysics") != 0 && std::strcmp(cls, "func_vehicleclip") != 0 && std::strncmp(cls, "trigger_", 8) != 0;
}

void scanEntities() {
	g_entityCount = 0;
	for (int i = 2; i < 2048 && g_entityCount < 512; i++) {
		void* e = edictAt(i);
		if (!edictInUse(e)) {
			continue;
		}
		if (!interestingClass(sdk::networkableClassName(sdk::edictNetworkable(e)))) {
			continue;
		}
		void* col = collideableOf(e);
		if (col && sdk::vcall<int>(col, kColSolid) != 0) {
			g_entityEdicts[g_entityCount++] = i;
		}
	}
}

void sendEntities() {
	if (g_colState != 1 || !g_modelInfo) {
		return;
	}
	if (--g_entityScanCountdown <= 0) {
		g_entityScanCountdown = 30;
		scanEntities();
	}
	if (--g_entitySendCountdown > 0) {
		return;
	}
	g_entitySendCountdown = 4; // ~16 Hz at Portal's 66 Hz server frame
	static pcproto::HostEntities packet;
	std::memcpy(packet.magic, "PCE1", 4);
	packet.seq = ++g_entitySeq;
	uint32_t n = 0;
	for (int k = 0; k < g_entityCount && n < pcproto::kMaxHostEntities; k++) {
		int index = g_entityEdicts[k];
		void* e = edictAt(index);
		if (!edictInUse(e)) {
			continue;
		}
		void* col = collideableOf(e);
		if (!col) {
			continue;
		}
		int solid = sdk::vcall<int>(col, kColSolid);
		int solidFlags = sdk::vcall<int>(col, kColSolidFlags);
		int group = sdk::vcall<int>(col, kColGroup);
		// Not solid right now, a trigger, or debris that players walk through.
		if (solid == 0 || (solidFlags & (FSOLID_NOT_SOLID | FSOLID_TRIGGER)) || (group >= 1 && group <= 3)) {
			continue;
		}
		const sdk::Vector& o = *sdk::vcall<const sdk::Vector*>(col, kColOrigin);
		if (dist(o, g_origin) > 2048.0f) {
			continue;
		}
		const sdk::Vector& a = *sdk::vcall<const sdk::Vector*>(col, kColAngles);
		const sdk::Vector& mins = *sdk::vcall<const sdk::Vector*>(col, kColMins);
		const sdk::Vector& maxs = *sdk::vcall<const sdk::Vector*>(col, kColMaxs);
		void* model = sdk::vcall<void*>(col, kColModel);
		const char* name = model ? sdk::vcall<const char*>(g_modelInfo, 3, model) : nullptr;
		if (!name || !*name) {
			continue;
		}
		pcproto::HostEntity& out = packet.entities[n++];
		out.index = uint16_t(index);
		out.solid = uint8_t(solid);
		pcproto::Vec3& last = g_lastEntityOrigin[index];
		out.flags = (last.x == o.x && last.y == o.y && last.z == o.z) ? pcproto::kEntityStatic : 0;
		void* net = sdk::edictNetworkable(e);
		if (net && looseEntity(net, sdk::networkableClassName(net))) {
			out.flags |= pcproto::kEntityLoose;
		}
		last = {o.x, o.y, o.z};
		out.origin = {o.x, o.y, o.z};
		out.angles = {a.x, a.y, a.z};
		out.mins = {mins.x, mins.y, mins.z};
		out.maxs = {maxs.x, maxs.y, maxs.z};
		std::strncpy(out.model, name, sizeof out.model - 1);
		out.model[sizeof out.model - 1] = 0;
	}
	packet.count = n;
	int bytes = int(offsetof(pcproto::HostEntities, entities) + n * sizeof(pcproto::HostEntity));
	sendto(g_sock, reinterpret_cast<const char*>(&packet), bytes, 0, reinterpret_cast<sockaddr*>(&g_mcAddr), sizeof g_mcAddr);

	static bool logged = false;
	if (!logged && n > 0) {
		logged = true;
		logf("streaming %u solid entities (first: #%u %s solid %u)", n, packet.entities[0].index, packet.entities[0].model, packet.entities[0].solid);
	}
}

// Dev: what Portal itself finds solid at a spot. A player-hull trace (IEngineTrace::TraceRay, slot 4
// of EngineTraceServer003) from `top` down to `at`: where it stops, and the surface and contents.
void* g_serverTrace = nullptr;
class TraceAll {
public:
	virtual bool ShouldHitEntity(void* entity, int) { return entity != skip; }
	virtual int GetTraceType() { return 0; }
	void* skip = nullptr;
};

// Portal's loose physics props (cubes, the radio, a camera knocked off its wall), refreshed every
// few ticks: the things a move of Steve's is checked against (clampToProps).
void* g_looseProps[64];
int g_loosePropCount = 0;

void refreshLooseProps() {
	g_loosePropCount = 0;
	for (int i = 2; i < 2048 && g_loosePropCount < 64; i++) {
		void* e = edictAt(i);
		void* networkable = edictInUse(e) ? sdk::edictNetworkable(e) : nullptr;
		const char* cls = networkable ? sdk::networkableClassName(networkable) : nullptr;
		// The ones a player moves, as Minecraft is told them (kEntityLoose). Not the physics prop
		// inside a floor button: with that in the list the sweep stopped Steve at a button's lip, and
		// he couldn't walk up onto it.
		if (looseEntity(networkable, cls)) {
			if (void* entity = sdk::networkableBaseEntity(networkable)) {
				g_looseProps[g_loosePropCount++] = entity;
			}
		}
	}
}

class TraceLooseProps {
public:
	virtual bool ShouldHitEntity(void* entity, int) {
		for (int i = 0; i < g_loosePropCount; i++) {
			if (g_looseProps[i] == entity) {
				return true;
			}
		}
		return false;
	}
	virtual int GetTraceType() { return 0; }
};

// One sweep of the player's standing hull (feet `from` to `to`) against the loose props. False if
// nothing is hit or it starts inside one; else where the hull stops and the face it stopped on.
bool sweepProps(const Vector& from, const Vector& to, Vector* end, Vector* normal) {
	TraceRayArgs ray{};
	ray.start[0] = from.x, ray.start[1] = from.y, ray.start[2] = from.z + 36.0f;
	ray.delta[0] = to.x - from.x, ray.delta[1] = to.y - from.y, ray.delta[2] = to.z - from.z;
	ray.extents[0] = ray.extents[1] = 16.0f, ray.extents[2] = 36.0f;
	ray.isSwept = true;
	TraceLooseProps filter;
	alignas(16) uint8_t tr[256] = {};
	__try {
		sdk::vcall<void>(g_serverTrace, 4, static_cast<const void*>(&ray), 0x201400Bu /* MASK_PLAYERSOLID */, static_cast<void*>(&filter),
			static_cast<void*>(tr));
	} __except (EXCEPTION_EXECUTE_HANDLER) {
		return false;
	}
	float fraction;
	std::memcpy(&fraction, tr + 44, 4);
	if (tr[55] /* startsolid */ || !(fraction < 1.0f)) {
		return false;
	}
	Vector e;
	std::memcpy(&e, tr + 12, 12);
	std::memcpy(normal, tr + 24, 12);
	*end = {e.x, e.y, e.z - 36.0f};
	return true;
}

// The player's standing hull swept from `from` to `*to` (feet), against the loose props only. If
// one is in the way, `*to` becomes where the hull ends up: stopped at it and slid along its face,
// as Source's own movement does. Starting inside one: no answer (Portal's physics has that case).
// Not while the gun holds an object: the cube in front of the player is not in his way (it stopped
// him dead, and Steve couldn't walk at all carrying one).
bool clampToProps(const Vector& from, Vector* to) {
	static int tick = 0;
	if ((tick++ & 31) == 0) {
		refreshLooseProps();
	}
	if (g_loosePropCount == 0 || g_gunEffect == 2 || dist(from, *to) < 0.01f) {
		return false; // (a sweep of no length reads as blocked)
	}
	if (!g_serverTrace && g_engineFactory) {
		g_serverTrace = g_engineFactory("EngineTraceServer003", nullptr);
	}
	if (!g_serverTrace) {
		return false;
	}
	Vector end, n;
	if (!sweepProps(from, *to, &end, &n)) {
		return false;
	}
	// What is left of the move, without the part into the face; a hair off the face first.
	Vector rest{to->x - end.x, to->y - end.y, to->z - end.z};
	float into = rest.x * n.x + rest.y * n.y + rest.z * n.z;
	rest = {rest.x - n.x * into, rest.y - n.y * into, rest.z - n.z * into};
	Vector start{end.x + n.x * 0.05f, end.y + n.y * 0.05f, end.z + n.z * 0.05f};
	Vector slid{start.x + rest.x, start.y + rest.y, start.z + rest.z};
	Vector end2, n2;
	if (dist(start, slid) > 0.01f && sweepProps(start, slid, &end2, &n2)) {
		slid = end2;
	}
	static int logs = 0;
	if (logs++ < 10) {
		logf("props: a loose prop is in the way of (%.1f %.1f %.1f): the player goes to (%.1f %.1f %.1f)", to->x, to->y, to->z, slid.x, slid.y, slid.z);
	}
	*to = slid;
	return true;
}

void logHostSolid(const Vector& at, void* playerEntity) {
	if (!g_serverTrace && g_engineFactory) {
		g_serverTrace = g_engineFactory("EngineTraceServer003", nullptr);
	}
	if (!g_serverTrace) {
		return;
	}
	TraceRayArgs ray{};
	ray.start[0] = at.x, ray.start[1] = at.y, ray.start[2] = at.z + 24.0f + 36.0f; // hull centre, 24 above
	ray.delta[2] = -24.0f;
	ray.extents[0] = ray.extents[1] = 16.0f, ray.extents[2] = 36.0f; // the player's standing hull
	ray.isSwept = true;
	TraceAll filter;
	filter.skip = playerEntity;
	alignas(16) uint8_t tr[256] = {};
	__try {
		sdk::vcall<void>(g_serverTrace, 4, static_cast<const void*>(&ray), 0x201400Bu /* MASK_PLAYERSOLID */, static_cast<void*>(&filter),
			static_cast<void*>(tr));
	} __except (EXCEPTION_EXECUTE_HANDLER) {
		return;
	}
	float fraction;
	int contents;
	Vector end;
	const char* surface = *reinterpret_cast<const char**>(tr + 60);
	std::memcpy(&fraction, tr + 44, 4);
	std::memcpy(&contents, tr + 48, 4);
	std::memcpy(&end, tr + 12, 12);
	void* hit = *reinterpret_cast<void**>(tr + 76);
	void* world = edictInUse(edictAt(0)) ? sdk::networkableBaseEntity(sdk::edictNetworkable(edictAt(0))) : nullptr;
	logf("  portal's hull from %.1f down to %.1f: stops at z %.2f (fraction %.2f, startsolid %d) on '%s' contents 0x%x, %s", at.z + 24.0f, at.z,
		end.z - 36.0f, fraction, tr[55], surface ? surface : "?", contents, hit == world ? "the world" : hit ? "an entity" : "nothing");
}

/** Every solid entity within 96 units: who might be shoving the player. */
void logSolidNear(const Vector& at) {
	if (g_colState != 1 || !g_modelInfo) {
		logf("  (entity list unavailable: collideable state %d)", g_colState);
		return;
	}
	for (int i = 2; i < 2048; i++) {
		void* e = edictAt(i);
		if (!edictInUse(e)) {
			continue;
		}
		void* col = collideableOf(e);
		if (!col || sdk::vcall<int>(col, kColSolid) == 0) {
			continue;
		}
		const sdk::Vector& o = *sdk::vcall<const sdk::Vector*>(col, kColOrigin);
		if (dist(o, at) > 96.0f) {
			continue;
		}
		void* model = sdk::vcall<void*>(col, kColModel);
		const char* name = model ? sdk::vcall<const char*>(g_modelInfo, 3, model) : "?";
		logf("  near: #%d %s model %s solid %d flags 0x%x group %d at (%.1f %.1f %.1f)", i, sdk::networkableClassName(sdk::edictNetworkable(e)),
			name ? name : "?", sdk::vcall<int>(col, kColSolid), sdk::vcall<int>(col, kColSolidFlags), sdk::vcall<int>(col, kColGroup), o.x, o.y, o.z);
	}
}

// ---- the mouse while a Minecraft screen is open -----------------------------------------
// Minecraft's inventory needs a pointer. While one of its screens is open, Portal lets go of the
// mouse the way it does for its own menus (IBaseClientDLL::IN_DeactivateMouse, slot 15: the camera
// stops turning and the OS cursor is free), and we send the cursor's place over Portal's window
// with every HostState. IN_ActivateMouse (slot 14) takes it back when the screen closes. Both
// checked in build 19017868's client.dll (they forward to IInput::Activate/DeactivateMouse).

void* g_clientDll = nullptr; // VClient017
bool g_mouseFreed = false;

HWND portalWindow() {
	static HWND cached = nullptr;
	if (cached && IsWindow(cached)) {
		return cached;
	}
	cached = nullptr;
	for (HWND w = FindWindowA("Valve001", nullptr); w; w = FindWindowExA(nullptr, w, "Valve001", nullptr)) {
		DWORD pid = 0;
		GetWindowThreadProcessId(w, &pid);
		if (pid == GetCurrentProcessId()) {
			cached = w;
			break;
		}
	}
	return cached;
}

// Portal takes its mouse back every frame (IInput::ActivateMouse from its own input code), which
// recentres the cursor: blocked while a Minecraft screen is open. IInput is found from
// IN_ActivateMouse's code (`mov ecx, [input]; mov eax, [ecx]; jmp [eax + 4 * slot]`).
using VoidFn = void(__thiscall*)(void* self);
VoidFn g_activateMouseOriginal = nullptr;
void** g_inputVtable = nullptr;
int g_activateSlot = -1;

void __fastcall hkActivateMouse(void* self, void* /*edx*/) {
	if (g_mouseFreed) {
		return;
	}
	g_activateMouseOriginal(self);
}

void hookActivateMouse() {
	static bool tried = false;
	if (tried || !g_clientDll) {
		return;
	}
	tried = true;
	const uint8_t* code = static_cast<const uint8_t*>((*static_cast<void***>(g_clientDll))[14]);
	void** inputGlobal = nullptr;
	for (int i = 0; i + 6 < 16 && !inputGlobal; i++) {
		if (code[i] == 0x8B && code[i + 1] == 0x0D) {
			std::memcpy(&inputGlobal, code + i + 2, 4);
			for (int j = i + 6; j + 2 < 20; j++) {
				if (code[j] == 0xFF && code[j + 1] == 0x60) {
					g_activateSlot = code[j + 2] / 4;
					break;
				}
			}
		}
	}
	void* input = inputGlobal ? *inputGlobal : nullptr;
	if (!input || g_activateSlot < 0) {
		logf("mouse: couldn't find IInput::ActivateMouse; the cursor may snap back in Minecraft screens");
		return;
	}
	g_inputVtable = *static_cast<void***>(input);
	if (hookSlot(input, g_activateSlot, reinterpret_cast<void*>(&hkActivateMouse), reinterpret_cast<void**>(&g_activateMouseOriginal))) {
		logf("mouse: hooked IInput::ActivateMouse (slot %d)", g_activateSlot);
	}
}

void updateMouseCapture() {
	bool want = mcReady() && (g_mc.flags & pcproto::kMcScreen) && g_inLevel;
	if (!g_clientDll) {
		g_clientDll = engineInterface("client.dll", "VClient017");
		if (!g_clientDll) {
			return;
		}
	}
	hookActivateMouse();
	if (want) {
		// Every frame while open: Portal takes the mouse back by itself when its window regains focus.
		sdk::vcall<void>(g_clientDll, 15); // IN_DeactivateMouse
		if (!g_mouseFreed) {
			g_mouseFreed = true;
			logf("Minecraft screen open: mouse freed for it");
		}
	} else if (g_mouseFreed) {
		g_mouseFreed = false;
		sdk::vcall<void>(g_clientDll, 14); // IN_ActivateMouse
		logf("Minecraft screen closed: mouse back to Portal");
	}
}

// The mouse wheel: GetAsyncKeyState can't see it, so we watch Portal's window messages
// (WM_MOUSEWHEEL, passed on untouched) and count notches for HostState.wheel.
WNDPROC g_portalWndProc = nullptr;
HWND g_subclassed = nullptr;
int g_wheelDelta = 0; // WHEEL_DELTA units so far

// Typing into Minecraft's chat and sign/book screens: while one of its screens is open, the
// characters typed into Portal's window go to Minecraft ("PCY1", UTF-16 units) and Portal doesn't
// see the keyboard at all, so typing "e" doesn't grab and Esc closes Minecraft's screen, not
// Portal's menu. (Key presses still reach Minecraft through HostState.keys.)
uint16_t g_typed[64];
int g_typedCount = 0;

LRESULT CALLBACK wheelWndProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam) {
	if (msg == WM_MOUSEWHEEL) {
		g_wheelDelta += GET_WHEEL_DELTA_WPARAM(wParam);
	}
	// F5 is Minecraft's camera (it reads keys by polling, so it still sees it); Portal would also
	// take a screenshot with it, every press. Portal doesn't get it while Minecraft is linked.
	if ((msg == WM_KEYDOWN || msg == WM_KEYUP) && wParam == VK_F5 && mcReady()) {
		return 0;
	}
	if (g_mouseFreed) {
		if (msg == WM_CHAR && wParam >= 32 && g_typedCount < 64) {
			g_typed[g_typedCount++] = uint16_t(wParam);
		}
		if (msg == WM_KEYDOWN || msg == WM_KEYUP || msg == WM_CHAR || msg == WM_SYSKEYDOWN || msg == WM_SYSKEYUP || msg == WM_SYSCHAR) {
			return 0; // Minecraft's, not Portal's
		}
	}
	return CallWindowProcA(g_portalWndProc, hwnd, msg, wParam, lParam);
}

void sendTyped() {
	if (g_typedCount == 0) {
		return;
	}
	char buf[4 + 64 * 2];
	std::memcpy(buf, "PCY1", 4);
	std::memcpy(buf + 4, g_typed, size_t(g_typedCount) * 2);
	sendto(g_sock, buf, 4 + g_typedCount * 2, 0, reinterpret_cast<sockaddr*>(&g_mcAddr), sizeof g_mcAddr);
	g_typedCount = 0;
}

void watchWheel() {
	HWND w = portalWindow();
	if (!w || w == g_subclassed) {
		return;
	}
	g_portalWndProc = reinterpret_cast<WNDPROC>(SetWindowLongPtrA(w, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(&wheelWndProc)));
	g_subclassed = g_portalWndProc ? w : nullptr;
	logf(g_subclassed ? "watching the mouse wheel" : "couldn't watch the mouse wheel (%lu)", GetLastError());
}

void unwatchWheel() {
	if (g_subclassed && IsWindow(g_subclassed) && g_portalWndProc) {
		SetWindowLongPtrA(g_subclassed, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(g_portalWndProc));
	}
	g_subclassed = nullptr;
}

void fillCursor(pcproto::HostState& s) {
	s.cursorX = s.cursorY = -1.0f;
	HWND w = g_mouseFreed ? portalWindow() : nullptr;
	POINT p;
	RECT r;
	if (!w || !GetCursorPos(&p) || !ScreenToClient(w, &p) || !GetClientRect(w, &r) || r.right <= 0 || r.bottom <= 0) {
		return;
	}
	s.cursorX = float(p.x) / float(r.right);
	s.cursorY = float(p.y) / float(r.bottom);
}

// ---- per-frame state out --------------------------------------------------------------

void sendState() {
	pcproto::HostState s{};
	std::memcpy(s.magic, "PCH6", 4);
	s.seq = ++g_hostSeq;
	std::memcpy(s.map, g_map, sizeof s.map);
	// Not "in game" until the player has moved once: before that the origin is (0, 0, 0), and
	// Minecraft would resync there, out in the void, before the level-start teleport.
	if (g_inLevel && g_edicts && g_haveOrigin) {
		s.flags |= pcproto::kHostInGame;
	}
	if (g_drivingNow) {
		s.flags |= pcproto::kHostDriving;
	}
	if (g_scripted) {
		s.flags |= pcproto::kHostScripted;
	}
	if (g_riding) {
		s.flags |= pcproto::kHostRiding;
	}
	// Steve holds the Minecraft portal gun in his hand; Portal's own gun model (its viewmodel) isn't
	// drawn over it while Minecraft is linked. Portal's portal-gun HUD (the crosshair halves that show
	// which portals are placed) stays: it isn't part of the viewmodel.
	camera::setViewModel(!mcReady());
	if (!g_engineClient) {
		g_engineClient = engineInterface("engine.dll", "VEngineClient013");
	}
	if (g_engineClient) {
		sdk::QAngle view{};
		sdk::clientGetViewAngles(g_engineClient, &view);
		s.pitch = view.x;
		s.yaw = view.y;
	}
	if (gameHasFocus()) {
		s.flags |= pcproto::kHostForeground;
		readInput(s);
	}
	if (GetTickCount() < g_fakeUntil) {
		s.flags |= pcproto::kHostForeground;
		for (int i = 0; i < 32; i++) {
			s.keys[i] |= g_fakeKeys[i];
		}
		s.mouse |= g_fakeMouse;
	} else if (g_fakeFrame || g_fakePortalButtons) {
		// The replay ended, or died holding fire: let go.
		g_fakeFrame = 0;
		setPortalButtons(0);
	}
	s.origin = {g_origin.x, g_origin.y, g_origin.z};
	s.velocity = {g_velocity.x, g_velocity.y, g_velocity.z};
	s.teleportSeq = g_teleportSeq;
	s.teleportOrigin = {g_teleportOrigin.x, g_teleportOrigin.y, g_teleportOrigin.z};
	s.teleportVelocity = {g_teleportVelocity.x, g_teleportVelocity.y, g_teleportVelocity.z};
	if (g_teleportKind == pcproto::kMoveShove) {
		// A shove is an offset, not a place: all the shoves Minecraft hasn't taken yet, summed (what we
		// add to its positions meanwhile). It adds the same to wherever its player is by then. Sent as
		// a place, a shove that arrived a tick into a jump read as "far away" and became a teleport
		// back to the ground.
		Vector pending = pendingShoves();
		s.teleportVelocity = {pending.x, pending.y, pending.z};
	}
	s.teleportKind = g_teleportKind;
	s.shots = g_shots;
	s.gunEffect = (g_gunEffect & 0xFFu) | (uint32_t(g_gunFizzles) << 8); // its state (0xFF: unknown), and its fizzles
	// Portal's light at the player's chest, for Minecraft to light Steve's hand by (eased, so walking
	// past a lamp doesn't flicker). An engine slot, so only on a build it was checked on.
	s.handLight = {-1.0f, 0.0f, 0.0f};
	if (g_engineClient && g_inLevel && g_haveOrigin && checkedBuild("engine.dll")) {
		static Vector eased{};
		static bool have = false;
		Vector at{g_origin.x, g_origin.y, g_origin.z + 48.0f};
		Vector now = sdk::clientLightForPoint(g_engineClient, at, true);
		if (!have) {
			eased = now;
			have = true;
			logf("hand light: Portal's light at the player is (%.2f %.2f %.2f)", now.x, now.y, now.z);
		}
		eased = {eased.x + (now.x - eased.x) * 0.12f, eased.y + (now.y - eased.y) * 0.12f, eased.z + (now.z - eased.z) * 0.12f};
		s.handLight = {eased.x, eased.y, eased.z};
	}
	if (g_inLevel && g_edicts) {
		fillPortals(s);
	}
	if (std::memcmp(g_portalsNow, s.portals, sizeof g_portalsNow) != 0) {
		g_portalsChangedAt = GetTickCount();
		for (int i = 0; i < 2; i++) { // dev: where the portals are, for scripted tests
			logf("portals: %s flags %#x at (%.1f %.1f %.1f) angles (%.0f %.0f %.0f)", i ? "orange" : "blue", s.portals[i].flags, s.portals[i].origin.x,
				s.portals[i].origin.y, s.portals[i].origin.z, s.portals[i].angles.x, s.portals[i].angles.y, s.portals[i].angles.z);
		}
	}
	std::memcpy(g_portalsNow, s.portals, sizeof g_portalsNow);
	s.crossBase = g_mc.teleportAck; // what the shove offset and the crossing below build on
	Xf cross;
	// Host moves only: Minecraft's own crossings it has already made.
	McAt mcAt{g_mc.teleportAck, g_mc.crossMatchedEcho}, hostAt{g_teleportSeq, g_mc.crossMatchedEcho};
	if (g_teleportKind == pcproto::kMoveTeleport && g_mc.teleportAck != g_teleportSeq && anyCrossingAfter(mcAt) &&
		crossingsBetween(mcAt, hostAt, &cross)) {
		s.crossBase = g_mc.teleportAck;
		s.crossValid = 1;
		std::memcpy(s.crossRot, cross.r, sizeof s.crossRot);
		s.crossMove = {cross.t.x, cross.t.y, cross.t.z};
	}
	s.crossMatched = g_matched;
	fillCursor(s);
	g_wheelDelta += g_fakeWheel * WHEEL_DELTA; // a replay's scrolling
	g_fakeWheel = 0;
	s.wheel = int8_t(g_wheelDelta / WHEEL_DELTA);
	recordReplay(s);
	sendTyped();
	sendto(g_sock, reinterpret_cast<const char*>(&s), sizeof s, 0, reinterpret_cast<sockaddr*>(&g_mcAddr), sizeof g_mcAddr);

	static DWORD lastTrace = 0;
	if (GetTickCount() - lastTrace > 1000 && (s.flags & pcproto::kHostInGame)) {
		lastTrace = GetTickCount();
		int keys = 0;
		for (uint8_t k : s.keys) keys += __popcnt(k);
		logf("trace: pos (%.0f %.0f %.0f) vel (%.0f %.0f %.0f) view p%.0f y%.0f | mc %s ack %u/%u pos (%.0f %.0f %.0f) ground %d sneak %d | driving %d keys %d",
			g_origin.x, g_origin.y, g_origin.z, g_velocity.x, g_velocity.y, g_velocity.z, s.pitch, s.yaw, mcReady() ? "ready" : "-",
			g_mc.teleportAck, g_teleportSeq, g_mc.origin.x, g_mc.origin.y, g_mc.origin.z, g_mc.onGround, g_mc.sneaking,
			g_drivingNow ? 1 : 0, keys);
	}
}

// ---- the plugin ----------------------------------------------------------------------

// Every 2 s, "PCP1" + Portal's maps folder: Minecraft reads the maps' collision from there, however it
// was started (by this plugin, or by hand, or by a Prism Launcher that was already open).
void sendMapsDir() {
	static DWORD last = 0;
	if (GetTickCount() - last < 2000) {
		return;
	}
	last = GetTickCount();
	char packet[4 + MAX_PATH + 1];
	std::memcpy(packet, "PCP1", 4);
	size_t n = strnlen(launcher::mapsDir(), MAX_PATH);
	std::memcpy(packet + 4, launcher::mapsDir(), n);
	packet[4 + n] = 0;
	sendto(g_sock, packet, int(4 + n + 1), 0, reinterpret_cast<sockaddr*>(&g_mcAddr), sizeof g_mcAddr);
}

// ---- perf: a line a minute in portalcraft.log (as SkyCraft's) ---------------------------
// Portal's frame rate and its worst frame, and what PortalCraft costs per frame: the plugin's own
// per-frame work and drawing Minecraft's world. For "it lags" reports.
struct Perf {
	LARGE_INTEGER freq{}, last{}, windowStart{};
	int frames = 0;
	double worstFrame = 0.0, workSeconds = 0.0;
} g_perf;

double perfSeconds(const LARGE_INTEGER& a, const LARGE_INTEGER& b) {
	return double(b.QuadPart - a.QuadPart) / double(g_perf.freq.QuadPart);
}

// Times one GameFrame of PortalCraft's own work (constructed at its start, whatever way it returns).
struct PerfFrame {
	LARGE_INTEGER start;
	PerfFrame() {
		if (!g_perf.freq.QuadPart) {
			QueryPerformanceFrequency(&g_perf.freq);
		}
		QueryPerformanceCounter(&start);
		if (g_perf.last.QuadPart) {
			double frame = perfSeconds(g_perf.last, start);
			if (frame < 2.0) { // longer is a level loading (no GameFrame meanwhile), not a slow frame
				g_perf.worstFrame = frame > g_perf.worstFrame ? frame : g_perf.worstFrame;
			}
		} else {
			g_perf.windowStart = start;
		}
		g_perf.last = start;
		g_perf.frames++;
	}
	~PerfFrame() {
		LARGE_INTEGER end;
		QueryPerformanceCounter(&end);
		g_perf.workSeconds += perfSeconds(start, end);
		double window = perfSeconds(g_perf.windowStart, end);
		if (window >= 60.0) {
			double draw = worldrender::takeDrawSeconds();
			logf("perf: %.0f fps (worst frame %.0f ms); PortalCraft per frame: plugin %.2f ms, drawing Minecraft %.2f ms; Minecraft %s, %zu block boxes",
				g_perf.frames / window, g_perf.worstFrame * 1000.0, g_perf.workSeconds * 1000.0 / g_perf.frames, draw * 1000.0 / g_perf.frames,
				mcReady() ? "linked" : "not linked", g_blockBoxes.size());
			g_perf.frames = 0;
			g_perf.worstFrame = g_perf.workSeconds = 0.0;
			g_perf.windowStart = end;
		}
	}
};

class Plugin {
public:
	virtual bool Load(sdk::CreateInterfaceFn interfaceFactory, sdk::CreateInterfaceFn gameServerFactory) {
		char path[MAX_PATH];
		GetModuleFileNameA(g_self, path, MAX_PATH);
		if (char* slash = std::strrchr(path, '\\')) {
			slash[1] = 0;
			std::strcpy(g_logDir, path);
			std::strcpy(slash + 1, instance::named("portalcraft", ".log").c_str());
		}
		g_log = std::fopen(path, "w");
		if (instance::number() > 0) {
			logf("instance %d (-pcinstance): link ports %d and %d, log %s", instance::number(), instance::hostPort(), instance::mcPort(), path);
		}
		if (HMODULE tier0 = GetModuleHandleA("tier0.dll")) {
			g_msg = reinterpret_cast<MsgFn>(GetProcAddress(tier0, "Msg"));
		}
		g_engineFactory = interfaceFactory;
		g_engineServer = interfaceFactory("VEngineServer021", nullptr);
		g_playerInfoMgr = gameServerFactory("PlayerInfoManager002", nullptr);
		void* gm = gameServerFactory("GameMovement001", nullptr);
		g_modelInfo = interfaceFactory("VModelInfoServer004", nullptr);
		if (!g_modelInfo) {
			g_modelInfo = interfaceFactory("VModelInfoServer003", nullptr);
		}
		logf("Load: engine %p playerinfo %p gamemovement %p modelinfo %p", g_engineServer, g_playerInfoMgr, gm, g_modelInfo);
		if (!g_engineServer || !g_playerInfoMgr || !gm) {
			logf("missing interfaces, staying inactive");
			return true; // stay loaded so the log is readable; do nothing
		}
		if (hookSlot(gm, sdk::kProcessMovementSlot, reinterpret_cast<void*>(&serverProcessMovement),
				reinterpret_cast<void**>(&g_serverOriginal))) {
			logf("hooked server GameMovement001");
		}
		linkOpen();
		launcher::init(&logf, g_self);
		launcher::frame(false, false, nullptr); // start Minecraft now, while Portal is still on its menu
		return true;
	}
	virtual void Unload() {
		logf("Unload");
		camera::shutdown();
		unwatchWheel();
		hooks::unpatchAll(); // every other slot we patched, back to Portal's own (see hooks.h)
		if (g_mouseFreed && g_clientDll) {
			sdk::vcall<void>(g_clientDll, 14); // give Portal its mouse back
		}
	}
	virtual void Pause() {}
	virtual void UnPause() {}
	virtual const char* GetPluginDescription() {
		return "PortalCraft: play Portal as a Minecraft player";
	}
	virtual void LevelInit(const char* mapName) {
		std::strncpy(g_map, mapName ? mapName : "", sizeof g_map - 1);
		g_needSync = true;
		g_haveSet = false;
		g_haveOrigin = false;
		g_shoveCount = 0;
		g_hardPending = false;
		g_crossingCount = 0;
		g_opaqueSeq = 0;
		g_matched = g_mc.crossCount;
		g_checkedLayout = false;
		g_po = PortalOffsets{};
		g_portalCount = 0;
		g_colState = 0;
		g_entityCount = 0;
		std::memset(g_lastEntityOrigin, 0, sizeof g_lastEntityOrigin);
		g_replayNext = g_replayCount = 0; // a recording is of one level: a replay can't cross a level change
		logf("LevelInit %s", g_map);
		worldrender::levelChanged();
	}
	virtual void ServerActivate(void* edictList, int edictCount, int clientMax) {
		g_edicts = static_cast<uint8_t*>(edictList);
		g_inLevel = true;
		forgetBlockBoxes();
		void* world = sdk::edictNetworkable(edictList);
		logf("ServerActivate: %d edicts, %d clients, edict0 = %s", edictCount, clientMax,
			world ? sdk::networkableClassName(world) : "(null)");
		hookClientMovement();
		hookServerTraces();
	}
	virtual void GameFrame(bool /*simulating*/) {
		PerfFrame perf;
		if (g_sock == INVALID_SOCKET) {
			return;
		}
		hookClientMovement();
		static bool overlayStarted = false;
		if (!overlayStarted) {
			overlayStarted = true;
			// What PortalCraft needs from Portal, whichever way Portal was launched (Steam's Play
			// button included). None is a cheat:
			//   cl_updaterate/cmdrate 66, cl_interp 0, cl_interp_ratio 1: one update per server tick and
			//     no interpolation delay, or the camera bobs against Minecraft's 20 Hz ticks;
			//   mat_queue_mode 0: draw on the main thread, where the world pass and overlay draw;
			//   engine_no_focus_sleep 0: keep running at full rate with Minecraft's window focused.
			sdk::serverCommand(g_engineServer,
				"cl_updaterate 66; cl_cmdrate 66; cl_interp 0; cl_interp_ratio 1; mat_queue_mode 0; engine_no_focus_sleep 0\n");
			logf("set cl_updaterate/cmdrate 66, cl_interp 0, mat_queue_mode 0, engine_no_focus_sleep 0%s", devMode() ? "; dev packets on" : "");
			overlay::init(&logf);
			worldrender::init(&logf, g_engineFactory);
		}
		linkPoll();
		launcher::frame(mcReady(), g_inLevel, checkedBuild("engine.dll") ? g_engineClient : nullptr);
		bridgeHealth();
		updateScripted();
		updateRiding();
		updateBlockPhysics();
		sendState();
		sendMapsDir();
		camera::init(&logf);
		camera::setMode(following() ? g_mc.cameraMode : 0, g_mc.cameraDistance);
		camera::setEyeHeight(following() && !g_scripted ? (g_mc.sneaking ? 50.8f : 64.0f) : 0.0f);
		camera::setGrounded(g_mc.onGround != 0, g_mc.velocity.z);
		camera::setSprinting(following() && !g_scripted && (g_mc.flags & pcproto::kMcSprint) != 0);
		camera::setPortals(&g_portalsNow[0].origin.x, &g_portalsNow[1].origin.x,
			(g_portalsNow[0].flags & pcproto::kPortalLinked) && (g_portalsNow[1].flags & pcproto::kPortalLinked));
		camera::setHideBody(mcReady()); // Chell -> Steve (worldrender draws him)
		updateMouseCapture();
		watchWheel();
		applyLights();
		if (g_inLevel && g_edicts) {
			checkCollideableLayout();
			sendEntities();
		}
	}
	virtual void LevelShutdown() {
		g_inLevel = false;
		forgetBlockBoxes();
		g_edicts = nullptr;
		g_portalCount = 0;
	}
	virtual void ClientActive(void*) {}
	virtual void ClientDisconnect(void*) {}
	virtual void ClientPutInServer(void*, const char*) {}
	virtual void SetCommandClient(int) {}
	virtual void ClientSettingsChanged(void*) {}
	virtual int ClientConnect(bool*, void*, const char*, const char*, char*, int) {
		return 0; // PLUGIN_CONTINUE
	}
	virtual int ClientCommand(void*, const void*) {
		return 0;
	}
	virtual int NetworkIDValidated(const char*, const char*) {
		return 0;
	}
	virtual void OnQueryCvarValueFinished(int, void*, int, const char*, const char*) {}
	virtual void OnEdictAllocated(void*) {}
	virtual void OnEdictFreed(const void*) {}
};

Plugin g_plugin;

} // namespace

extern "C" __declspec(dllexport) void* CreateInterface(const char* name, int* returnCode) {
	if (std::strncmp(name, "ISERVERPLUGINCALLBACKS00", 24) == 0 && name[24] >= '1' && name[24] <= '3') {
		if (returnCode) *returnCode = 0;
		return &g_plugin;
	}
	if (returnCode) *returnCode = 1;
	return nullptr;
}

BOOL WINAPI DllMain(HINSTANCE instance, DWORD reason, LPVOID) {
	if (reason == DLL_PROCESS_ATTACH) {
		g_self = instance;
		DisableThreadLibraryCalls(instance);
	}
	return TRUE;
}
