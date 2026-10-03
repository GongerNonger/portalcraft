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

#include <cstddef>
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
	kHostScripted = 1u << 3,   // a scripted scene has the player (a point_viewcontrol camera, frozen): Minecraft follows, no input
	kHostGun = 1u << 4,        // Portal's player has its portal gun and Portal draws it (Steve holds the Minecraft one): Minecraft hides its own
};

// What the host's latest move (teleportSeq) is. A shove only places the player; an impulse
// (trigger_push air currents, explosions) also sets its velocity; a teleport (portals, level start)
// places it and replaces its velocity, which the host has carried through the portal.
enum TeleportKind : uint8_t {
	kMoveShove = 0,
	kMoveImpulse = 1,
	kMoveTeleport = 2,
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
	char magic[4]; // "PCH2"
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
	int8_t wheel;         // mouse-wheel notches so far, wrapping (up is positive): Minecraft scrolls by the change
	uint8_t teleportKind; // TeleportKind of the move teleportSeq names
	uint8_t pad;
	HostPortal portals[2]; // [0] blue, [1] orange
	// PCH2: the OS cursor over the host's window, 0..1 across its client area (top-left origin), or
	// -1 when there is none. Only meaningful while Minecraft has a screen open (McFlags kMcScreen):
	// the host then lets go of the mouse, so the player points at Minecraft's inventory with it.
	float cursorX, cursorY;
};

enum McFlags : uint32_t {
	kMcReady = 1u << 0,  // in a world with a player: the host may follow origin/velocity
	kMcScreen = 1u << 1, // a Minecraft screen (inventory, chest, chat) is open: the host frees the mouse
};

struct McState {
	char magic[4]; // "PCM3"
	uint32_t seq;
	uint32_t flags;       // McFlags
	uint32_t teleportAck; // last HostState.teleportSeq Minecraft has applied
	Vec3 origin;          // Minecraft player feet, host units
	Vec3 velocity;        // host units/s
	uint8_t onGround;
	uint8_t sneaking;
	uint8_t holdingPortalGun;
	uint8_t cameraMode; // Minecraft's F5 view: 0 first person, 1 third person behind, 2 third person in front
	// Minecraft's last two physics steps (20 Hz), host units. The host interpolates between them
	// on its own clock, starting when a new tickSeq arrives (sent the moment the tick ends), so the
	// player moves the same amount every host tick instead of whatever the last render frame had.
	Vec3 tickPrevious;
	Vec3 tickCurrent;
	uint32_t tickSeq;
	// PCM3: in third person, how far Minecraft's own camera gets before its blocks or the host's
	// walls stop it (host units; Camera.getMaxZoom). The host's camera stops at the nearer of this
	// and its own trace, so it never passes into blocks Steve placed (which the host can't trace).
	float cameraDistance;
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

// ---- Minecraft's blasts and hits, for the host's props (Minecraft -> host) ------------------------
// Minecraft's explosions become blasts in the host (cubes thrown, turrets knocked over), and its
// projectiles and punches that land on a host entity become a hit on it. Host coordinates.

struct McBlast {
	char magic[4]; // "PCB1"
	Vec3 origin;
	float radius; // units
	float damage; // at the centre; also what drives the push
};

struct McHit {
	char magic[4]; // "PCI1"
	uint32_t index; // HostEntity::index (edict)
	Vec3 point;     // where it landed
	Vec3 force;     // physics force along the hit
	float damage;
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

// ---- world: Minecraft's blocks and dropped items, drawn by the host in its own 3D pass ----------
// A second named mapping created by the host: Local\PortalCraft_World_v1.
//   [0, 4096)                                         WorldHeader
//   [kWorldAtlasOffset, + kWorldAtlasBytes)           Minecraft's block atlas, RGBA8, rows TOP-DOWN, atlasWidth x atlasHeight
//   [kWorldMeshOffset + i * kWorldSlotBytes)          block mesh slot i of 2: WorldVertex[slotSolid[i] + slotTranslucent[i]]
//   [kWorldItemAtlasOffset, + kWorldItemAtlasBytes)   Minecraft's item atlas, RGBA8, rows TOP-DOWN, itemAtlasWidth x itemAtlasHeight
//   [kWorldEntityOffset + i * kWorldEntitySlotBytes)  entity mesh slot i of 2 (see below)
// The block mesh is a triangle list (6 vertices per quad) in host space (Source units), solid/cutout
// triangles first, then translucent ones. Minecraft writes a slot that is neither `front` nor
// `reading`, sets its counts, then `front`, then bumps `meshSeq`. Each atlas is written once (and
// again after a resource reload): size first, pixels, then its seq.
//
// The entity mesh (dropped items; mobs later) is rebuilt by Minecraft every render frame, with the
// same slot rules on entityFront / entityReading / entitySeq. A slot holds four consecutive ranges:
// block-atlas solid, block-atlas translucent, item-atlas solid, item-atlas translucent, of
// entityBlockSolid, entityBlockTranslucent, entityItemSolid, entityItemTranslucent vertices. Solid
// ranges are drawn alpha tested, translucent ones blended, like the block mesh.
//
// PCW2 appended its fields to WorldHeader and its regions after the block mesh slots: every PCW1
// offset is unchanged.
//
// PCW3 adds the player's own avatar (Steve, posed, with what he holds) for third person (F5): five
// more ranges after the four entity ranges in each entity slot, avatarSkin (the skin texture, alpha
// tested) and then the same block/item atlas split as the entity ranges. Avatar vertices are
// RELATIVE to the player's feet (host units, already rotated to the world): the host adds the
// position it renders its own player at, so the body never lags or leads the camera. The skin is a
// third texture, written like the atlases (size, pixels, then skinSeq):
//   [kWorldSkinOffset, + kWorldSkinBytes)             the player's skin, RGBA8, rows TOP-DOWN, skinWidth x skinHeight
//
// PCW4 adds particles and block-breaking cracks: three more ranges after the avatar's, in world
// space like the entity ranges. particleSolid / particleTranslucent are textured from Minecraft's
// particle atlas (block-debris particles use the block atlas and go in the entity block ranges);
// crack is the breaking overlay, drawn multiplied over what is behind it (Minecraft's crumbling
// blend), textured from the crack strip: the ten destroy stages side by side, u = (stage + s) / 10.
//   [kWorldParticleAtlasOffset, + kWorldParticleAtlasBytes)  the particle atlas, RGBA8, rows TOP-DOWN
//   [kWorldCrackOffset, + kWorldCrackBytes)                    the crack strip, RGBA8, rows TOP-DOWN
//
// PCW5 adds mobs (and other players): Minecraft packs the entity textures it meets (pig, sheep,
// armour layers, skins) into the mob atlas and remaps each model's UVs into it; two ranges after
// the cracks, world space, solid then translucent.
//   [kWorldMobAtlasOffset, + kWorldMobAtlasBytes)              the mob atlas, RGBA8, rows TOP-DOWN, mobAtlasWidth x mobAtlasHeight
//
// PCW6: the avatar's own armour and cape, textured from the mob atlas and feet-relative like the
// rest of the avatar: two more ranges after the mob ranges, solid then translucent.
constexpr const char* kWorldMapping = "Local\\PortalCraft_World_v1";
constexpr uint32_t kWorldAtlasMaxW = 2048;
constexpr uint32_t kWorldAtlasMaxH = 2048;
constexpr uint32_t kWorldAtlasOffset = 4096;
constexpr uint32_t kWorldAtlasBytes = kWorldAtlasMaxW * kWorldAtlasMaxH * 4;
constexpr uint32_t kWorldMaxVertices = 196608; // 32768 quads
constexpr uint32_t kWorldMeshOffset = kWorldAtlasOffset + kWorldAtlasBytes;
constexpr uint32_t kWorldSlotBytes = kWorldMaxVertices * 24;
// Vanilla 26.3's item atlas is 1024x512. Room for twice that (1024x1024 or 2048x512), since mods and
// resource packs grow an atlas by doubling one side; each side is capped too, for the texture.
constexpr uint32_t kWorldItemAtlasMaxW = 2048;
constexpr uint32_t kWorldItemAtlasMaxH = 2048;
constexpr uint32_t kWorldItemAtlasMaxPixels = 1024 * 1024;
constexpr uint32_t kWorldItemAtlasOffset = kWorldMeshOffset + 2 * kWorldSlotBytes;
constexpr uint32_t kWorldItemAtlasBytes = kWorldItemAtlasMaxPixels * 4;
// A flat item's extruded model is roughly 50-150 quads and a stack draws up to 5 copies, so this is
// some 20-70 full stacks of tools, or ~1800 single blocks. Minecraft keeps the nearest that fit.
constexpr uint32_t kWorldEntityMaxVertices = 65536;
constexpr uint32_t kWorldEntityOffset = kWorldItemAtlasOffset + kWorldItemAtlasBytes;
constexpr uint32_t kWorldEntitySlotBytes = kWorldEntityMaxVertices * 24;
constexpr uint32_t kWorldPcw2Bytes = kWorldEntityOffset + 2 * kWorldEntitySlotBytes;
constexpr uint32_t kWorldSkinMaxW = 256; // 64x64 vanilla; room for HD skins
constexpr uint32_t kWorldSkinMaxH = 256;
constexpr uint32_t kWorldSkinOffset = kWorldPcw2Bytes;
constexpr uint32_t kWorldSkinBytes = kWorldSkinMaxW * kWorldSkinMaxH * 4;
constexpr uint32_t kWorldPcw3Bytes = kWorldSkinOffset + kWorldSkinBytes;
constexpr uint32_t kWorldParticleAtlasMaxW = 2048;
constexpr uint32_t kWorldParticleAtlasMaxH = 2048;
constexpr uint32_t kWorldParticleAtlasMaxPixels = 1024 * 1024;
constexpr uint32_t kWorldParticleAtlasOffset = kWorldPcw3Bytes;
constexpr uint32_t kWorldParticleAtlasBytes = kWorldParticleAtlasMaxPixels * 4;
constexpr uint32_t kWorldCrackMaxW = 512; // ten 16-pixel stages; room for 32-pixel resource packs
constexpr uint32_t kWorldCrackMaxH = 64;
constexpr uint32_t kWorldCrackOffset = kWorldParticleAtlasOffset + kWorldParticleAtlasBytes;
constexpr uint32_t kWorldCrackBytes = kWorldCrackMaxW * kWorldCrackMaxH * 4;
constexpr uint32_t kWorldPcw4Bytes = kWorldCrackOffset + kWorldCrackBytes;
constexpr uint32_t kWorldMobAtlasMaxW = 1024;
constexpr uint32_t kWorldMobAtlasMaxH = 1024;
constexpr uint32_t kWorldMobAtlasOffset = kWorldPcw4Bytes;
constexpr uint32_t kWorldMobAtlasBytes = kWorldMobAtlasMaxW * kWorldMobAtlasMaxH * 4;
constexpr uint32_t kWorldBytes = kWorldMobAtlasOffset + kWorldMobAtlasBytes;

struct WorldVertex { // matches D3DFVF_XYZ | D3DFVF_DIFFUSE | D3DFVF_TEX1
	float x, y, z;  // host world space, Source units
	uint32_t color; // D3DCOLOR 0xAARRGGBB: tint x ambient occlusion x face shade
	float u, v;     // into the atlas, 0..1, v down
};

struct WorldHeader {
	char magic[4];            // "PCW6", written by the host
	uint32_t atlasWidth;      // Minecraft
	uint32_t atlasHeight;
	volatile uint32_t atlasSeq; // bumped by Minecraft after the atlas pixels are written; 0 = none
	volatile uint32_t meshSeq;  // bumped by Minecraft after each mesh publish; 0 = none
	volatile uint32_t front;    // newest complete mesh slot (Minecraft)
	volatile uint32_t reading;  // slot the host is drawing from now, 0xFFFFFFFF = none (host)
	uint32_t slotSolid[2];       // solid + cutout vertices in each slot (drawn with alpha test)
	uint32_t slotTranslucent[2]; // translucent vertices after them (drawn blended, no depth write)
	// ---- PCW2: item atlas and entity mesh ----
	uint32_t itemAtlasWidth; // Minecraft
	uint32_t itemAtlasHeight;
	volatile uint32_t itemAtlasSeq;  // bumped by Minecraft after the item atlas pixels are written; 0 = none
	volatile uint32_t entitySeq;     // bumped by Minecraft after each entity mesh publish; 0 = none
	volatile uint32_t entityFront;   // newest complete entity slot (Minecraft)
	volatile uint32_t entityReading; // entity slot the host is drawing from now, 0xFFFFFFFF = none (host)
	uint32_t entityBlockSolid[2];       // block-atlas vertices first, alpha tested
	uint32_t entityBlockTranslucent[2]; // then block-atlas blended
	uint32_t entityItemSolid[2];        // then item-atlas alpha tested
	uint32_t entityItemTranslucent[2];  // then item-atlas blended
	// ---- PCW3: the player's avatar (third person) ----
	uint32_t skinWidth; // Minecraft
	uint32_t skinHeight;
	volatile uint32_t skinSeq;          // bumped by Minecraft after the skin pixels are written; 0 = none
	uint32_t avatarSkin[2];             // after the entity ranges: skin-textured, alpha tested
	uint32_t avatarBlockSolid[2];       // then held block-atlas items, alpha tested
	uint32_t avatarBlockTranslucent[2]; // then block-atlas blended
	uint32_t avatarItemSolid[2];        // then item-atlas alpha tested
	uint32_t avatarItemTranslucent[2];  // then item-atlas blended
	// ---- PCW4: particles and cracks ----
	uint32_t particleAtlasWidth; // Minecraft
	uint32_t particleAtlasHeight;
	volatile uint32_t particleAtlasSeq; // bumped after the particle atlas pixels are written; 0 = none
	uint32_t crackWidth;
	uint32_t crackHeight;
	volatile uint32_t crackSeq;     // bumped after the crack strip is written; 0 = none
	uint32_t particleSolid[2];      // after the avatar ranges: particle atlas, alpha tested
	uint32_t particleTranslucent[2]; // then particle atlas, blended
	uint32_t crack[2];              // then the breaking overlay, multiplied
	// ---- PCW5: mobs ----
	uint32_t mobAtlasWidth; // Minecraft
	uint32_t mobAtlasHeight;
	volatile uint32_t mobAtlasSeq; // bumped after the mob atlas pixels are written; 0 = none
	uint32_t mobSolid[2];          // after the cracks: mob atlas, alpha tested
	uint32_t mobTranslucent[2];    // then mob atlas, blended
	// ---- PCW6: the avatar's armour and cape ----
	uint32_t avatarMobSolid[2];       // after the mob ranges: mob atlas, feet-relative, alpha tested
	uint32_t avatarMobTranslucent[2]; // then mob atlas, feet-relative, blended
};

#pragma pack(pop)

static_assert(sizeof(WorldVertex) == 24, "WorldVertex layout");
static_assert(sizeof(WorldHeader) == 244, "WorldHeader layout");
static_assert(offsetof(WorldHeader, avatarMobSolid) == 228, "WorldHeader PCW5 offsets unchanged");
static_assert(offsetof(WorldHeader, avatarMobTranslucent) == 236, "WorldHeader layout");
static_assert(offsetof(WorldHeader, mobAtlasWidth) == 200, "WorldHeader PCW4 offsets unchanged");
static_assert(offsetof(WorldHeader, mobSolid) == 212, "WorldHeader layout");
static_assert(offsetof(WorldHeader, mobTranslucent) == 220, "WorldHeader layout");
static_assert(offsetof(WorldHeader, particleAtlasWidth) == 152, "WorldHeader PCW3 offsets unchanged");
static_assert(offsetof(WorldHeader, crackSeq) == 172, "WorldHeader layout");
static_assert(offsetof(WorldHeader, particleSolid) == 176, "WorldHeader layout");
static_assert(offsetof(WorldHeader, crack) == 192, "WorldHeader layout");
static_assert(offsetof(WorldHeader, skinWidth) == 100, "WorldHeader PCW2 offsets unchanged");
static_assert(offsetof(WorldHeader, skinSeq) == 108, "WorldHeader layout");
static_assert(offsetof(WorldHeader, avatarSkin) == 112, "WorldHeader layout");
static_assert(offsetof(WorldHeader, avatarBlockSolid) == 120, "WorldHeader layout");
static_assert(offsetof(WorldHeader, avatarBlockTranslucent) == 128, "WorldHeader layout");
static_assert(offsetof(WorldHeader, avatarItemSolid) == 136, "WorldHeader layout");
static_assert(offsetof(WorldHeader, avatarItemTranslucent) == 144, "WorldHeader layout");
static_assert(offsetof(WorldHeader, slotTranslucent) == 36, "WorldHeader PCW1 offsets unchanged");
static_assert(offsetof(WorldHeader, itemAtlasWidth) == 44, "WorldHeader layout");
static_assert(offsetof(WorldHeader, itemAtlasSeq) == 52, "WorldHeader layout");
static_assert(offsetof(WorldHeader, entitySeq) == 56, "WorldHeader layout");
static_assert(offsetof(WorldHeader, entityFront) == 60, "WorldHeader layout");
static_assert(offsetof(WorldHeader, entityReading) == 64, "WorldHeader layout");
static_assert(offsetof(WorldHeader, entityBlockSolid) == 68, "WorldHeader layout");
static_assert(offsetof(WorldHeader, entityBlockTranslucent) == 76, "WorldHeader layout");
static_assert(offsetof(WorldHeader, entityItemSolid) == 84, "WorldHeader layout");
static_assert(offsetof(WorldHeader, entityItemTranslucent) == 92, "WorldHeader layout");
static_assert(sizeof(WorldHeader) <= kWorldAtlasOffset, "WorldHeader fits its page");
static_assert(kWorldMeshOffset == 16781312 && kWorldSlotBytes == 4718592, "PCW1 offsets unchanged");
static_assert(kWorldItemAtlasOffset == 26218496, "world layout"); // where the PCW1 mapping ended
static_assert(kWorldEntityOffset == 30412800, "world layout");
static_assert(kWorldEntitySlotBytes == 1572864, "world layout");
static_assert(kWorldPcw2Bytes == 33558528, "world layout"); // where the PCW2 mapping ended
static_assert(kWorldPcw3Bytes == 33820672, "world layout");
static_assert(kWorldCrackOffset == 38014976, "world layout");
static_assert(kWorldPcw4Bytes == 38146048, "world layout");
static_assert(kWorldBytes == 42340352, "world layout");

static_assert(sizeof(HostEntity) == 108, "HostEntity layout");
static_assert(sizeof(HostPortal) == 28, "HostPortal layout");
static_assert(sizeof(HostState) == 236, "HostState layout");
static_assert(sizeof(McState) == 76, "McState layout");
static_assert(sizeof(McBlast) == 24, "McBlast layout");
static_assert(sizeof(McHit) == 36, "McHit layout");

} // namespace pcproto
