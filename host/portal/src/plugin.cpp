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
#include <intrin.h>

#include "../../../protocol/portalcraft_protocol.h"
#include "camera.h"
#include "overlay.h"
#include "worldrender.h"
#include "sdk.h"

using sdk::Vector;

namespace {

// ---- logging -------------------------------------------------------------------------

FILE* g_log = nullptr;
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
// Minecraft's recent physics steps on a smoothed timeline (see interpolatedMinecraft).
struct TickSample {
	uint32_t seq;
	pcproto::Vec3 pos;
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
	addr.sin_port = htons(pcproto::kHostPort);
	addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
	if (bind(g_sock, reinterpret_cast<sockaddr*>(&addr), sizeof addr) != 0) {
		logf("bind 127.0.0.1:%d failed (%d): is another Portal running?", pcproto::kHostPort, WSAGetLastError());
	}
	g_mcAddr.sin_family = AF_INET;
	g_mcAddr.sin_port = htons(pcproto::kMcPort);
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

void linkPoll() {
	char buf[512];
	for (int i = 0; i < 64; i++) {
		int n = recv(g_sock, buf, sizeof buf, 0);
		if (n <= 0) {
			return;
		}
		if (n == sizeof(pcproto::McState) && std::memcmp(buf, "PCM3", 4) == 0) {
			bool wasReady = mcReady();
			uint32_t oldAck = g_mc.teleportAck;
			std::memcpy(&g_mc, buf, sizeof g_mc);
			g_mcTime = GetTickCount();
			if (g_mc.teleportAck != oldAck) {
				// Minecraft just applied a teleport: its earlier steps are from before it, and playing
				// them back (one step behind) would put the player back where he was, e.g. outside the
				// map after a Portal restart. Start the timeline over from this step.
				std::memset(g_ticks, 0, sizeof g_ticks);
				g_haveOffset = false;
			}
			if (g_mc.tickSeq != g_tickSeq) {
				// A physics step just ended in Minecraft. Keep it, and fold its arrival time into a
				// slow average of where Minecraft's 20 Hz timeline sits on our clock: arrival jitter
				// averages out instead of jerking the camera.
				g_tickSeq = g_mc.tickSeq;
				g_ticks[g_tickSeq % kTickHistory] = {g_tickSeq, g_mc.tickCurrent};
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
		} else if ((n == 4 + 32 + 4 || n == 4 + 32 + 4 + 1) && std::memcmp(buf, "PCK1", 4) == 0) {
			std::memcpy(g_fakeKeys, buf + 4, 32);
			uint32_t ms;
			std::memcpy(&ms, buf + 36, 4);
			g_fakeMouse = n == 41 ? uint8_t(buf[40]) : 0;
			g_fakeUntil = GetTickCount() + ms;
		} else if (n == 4 + 8 && std::memcmp(buf, "PCV1", 4) == 0 && g_engineClient) {
			// Dev: point the camera (pitch, yaw), e.g. to aim at a floor for a placement test.
			float pitchYaw[2];
			std::memcpy(pitchYaw, buf + 4, 8);
			sdk::QAngle view{pitchYaw[0], pitchYaw[1], 0.0f};
			sdk::clientSetViewAngles(g_engineClient, &view);
		} else if (n == 8 && std::memcmp(buf, "PCX1", 4) == 0) {
			float exposure; // dev: lighting exposure (tools/fake_mc.py --exposure)
			std::memcpy(&exposure, buf + 4, 4);
			worldrender::setExposure(exposure);
		} else if (n > 4 && std::memcmp(buf, "PCC1", 4) == 0 && g_engineServer) {
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
	{VK_RETURN, 40}, {VK_TAB, 43}, {VK_SPACE, 44},
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
bool g_needSync = true;   // first tick of a level: hand Portal's spawn point to Minecraft
bool g_haveSet = false;   // we wrote the player's origin last server tick
Vector g_lastSet{};       // ... to this
bool g_drivingNow = false;
Vector g_origin{}, g_velocity{}; // player state after the last server movement tick
bool g_haveOrigin = false;       // a movement tick has run this level: g_origin is real

bool following() {
	return mcReady() && g_mc.teleportAck == g_teleportSeq;
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

Vector interpolatedMinecraft(Vector* velocity) {
	*velocity = toVec(g_mc.velocity);
	if (!g_haveOffset) {
		return toVec(g_mc.origin);
	}
	double ticks = (nowSeconds() - g_tickOffset) / 0.05 - 1.0;
	if (ticks > double(g_tickSeq)) {
		ticks = double(g_tickSeq); // ahead of the newest step (it's late): hold it
	}
	uint32_t k = ticks < 1.0 ? 1u : uint32_t(ticks);
	const TickSample* a = tickAt(k);
	const TickSample* b = tickAt(k + 1);
	if (!a) {
		const TickSample* newest = tickAt(g_tickSeq);
		return newest ? toVec(newest->pos) : toVec(g_mc.origin);
	}
	Vector pa = toVec(a->pos);
	if (!b) {
		return pa;
	}
	Vector pb = toVec(b->pos);
	*velocity = {(pb.x - pa.x) * 20.0f, (pb.y - pa.y) * 20.0f, (pb.z - pa.z) * 20.0f};
	if (dist(pa, pb) > 64.0f) {
		return pb; // a teleport between the two steps: don't smear it across the map
	}
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

void logSolidNear(const Vector& at);

using ProcessMovementFn = void(__thiscall*)(void* self, void* player, void* mv);
ProcessMovementFn g_serverOriginal = nullptr;
ProcessMovementFn g_clientOriginal = nullptr;
bool g_checkedLayout = false;

void __fastcall serverProcessMovement(void* self, void* /*edx*/, void* player, void* mvRaw) {
	auto* mv = static_cast<uint8_t*>(mvRaw);
	Vector& origin = *reinterpret_cast<Vector*>(mv + sdk::kMvAbsOrigin);

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
			}
		}
	}

	if (g_haveSet) {
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
	if (g_needSync || (g_haveSet && dist(origin, g_lastSet) > 0.5f)) {
		g_teleportSeq++;
		g_teleportOrigin = origin;
		g_teleportVelocity = *reinterpret_cast<Vector*>(mv + sdk::kMvVelocity);
		if (g_needSync || dist(origin, g_lastSet) > 24.0f) {
			logf("handing teleport %u to Minecraft: (%.1f %.1f %.1f)%s", g_teleportSeq, origin.x, origin.y, origin.z,
				g_needSync ? " [level start]" : "");
		}
		g_needSync = false;
	}

	g_drivingNow = following();
	if (g_drivingNow) {
		quietPortalMovement(mv);
	}
	float zIn = origin.z;
	g_serverOriginal(self, player, mvRaw);
	float zPortal = origin.z;
	if (g_drivingNow) {
		applyMinecraft(mv);
		g_lastSet = origin;
		g_haveSet = true;
	} else {
		g_haveSet = false;
	}
	if (g_needSync) {
		g_zLift = 0.0f;
	}
	if (g_traceTicks > 0 && g_log) {
		g_traceTicks--;
		fprintf(g_log, "S %lu z in %.3f portal %.3f out %.3f | mc z %.3f vz %.1f ground %d lift %.2f | xy (%.2f %.2f)", GetTickCount(), zIn, zPortal,
			origin.z, g_mc.origin.z, g_mc.velocity.z, g_mc.onGround, g_zLift, origin.x, origin.y);
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
	}
	if (g_traceTicks > 0 && g_log) {
		fprintf(g_log, "C %lu z in %.3f portal %.3f out %.3f", GetTickCount(), zIn, zPortal, reinterpret_cast<Vector*>(mv + sdk::kMvAbsOrigin)->z);
		fputc(10, g_log); // newline
	}
}

bool hookSlot(void* object, int slot, void* replacement, void** original) {
	void** vtable = *static_cast<void***>(object);
	if (vtable[slot] == replacement) {
		return true;
	}
	DWORD old;
	if (!VirtualProtect(&vtable[slot], sizeof(void*), PAGE_READWRITE, &old)) {
		return false;
	}
	*original = vtable[slot];
	vtable[slot] = replacement;
	VirtualProtect(&vtable[slot], sizeof(void*), old, &old);
	return true;
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

bool interestingClass(const char* cls) {
	return cls && std::strcmp(cls, "player") != 0 && std::strcmp(cls, "prop_portal") != 0 && std::strcmp(cls, "worldspawn") != 0 &&
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

void updateMouseCapture() {
	bool want = mcReady() && (g_mc.flags & pcproto::kMcScreen) && g_inLevel;
	if (!g_clientDll) {
		g_clientDll = engineInterface("client.dll", "VClient017");
		if (!g_clientDll) {
			return;
		}
	}
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

LRESULT CALLBACK wheelWndProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam) {
	if (msg == WM_MOUSEWHEEL) {
		g_wheelDelta += GET_WHEEL_DELTA_WPARAM(wParam);
	}
	return CallWindowProcA(g_portalWndProc, hwnd, msg, wParam, lParam);
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
	std::memcpy(s.magic, "PCH2", 4);
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
	}
	s.origin = {g_origin.x, g_origin.y, g_origin.z};
	s.velocity = {g_velocity.x, g_velocity.y, g_velocity.z};
	s.teleportSeq = g_teleportSeq;
	s.teleportOrigin = {g_teleportOrigin.x, g_teleportOrigin.y, g_teleportOrigin.z};
	s.teleportVelocity = {g_teleportVelocity.x, g_teleportVelocity.y, g_teleportVelocity.z};
	if (g_inLevel && g_edicts) {
		fillPortals(s);
	}
	fillCursor(s);
	s.wheel = int8_t(g_wheelDelta / WHEEL_DELTA);
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

class Plugin {
public:
	virtual bool Load(sdk::CreateInterfaceFn interfaceFactory, sdk::CreateInterfaceFn gameServerFactory) {
		char path[MAX_PATH];
		GetModuleFileNameA(g_self, path, MAX_PATH);
		if (char* slash = std::strrchr(path, '\\')) {
			std::strcpy(slash + 1, "portalcraft.log");
		}
		g_log = std::fopen(path, "w");
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
		return true;
	}
	virtual void Unload() {
		logf("Unload");
		camera::shutdown();
		unwatchWheel();
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
		g_checkedLayout = false;
		g_po = PortalOffsets{};
		g_portalCount = 0;
		g_colState = 0;
		g_entityCount = 0;
		std::memset(g_lastEntityOrigin, 0, sizeof g_lastEntityOrigin);
		logf("LevelInit %s", g_map);
		worldrender::levelChanged();
	}
	virtual void ServerActivate(void* edictList, int edictCount, int clientMax) {
		g_edicts = static_cast<uint8_t*>(edictList);
		g_inLevel = true;
		void* world = sdk::edictNetworkable(edictList);
		logf("ServerActivate: %d edicts, %d clients, edict0 = %s", edictCount, clientMax,
			world ? sdk::networkableClassName(world) : "(null)");
		hookClientMovement();
	}
	virtual void GameFrame(bool /*simulating*/) {
		if (g_sock == INVALID_SOCKET) {
			return;
		}
		hookClientMovement();
		static bool overlayStarted = false;
		if (!overlayStarted) {
			overlayStarted = true;
			overlay::init(&logf);
			worldrender::init(&logf, g_engineFactory);
		}
		linkPoll();
		sendState();
		camera::init(&logf);
		camera::setMode(following() ? g_mc.cameraMode : 0, g_mc.cameraDistance);
		camera::setHideBody(mcReady()); // Chell -> Steve (worldrender draws him)
		updateMouseCapture();
		watchWheel();
		if (g_inLevel && g_edicts) {
			checkCollideableLayout();
			sendEntities();
		}
	}
	virtual void LevelShutdown() {
		g_inLevel = false;
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
