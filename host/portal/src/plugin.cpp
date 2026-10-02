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
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <intrin.h>

#include "../../../protocol/portalcraft_protocol.h"
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
DWORD g_fakeUntil = 0;

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
		if (n == sizeof(pcproto::McState) && std::memcmp(buf, "PCM1", 4) == 0) {
			bool wasReady = mcReady();
			std::memcpy(&g_mc, buf, sizeof g_mc);
			g_mcTime = GetTickCount();
			if (!wasReady && mcReady()) {
				logf("Minecraft linked");
			}
		} else if (n == 4 + 32 + 4 && std::memcmp(buf, "PCK1", 4) == 0) {
			std::memcpy(g_fakeKeys, buf + 4, 32);
			uint32_t ms;
			std::memcpy(&ms, buf + 36, 4);
			g_fakeUntil = GetTickCount() + ms;
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

bool following() {
	return mcReady() && g_mc.teleportAck == g_teleportSeq;
}

Vector toVec(const pcproto::Vec3& v) {
	return {v.x, v.y, v.z};
}

void applyMinecraft(uint8_t* mv) {
	*reinterpret_cast<Vector*>(mv + sdk::kMvAbsOrigin) = toVec(g_mc.origin);
	*reinterpret_cast<Vector*>(mv + sdk::kMvVelocity) = toVec(g_mc.velocity);
}

void quietPortalMovement(uint8_t* mv) {
	// Minecraft decides jumping, crouching and walking. Portal only crouches when Minecraft sneaks,
	// which lowers Portal's camera the way sneaking lowers Steve's.
	int& buttons = *reinterpret_cast<int*>(mv + sdk::kMvButtons);
	buttons &= ~(sdk::IN_JUMP | sdk::IN_DUCK);
	if (g_mc.sneaking) {
		buttons |= sdk::IN_DUCK;
	}
	*reinterpret_cast<float*>(mv + sdk::kMvForwardMove) = 0;
	*reinterpret_cast<float*>(mv + sdk::kMvSideMove) = 0;
	*reinterpret_cast<float*>(mv + sdk::kMvUpMove) = 0;
}

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

	// Something other than us moved the player since last tick: a portal, a trigger_teleport,
	// or a fresh level. That move is Portal's; give it to Minecraft and wait for the ack.
	if (g_needSync || (g_haveSet && dist(origin, g_lastSet) > 24.0f)) {
		g_teleportSeq++;
		g_teleportOrigin = origin;
		g_teleportVelocity = *reinterpret_cast<Vector*>(mv + sdk::kMvVelocity);
		logf("handing teleport %u to Minecraft: (%.1f %.1f %.1f)%s", g_teleportSeq, origin.x, origin.y, origin.z,
			g_needSync ? " [level start]" : "");
		g_needSync = false;
	}

	g_drivingNow = following();
	if (g_drivingNow) {
		quietPortalMovement(mv);
	}
	g_serverOriginal(self, player, mvRaw);
	if (g_drivingNow) {
		applyMinecraft(mv);
		g_lastSet = origin;
		g_haveSet = true;
	} else {
		g_haveSet = false;
	}
	g_origin = origin;
	g_velocity = *reinterpret_cast<Vector*>(mv + sdk::kMvVelocity);
}

void __fastcall clientProcessMovement(void* self, void* /*edx*/, void* player, void* mvRaw) {
	// Client prediction runs the same movement; give it the same answer so it never disagrees.
	auto* mv = static_cast<uint8_t*>(mvRaw);
	bool drive = following();
	if (drive) {
		quietPortalMovement(mv);
	}
	g_clientOriginal(self, player, mvRaw);
	if (drive) {
		applyMinecraft(mv);
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

// ---- per-frame state out --------------------------------------------------------------

void sendState() {
	pcproto::HostState s{};
	std::memcpy(s.magic, "PCH1", 4);
	s.seq = ++g_hostSeq;
	std::memcpy(s.map, g_map, sizeof s.map);
	if (g_inLevel && g_edicts) {
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
	}
	s.origin = {g_origin.x, g_origin.y, g_origin.z};
	s.velocity = {g_velocity.x, g_velocity.y, g_velocity.z};
	s.teleportSeq = g_teleportSeq;
	s.teleportOrigin = {g_teleportOrigin.x, g_teleportOrigin.y, g_teleportOrigin.z};
	s.teleportVelocity = {g_teleportVelocity.x, g_teleportVelocity.y, g_teleportVelocity.z};
	if (g_inLevel && g_edicts) {
		fillPortals(s);
	}
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
		g_engineServer = interfaceFactory("VEngineServer021", nullptr);
		g_playerInfoMgr = gameServerFactory("PlayerInfoManager002", nullptr);
		void* gm = gameServerFactory("GameMovement001", nullptr);
		logf("Load: engine %p playerinfo %p gamemovement %p", g_engineServer, g_playerInfoMgr, gm);
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
		g_checkedLayout = false;
		g_po = PortalOffsets{};
		g_portalCount = 0;
		logf("LevelInit %s", g_map);
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
		linkPoll();
		sendState();
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
