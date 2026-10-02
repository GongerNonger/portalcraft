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

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <d3d9.h>

#include <cstdint>
#include <cstring>

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
bool g_loggedAvatar = false;
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

void draw(IDirect3DDevice9* dev) {
	if (!g_header || (g_header->meshSeq == 0 && g_header->entitySeq == 0)) {
		return skipped("no mesh published yet");
	}
	if (!overlay::overlayFresh()) {
		return skipped("Minecraft isn't sending frames");
	}
	uploadAtlases(dev);
	const float* m = worldToScreen();
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
			uint64_t total = uint64_t(eBlockSolid) + eBlockTranslucent + eItemSolid + eItemTranslucent + aSkin + aBlockSolid + aBlockTranslucent +
				aItemSolid + aItemTranslucent;
			if (total == 0 || total > pcproto::kWorldEntityMaxVertices) {
				eBlockSolid = eBlockTranslucent = eItemSolid = eItemTranslucent = 0;
				aSkin = aBlockSolid = aBlockTranslucent = aItemSolid = aItemTranslucent = 0;
				g_header->entityReading = kNoSlot;
			} else {
				entities = reinterpret_cast<const pcproto::WorldVertex*>(
					g_shm + pcproto::kWorldEntityOffset + size_t(slot) * pcproto::kWorldEntitySlotBytes);
				eItems = entities + eBlockSolid + eBlockTranslucent;
				avatar = eItems + eItemSolid + eItemTranslucent;
			}
		}
	}
	// The avatar only while Portal's camera is out of the player's head, placed on the player.
	sdk::Vector feet{};
	const pcproto::WorldVertex* aBlocks = avatar ? avatar + aSkin : nullptr;
	const pcproto::WorldVertex* aItems = aBlocks ? aBlocks + aBlockSolid + aBlockTranslucent : nullptr;
	if (!avatar || !camera::thirdPerson() || !camera::playerFeet(&feet)) {
		aSkin = aBlockSolid = aBlockTranslucent = aItemSolid = aItemTranslucent = 0;
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
	dev->SetRenderState(D3DRS_STENCILENABLE, FALSE);
	dev->SetRenderState(D3DRS_SCISSORTESTENABLE, FALSE);
	dev->SetRenderState(D3DRS_CLIPPING, TRUE);
	dev->SetRenderState(D3DRS_COLORWRITEENABLE, 0xF);
	dev->SetRenderState(D3DRS_ZENABLE, D3DZB_TRUE);
	dev->SetRenderState(D3DRS_ZFUNC, D3DCMP_LESSEQUAL);

	const pcproto::WorldVertex* eBlocks = entities;

	// Opaque first, all of it, so the blended passes stack over every solid.
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
	if (translucent >= 3 || eBlockTranslucent >= 3 || eItemTranslucent >= 3 || aBlockTranslucent >= 3 || aItemTranslucent >= 3) {
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
			draw(dev);
		}
	}
	g_sceneEndOriginal(self);
}

} // namespace

void frameDone() {
	if (g_framesLogged < 3 && g_scenesThisFrame > 0 && g_log) {
		g_framesLogged++;
		g_log("world: %d SceneEnd calls this frame", g_scenesThisFrame);
	}
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
	std::memcpy(g_header->magic, "PCW3", 4);

	g_engineClient = engineFactory("VEngineClient013", nullptr);
	void* renderView = engineFactory("VEngineRenderView014", nullptr);
	if (!renderView) {
		log("world: no VEngineRenderView014");
		return false;
	}
	// IVRenderView: 9 SceneEnd()
	void** vt = *static_cast<void***>(renderView);
	DWORD old;
	VirtualProtect(&vt[9], sizeof(void*), PAGE_READWRITE, &old);
	g_sceneEndOriginal = reinterpret_cast<SceneEndFn>(vt[9]);
	vt[9] = reinterpret_cast<void*>(&hkSceneEnd);
	VirtualProtect(&vt[9], sizeof(void*), old, &old);
	log("world: mapping %s ready (%u MB), SceneEnd hooked", pcproto::kWorldMapping, pcproto::kWorldBytes >> 20);
	return true;
}

} // namespace worldrender
