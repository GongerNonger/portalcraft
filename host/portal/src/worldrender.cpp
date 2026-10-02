// Draws the blocks the player placed in Minecraft inside Portal's own 3D scene.
//
// Minecraft publishes a triangle mesh (host space) and its block atlas through shared memory
// (protocol: WorldHeader). We hook IVRenderView::SceneEnd, which the client calls when a view's
// 3D world is done but before the view model and HUD: the device's render target and depth buffer
// still hold the scene, so the blocks are hidden behind Portal's walls and in front of nothing
// they shouldn't be. The camera comes from IVEngineClient::WorldToScreenMatrix.

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <d3d9.h>

#include <cstdint>
#include <cstring>

#include "../../../protocol/portalcraft_protocol.h"
#include "overlay.h"
#include "sdk.h"
#include "worldrender.h"

namespace worldrender {
namespace {

overlay::LogFn g_log = nullptr;
uint8_t* g_shm = nullptr;
pcproto::WorldHeader* g_header = nullptr;
void* g_engineClient = nullptr;

IDirect3DTexture9* g_atlas = nullptr;
uint32_t g_atlasSeq = 0;
IDirect3DStateBlock9* g_state = nullptr;

using SceneEndFn = void(__thiscall*)(void* self);
SceneEndFn g_sceneEndOriginal = nullptr;

int g_scenesThisFrame = 0;
int g_framesLogged = 0;
bool g_loggedDraw = false;

// IVEngineClient (013): 36 WorldToScreenMatrix() -> const VMatrix& (row-major, clip = M * v).
const float* worldToScreen() {
	return g_engineClient ? sdk::vcall<const float*>(g_engineClient, 36) : nullptr;
}

void uploadAtlas(IDirect3DDevice9* dev) {
	uint32_t w = g_header->atlasWidth, h = g_header->atlasHeight;
	if (w == 0 || h == 0 || w > pcproto::kWorldAtlasMaxW || h > pcproto::kWorldAtlasMaxH) {
		return;
	}
	if (g_atlas) {
		g_atlas->Release();
		g_atlas = nullptr;
	}
	// Portal's device is D3D9Ex, which has no managed pool: a dynamic default-pool texture,
	// released before a device reset and uploaded again after.
	HRESULT hr = dev->CreateTexture(w, h, 1, D3DUSAGE_DYNAMIC, D3DFMT_A8R8G8B8, D3DPOOL_DEFAULT, &g_atlas, nullptr);
	if (FAILED(hr)) {
		g_atlas = nullptr;
		if (g_log) {
			g_log("world: atlas CreateTexture %ux%u failed hr=0x%08lX", w, h, static_cast<unsigned long>(hr));
		}
		return;
	}
	D3DLOCKED_RECT lr;
	if (FAILED(g_atlas->LockRect(0, &lr, nullptr, D3DLOCK_DISCARD))) {
		return;
	}
	const uint8_t* src = g_shm + pcproto::kWorldAtlasOffset;
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
	g_atlas->UnlockRect(0);
	if (g_log) {
		g_log("world: atlas %ux%u uploaded", w, h);
	}
}

void skipped(const char* why) {
	static const char* last = nullptr;
	if (why != last && g_log) {
		last = why;
		g_log("world: not drawing: %s", why);
	}
}

void draw(IDirect3DDevice9* dev) {
	if (!g_header || g_header->meshSeq == 0) {
		return skipped("no mesh published yet");
	}
	if (!overlay::overlayFresh()) {
		return skipped("Minecraft isn't sending frames");
	}
	if (g_header->atlasSeq != g_atlasSeq) {
		g_atlasSeq = g_header->atlasSeq;
		uploadAtlas(dev);
	}
	const float* m = worldToScreen();
	uint32_t slot = g_header->front;
	if (!g_atlas || !m || slot > 1) {
		return skipped(!g_atlas ? "no atlas" : !m ? "no WorldToScreenMatrix" : "bad slot");
	}
	// Claim the slot, then make sure it's still the newest: Minecraft may have published the
	// other one and started rewriting this one between our read of `front` and the claim.
	g_header->reading = slot;
	MemoryBarrier();
	if (g_header->front != slot) {
		slot = g_header->front;
		if (slot > 1) {
			g_header->reading = 0xFFFFFFFFu;
			return;
		}
		g_header->reading = slot;
		MemoryBarrier();
	}
	uint32_t solid = g_header->slotSolid[slot], translucent = g_header->slotTranslucent[slot];
	if (solid + translucent > pcproto::kWorldMaxVertices) {
		g_header->reading = 0xFFFFFFFFu;
		return;
	}
	const auto* vertices = reinterpret_cast<const pcproto::WorldVertex*>(g_shm + pcproto::kWorldMeshOffset + size_t(slot) * pcproto::kWorldSlotBytes);

	if (!g_state && FAILED(dev->CreateStateBlock(D3DSBT_ALL, &g_state))) {
		g_header->reading = 0xFFFFFFFFu;
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
	dev->SetTexture(0, g_atlas);
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
	dev->SetRenderState(D3DRS_CULLMODE, D3DCULL_NONE);
	dev->SetRenderState(D3DRS_STENCILENABLE, FALSE);
	dev->SetRenderState(D3DRS_SCISSORTESTENABLE, FALSE);
	dev->SetRenderState(D3DRS_CLIPPING, TRUE);
	dev->SetRenderState(D3DRS_COLORWRITEENABLE, 0xF);
	dev->SetRenderState(D3DRS_ZENABLE, D3DZB_TRUE);
	dev->SetRenderState(D3DRS_ZFUNC, D3DCMP_LESSEQUAL);

	// Solid and cutout (leaves, glass panes): alpha tested, writing depth.
	dev->SetRenderState(D3DRS_ZWRITEENABLE, TRUE);
	dev->SetRenderState(D3DRS_ALPHABLENDENABLE, FALSE);
	dev->SetRenderState(D3DRS_ALPHATESTENABLE, TRUE);
	dev->SetRenderState(D3DRS_ALPHAREF, 128);
	dev->SetRenderState(D3DRS_ALPHAFUNC, D3DCMP_GREATEREQUAL);
	if (solid >= 3) {
		dev->DrawPrimitiveUP(D3DPT_TRIANGLELIST, solid / 3, vertices, sizeof(pcproto::WorldVertex));
	}
	// Translucent (stained glass, water, ice): blended over everything, not writing depth.
	if (translucent >= 3) {
		dev->SetRenderState(D3DRS_ZWRITEENABLE, FALSE);
		dev->SetRenderState(D3DRS_ALPHATESTENABLE, FALSE);
		dev->SetRenderState(D3DRS_ALPHABLENDENABLE, TRUE);
		dev->SetRenderState(D3DRS_SRCBLEND, D3DBLEND_SRCALPHA);
		dev->SetRenderState(D3DRS_DESTBLEND, D3DBLEND_INVSRCALPHA);
		dev->DrawPrimitiveUP(D3DPT_TRIANGLELIST, translucent / 3, vertices + solid, sizeof(pcproto::WorldVertex));
	}

	g_state->Apply();
	g_header->reading = 0xFFFFFFFFu;
	if (!g_loggedDraw && g_log) {
		g_loggedDraw = true;
		g_log("world: drawing %u solid + %u translucent vertices", solid, translucent);
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
	g_atlasSeq = 0; // upload it again after the reset
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
	g_header->reading = 0xFFFFFFFFu;
	std::memcpy(g_header->magic, "PCW1", 4);

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
