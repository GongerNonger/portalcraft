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
#include "launcher.h"
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
void dropAppliedShoves(uint32_t ack); // the player puppet, below
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

DamageInfo damageInfo(float damage, int type) {
	DamageInfo info{};
	info.inflictor = info.attacker = info.weapon = 0xFFFFFFFFu; // INVALID_EHANDLE_INDEX
	if (void* unknown = edictInUse(edictAt(1)) ? sdk::edictUnknown(edictAt(1)) : nullptr) {
		// IHandleEntity slot 2, GetRefEHandle: the player's own handle.
		info.inflictor = info.attacker = *sdk::vcall<const uint32_t*>(unknown, 2);
	}
	info.damage = info.maxDamage = info.baseDamage = damage;
	info.damageType = type;
	info.ammoType = -1;
	return info;
}

bool serverToolsReady() {
	if (!g_serverTools) {
		g_serverTools = engineInterface("server.dll", "VSERVERTOOLS002");
		logf("server tools %p", g_serverTools);
	}
	return g_serverTools && g_inLevel && playerBase();
}

// ---- Minecraft owns the player's health (as SkyCraft does) ----------------------------------
// Whatever hurts Portal's player (turrets, energy balls, goo, crushers) is refunded every server
// frame and sent to Minecraft ("PCU1"), where Steve takes it, armor and all. A hit that kills
// Portal's player outright (goo, an energy ball) kills Steve too (flag 1). When Steve dies
// ("PCZ1": a fall, lava, TNT, or the turret damage above), Portal's player is killed here, so
// Portal's own death and checkpoint reload follow. Health is kept at Portal's own maximum, never
// above it, so nothing in Portal that clamps or regenerates health can look like a hit.
struct PlayerOffsets {
	bool ready = false;
	int health = -1, lifeState = -1;
} g_pl;
constexpr int kFullHealth = 100;
float g_hurtPending = 0.0f;
DWORD g_hurtSentAt = 0;
bool g_wasAlive = false;
bool g_killedForMc = false; // Portal's player died because Steve did: don't kill Steve back
bool g_killPending = false;
uint32_t g_mcDeathSeq = 0;

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
	if (!g_inLevel || !edictInUse(e)) {
		g_wasAlive = false;
		return;
	}
	void* networkable = sdk::edictNetworkable(e);
	auto* base = static_cast<uint8_t*>(sdk::networkableBaseEntity(networkable));
	if (!base) {
		return;
	}
	if (!g_pl.ready) {
		g_pl.ready = true;
		auto* sc = static_cast<sdk::ServerClass*>(sdk::networkableServerClass(networkable));
		if (sc && sc->table) {
			g_pl.health = findProp(sc->table, "m_iHealth", 0);
			g_pl.lifeState = findProp(sc->table, "m_lifeState", 0);
		}
		logf("health: %s m_iHealth %d m_lifeState %d", sc ? sc->name : "?", g_pl.health, g_pl.lifeState);
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

void wakeAround(const float* b) {
	if (!serverToolsReady()) {
		return;
	}
	float hx = (b[3] - b[0]) * 0.5f, hy = (b[4] - b[1]) * 0.5f, hz = (b[5] - b[2]) * 0.5f;
	Vector at{b[0] + hx, b[1] + hy, b[2] + hz};
	float radius = std::sqrt(hx * hx + hy * hy + hz * hz) + 32.0f;
	DamageInfo info = damageInfo(0.5f, 0 /* DMG_GENERIC */);
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
	if (!g_inLevel || !g_blockDirty) {
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
				dropAppliedShoves(g_mc.teleportAck);
				// Minecraft just applied a move: its earlier steps are from before it, and playing them
				// back (one step behind) would put the player back where he was (outside the map after a
				// Portal restart; a step back along a fling after a portal, the snap). Keep the timeline
				// but move those steps to where Minecraft is now, so playback starts from the new place.
				for (TickSample& t : g_ticks) {
					if (t.seq != 0) {
						t.pos = g_mc.origin;
					}
				}
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
		} else if ((n == 4 + 32 + 4 || n == 4 + 32 + 4 + 1) && std::memcmp(buf, "PCK1", 4) == 0 && devMode()) {
			std::memcpy(g_fakeKeys, buf + 4, 32);
			uint32_t ms;
			std::memcpy(&ms, buf + 36, 4);
			g_fakeMouse = n == 41 ? uint8_t(buf[40]) : 0;
			g_fakeUntil = GetTickCount() + ms;
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

bool following() {
	return mcReady() && (g_mc.teleportAck == g_teleportSeq || !g_hardPending);
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

void logSolidNear(const Vector& at);
void logHostSolid(const Vector& at, void* playerEntity);

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
				logHostSolid(g_lastSet, player);
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
	// Portal changed the player's velocity since last tick (a trigger_push air current, an
	// env_physexplosion): an impulse, handed to Minecraft with the new velocity. We overwrite the
	// velocity every tick, so without this every push was lost.
	Vector& mvVelocity = *reinterpret_cast<Vector*>(mv + sdk::kMvVelocity);
	bool impulse = false;
	if (g_haveSet && !g_needSync) {
		Vector dv{mvVelocity.x - g_lastSetVelocity.x, mvVelocity.y - g_lastSetVelocity.y, mvVelocity.z - g_lastSetVelocity.z};
		// Only a pure velocity change: a physics shove (which also moves the player) stays a shove.
		impulse = dv.x * dv.x + dv.y * dv.y + dv.z * dv.z > 40.0f * 40.0f && dist(origin, g_lastSet) <= 0.5f;
		static int impulseLogs = 0;
		if (impulse && impulseLogs++ < 40) {
			logf("impulse: Portal changed the velocity by (%.0f %.0f %.0f) to (%.0f %.0f %.0f)", dv.x, dv.y, dv.z, mvVelocity.x, mvVelocity.y,
				mvVelocity.z);
		}
	}

	if (g_needSync || impulse || (g_haveSet && dist(origin, g_lastSet) > 0.5f)) {
		g_teleportSeq++;
		g_teleportOrigin = origin;
		g_teleportVelocity = mvVelocity;
		bool hard = g_needSync || !g_haveSet || dist(origin, g_lastSet) > 24.0f;
		g_teleportKind = hard ? pcproto::kMoveTeleport : impulse ? pcproto::kMoveImpulse : pcproto::kMoveShove;
		if (hard) {
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
				g_needSync ? " [level start]" : "");
		}
		g_needSync = false;
	}

	bool wasDriving = g_drivingNow;
	g_drivingNow = following();
	if (g_drivingNow) {
		quietPortalMovement(mv);
	}
	float zIn = origin.z;
	g_serverOriginal(self, player, mvRaw);
	float zPortal = origin.z;
	if (g_drivingNow) {
		Vector portalWent = origin;
		applyMinecraft(mv);
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

// Dev: what Portal itself finds solid at a spot. A player-hull trace (IEngineTrace::TraceRay, slot 4
// of EngineTraceServer003) from `top` down to `at`: where it stops, and the surface and contents.
void* g_serverTrace = nullptr;
struct alignas(16) TraceRayArgs {
	float start[4], delta[4], startOffset[4], extents[4];
	bool isRay, isSwept;
};
class TraceAll {
public:
	virtual bool ShouldHitEntity(void* entity, int) { return entity != skip; }
	virtual int GetTraceType() { return 0; }
	void* skip = nullptr;
};

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
	s.teleportKind = g_teleportKind;
	if (g_inLevel && g_edicts) {
		fillPortals(s);
	}
	fillCursor(s);
	s.wheel = int8_t(g_wheelDelta / WHEEL_DELTA);
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
		launcher::init(&logf, g_self);
		launcher::frame(false, false, nullptr); // start Minecraft now, while Portal is still on its menu
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
		g_shoveCount = 0;
		g_hardPending = false;
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
		forgetBlockBoxes();
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
		launcher::frame(mcReady(), g_inLevel, g_engineClient);
		bridgeHealth();
		updateBlockPhysics();
		sendState();
		camera::init(&logf);
		camera::setMode(following() ? g_mc.cameraMode : 0, g_mc.cameraDistance);
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
