// PortalCraft host <-> Minecraft link. UDP datagrams on 127.0.0.1, little-endian, packed.
//
//   host plugin  binds 27515, sends HostState to 27516 every server frame (~66 Hz)
//   Minecraft    binds 27516, sends McState  to 27515 every render frame
//   anyone       may send a Command to 27515 (dev console channel)
//
// Coordinates on the wire are the HOST's (Source units, Z up). Minecraft converts:
//   1 block = 40 units;  mc.x = src.x/40,  mc.y = src.z/40,  mc.z = -src.y/40
//   mc.yaw = -src.yaw - 90,  mc.pitch = src.pitch
// Mirrored in Java by dev.portalcraft.link.Proto. Keep both in step and bump the magic on change.
#pragma once

#include <cstdint>

namespace pcproto {

constexpr uint16_t kHostPort = 27515;
constexpr uint16_t kMcPort = 27516;

#pragma pack(push, 1)

struct Vec3 {
	float x, y, z;
};

enum HostFlags : uint32_t {
	kHostInGame = 1u << 0,     // a map is loaded and the player exists
	kHostForeground = 1u << 1, // the game window has focus and no console/menu: keys are live
	kHostDriving = 1u << 2,    // the host is applying McState to its player this tick
};

enum PortalFlags : uint32_t {
	kPortalExists = 1u << 0,
	kPortalActive = 1u << 1, // placed and open
	kPortalLinked = 1u << 2, // its partner is placed too: things can go through
	kPortalSecond = 1u << 3, // the orange one
};

struct HostPortal {
	uint32_t flags;
	Vec3 origin; // centre of the oval, on the surface
	Vec3 angles; // pitch, yaw, roll; forward = surface normal, up = the oval's long axis
};

struct HostState {
	char magic[4]; // "PCH1"
	uint32_t seq;
	uint32_t flags; // HostFlags
	char map[64];
	float yaw, pitch;     // where the host camera looks: Minecraft follows this
	Vec3 origin;          // host player feet, now
	Vec3 velocity;        // units/s
	uint32_t teleportSeq; // bumped whenever the host moves the player itself (portal, level start)
	Vec3 teleportOrigin;  // ... to here
	Vec3 teleportVelocity;
	uint8_t keys[32];     // pressed SDL scancodes 0..255, bit per scancode
	uint8_t mouse;        // bit0 left, bit1 right, bit2 middle
	uint8_t pad[3];
	HostPortal portals[2]; // [0] blue, [1] orange
};

enum McFlags : uint32_t {
	kMcReady = 1u << 0, // in a world with a player: the host may follow origin/velocity
};

struct McState {
	char magic[4]; // "PCM2"
	uint32_t seq;
	uint32_t flags;       // McFlags
	uint32_t teleportAck; // last HostState.teleportSeq Minecraft has applied
	Vec3 origin;          // Minecraft player feet, host units
	Vec3 velocity;        // host units/s
	uint8_t onGround;
	uint8_t sneaking;
	uint8_t holdingPortalGun;
	uint8_t pad;
	// Minecraft's last two physics steps (20 Hz), host units. The host interpolates between them
	// on its own clock, starting when a new tickSeq arrives (sent the moment the tick ends), so the
	// player moves the same amount every host tick instead of whatever the last render frame had.
	Vec3 tickPrevious;
	Vec3 tickCurrent;
	uint32_t tickSeq;
};

struct Command {
	char magic[4]; // "PCC1"
	char text[252]; // a console command, NUL-terminated
};

// ---- solid entities: doors, buttons, lifts, cubes, toggling walls -------------------------------
// HostEntities is sent ~16 times a second (UDP, to kMcPort) with every solid, non-trigger entity
// near the player. Minecraft builds collision for each from its model: "*N" is the map's brush
// model N (positioned by origin/angles); a .mdl is a physics model (solid == 6) or its box.
enum EntityFlags : uint8_t {
	kEntityStatic = 1u << 0, // hasn't moved since the last packet
};

struct HostEntity {
	uint16_t index; // edict index, stable while the entity lives
	uint8_t solid;  // SolidType_t: 1 BSP, 2 BBOX, 3/4 OBB, 6 VPHYSICS
	uint8_t flags;  // EntityFlags
	Vec3 origin;    // world space (works for parented parts too)
	Vec3 angles;
	Vec3 mins, maxs; // collision bounds in the entity's own space
	char model[56];  // "*12" or "models/props/portal_button.mdl"
};

constexpr uint32_t kMaxHostEntities = 128;

struct HostEntities {
	char magic[4]; // "PCE1"
	uint32_t seq;
	uint32_t count;
	HostEntity entities[kMaxHostEntities]; // only `count` are sent
};

// ---- overlay: Minecraft's hand + HUD, drawn by the host on top of its frame ---------------------
// A named shared-memory mapping created by the host: Local\PortalCraft_Overlay_v1.
//   [0, 4096)                      OverlayHeader
//   [4096 + i * kOverlaySlotBytes] slot i of kOverlaySlots, RGBA8, rows bottom-up (OpenGL order)
// Minecraft writes a slot that is neither `front` nor `reading`, then publishes it by storing its
// size and finally `front` and `frameSeq`. The host sets `reading` to the slot it is uploading.
constexpr const char* kOverlayMapping = "Local\\PortalCraft_Overlay_v1";
constexpr uint32_t kOverlayMaxW = 2560;
constexpr uint32_t kOverlayMaxH = 1440;
constexpr uint32_t kOverlaySlots = 3;
constexpr uint32_t kOverlaySlotBytes = kOverlayMaxW * kOverlayMaxH * 4;
constexpr uint32_t kOverlayHeaderBytes = 4096;
constexpr uint32_t kOverlayBytes = kOverlayHeaderBytes + kOverlaySlots * kOverlaySlotBytes;

struct OverlayHeader {
	char magic[4];           // "PCO1", written by the host
	uint32_t hostWidth;      // host back buffer size: Minecraft sizes its window to match
	uint32_t hostHeight;
	volatile uint32_t front;    // newest complete slot (Minecraft)
	volatile uint32_t reading;  // slot the host is uploading now (host)
	volatile uint32_t frameSeq; // bumped by Minecraft after each publish; 0 = nothing yet
	uint32_t slotWidth[kOverlaySlots];
	uint32_t slotHeight[kOverlaySlots];
	volatile uint32_t mcHeartbeat; // GetTickCount-ish millis from Minecraft; stale = draw nothing
};

// ---- world: blocks the player placed in Minecraft, drawn by the host in its own 3D pass ---------
// A second named mapping created by the host: Local\PortalCraft_World_v1.
//   [0, 4096)                                  WorldHeader
//   [kWorldAtlasOffset, + kWorldAtlasBytes)    Minecraft's block atlas, RGBA8, rows TOP-DOWN, atlasWidth x atlasHeight
//   [kWorldMeshOffset + i * kWorldSlotBytes)   mesh slot i of 2: WorldVertex[slotSolid[i] + slotTranslucent[i]]
// A mesh is a triangle list (6 vertices per quad) in host space (Source units), solid/cutout
// triangles first, then translucent ones. Minecraft writes a slot that is neither `front` nor
// `reading`, sets its counts, then `front`, then bumps `meshSeq`. The atlas is written once (and
// again after a resource reload): size first, pixels, then `atlasSeq`.
constexpr const char* kWorldMapping = "Local\\PortalCraft_World_v1";
constexpr uint32_t kWorldAtlasMaxW = 2048;
constexpr uint32_t kWorldAtlasMaxH = 2048;
constexpr uint32_t kWorldAtlasOffset = 4096;
constexpr uint32_t kWorldAtlasBytes = kWorldAtlasMaxW * kWorldAtlasMaxH * 4;
constexpr uint32_t kWorldMaxVertices = 196608; // 32768 quads
constexpr uint32_t kWorldMeshOffset = kWorldAtlasOffset + kWorldAtlasBytes;
constexpr uint32_t kWorldSlotBytes = kWorldMaxVertices * 24;
constexpr uint32_t kWorldBytes = kWorldMeshOffset + 2 * kWorldSlotBytes;

struct WorldVertex { // matches D3DFVF_XYZ | D3DFVF_DIFFUSE | D3DFVF_TEX1
	float x, y, z;  // host world space, Source units
	uint32_t color; // D3DCOLOR 0xAARRGGBB: tint x ambient occlusion x face shade
	float u, v;     // into the atlas, 0..1, v down
};

struct WorldHeader {
	char magic[4];            // "PCW1", written by the host
	uint32_t atlasWidth;      // Minecraft
	uint32_t atlasHeight;
	volatile uint32_t atlasSeq; // bumped by Minecraft after the atlas pixels are written; 0 = none
	volatile uint32_t meshSeq;  // bumped by Minecraft after each mesh publish; 0 = none
	volatile uint32_t front;    // newest complete mesh slot (Minecraft)
	volatile uint32_t reading;  // slot the host is drawing from now, 0xFFFFFFFF = none (host)
	uint32_t slotSolid[2];       // solid + cutout vertices in each slot (drawn with alpha test)
	uint32_t slotTranslucent[2]; // translucent vertices after them (drawn blended, no depth write)
};

#pragma pack(pop)

static_assert(sizeof(WorldVertex) == 24, "WorldVertex layout");
static_assert(sizeof(HostEntity) == 108, "HostEntity layout");
static_assert(sizeof(HostPortal) == 28, "HostPortal layout");
static_assert(sizeof(HostState) == 228, "HostState layout");
static_assert(sizeof(McState) == 72, "McState layout");

} // namespace pcproto
