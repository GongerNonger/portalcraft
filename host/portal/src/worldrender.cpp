// Draws Minecraft's placed blocks and dropped items inside Portal's own 3D scene.
//
// Minecraft publishes two triangle meshes (host space) and the block and item atlases through
// shared memory (protocol: WorldHeader): the block mesh when blocks change, the entity mesh every
// frame. We hook IVRenderView::SceneEnd, which the client calls when a view's 3D world is done but
// before the view model and HUD: the device's render target and depth buffer still hold the scene,
// so blocks and items are hidden behind Portal's walls and in front of nothing they shouldn't be.
// The camera comes from IVEngineClient::WorldToScreenMatrix.
//
// In third person (Minecraft's F5, see camera.cpp) the player's avatar is drawn too: its vertices
// are relative to the player's feet, and we place them where Portal draws its player this frame.
//
// Views through portals: Portal draws each one as a nested 3D view (IVRenderView::Push3DView ...
// PopView) inside the main view, its camera moved through the portal pair, with the stencil
// buffer limiting it to the portal's oval. We follow the view stack and, when a view whose camera
// differs from its parent's is popped, draw everything (blocks, items, and the avatar even in first
// person, since that is how you see yourself) with that view's own matrices and Portal's stencil
// left on. Slots, checked in build 19017868's engine.dll (MSVC puts the 5-argument Push3DView
// overload first): 37 Push3DView(+depth), 38 Push3DView, 39 Push2DView, 40 PopView,
// 50 GetMatricesForView.
//
// Two passes per view. Glass and other see-through surfaces don't write depth, so anything drawn
// after them lands on top: our solid triangles go in just before the view's first translucent
// world surface (IVRenderView::DrawTranslucentSurfaces, slot 18), and our translucent ones at the
// view's end. A view with no translucent surfaces gets both at the end.
//
// Portal's lighting: every Minecraft triangle is tinted by what Portal would light a model with at
// that spot, facing that way: IVEngineClient::ComputeLighting (slot 67; the ambient cube from the
// map's light probes plus its nearby lights), the same lighting Portal gives its cubes and turrets.
// Cached per 8-unit grid point and face direction (Portal's lighting is baked); the block mesh is lit
// once per publish, the entity mesh when it changes. That is what makes the blocks sit in the
// chamber's cool light and darken in its shadows instead of glowing at full brightness.

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <d3d9.h>

#include <cmath>
#include <cstdint>
#include <cstring>
#include <unordered_map>
#include <vector>

#include "../../../protocol/portalcraft_protocol.h"
#include "camera.h"
#include "overlay.h"
#include "sdk.h"
#include "worldrender.h"

namespace worldrender {
namespace {

constexpr uint32_t kNoSlot = 0xFFFFFFFFu;

overlay::LogFn g_log = nullptr;
uint8_t* g_shm = nullptr;
pcproto::WorldHeader* g_header = nullptr;
void* g_engineClient = nullptr;

IDirect3DTexture9* g_atlas = nullptr;
uint32_t g_atlasSeq = 0;
IDirect3DTexture9* g_itemAtlas = nullptr;
uint32_t g_itemAtlasSeq = 0;
IDirect3DTexture9* g_skin = nullptr;
uint32_t g_skinSeq = 0;
IDirect3DTexture9* g_particleAtlas = nullptr;
uint32_t g_particleAtlasSeq = 0;
IDirect3DTexture9* g_cracks = nullptr;
uint32_t g_cracksSeq = 0;
IDirect3DTexture9* g_mobAtlas = nullptr;
uint32_t g_mobAtlasSeq = 0;
bool g_loggedAvatar = false;
void* g_renderView = nullptr;

// The 3D view stack, as Portal pushes it this frame.
struct ViewEntry {
	const uint8_t* setup; // the CViewSetup being drawn (alive until its PopView)
	sdk::Vector origin;
	bool throughPortal;   // draw our meshes into it when it is popped
	bool isMain;          // the frame's first view straight to the screen: the player's own
	bool solidDone;       // our solid pass is in (before its glass)
};
bool g_mainSolidDone = false;

// ---- Portal's lighting ----------------------------------------------------------------------

std::unordered_map<uint64_t, uint32_t> g_lightCache; // cell + face -> 0x00RRGGBB tint
std::vector<pcproto::WorldVertex> g_litBlocks, g_litEntities;
uint32_t g_litBlocksSeq = 0, g_litBlocksSlot = kNoSlot, g_litEntitySeq = 0, g_litEntitySlot = kNoSlot;
sdk::Vector g_litFeet{};

// Linear light -> a multiplier for the gamma-space vertex colour. ComputeLighting's values are
// model lighting before Portal's overbright and tone mapping (a lit chamber face reads ~0.1-0.3),
// so they're scaled by an exposure first (tunable live: fake_mc.py --exposure).
float g_exposure = 1.6f;
// How much of a face's light is its own direction; the rest is the average of the ambient cube's six
// sides. Portal's cube is very directional (lit side ~0.7, shadow side ~0.08), and Minecraft's own face
// shading is already in the vertex colours, so taken straight it made unlit faces black.
constexpr float kDirectional = 0.45f;
// Bounce light the ambient cube misses: Portal's white walls fill shadowed sides far more than its
// light probes say (a face turned from the lights read 0.08, next to a wall that looks half lit).
// Added after exposure, in the probes' own hue, so shadow sides stay readable and keep the tint.
constexpr float kBounceLift = 0.15f;
constexpr float kLightFloor = 0.06f;
int g_lightSamplesLogged = 0;

uint8_t toTint(float linear) {
	float c = linear > 0.0f ? std::pow(linear, 1.0f / 2.2f) : 0.0f;
	c = c < kLightFloor ? kLightFloor : c > 1.0f ? 1.0f : c;
	return uint8_t(c * 255.0f + 0.5f);
}

// Exposed light -> tint, scaled down as a whole when a channel would clip, so a bright face keeps
// the chamber's blue cast instead of going white.
uint32_t tintOf(sdk::Vector c) {
	if (g_exposure <= 0.0f) {
		return 0xFFFFFF; // lighting off: Minecraft's own brightness
	}
	c = {c.x * g_exposure, c.y * g_exposure, c.z * g_exposure};
	float peak = c.x > c.y ? (c.x > c.z ? c.x : c.z) : (c.y > c.z ? c.y : c.z);
	if (peak > 1.0f) {
		c = {c.x / peak, c.y / peak, c.z / peak};
	}
	return uint32_t(toTint(c.x)) << 16 | uint32_t(toTint(c.y)) << 8 | toTint(c.z);
}

uint32_t lightTint(const sdk::Vector& p, const sdk::Vector& n) {
	int face = 0;
	float ax = std::fabs(n.x), ay = std::fabs(n.y), az = std::fabs(n.z);
	if (ax >= ay && ax >= az) {
		face = n.x >= 0 ? 0 : 1;
	} else if (ay >= az) {
		face = n.y >= 0 ? 2 : 3;
	} else {
		face = n.z >= 0 ? 4 : 5;
	}
	int64_t cx = int64_t(std::floor(p.x / 8.0f + 0.5f)) & 0xFFFFF, cy = int64_t(std::floor(p.y / 8.0f + 0.5f)) & 0xFFFFF,
		cz = int64_t(std::floor(p.z / 8.0f + 0.5f)) & 0xFFFFF;
	uint64_t key = uint64_t(cx) | uint64_t(cy) << 20 | uint64_t(cz) << 40 | uint64_t(face) << 60;
	auto it = g_lightCache.find(key);
	if (it != g_lightCache.end()) {
		return it->second;
	}
	static const sdk::Vector kAxes[6] = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
	const sdk::Vector& axis = kAxes[face];
	sdk::Vector at = p; // the caller put it in front of the face
	sdk::Vector color{1.0f, 1.0f, 1.0f};
	sdk::Vector box[6] = {};
	bool haveBox = false;
	if (g_engineClient) {
		__try {
			sdk::vcall<void>(g_engineClient, 67, static_cast<const sdk::Vector*>(&at), static_cast<const sdk::Vector*>(&axis), true,
				static_cast<sdk::Vector*>(&color), static_cast<sdk::Vector*>(box));
			haveBox = true;
		} __except (EXCEPTION_EXECUTE_HANDLER) {
			color = {1.0f, 1.0f, 1.0f};
		}
	}
	if (haveBox) {
		sdk::Vector avg{};
		for (const sdk::Vector& b : box) {
			avg = {avg.x + b.x / 6.0f, avg.y + b.y / 6.0f, avg.z + b.z / 6.0f};
		}
		color = {color.x * kDirectional + avg.x * (1.0f - kDirectional), color.y * kDirectional + avg.y * (1.0f - kDirectional),
			color.z * kDirectional + avg.z * (1.0f - kDirectional)};
		float peak = avg.x > avg.y ? (avg.x > avg.z ? avg.x : avg.z) : (avg.y > avg.z ? avg.y : avg.z);
		if (peak > 1e-4f && g_exposure > 0.0f) {
			float lift = kBounceLift / g_exposure / peak; // tintOf multiplies by the exposure
			color = {color.x + avg.x * lift, color.y + avg.y * lift, color.z + avg.z * lift};
		}
	}
	uint32_t tint = tintOf(color);
	if (g_lightSamplesLogged < 12 && g_log) {
		g_lightSamplesLogged++;
		g_log("world: Portal light at (%.0f %.0f %.0f) facing %d: (%.3f %.3f %.3f) -> tint %06X", at.x, at.y, at.z, face, color.x, color.y,
			color.z, tint);
	}
	g_lightCache.emplace(key, tint);
	return tint;
}

uint32_t modulate(uint32_t color, uint32_t tint) {
	uint32_t r = ((color >> 16) & 0xFF) * ((tint >> 16) & 0xFF) / 255, g = ((color >> 8) & 0xFF) * ((tint >> 8) & 0xFF) / 255,
		b = (color & 0xFF) * (tint & 0xFF) / 255;
	return (color & 0xFF000000u) | r << 16 | g << 8 | b;
}

// Appends `count` vertices of `src` to `out`, each vertex tinted by Portal's light just in front of
// it (plus `offset`, for the avatar's feet-relative vertices) facing its triangle's normal. Per
// vertex, so a face shades smoothly and the two triangles of a quad never disagree (lit per
// triangle, they made a checkerboard). The sample is pulled a little in from the corner, which can
// sit right on a Portal wall, and taken a few units out in front of the face.
void appendLit(std::vector<pcproto::WorldVertex>& out, const pcproto::WorldVertex* src, uint32_t count, const sdk::Vector& offset) {
	size_t base = out.size();
	out.insert(out.end(), src, src + count);
	for (uint32_t i = 0; i + 2 < count; i += 3) {
		pcproto::WorldVertex* t = out.data() + base + i;
		float ux = t[1].x - t[0].x, uy = t[1].y - t[0].y, uz = t[1].z - t[0].z;
		float vx = t[2].x - t[0].x, vy = t[2].y - t[0].y, vz = t[2].z - t[0].z;
		sdk::Vector n{uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx};
		float len = std::sqrt(n.x * n.x + n.y * n.y + n.z * n.z);
		if (!(len > 1e-6f)) {
			continue;
		}
		n = {n.x / len, n.y / len, n.z / len};
		float cx = (t[0].x + t[1].x + t[2].x) / 3.0f, cy = (t[0].y + t[1].y + t[2].y) / 3.0f, cz = (t[0].z + t[1].z + t[2].z) / 3.0f;
		for (int k = 0; k < 3; k++) {
			float dx = cx - t[k].x, dy = cy - t[k].y, dz = cz - t[k].z;
			float d = std::sqrt(dx * dx + dy * dy + dz * dz);
			float in = d > 2.0f ? 2.0f / d : 0.0f;
			sdk::Vector at{t[k].x + dx * in + n.x * 6.0f + offset.x, t[k].y + dy * in + n.y * 6.0f + offset.y, t[k].z + dz * in + n.z * 6.0f + offset.z};
			t[k].color = modulate(t[k].color, lightTint(at, n));
		}
	}
}
constexpr int kPassSolid = 1, kPassTranslucent = 2, kPassBoth = 3;
constexpr int kMaxViews = 32;
ViewEntry g_views[kMaxViews];
int g_viewDepth = 0;
int g_portalViewsThisFrame = 0;
int g_portalViewsLogged = 0;
IDirect3DStateBlock9* g_state = nullptr;

using SceneEndFn = void(__thiscall*)(void* self);
SceneEndFn g_sceneEndOriginal = nullptr;

int g_scenesThisFrame = 0;
int g_framesLogged = 0;
bool g_loggedDraw = false;
bool g_loggedEntities = false;

// IVEngineClient (013): 36 WorldToScreenMatrix() -> const VMatrix& (row-major, clip = M * v).
const float* worldToScreen() {
	return g_engineClient ? sdk::vcall<const float*>(g_engineClient, 36) : nullptr;
}

// (Re)creates `*tex` as a w x h texture from the RGBA8 pixels at `src` (rows top-down).
void uploadAtlas(IDirect3DDevice9* dev, IDirect3DTexture9** tex, const uint8_t* src, uint32_t w, uint32_t h, const char* what) {
	if (*tex) {
		(*tex)->Release();
		*tex = nullptr;
	}
	// Portal's device is D3D9Ex, which has no managed pool: a dynamic default-pool texture,
	// released before a device reset and uploaded again after.
	HRESULT hr = dev->CreateTexture(w, h, 1, D3DUSAGE_DYNAMIC, D3DFMT_A8R8G8B8, D3DPOOL_DEFAULT, tex, nullptr);
	if (FAILED(hr)) {
		*tex = nullptr;
		if (g_log) {
			g_log("world: %s atlas CreateTexture %ux%u failed hr=0x%08lX", what, w, h, static_cast<unsigned long>(hr));
		}
		return;
	}
	D3DLOCKED_RECT lr;
	if (FAILED((*tex)->LockRect(0, &lr, nullptr, D3DLOCK_DISCARD))) {
		(*tex)->Release();
		*tex = nullptr;
		return;
	}
	for (uint32_t y = 0; y < h; y++) {
		const uint8_t* row = src + size_t(y) * w * 4;
		uint8_t* out = static_cast<uint8_t*>(lr.pBits) + size_t(y) * lr.Pitch;
		for (uint32_t x = 0; x < w; x++) { // RGBA -> BGRA
			out[x * 4 + 0] = row[x * 4 + 2];
			out[x * 4 + 1] = row[x * 4 + 1];
			out[x * 4 + 2] = row[x * 4 + 0];
			out[x * 4 + 3] = row[x * 4 + 3];
		}
	}
	(*tex)->UnlockRect(0);
	if (g_log) {
		g_log("world: %s atlas %ux%u uploaded", what, w, h);
	}
}

void uploadAtlases(IDirect3DDevice9* dev) {
	if (g_header->atlasSeq != g_atlasSeq) {
		g_atlasSeq = g_header->atlasSeq;
		uint32_t w = g_header->atlasWidth, h = g_header->atlasHeight;
		if (w != 0 && h != 0 && w <= pcproto::kWorldAtlasMaxW && h <= pcproto::kWorldAtlasMaxH) {
			uploadAtlas(dev, &g_atlas, g_shm + pcproto::kWorldAtlasOffset, w, h, "block");
		}
	}
	if (g_header->itemAtlasSeq != g_itemAtlasSeq) {
		g_itemAtlasSeq = g_header->itemAtlasSeq;
		uint32_t w = g_header->itemAtlasWidth, h = g_header->itemAtlasHeight;
		if (w != 0 && h != 0 && w <= pcproto::kWorldItemAtlasMaxW && h <= pcproto::kWorldItemAtlasMaxH
			&& uint64_t(w) * h <= pcproto::kWorldItemAtlasMaxPixels) {
			uploadAtlas(dev, &g_itemAtlas, g_shm + pcproto::kWorldItemAtlasOffset, w, h, "item");
		}
	}
	if (g_header->skinSeq != g_skinSeq) {
		g_skinSeq = g_header->skinSeq;
		uint32_t w = g_header->skinWidth, h = g_header->skinHeight;
		if (w != 0 && h != 0 && w <= pcproto::kWorldSkinMaxW && h <= pcproto::kWorldSkinMaxH) {
			uploadAtlas(dev, &g_skin, g_shm + pcproto::kWorldSkinOffset, w, h, "skin");
		}
	}
	if (g_header->particleAtlasSeq != g_particleAtlasSeq) {
		g_particleAtlasSeq = g_header->particleAtlasSeq;
		uint32_t w = g_header->particleAtlasWidth, h = g_header->particleAtlasHeight;
		if (w != 0 && h != 0 && w <= pcproto::kWorldParticleAtlasMaxW && h <= pcproto::kWorldParticleAtlasMaxH &&
			uint64_t(w) * h <= pcproto::kWorldParticleAtlasMaxPixels) {
			uploadAtlas(dev, &g_particleAtlas, g_shm + pcproto::kWorldParticleAtlasOffset, w, h, "particle");
		}
	}
	if (g_header->mobAtlasSeq != g_mobAtlasSeq) {
		g_mobAtlasSeq = g_header->mobAtlasSeq;
		uint32_t w = g_header->mobAtlasWidth, h = g_header->mobAtlasHeight;
		if (w != 0 && h != 0 && w <= pcproto::kWorldMobAtlasMaxW && h <= pcproto::kWorldMobAtlasMaxH) {
			uploadAtlas(dev, &g_mobAtlas, g_shm + pcproto::kWorldMobAtlasOffset, w, h, "mob");
		}
	}
	if (g_header->crackSeq != g_cracksSeq) {
		g_cracksSeq = g_header->crackSeq;
		uint32_t w = g_header->crackWidth, h = g_header->crackHeight;
		if (w != 0 && h != 0 && w <= pcproto::kWorldCrackMaxW && h <= pcproto::kWorldCrackMaxH) {
			uploadAtlas(dev, &g_cracks, g_shm + pcproto::kWorldCrackOffset, w, h, "crack");
		}
	}
}

void skipped(const char* why) {
	static const char* last = nullptr;
	if (why != last && g_log) {
		last = why;
		g_log("world: not drawing: %s", why);
	}
}

// Claims the newest slot of a double-buffered mesh: sets `reading` to it, then makes sure it's
// still the newest, since Minecraft may have published the other one and started rewriting this one
// between our read of `front` and the claim. Returns the slot, or kNoSlot (with `reading` cleared).
uint32_t claimSlot(volatile uint32_t& front, volatile uint32_t& reading) {
	uint32_t slot = front;
	if (slot > 1) {
		return kNoSlot;
	}
	reading = slot;
	MemoryBarrier();
	if (front != slot) {
		slot = front;
		if (slot > 1) {
			reading = kNoSlot;
			return kNoSlot;
		}
		reading = slot;
		MemoryBarrier();
	}
	return slot;
}

void drawTriangles(IDirect3DDevice9* dev, const pcproto::WorldVertex* vertices, uint32_t count) {
	if (count >= 3) {
		dev->DrawPrimitiveUP(D3DPT_TRIANGLELIST, count / 3, vertices, sizeof(pcproto::WorldVertex));
	}
}

void setSolid(IDirect3DDevice9* dev) { // solid and cutout (leaves, glass panes): alpha tested, writing depth
	dev->SetRenderState(D3DRS_ZWRITEENABLE, TRUE);
	dev->SetRenderState(D3DRS_ALPHABLENDENABLE, FALSE);
	dev->SetRenderState(D3DRS_ALPHATESTENABLE, TRUE);
	dev->SetRenderState(D3DRS_ALPHAREF, 128);
	dev->SetRenderState(D3DRS_ALPHAFUNC, D3DCMP_GREATEREQUAL);
}

void setTranslucent(IDirect3DDevice9* dev) { // stained glass, water, ice: blended, not writing depth
	dev->SetRenderState(D3DRS_ZWRITEENABLE, FALSE);
	dev->SetRenderState(D3DRS_ALPHATESTENABLE, FALSE);
	dev->SetRenderState(D3DRS_ALPHABLENDENABLE, TRUE);
	dev->SetRenderState(D3DRS_SRCBLEND, D3DBLEND_SRCALPHA);
	dev->SetRenderState(D3DRS_DESTBLEND, D3DBLEND_INVSRCALPHA);
}

// Draws everything with the world-to-clip matrix `m` (row-major, clip = M * v). A view through a
// portal keeps Portal's stencil test (its oval) and always shows the avatar; the main view shows
// the avatar only in third person.
void draw(IDirect3DDevice9* dev, const float* m, bool throughPortal, int passes) {
	if (!g_header || (g_header->meshSeq == 0 && g_header->entitySeq == 0)) {
		return skipped("no mesh published yet");
	}
	if (!overlay::overlayFresh()) {
		return skipped("Minecraft isn't sending frames");
	}
	uploadAtlases(dev);
	if (!m) {
		return skipped("no WorldToScreenMatrix");
	}

	// The block mesh, if there is one and its atlas is up.
	const pcproto::WorldVertex* blocks = nullptr;
	uint32_t solid = 0, translucent = 0;
	if (g_header->meshSeq != 0 && g_atlas) {
		uint32_t slot = claimSlot(g_header->front, g_header->reading);
		if (slot != kNoSlot) {
			solid = g_header->slotSolid[slot];
			translucent = g_header->slotTranslucent[slot];
			if (uint64_t(solid) + translucent > pcproto::kWorldMaxVertices) {
				solid = translucent = 0;
				g_header->reading = kNoSlot;
			} else {
				blocks = reinterpret_cast<const pcproto::WorldVertex*>(g_shm + pcproto::kWorldMeshOffset + size_t(slot) * pcproto::kWorldSlotBytes);
			}
		}
	}

	// The entity mesh: block-atlas solid, block-atlas translucent, item-atlas solid, item-atlas
	// translucent, then the avatar: skin, and its own block/item ranges in the same order.
	const pcproto::WorldVertex* entities = nullptr; // == the block-atlas ranges
	const pcproto::WorldVertex* eItems = nullptr;   // the item-atlas ranges
	const pcproto::WorldVertex* avatar = nullptr;   // the avatar's skin range, then its block and item ranges
	uint32_t eBlockSolid = 0, eBlockTranslucent = 0, eItemSolid = 0, eItemTranslucent = 0;
	uint32_t aSkin = 0, aBlockSolid = 0, aBlockTranslucent = 0, aItemSolid = 0, aItemTranslucent = 0;
	const pcproto::WorldVertex* particles = nullptr; // particle-atlas solid then translucent, then the cracks
	uint32_t pSolid = 0, pTranslucent = 0, cracks = 0;
	const pcproto::WorldVertex* mobs = nullptr; // mob atlas solid then translucent
	uint32_t mSolid = 0, mTranslucent = 0;
	const pcproto::WorldVertex* avatarMobs = nullptr; // the avatar's armour and cape (mob atlas, feet-relative)
	uint32_t amSolid = 0, amTranslucent = 0;
	if (g_header->entitySeq != 0) {
		uint32_t slot = claimSlot(g_header->entityFront, g_header->entityReading);
		if (slot != kNoSlot) {
			eBlockSolid = g_header->entityBlockSolid[slot];
			eBlockTranslucent = g_header->entityBlockTranslucent[slot];
			eItemSolid = g_header->entityItemSolid[slot];
			eItemTranslucent = g_header->entityItemTranslucent[slot];
			aSkin = g_header->avatarSkin[slot];
			aBlockSolid = g_header->avatarBlockSolid[slot];
			aBlockTranslucent = g_header->avatarBlockTranslucent[slot];
			aItemSolid = g_header->avatarItemSolid[slot];
			aItemTranslucent = g_header->avatarItemTranslucent[slot];
			pSolid = g_header->particleSolid[slot];
			pTranslucent = g_header->particleTranslucent[slot];
			cracks = g_header->crack[slot];
			mSolid = g_header->mobSolid[slot];
			mTranslucent = g_header->mobTranslucent[slot];
			amSolid = g_header->avatarMobSolid[slot];
			amTranslucent = g_header->avatarMobTranslucent[slot];
			uint64_t total = uint64_t(eBlockSolid) + eBlockTranslucent + eItemSolid + eItemTranslucent + aSkin + aBlockSolid + aBlockTranslucent +
				aItemSolid + aItemTranslucent + pSolid + pTranslucent + cracks + mSolid + mTranslucent + amSolid + amTranslucent;
			if (total == 0 || total > pcproto::kWorldEntityMaxVertices) {
				eBlockSolid = eBlockTranslucent = eItemSolid = eItemTranslucent = 0;
				aSkin = aBlockSolid = aBlockTranslucent = aItemSolid = aItemTranslucent = 0;
				pSolid = pTranslucent = cracks = 0;
				mSolid = mTranslucent = 0;
				amSolid = amTranslucent = 0;
				g_header->entityReading = kNoSlot;
			} else {
				entities = reinterpret_cast<const pcproto::WorldVertex*>(
					g_shm + pcproto::kWorldEntityOffset + size_t(slot) * pcproto::kWorldEntitySlotBytes);
				eItems = entities + eBlockSolid + eBlockTranslucent;
				avatar = eItems + eItemSolid + eItemTranslucent;
				particles = avatar + aSkin + aBlockSolid + aBlockTranslucent + aItemSolid + aItemTranslucent;
				mobs = particles + pSolid + pTranslucent + cracks;
				avatarMobs = mobs + mSolid + mTranslucent;
			}
		}
	}
	// Portal's lighting on all of it (see the top of the file). Pointers move to the lit copies.
	sdk::Vector feet{};
	bool haveFeet = camera::playerFeet(&feet);
	if (blocks) {
		uint32_t slot = g_header->reading;
		if (slot != g_litBlocksSlot || g_header->meshSeq != g_litBlocksSeq) {
			g_litBlocks.clear();
			appendLit(g_litBlocks, blocks, solid + translucent, sdk::Vector{});
			g_litBlocksSlot = slot;
			g_litBlocksSeq = g_header->meshSeq;
		}
		blocks = g_litBlocks.data();
	}
	if (entities) {
		uint32_t slot = g_header->entityReading;
		uint32_t eTotal = uint32_t(avatar - entities);
		uint32_t aTotal = aSkin + aBlockSolid + aBlockTranslucent + aItemSolid + aItemTranslucent;
		bool feetMoved = haveFeet && (feet.x != g_litFeet.x || feet.y != g_litFeet.y || feet.z != g_litFeet.z);
		if (slot != g_litEntitySlot || g_header->entitySeq != g_litEntitySeq || feetMoved) {
			g_litEntities.clear();
			appendLit(g_litEntities, entities, eTotal, sdk::Vector{});
			appendLit(g_litEntities, avatar, aTotal, haveFeet ? feet : sdk::Vector{});
			appendLit(g_litEntities, particles, pSolid + pTranslucent, sdk::Vector{});
			g_litEntities.insert(g_litEntities.end(), particles + pSolid + pTranslucent, particles + pSolid + pTranslucent + cracks); // multiplied: unlit
			appendLit(g_litEntities, mobs, mSolid + mTranslucent, sdk::Vector{});
			appendLit(g_litEntities, avatarMobs, amSolid + amTranslucent, haveFeet ? feet : sdk::Vector{});
			g_litEntitySlot = slot;
			g_litEntitySeq = g_header->entitySeq;
			g_litFeet = feet;
		}
		const pcproto::WorldVertex* lit = g_litEntities.data();
		eItems = lit + (eItems - entities);
		avatar = lit + eTotal;
		particles = avatar + aTotal;
		mobs = particles + pSolid + pTranslucent + cracks;
		avatarMobs = mobs + mSolid + mTranslucent;
		entities = lit;
	}
	// The avatar only while Portal's camera is out of the player's head, placed on the player.
	const pcproto::WorldVertex* aBlocks = avatar ? avatar + aSkin : nullptr;
	const pcproto::WorldVertex* aItems = aBlocks ? aBlocks + aBlockSolid + aBlockTranslucent : nullptr;
	if (!avatar || !(throughPortal || camera::thirdPerson()) || !haveFeet) {
		aSkin = aBlockSolid = aBlockTranslucent = aItemSolid = aItemTranslucent = 0;
		amSolid = amTranslucent = 0;
	}
	if (!g_skin) {
		aSkin = 0;
	}
	if (!g_atlas) { // the entity ranges that need it can't draw either
		eBlockSolid = eBlockTranslucent = 0;
		aBlockSolid = aBlockTranslucent = 0;
	}
	if (!g_itemAtlas) {
		eItemSolid = eItemTranslucent = 0;
		aItemSolid = aItemTranslucent = 0;
	}
	const pcproto::WorldVertex* crackVerts = particles ? particles + pSolid + pTranslucent : nullptr;
	if (!g_particleAtlas) {
		pSolid = pTranslucent = 0;
	}
	if (!g_cracks) {
		cracks = 0;
	}
	if (!g_mobAtlas) {
		mSolid = mTranslucent = 0;
		amSolid = amTranslucent = 0;
	}
	D3DMATRIX atFeet = {};
	atFeet._11 = atFeet._22 = atFeet._33 = atFeet._44 = 1.0f;
	atFeet._41 = feet.x;
	atFeet._42 = feet.y;
	atFeet._43 = feet.z;
	auto release = [] {
		g_header->reading = kNoSlot;
		g_header->entityReading = kNoSlot;
	};
	if (!blocks && !entities) {
		release();
		return skipped(!g_atlas ? "no atlas" : "no mesh slot");
	}
	if (!g_state && FAILED(dev->CreateStateBlock(D3DSBT_ALL, &g_state))) {
		release();
		return;
	}
	g_state->Capture();

	// Source: clip = M * v (column vector). D3D fixed function: clip = v * P, so P = M transposed.
	D3DMATRIX identity = {};
	identity._11 = identity._22 = identity._33 = identity._44 = 1.0f;
	D3DMATRIX proj;
	for (int r = 0; r < 4; r++) {
		for (int c = 0; c < 4; c++) {
			proj.m[r][c] = m[c * 4 + r];
		}
	}
	dev->SetTransform(D3DTS_WORLD, &identity);
	dev->SetTransform(D3DTS_VIEW, &identity);
	dev->SetTransform(D3DTS_PROJECTION, &proj);

	dev->SetVertexShader(nullptr);
	dev->SetPixelShader(nullptr);
	dev->SetFVF(D3DFVF_XYZ | D3DFVF_DIFFUSE | D3DFVF_TEX1);
	dev->SetTextureStageState(0, D3DTSS_COLOROP, D3DTOP_MODULATE);
	dev->SetTextureStageState(0, D3DTSS_COLORARG1, D3DTA_TEXTURE);
	dev->SetTextureStageState(0, D3DTSS_COLORARG2, D3DTA_DIFFUSE);
	dev->SetTextureStageState(0, D3DTSS_ALPHAOP, D3DTOP_MODULATE);
	dev->SetTextureStageState(0, D3DTSS_ALPHAARG1, D3DTA_TEXTURE);
	dev->SetTextureStageState(0, D3DTSS_ALPHAARG2, D3DTA_DIFFUSE);
	dev->SetTextureStageState(0, D3DTSS_TEXCOORDINDEX, 0);
	dev->SetTextureStageState(0, D3DTSS_TEXTURETRANSFORMFLAGS, D3DTTFF_DISABLE);
	dev->SetTextureStageState(1, D3DTSS_COLOROP, D3DTOP_DISABLE);
	dev->SetTextureStageState(1, D3DTSS_ALPHAOP, D3DTOP_DISABLE);
	// Minecraft's pixel art: nearest filtering, no mips.
	dev->SetSamplerState(0, D3DSAMP_MINFILTER, D3DTEXF_POINT);
	dev->SetSamplerState(0, D3DSAMP_MAGFILTER, D3DTEXF_POINT);
	dev->SetSamplerState(0, D3DSAMP_MIPFILTER, D3DTEXF_NONE);
	dev->SetSamplerState(0, D3DSAMP_ADDRESSU, D3DTADDRESS_CLAMP);
	dev->SetSamplerState(0, D3DSAMP_ADDRESSV, D3DTADDRESS_CLAMP);
	dev->SetSamplerState(0, D3DSAMP_SRGBTEXTURE, FALSE);
	dev->SetRenderState(D3DRS_SRGBWRITEENABLE, FALSE);
	dev->SetRenderState(D3DRS_LIGHTING, FALSE);
	dev->SetRenderState(D3DRS_FOGENABLE, FALSE);
	dev->SetRenderState(D3DRS_CULLMODE, D3DCULL_NONE); // also makes flat item sprites two-sided
	if (!throughPortal) {
		dev->SetRenderState(D3DRS_STENCILENABLE, FALSE);
	}
	dev->SetRenderState(D3DRS_CLIPPLANEENABLE, 0); // Portal's are clip-space planes for its shaders, not ours
	dev->SetRenderState(D3DRS_SCISSORTESTENABLE, FALSE);
	dev->SetRenderState(D3DRS_CLIPPING, TRUE);
	dev->SetRenderState(D3DRS_COLORWRITEENABLE, 0xF);
	dev->SetRenderState(D3DRS_ZENABLE, D3DZB_TRUE);
	dev->SetRenderState(D3DRS_ZFUNC, D3DCMP_LESSEQUAL);

	const pcproto::WorldVertex* eBlocks = entities;

	// Opaque first, all of it, so the blended passes stack over every solid.
	if (passes & kPassSolid) {
		setSolid(dev);
		dev->SetTexture(0, g_atlas);
		if (blocks) {
			drawTriangles(dev, blocks, solid);
		}
		if (eBlocks) {
			drawTriangles(dev, eBlocks, eBlockSolid);
		}
		if (eItems && eItemSolid) {
			dev->SetTexture(0, g_itemAtlas);
			drawTriangles(dev, eItems, eItemSolid);
		}
		if (aSkin || aBlockSolid || aItemSolid) {
			dev->SetTransform(D3DTS_WORLD, &atFeet);
			dev->SetTexture(0, g_skin);
			drawTriangles(dev, avatar, aSkin);
			dev->SetTexture(0, g_atlas);
			drawTriangles(dev, aBlocks, aBlockSolid);
			dev->SetTexture(0, g_itemAtlas);
			drawTriangles(dev, aItems, aItemSolid);
			dev->SetTransform(D3DTS_WORLD, &identity);
		}
		if (pSolid) {
			dev->SetTexture(0, g_particleAtlas);
			drawTriangles(dev, particles, pSolid);
		}
		if (mSolid) {
			dev->SetTexture(0, g_mobAtlas);
			drawTriangles(dev, mobs, mSolid);
		}
		if (amSolid) {
			dev->SetTransform(D3DTS_WORLD, &atFeet);
			dev->SetTexture(0, g_mobAtlas);
			drawTriangles(dev, avatarMobs, amSolid);
			dev->SetTransform(D3DTS_WORLD, &identity);
		}
		if (cracks) { // Minecraft's crumbling blend: what's behind, times the crack texture
			// The blend doubles (dst*src + src*dst), so a texel must be dropped where the crack texture
			// is clear, or its white-ish RGB brightens the whole face.
			dev->SetRenderState(D3DRS_ALPHATESTENABLE, TRUE);
			dev->SetRenderState(D3DRS_ALPHAREF, 25);
			dev->SetRenderState(D3DRS_ALPHAFUNC, D3DCMP_GREATER);
			dev->SetRenderState(D3DRS_ZWRITEENABLE, FALSE);
			dev->SetRenderState(D3DRS_ALPHABLENDENABLE, TRUE);
			dev->SetRenderState(D3DRS_SRCBLEND, D3DBLEND_DESTCOLOR);
			dev->SetRenderState(D3DRS_DESTBLEND, D3DBLEND_SRCCOLOR);
			dev->SetTexture(0, g_cracks);
			drawTriangles(dev, crackVerts, cracks);
			setSolid(dev);
		}
	}
	if ((passes & kPassTranslucent) &&
		(translucent >= 3 || eBlockTranslucent >= 3 || eItemTranslucent >= 3 || aBlockTranslucent >= 3 || aItemTranslucent >= 3 || pTranslucent >= 3 || mTranslucent >= 3 || amTranslucent >= 3)) {
		setTranslucent(dev);
		dev->SetTexture(0, g_atlas);
		if (blocks) {
			drawTriangles(dev, blocks + solid, translucent);
		}
		if (eBlocks) {
			drawTriangles(dev, eBlocks + eBlockSolid, eBlockTranslucent);
		}
		if (eItems && eItemTranslucent) {
			dev->SetTexture(0, g_itemAtlas);
			drawTriangles(dev, eItems + eItemSolid, eItemTranslucent);
		}
		if (aBlockTranslucent || aItemTranslucent) {
			dev->SetTransform(D3DTS_WORLD, &atFeet);
			dev->SetTexture(0, g_atlas);
			drawTriangles(dev, aBlocks + aBlockSolid, aBlockTranslucent);
			dev->SetTexture(0, g_itemAtlas);
			drawTriangles(dev, aItems + aItemSolid, aItemTranslucent);
			dev->SetTransform(D3DTS_WORLD, &identity);
		}
		if (pTranslucent) {
			dev->SetTexture(0, g_particleAtlas);
			drawTriangles(dev, particles + pSolid, pTranslucent);
		}
		if (mTranslucent) {
			dev->SetTexture(0, g_mobAtlas);
			drawTriangles(dev, mobs + mSolid, mTranslucent);
		}
		if (amTranslucent) {
			dev->SetTransform(D3DTS_WORLD, &atFeet);
			dev->SetTexture(0, g_mobAtlas);
			drawTriangles(dev, avatarMobs + amSolid, amTranslucent);
			dev->SetTransform(D3DTS_WORLD, &identity);
		}
	}

	g_state->Apply();
	release();
	if (!g_loggedDraw && blocks && g_log) {
		g_loggedDraw = true;
		g_log("world: drawing %u solid + %u translucent vertices", solid, translucent);
	}
	if (!g_loggedAvatar && aSkin && g_log) {
		g_loggedAvatar = true;
		g_log("world: drawing the avatar: %u skin + %u held vertices at feet (%.1f %.1f %.1f)", aSkin,
			aBlockSolid + aBlockTranslucent + aItemSolid + aItemTranslucent, feet.x, feet.y, feet.z);
	}
	if (!g_loggedEntities && entities && g_log) {
		g_loggedEntities = true;
		g_log("world: drawing entities: %u + %u block-atlas, %u + %u item-atlas vertices", eBlockSolid, eBlockTranslucent, eItemSolid,
			eItemTranslucent);
	}
}

void __fastcall hkSceneEnd(void* self, void* /*edx*/) {
	g_scenesThisFrame++;
	// Only the first scene of a frame is the player's main view; the rest are portal views,
	// monitors and the like, which WorldToScreenMatrix doesn't describe.
	if (g_scenesThisFrame == 1) {
		if (auto* dev = static_cast<IDirect3DDevice9*>(overlay::device())) {
			draw(dev, worldToScreen(), false, g_mainSolidDone ? kPassTranslucent : kPassBoth);
		}
	}
	g_sceneEndOriginal(self);
}

// ---- the view stack ----------------------------------------------------------------------

using Push3DView4Fn = void(__thiscall*)(void* self, const void* view, int flags, void* target, void* frustum);
using Push3DView5Fn = void(__thiscall*)(void* self, const void* view, int flags, void* target, void* frustum, void* depth);
using PopViewFn = void(__thiscall*)(void* self, void* frustum);
Push3DView4Fn g_push3d4Original = nullptr;
Push3DView5Fn g_push3d5Original = nullptr;
Push3DView4Fn g_push2dOriginal = nullptr;
PopViewFn g_popOriginal = nullptr;

constexpr int kSetupOrigin = 64; // CViewSetup::origin

void pushView(const void* view, bool candidate, bool toScreen) {
	if (g_viewDepth >= kMaxViews) {
		g_viewDepth++; // keep counting so the pops still match
		return;
	}
	ViewEntry& e = g_views[g_viewDepth];
	e.setup = static_cast<const uint8_t*>(view);
	e.origin = view ? *reinterpret_cast<const sdk::Vector*>(e.setup + kSetupOrigin) : sdk::Vector{};
	e.throughPortal = false;
	e.isMain = g_viewDepth == 0 && toScreen && view && !g_mainSolidDone;
	e.solidDone = false;
	if (candidate && view && g_viewDepth >= 1 && g_viewDepth - 1 < kMaxViews) {
		const sdk::Vector& p = g_views[g_viewDepth - 1].origin;
		float dx = e.origin.x - p.x, dy = e.origin.y - p.y, dz = e.origin.z - p.z;
		e.throughPortal = dx * dx + dy * dy + dz * dz > 1.0f; // its camera moved: a view through a portal
	} else if (g_viewDepth >= 1 && !view) {
		e.origin = g_views[g_viewDepth - 1].origin;
	}
	g_viewDepth++;
}

// Push3DView without a depth texture: the main view, world views, and the views through portals.
void __fastcall hkPush3DView4(void* self, void* /*edx*/, const void* view, int flags, void* target, void* frustum) {
	pushView(view, target == nullptr, target == nullptr); // render-to-texture views (water, monitors) aren't through portals
	g_push3d4Original(self, view, flags, target, frustum);
}

// With a depth texture: the 3D skybox and shadow depth views, in their own coordinates.
void __fastcall hkPush3DView5(void* self, void* /*edx*/, const void* view, int flags, void* target, void* frustum, void* depth) {
	pushView(view, false, target == nullptr && depth == nullptr); // also the main view (no HDR)
	g_push3d5Original(self, view, flags, target, frustum, depth);
}

void __fastcall hkPush2DView(void* self, void* /*edx*/, const void* view, int flags, void* target, void* frustum) {
	pushView(nullptr, false, false);
	g_push2dOriginal(self, view, flags, target, frustum);
}

// IVRenderView::GetMatricesForView for a view through a portal: its world-to-clip matrix.
void portalMatrix(const ViewEntry& e, float* worldToProjection) {
	float worldToView[16], viewToProjection[16], worldToPixels[16];
	sdk::vcall<void>(g_renderView, 50, static_cast<const void*>(e.setup), static_cast<void*>(worldToView), static_cast<void*>(viewToProjection),
		static_cast<void*>(worldToProjection), static_cast<void*>(worldToPixels));
}

using DrawTranslucentSurfacesFn = void(__thiscall*)(void* self, void* list, int sortIndex, unsigned long flags, bool shadowDepth);
DrawTranslucentSurfacesFn g_translucentOriginal = nullptr;

// The view's first translucent world surface: our solids go in now, under its glass.
void __fastcall hkDrawTranslucentSurfaces(void* self, void* /*edx*/, void* list, int sortIndex, unsigned long flags, bool shadowDepth) {
	if (!shadowDepth && g_viewDepth > 0 && g_viewDepth <= kMaxViews) {
		ViewEntry& e = g_views[g_viewDepth - 1];
		if (!e.solidDone && (e.isMain || e.throughPortal)) {
			e.solidDone = true;
			if (auto* dev = static_cast<IDirect3DDevice9*>(overlay::device())) {
				if (e.isMain) {
					g_mainSolidDone = true;
					draw(dev, worldToScreen(), false, kPassSolid);
				} else if (g_renderView) {
					float worldToProjection[16];
					portalMatrix(e, worldToProjection);
					draw(dev, worldToProjection, true, kPassSolid);
				}
			}
		}
	}
	g_translucentOriginal(self, list, sortIndex, flags, shadowDepth);
}

void __fastcall hkPopView(void* self, void* /*edx*/, void* frustum) {
	if (g_viewDepth > 0) {
		g_viewDepth--;
		if (g_viewDepth < kMaxViews && g_views[g_viewDepth].throughPortal && g_renderView) {
			auto* dev = static_cast<IDirect3DDevice9*>(overlay::device());
			float worldToProjection[16];
			portalMatrix(g_views[g_viewDepth], worldToProjection);
			if (dev) {
				g_portalViewsThisFrame++;
				draw(dev, worldToProjection, true, g_views[g_viewDepth].solidDone ? kPassTranslucent : kPassBoth);
			}
		}
	}
	g_popOriginal(self, frustum);
}

void hook(void** vt, int slot, void* replacement, void** original) {
	DWORD old;
	VirtualProtect(&vt[slot], sizeof(void*), PAGE_READWRITE, &old);
	*original = vt[slot];
	vt[slot] = replacement;
	VirtualProtect(&vt[slot], sizeof(void*), old, &old);
}

} // namespace

void levelChanged() {
	g_lightCache.clear(); // another map's light
	g_litBlocksSlot = g_litEntitySlot = kNoSlot;
	g_lightSamplesLogged = 0;
}

void setExposure(float exposure) {
	g_exposure = exposure;
	levelChanged(); // relight everything
	if (g_log) {
		g_log("world: lighting exposure %.2f", exposure);
	}
}

void frameDone() {
	if (g_framesLogged < 3 && g_scenesThisFrame > 0 && g_log) {
		g_framesLogged++;
		g_log("world: %d SceneEnd calls this frame", g_scenesThisFrame);
	}
	if (g_portalViewsThisFrame > 0 && g_portalViewsLogged < 3 && g_log) {
		g_portalViewsLogged++;
		g_log("world: drew into %d views through portals this frame", g_portalViewsThisFrame);
	}
	if (g_viewDepth != 0 && g_log) {
		static bool logged = false;
		if (!logged) {
			logged = true;
			g_log("world: view stack unbalanced at frame end (%d); resetting (logged once)", g_viewDepth);
		}
	}
	g_viewDepth = 0;
	g_mainSolidDone = false;
	g_portalViewsThisFrame = 0;
	g_scenesThisFrame = 0;
}

void releaseDeviceObjects() {
	if (g_state) {
		g_state->Release();
		g_state = nullptr;
	}
	if (g_atlas) {
		g_atlas->Release();
		g_atlas = nullptr;
	}
	if (g_itemAtlas) {
		g_itemAtlas->Release();
		g_itemAtlas = nullptr;
	}
	if (g_skin) {
		g_skin->Release();
		g_skin = nullptr;
	}
	if (g_particleAtlas) {
		g_particleAtlas->Release();
		g_particleAtlas = nullptr;
	}
	if (g_cracks) {
		g_cracks->Release();
		g_cracks = nullptr;
	}
	if (g_mobAtlas) {
		g_mobAtlas->Release();
		g_mobAtlas = nullptr;
	}
	g_mobAtlasSeq = 0;
	g_particleAtlasSeq = 0;
	g_cracksSeq = 0;
	g_atlasSeq = 0; // upload them again after the reset
	g_itemAtlasSeq = 0;
	g_skinSeq = 0;
}

bool init(overlay::LogFn log, sdk::CreateInterfaceFn engineFactory) {
	g_log = log;
	HANDLE mapping = CreateFileMappingA(INVALID_HANDLE_VALUE, nullptr, PAGE_READWRITE, 0, pcproto::kWorldBytes, pcproto::kWorldMapping);
	g_shm = mapping ? static_cast<uint8_t*>(MapViewOfFile(mapping, FILE_MAP_ALL_ACCESS, 0, 0, pcproto::kWorldBytes)) : nullptr;
	if (!g_shm) {
		log("world: can't create the world mapping (%lu)", GetLastError());
		return false;
	}
	g_header = reinterpret_cast<pcproto::WorldHeader*>(g_shm);
	g_header->reading = kNoSlot;
	g_header->entityReading = kNoSlot;
	std::memcpy(g_header->magic, "PCW6", 4);

	g_engineClient = engineFactory("VEngineClient013", nullptr);
	void* renderView = engineFactory("VEngineRenderView014", nullptr);
	if (!renderView) {
		log("world: no VEngineRenderView014");
		return false;
	}
	g_renderView = renderView;
	// IVRenderView: 9 SceneEnd(), and the view stack (see the top of this file)
	void** vt = *static_cast<void***>(renderView);
	hook(vt, 9, reinterpret_cast<void*>(&hkSceneEnd), reinterpret_cast<void**>(&g_sceneEndOriginal));
	hook(vt, 37, reinterpret_cast<void*>(&hkPush3DView5), reinterpret_cast<void**>(&g_push3d5Original));
	hook(vt, 38, reinterpret_cast<void*>(&hkPush3DView4), reinterpret_cast<void**>(&g_push3d4Original));
	hook(vt, 39, reinterpret_cast<void*>(&hkPush2DView), reinterpret_cast<void**>(&g_push2dOriginal));
	hook(vt, 40, reinterpret_cast<void*>(&hkPopView), reinterpret_cast<void**>(&g_popOriginal));
	hook(vt, 18, reinterpret_cast<void*>(&hkDrawTranslucentSurfaces), reinterpret_cast<void**>(&g_translucentOriginal));
	log("world: mapping %s ready (%u MB), SceneEnd and the view stack hooked", pcproto::kWorldMapping, pcproto::kWorldBytes >> 20);
	return true;
}

} // namespace worldrender
