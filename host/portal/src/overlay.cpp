// Draws Minecraft's hand + HUD (published by Minecraft into shared memory) over Portal's frame.
//
// Pattern-free: a throwaway D3D9 device gives us the d3d9.dll vtable that Portal's own device
// shares, and we patch Present / PresentEx / Reset / ResetEx in it. Right before each present we
// upload the newest Minecraft frame into a texture and draw it as a full-screen quad.

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <d3d9.h>
#include <tmmintrin.h>

#include <cstdint>
#include <cstring>

#include "../../../protocol/portalcraft_protocol.h"
#include "overlay.h"

namespace overlay {
namespace {

LogFn g_log = nullptr;
HANDLE g_mapping = nullptr;
uint8_t* g_shm = nullptr;
pcproto::OverlayHeader* g_header = nullptr;

IDirect3DTexture9* g_tex = nullptr;
UINT g_texW = 0, g_texH = 0;
IDirect3DStateBlock9* g_state = nullptr;
uint32_t g_uploadedSeq = 0;
uint32_t g_lastSeenSeq = 0;
DWORD g_lastSeqChange = 0;
DWORD g_lastSizeCheck = 0;
bool g_hooked = false;
bool g_loggedFirstDraw = false;

using PresentFn = HRESULT(WINAPI*)(IDirect3DDevice9*, const RECT*, const RECT*, HWND, const RGNDATA*);
using PresentExFn = HRESULT(WINAPI*)(IDirect3DDevice9Ex*, const RECT*, const RECT*, HWND, const RGNDATA*, DWORD);
using ResetFn = HRESULT(WINAPI*)(IDirect3DDevice9*, D3DPRESENT_PARAMETERS*);
using ResetExFn = HRESULT(WINAPI*)(IDirect3DDevice9Ex*, D3DPRESENT_PARAMETERS*, D3DDISPLAYMODEEX*);

// One set of originals per distinct vtable we patched (plain and Ex devices may differ).
struct Hooked {
	void** vtable = nullptr;
	PresentFn present = nullptr;
	ResetFn reset = nullptr;
	PresentExFn presentEx = nullptr;
	ResetExFn resetEx = nullptr;
};
Hooked g_vt[4];

Hooked* forDevice(void* device) {
	void** vt = *static_cast<void***>(device);
	for (Hooked& h : g_vt) {
		if (h.vtable == vt) {
			return &h;
		}
	}
	return &g_vt[0];
}

void releaseDeviceObjects() {
	if (g_tex) {
		g_tex->Release();
		g_tex = nullptr;
		g_texW = g_texH = 0;
	}
	if (g_state) {
		g_state->Release();
		g_state = nullptr;
	}
	g_uploadedSeq = 0;
}

// RGBA bottom-up (OpenGL) -> BGRA top-down (D3D).
void upload(const uint8_t* src, UINT w, UINT h) {
	D3DLOCKED_RECT lr;
	if (FAILED(g_tex->LockRect(0, &lr, nullptr, D3DLOCK_DISCARD))) {
		return;
	}
	const __m128i swap = _mm_setr_epi8(2, 1, 0, 3, 6, 5, 4, 7, 10, 9, 8, 11, 14, 13, 12, 15);
	for (UINT y = 0; y < h; y++) {
		const uint8_t* row = src + size_t(h - 1 - y) * w * 4;
		uint8_t* out = static_cast<uint8_t*>(lr.pBits) + size_t(y) * lr.Pitch;
		UINT x = 0;
		for (; x + 4 <= w; x += 4) {
			__m128i px = _mm_loadu_si128(reinterpret_cast<const __m128i*>(row + x * 4));
			_mm_storeu_si128(reinterpret_cast<__m128i*>(out + x * 4), _mm_shuffle_epi8(px, swap));
		}
		for (; x < w; x++) {
			out[x * 4 + 0] = row[x * 4 + 2];
			out[x * 4 + 1] = row[x * 4 + 1];
			out[x * 4 + 2] = row[x * 4 + 0];
			out[x * 4 + 3] = row[x * 4 + 3];
		}
	}
	g_tex->UnlockRect(0);
}

void draw(IDirect3DDevice9* dev) {
	if (!g_header) {
		return;
	}
	IDirect3DSurface9* backBuffer = nullptr;
	if (FAILED(dev->GetBackBuffer(0, 0, D3DBACKBUFFER_TYPE_MONO, &backBuffer))) {
		return;
	}
	D3DSURFACE_DESC bb;
	backBuffer->GetDesc(&bb);
	g_header->hostWidth = bb.Width;
	g_header->hostHeight = bb.Height;

	// Minecraft gone quiet (closed, or not linked): draw nothing rather than a frozen HUD.
	uint32_t seq = g_header->frameSeq;
	DWORD now = GetTickCount();
	if (seq != g_lastSeenSeq) {
		g_lastSeenSeq = seq;
		g_lastSeqChange = now;
	}
	if (seq == 0 || now - g_lastSeqChange > 500) {
		backBuffer->Release();
		return;
	}

	uint32_t slot = g_header->front;
	if (slot >= pcproto::kOverlaySlots) {
		backBuffer->Release();
		return;
	}
	UINT w = g_header->slotWidth[slot], h = g_header->slotHeight[slot];
	if (w == 0 || h == 0 || w > pcproto::kOverlayMaxW || h > pcproto::kOverlayMaxH) {
		backBuffer->Release();
		return;
	}
	if (!g_tex || g_texW != w || g_texH != h) {
		if (g_tex) {
			g_tex->Release();
			g_tex = nullptr;
		}
		if (FAILED(dev->CreateTexture(w, h, 1, D3DUSAGE_DYNAMIC, D3DFMT_A8R8G8B8, D3DPOOL_DEFAULT, &g_tex, nullptr))) {
			backBuffer->Release();
			return;
		}
		g_texW = w;
		g_texH = h;
		g_uploadedSeq = 0;
	}
	if (seq != g_uploadedSeq) {
		g_header->reading = slot;
		MemoryBarrier();
		upload(g_shm + pcproto::kOverlayHeaderBytes + size_t(slot) * pcproto::kOverlaySlotBytes, w, h);
		g_header->reading = 0xFFFFFFFFu;
		g_uploadedSeq = seq;
	}

	if (!g_state && FAILED(dev->CreateStateBlock(D3DSBT_ALL, &g_state))) {
		backBuffer->Release();
		return;
	}
	g_state->Capture();
	IDirect3DSurface9* oldTarget = nullptr;
	dev->GetRenderTarget(0, &oldTarget);
	IDirect3DSurface9* oldDepth = nullptr;
	dev->GetDepthStencilSurface(&oldDepth);

	dev->SetRenderTarget(0, backBuffer);
	dev->SetDepthStencilSurface(nullptr);
	D3DVIEWPORT9 vp = {0, 0, bb.Width, bb.Height, 0.0f, 1.0f};
	dev->SetViewport(&vp);
	dev->SetVertexShader(nullptr);
	dev->SetPixelShader(nullptr);
	dev->SetFVF(D3DFVF_XYZRHW | D3DFVF_TEX1);
	dev->SetTexture(0, g_tex);
	dev->SetTextureStageState(0, D3DTSS_COLOROP, D3DTOP_SELECTARG1);
	dev->SetTextureStageState(0, D3DTSS_COLORARG1, D3DTA_TEXTURE);
	dev->SetTextureStageState(0, D3DTSS_ALPHAOP, D3DTOP_SELECTARG1);
	dev->SetTextureStageState(0, D3DTSS_ALPHAARG1, D3DTA_TEXTURE);
	dev->SetTextureStageState(0, D3DTSS_TEXCOORDINDEX, 0);
	dev->SetTextureStageState(0, D3DTSS_TEXTURETRANSFORMFLAGS, D3DTTFF_DISABLE);
	dev->SetTextureStageState(1, D3DTSS_COLOROP, D3DTOP_DISABLE);
	dev->SetTextureStageState(1, D3DTSS_ALPHAOP, D3DTOP_DISABLE);
	dev->SetSamplerState(0, D3DSAMP_MINFILTER, D3DTEXF_LINEAR);
	dev->SetSamplerState(0, D3DSAMP_MAGFILTER, D3DTEXF_LINEAR);
	dev->SetSamplerState(0, D3DSAMP_MIPFILTER, D3DTEXF_NONE);
	dev->SetSamplerState(0, D3DSAMP_ADDRESSU, D3DTADDRESS_CLAMP);
	dev->SetSamplerState(0, D3DSAMP_ADDRESSV, D3DTADDRESS_CLAMP);
	dev->SetSamplerState(0, D3DSAMP_SRGBTEXTURE, FALSE);
	dev->SetRenderState(D3DRS_ZENABLE, FALSE);
	dev->SetRenderState(D3DRS_ZWRITEENABLE, FALSE);
	dev->SetRenderState(D3DRS_STENCILENABLE, FALSE);
	dev->SetRenderState(D3DRS_SCISSORTESTENABLE, FALSE);
	dev->SetRenderState(D3DRS_ALPHATESTENABLE, FALSE);
	dev->SetRenderState(D3DRS_CULLMODE, D3DCULL_NONE);
	dev->SetRenderState(D3DRS_LIGHTING, FALSE);
	dev->SetRenderState(D3DRS_FOGENABLE, FALSE);
	dev->SetRenderState(D3DRS_SRGBWRITEENABLE, FALSE);
	dev->SetRenderState(D3DRS_COLORWRITEENABLE, 0xF);
	// Minecraft's GUI blends onto a transparent clear, so its colours are already premultiplied.
	dev->SetRenderState(D3DRS_ALPHABLENDENABLE, TRUE);
	dev->SetRenderState(D3DRS_SEPARATEALPHABLENDENABLE, FALSE);
	dev->SetRenderState(D3DRS_BLENDOP, D3DBLENDOP_ADD);
	dev->SetRenderState(D3DRS_SRCBLEND, D3DBLEND_ONE);
	dev->SetRenderState(D3DRS_DESTBLEND, D3DBLEND_INVSRCALPHA);

	struct V {
		float x, y, z, rhw, u, v;
	};
	float W = float(bb.Width) - 0.5f, H = float(bb.Height) - 0.5f;
	V quad[4] = {
		{-0.5f, -0.5f, 0.0f, 1.0f, 0.0f, 0.0f},
		{W, -0.5f, 0.0f, 1.0f, 1.0f, 0.0f},
		{-0.5f, H, 0.0f, 1.0f, 0.0f, 1.0f},
		{W, H, 0.0f, 1.0f, 1.0f, 1.0f},
	};
	if (SUCCEEDED(dev->BeginScene())) {
		dev->DrawPrimitiveUP(D3DPT_TRIANGLESTRIP, 2, quad, sizeof(V));
		dev->EndScene();
		if (!g_loggedFirstDraw && g_log) {
			g_loggedFirstDraw = true;
			g_log("overlay: drawing Minecraft %ux%u onto %ux%u", w, h, bb.Width, bb.Height);
		}
	}

	dev->SetRenderTarget(0, oldTarget);
	dev->SetDepthStencilSurface(oldDepth);
	if (oldTarget) oldTarget->Release();
	if (oldDepth) oldDepth->Release();
	g_state->Apply();
	backBuffer->Release();
}

HRESULT WINAPI hkPresent(IDirect3DDevice9* dev, const RECT* src, const RECT* dst, HWND wnd, const RGNDATA* dirty) {
	draw(dev);
	return forDevice(dev)->present(dev, src, dst, wnd, dirty);
}

HRESULT WINAPI hkPresentEx(IDirect3DDevice9Ex* dev, const RECT* src, const RECT* dst, HWND wnd, const RGNDATA* dirty, DWORD flags) {
	draw(dev);
	return forDevice(dev)->presentEx(dev, src, dst, wnd, dirty, flags);
}

HRESULT WINAPI hkReset(IDirect3DDevice9* dev, D3DPRESENT_PARAMETERS* pp) {
	releaseDeviceObjects();
	return forDevice(dev)->reset(dev, pp);
}

HRESULT WINAPI hkResetEx(IDirect3DDevice9Ex* dev, D3DPRESENT_PARAMETERS* pp, D3DDISPLAYMODEEX* mode) {
	releaseDeviceObjects();
	return forDevice(dev)->resetEx(dev, pp, mode);
}

// IDirect3DDevice9 vtable slots (d3d9.h declaration order).
constexpr int kReset = 16, kPresent = 17, kPresentEx = 121, kResetEx = 132;

bool patch(void** vt, int slot, void* fn, void** original) {
	if (vt[slot] == fn) {
		return true;
	}
	DWORD old;
	if (!VirtualProtect(&vt[slot], sizeof(void*), PAGE_READWRITE, &old)) {
		return false;
	}
	*original = vt[slot];
	vt[slot] = fn;
	VirtualProtect(&vt[slot], sizeof(void*), old, &old);
	return true;
}

void hookVtable(void** vt, bool ex) {
	for (Hooked& h : g_vt) {
		if (h.vtable == vt) {
			return; // already patched (plain and Ex share it)
		}
	}
	Hooked* h = nullptr;
	for (Hooked& slot : g_vt) {
		if (!slot.vtable) {
			h = &slot;
			break;
		}
	}
	if (!h) {
		return;
	}
	h->vtable = vt;
	patch(vt, kPresent, reinterpret_cast<void*>(&hkPresent), reinterpret_cast<void**>(&h->present));
	patch(vt, kReset, reinterpret_cast<void*>(&hkReset), reinterpret_cast<void**>(&h->reset));
	if (ex) {
		patch(vt, kPresentEx, reinterpret_cast<void*>(&hkPresentEx), reinterpret_cast<void**>(&h->presentEx));
		patch(vt, kResetEx, reinterpret_cast<void*>(&hkResetEx), reinterpret_cast<void**>(&h->resetEx));
	}
}

LRESULT CALLBACK dummyProc(HWND w, UINT m, WPARAM a, LPARAM b) {
	return DefWindowProcA(w, m, a, b);
}

int g_candidates = 0;

struct Range {
	uintptr_t lo = 0, hi = 0;
	bool has(const void* p) const { return uintptr_t(p) >= lo && uintptr_t(p) < hi; }
};

Range moduleRange(HMODULE m) {
	auto* dos = reinterpret_cast<IMAGE_DOS_HEADER*>(m);
	auto* nt = reinterpret_cast<IMAGE_NT_HEADERS*>(reinterpret_cast<uint8_t*>(m) + dos->e_lfanew);
	return {uintptr_t(m), uintptr_t(m) + nt->OptionalHeader.SizeOfImage};
}

// SEH-guarded read: a candidate pointer may point anywhere.
void* readPtr(const void* p) {
	__try {
		return *static_cast<void* const*>(p);
	} __except (EXCEPTION_EXECUTE_HANDLER) {
		return nullptr;
	}
}

// The candidate is the device itself, or any D3D9 resource (texture, surface, buffer), which
// knows its device.
bool isDevice(void* candidate, IDirect3DDevice9** out) {
	__try {
		auto* unknown = static_cast<IUnknown*>(candidate);
		if (SUCCEEDED(unknown->QueryInterface(__uuidof(IDirect3DDevice9), reinterpret_cast<void**>(out)))) {
			return true;
		}
		IDirect3DResource9* resource = nullptr;
		if (SUCCEEDED(unknown->QueryInterface(__uuidof(IDirect3DResource9), reinterpret_cast<void**>(&resource)))) {
			HRESULT hr = resource->GetDevice(out);
			resource->Release();
			return SUCCEEDED(hr) && *out;
		}
		return false;
	} __except (EXCEPTION_EXECUTE_HANDLER) {
		return false;
	}
}

IDirect3DDevice9* tryDevice(void* object, const Range& d3dCode) {
	if (!object || d3dCode.has(object) || uintptr_t(object) < 0x10000) {
		return nullptr;
	}
	void* vtable = readPtr(object);
	if (!vtable || !d3dCode.has(vtable)) {
		return nullptr;
	}
	// Don't require Present itself to be in d3d9.dll: Steam/Discord overlays patch that slot.
	g_candidates++;
	IDirect3DDevice9* dev = nullptr;
	return isDevice(object, &dev) ? dev : nullptr; // AddRef'd by QueryInterface
}

// Portal's shaderapidx9 holds its IDirect3DDevice9 either in a global or inside a heap object a
// global points to (its shader-device wrapper). Scan that DLL's writable data, and one level of
// objects behind it, for an object whose vtable lives in d3d9.dll and which answers
// QueryInterface(IDirect3DDevice9). Exact, and independent of game build.
IDirect3DDevice9* findGameDevice() {
	HMODULE shaderApi = GetModuleHandleA("shaderapidx9.dll");
	HMODULE d3d9 = GetModuleHandleA("d3d9.dll");
	if (!shaderApi || !d3d9) {
		return nullptr;
	}
	Range d3dCode = moduleRange(d3d9);
	auto* dos = reinterpret_cast<IMAGE_DOS_HEADER*>(shaderApi);
	auto* nt = reinterpret_cast<IMAGE_NT_HEADERS*>(reinterpret_cast<uint8_t*>(shaderApi) + dos->e_lfanew);
	for (int depth = 0; depth < 2; depth++) {
		IMAGE_SECTION_HEADER* sec = IMAGE_FIRST_SECTION(nt);
		for (int i = 0; i < nt->FileHeader.NumberOfSections; i++, sec++) {
			if (!(sec->Characteristics & IMAGE_SCN_MEM_WRITE)) {
				continue;
			}
			auto* begin = reinterpret_cast<void**>(reinterpret_cast<uint8_t*>(shaderApi) + sec->VirtualAddress);
			size_t count = sec->Misc.VirtualSize / sizeof(void*);
			for (size_t k = 0; k < count; k++) {
				void* object = begin[k];
				if (depth == 0) {
					if (IDirect3DDevice9* dev = tryDevice(object, d3dCode)) {
						return dev;
					}
					continue;
				}
				if (!object || uintptr_t(object) < 0x10000 || d3dCode.has(object)) {
					continue;
				}
				MEMORY_BASIC_INFORMATION mbi;
				if (!VirtualQuery(object, &mbi, sizeof mbi) || mbi.State != MEM_COMMIT ||
					(mbi.Protect & (PAGE_NOACCESS | PAGE_GUARD)) || !(mbi.Protect & (PAGE_READWRITE | PAGE_READONLY | PAGE_EXECUTE_READWRITE))) {
					continue;
				}
				uintptr_t end = uintptr_t(mbi.BaseAddress) + mbi.RegionSize;
				for (int f = 0; f < 64 && uintptr_t(static_cast<void**>(object) + f + 1) <= end; f++) {
					if (IDirect3DDevice9* dev = tryDevice(static_cast<void**>(object)[f], d3dCode)) {
						return dev;
					}
				}
			}
		}
	}
	return nullptr;
}

} // namespace

bool init(LogFn log) {
	g_log = log;
	if (g_hooked) {
		return true;
	}
	g_mapping = CreateFileMappingA(INVALID_HANDLE_VALUE, nullptr, PAGE_READWRITE, 0, pcproto::kOverlayBytes, pcproto::kOverlayMapping);
	if (!g_mapping) {
		log("overlay: CreateFileMapping failed (%lu)", GetLastError());
		return false;
	}
	g_shm = static_cast<uint8_t*>(MapViewOfFile(g_mapping, FILE_MAP_ALL_ACCESS, 0, 0, pcproto::kOverlayBytes));
	if (!g_shm) {
		log("overlay: MapViewOfFile failed (%lu)", GetLastError());
		return false;
	}
	g_header = reinterpret_cast<pcproto::OverlayHeader*>(g_shm);
	g_header->reading = 0xFFFFFFFFu;
	std::memcpy(g_header->magic, "PCO1", 4);

	static const char* const kRendererModules[] = {"shaderapidx9.dll", "shaderapivk.dll", "shaderapiempty.dll", "d3d9.dll", "dxvk_d3d9.dll", "vulkan-1.dll"};
	for (const char* m : kRendererModules) {
		if (HMODULE h = GetModuleHandleA(m)) {
			char path[MAX_PATH] = {};
			GetModuleFileNameA(h, path, MAX_PATH);
			log("overlay: loaded %s (%s)", m, path);
		}
	}

	if (IDirect3DDevice9* dev = findGameDevice()) {
		IDirect3DDevice9Ex* ex = nullptr;
		bool isEx = SUCCEEDED(dev->QueryInterface(__uuidof(IDirect3DDevice9Ex), reinterpret_cast<void**>(&ex)));
		if (ex) ex->Release();
		hookVtable(*reinterpret_cast<void***>(dev), isEx);
		void** vt = *reinterpret_cast<void***>(dev);
		HMODULE owner = nullptr;
		char ownerName[MAX_PATH] = "?";
		if (GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT, static_cast<LPCSTR>(vt[kPresent]), &owner)) {
			GetModuleFileNameA(owner, ownerName, MAX_PATH);
		}
		log("overlay: found Portal's device %p (%s), vtable %p, Present currently in %s", dev, isEx ? "D3D9Ex" : "D3D9", vt, ownerName);
		dev->Release();
	} else {
		log("overlay: Portal's device not found in shaderapidx9 (%d d3d9 objects checked); falling back to probe devices", g_candidates);
	}

	WNDCLASSA wc = {};
	wc.lpfnWndProc = dummyProc;
	wc.hInstance = GetModuleHandleA(nullptr);
	wc.lpszClassName = "PortalCraftD3D9Probe";
	RegisterClassA(&wc);
	HWND wnd = CreateWindowA(wc.lpszClassName, "", WS_OVERLAPPEDWINDOW, 0, 0, 128, 128, nullptr, nullptr, wc.hInstance, nullptr);

	D3DPRESENT_PARAMETERS pp = {};
	pp.Windowed = TRUE;
	pp.SwapEffect = D3DSWAPEFFECT_DISCARD;
	pp.hDeviceWindow = wnd;
	pp.BackBufferFormat = D3DFMT_UNKNOWN;
	pp.BackBufferWidth = 64; // explicit: a tiny hidden window can have an empty client area
	pp.BackBufferHeight = 64;
	pp.BackBufferCount = 1;

	if (IDirect3D9* d3d = Direct3DCreate9(D3D_SDK_VERSION)) {
		IDirect3DDevice9* dev = nullptr;
		HRESULT hr = d3d->CreateDevice(D3DADAPTER_DEFAULT, D3DDEVTYPE_HAL, wnd, D3DCREATE_SOFTWARE_VERTEXPROCESSING, &pp, &dev);
		if (SUCCEEDED(hr)) {
			hookVtable(*reinterpret_cast<void***>(dev), false);
			dev->Release();
		} else {
			log("overlay: probe CreateDevice failed hr=0x%08lX (window %p)", static_cast<unsigned long>(hr), wnd);
		}
		d3d->Release();
	} else {
		log("overlay: Direct3DCreate9 returned null");
	}
	using CreateExFn = HRESULT(WINAPI*)(UINT, IDirect3D9Ex**);
	auto createEx = reinterpret_cast<CreateExFn>(GetProcAddress(GetModuleHandleA("d3d9.dll"), "Direct3DCreate9Ex"));
	IDirect3D9Ex* d3dEx = nullptr;
	if (createEx && SUCCEEDED(createEx(D3D_SDK_VERSION, &d3dEx))) {
		IDirect3DDevice9Ex* dev = nullptr;
		HRESULT hr = d3dEx->CreateDeviceEx(D3DADAPTER_DEFAULT, D3DDEVTYPE_HAL, wnd, D3DCREATE_SOFTWARE_VERTEXPROCESSING, &pp, nullptr, &dev);
		if (SUCCEEDED(hr)) {
			hookVtable(*reinterpret_cast<void***>(dev), true);
			dev->Release();
		} else {
			log("overlay: probe CreateDeviceEx failed hr=0x%08lX", static_cast<unsigned long>(hr));
		}
		d3dEx->Release();
	}
	DestroyWindow(wnd);
	UnregisterClassA(wc.lpszClassName, wc.hInstance);

	g_hooked = g_vt[0].vtable != nullptr;
	log("overlay: shared memory %s ready; d3d9 vtables hooked: %p %p %p", pcproto::kOverlayMapping, g_vt[0].vtable, g_vt[1].vtable, g_vt[2].vtable);
	return g_hooked;
}

} // namespace overlay
