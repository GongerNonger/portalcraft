// Minecraft's F5 in Portal: a third-person camera, and the hooks that keep the first-person bits
// (Portal's gun view model, its crosshair) out of that view.
//
// Portal's thirdperson command is a cheat, so we don't touch it. Instead we hook the client mode's
// OverrideView, the client's own "move the camera after the player computed it" seam (it is how
// Source's third person works too): Portal fills in the eye position and angles, then we pull the
// camera back (or around to the front) along the view, stopped short of walls by a hull trace,
// the way Minecraft's camera does. Mouse look stays Portal's own, so it is as smooth as first person.
//
// Finding the client mode is pattern-free in spirit: IBaseClientDLL::HudProcessInput (slot 10) is
// `g_pClientMode->ProcessInput(b)`, which compiles to `mov ecx, [g_pClientMode]; mov eax, [ecx];
// jmp [eax + 4 * slot]`. That gives us the global and the ProcessInput slot; the slots we hook are at
// fixed distances from it in IClientMode (+6 OverrideView, +14 ShouldDrawViewModel, +15
// ShouldDrawCrosshair; checked against Portal build 19017868's client.dll, where ProcessInput is
// slot 11). Every hook is checked before use: the view setup must hold the player's eye.
//
// Chell -> Steve: Portal draws its own player (Chell) through portals, and a "ghost" copy of her
// while she stands in one. Both go through IClientRenderable::DrawModel (slot 10, checked in the
// same build), so while Minecraft drives the player we make that draw nothing for C_Portal_Player
// and for any C_PortalGhostRenderable showing her model. The vtables are found from MSVC RTTI in
// client.dll (class names, not code patterns). Steve is drawn instead by worldrender.

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>

#include <cmath>
#include <cstdint>
#include <cstring>

#include "camera.h"

namespace camera {
namespace {

using sdk::Vector;

overlay::LogFn g_log = nullptr;
int g_initState = 0; // 0 not yet, 1 hooked, -1 gave up
int g_tries = 0;

void* g_entityList = nullptr; // VClientEntityList003: 3 GetClientEntity(int)
void* g_trace = nullptr;      // EngineTraceClient003: 4 TraceRay(const Ray_t&, unsigned, ITraceFilter*, trace_t*)

int g_mode = 0;
float g_mcDistance = 0.0f;
bool g_third = false;    // this frame's view is third person
bool g_feetValid = false;
Vector g_feet{};
void* g_localEntity = nullptr;

// CViewSetup (SP 2013, view_shared.h): origin at 64, angles right after.
constexpr int kSetupOrigin = 64;
constexpr int kSetupAngles = 76;
int g_setupState = 0; // 0 not seen yet, 1 the view setup has held the player's eye
int g_setupMisses = 0;

// Hooked slots, for Unload.
struct Hook {
	void** vtable = nullptr;
	int slot = -1;
	void* original = nullptr;
};
Hook g_hookView, g_hookViewModel, g_hookCrosshair, g_hookPlayerDraw, g_hookGhostDraw;

bool g_hideBody = false;
const void* g_playerModel = nullptr; // Chell's model_t, which her ghosts share
int g_bodyState = 0;                 // 0 not yet, 1 hooked, -1 couldn't

bool patch(void** vtable, int slot, void* replacement, Hook* hook) {
	if (vtable[slot] == replacement) {
		return true; // already ours (keep the original we saved)
	}
	DWORD old;
	if (!VirtualProtect(&vtable[slot], sizeof(void*), PAGE_READWRITE, &old)) {
		return false;
	}
	hook->vtable = vtable;
	hook->slot = slot;
	hook->original = vtable[slot];
	vtable[slot] = replacement;
	VirtualProtect(&vtable[slot], sizeof(void*), old, &old);
	return true;
}

void unpatch(Hook* hook) {
	if (!hook->vtable) {
		return;
	}
	DWORD old;
	if (VirtualProtect(&hook->vtable[hook->slot], sizeof(void*), PAGE_READWRITE, &old)) {
		hook->vtable[hook->slot] = hook->original;
		VirtualProtect(&hook->vtable[hook->slot], sizeof(void*), old, &old);
	}
	hook->vtable = nullptr;
}

void* moduleInterface(const char* module, const char* name) {
	HMODULE m = GetModuleHandleA(module);
	auto factory = m ? reinterpret_cast<sdk::CreateInterfaceFn>(GetProcAddress(m, "CreateInterface")) : nullptr;
	return factory ? factory(name, nullptr) : nullptr;
}

// The local player's collision origin (its feet, where Portal draws it), from the client's own
// entity: the camera is computed from the same snapshot, so they never disagree.
bool localFeet(Vector* out) {
	if (!g_entityList) {
		return false;
	}
	__try {
		void* entity = sdk::vcall<void*>(g_entityList, 3, 1); // GetClientEntity(1): the SP player
		if (!entity) {
			return false;
		}
		void* collideable = sdk::vcall<void*>(entity, 3); // IClientUnknown::GetCollideable
		if (!collideable) {
			return false;
		}
		*out = *sdk::vcall<const Vector*>(collideable, 10); // ICollideable::GetCollisionOrigin
		g_localEntity = entity;
		void* renderable = sdk::vcall<void*>(entity, 5); // IClientUnknown::GetClientRenderable
		g_playerModel = renderable ? sdk::vcall<const void*>(renderable, 9) : nullptr; // IClientRenderable::GetModel
		return true;
	} __except (EXCEPTION_EXECUTE_HANDLER) {
		return false;
	}
}

// ---- tracing ---------------------------------------------------------------------------

struct alignas(16) Ray { // Ray_t
	float start[4];
	float delta[4];
	float startOffset[4];
	float extents[4];
	bool isRay;
	bool isSwept;
};

class SkipSelf { // ITraceFilter: 0 ShouldHitEntity(IHandleEntity*, int), 1 GetTraceType()
public:
	virtual bool ShouldHitEntity(void* entity, int /*contentsMask*/) { return entity != skip; }
	virtual int GetTraceType() { return 0; } // TRACE_EVERYTHING
	void* skip = nullptr;
};

constexpr unsigned kMaskSolid = 0x1 | 0x2 | 0x8 | 0x4000 | 0x2000000; // MASK_SOLID: solid, window, grate, moveable, monster

// How far along eye -> want a box of half-size `r` gets before it hits something (0..1).
float traceFraction(const Vector& eye, const Vector& want, float r) {
	if (!g_trace) {
		return 1.0f;
	}
	Ray ray{};
	ray.start[0] = eye.x, ray.start[1] = eye.y, ray.start[2] = eye.z;
	ray.delta[0] = want.x - eye.x, ray.delta[1] = want.y - eye.y, ray.delta[2] = want.z - eye.z;
	ray.extents[0] = ray.extents[1] = ray.extents[2] = r;
	ray.isRay = false;
	ray.isSwept = true;
	SkipSelf filter;
	filter.skip = g_localEntity;
	alignas(16) uint8_t trace[256] = {};
	__try {
		sdk::vcall<void>(g_trace, 4, static_cast<const Ray*>(&ray), kMaskSolid, static_cast<void*>(&filter), static_cast<void*>(trace));
	} __except (EXCEPTION_EXECUTE_HANDLER) {
		return 1.0f;
	}
	float fraction;
	std::memcpy(&fraction, trace + 44, 4); // CBaseTrace::fraction
	bool startSolid = trace[55] != 0;
	if (startSolid || !(fraction >= 0.0f && fraction <= 1.0f)) {
		return 0.0f;
	}
	return fraction;
}

// ---- hooks ------------------------------------------------------------------------------

using OverrideViewFn = void(__thiscall*)(void* self, void* setup);
using BoolFn = bool(__thiscall*)(void* self);

// Minecraft's third-person distance is 4 blocks.
constexpr float kDistance = 160.0f; // Minecraft's third-person distance for a player Steve's size, in units
constexpr float kHull = 6.0f;

bool g_grounded = false; // Minecraft has Steve on the ground (setGrounded)
float g_wantEye = 0.0f; // the eye's height over the feet to show (0: Portal's own)

void __fastcall hkOverrideView(void* self, void* /*edx*/, void* setupRaw) {
	reinterpret_cast<OverrideViewFn>(g_hookView.original)(self, setupRaw);
	g_third = false;
	g_feetValid = false;
	auto* setup = static_cast<uint8_t*>(setupRaw);
	Vector& origin = *reinterpret_cast<Vector*>(setup + kSetupOrigin);
	Vector& angles = *reinterpret_cast<Vector*>(setup + kSetupAngles);
	Vector feet;
	if (!localFeet(&feet)) {
		return;
	}
	// Every frame: is this view the player's own eyes? (Not during a level's intro camera, a
	// view entity, or if the layout were ever different.) Only then is it ours to move.
	float dx = origin.x - feet.x, dy = origin.y - feet.y, dz = origin.z - feet.z;
	bool eyeOverFeet = std::fabs(dx) < 4.0f && std::fabs(dy) < 4.0f && dz > 10.0f && dz < 90.0f;
	if (!eyeOverFeet) {
		if (g_setupMisses++ == 0) {
			g_log("camera: view (%.1f %.1f %.1f) isn't over the player's feet (%.1f %.1f %.1f): leaving it alone (logged once)", origin.x, origin.y,
				origin.z, feet.x, feet.y, feet.z);
		}
		return;
	}
	if (g_setupState == 0) {
		g_setupState = 1;
		g_log("camera: view setup check: eye (%.1f %.1f %.1f) over feet (%.1f %.1f %.1f) -> layout OK", origin.x, origin.y, origin.z, feet.x, feet.y,
			feet.z);
	}
	g_feet = feet;
	g_feetValid = true;
	// Steve's eye height, not Chell's: sneaking, Portal ducks its player and drops the eye to 28 units,
	// most of the way to the floor, where Minecraft's sneak only dips to about 51.
	if (g_wantEye > 0.0f) {
		static float eye = 64.0f;
		if (std::fabs(eye - dz) > 40.0f) {
			eye = dz; // a fresh view: start from where Portal has it
		}
		eye += (g_wantEye - eye) * 0.2f;
		// Small steps under a walking Steve are smoothed out of the view, as Source does for its own
		// player on stairs. Minecraft builds a sloped or domed surface (a floor button's top, a ramp)
		// out of two-unit columns, and takes each as an instant step: walking over the button the
		// eye twitched a unit up or down at every one. Only while he's on the ground, and only up to
		// a stair's height: a jump, a fall or a teleport is shown as it is.
		static float shownFeet = 0.0f;
		static DWORD last = 0;
		DWORD now = GetTickCount();
		float dt = last && now - last < 200 ? float(now - last) / 1000.0f : 0.0f;
		last = now;
		if (!g_grounded || dt == 0.0f || std::fabs(feet.z - shownFeet) > 18.0f) {
			shownFeet = feet.z;
		} else {
			float k = dt * 14.0f;
			shownFeet += (feet.z - shownFeet) * (k > 1.0f ? 1.0f : k);
		}
		origin.z = shownFeet + eye;
	}
	if (g_mode == 0) {
		return;
	}
	const float d2r = 3.14159265f / 180.0f;
	float p = angles.x * d2r, y = angles.y * d2r;
	Vector forward{std::cos(p) * std::cos(y), std::cos(p) * std::sin(y), -std::sin(p)};
	float sign = g_mode == 2 ? 1.0f : -1.0f; // in front of the face, or behind the head
	Vector eye = origin;
	// No further than Minecraft's camera got (its blocks), then stopped by our own walls.
	float distance = g_mcDistance > 0.0f && g_mcDistance < kDistance ? g_mcDistance : kDistance;
	Vector want{eye.x + forward.x * distance * sign, eye.y + forward.y * distance * sign, eye.z + forward.z * distance * sign};
	float f = traceFraction(eye, want, kHull);
	origin = {eye.x + (want.x - eye.x) * f, eye.y + (want.y - eye.y) * f, eye.z + (want.z - eye.z) * f};
	if (g_mode == 2) {
		angles.x = -angles.x; // look back at the player
		angles.y += 180.0f;
	}
	g_third = true;
}

bool g_viewModelAllowed = true;

bool __fastcall hkShouldDrawViewModel(void* self, void* /*edx*/) {
	return !g_third && g_viewModelAllowed && reinterpret_cast<BoolFn>(g_hookViewModel.original)(self);
}

bool __fastcall hkShouldDrawCrosshair(void* self, void* /*edx*/) {
	return !g_third && reinterpret_cast<BoolFn>(g_hookCrosshair.original)(self);
}

// ---- Chell's draws ------------------------------------------------------------------------

using DrawModelFn = int(__thiscall*)(void* self, int flags);

int __fastcall hkPlayerDrawModel(void* self, void* /*edx*/, int flags) {
	return g_hideBody ? 0 : reinterpret_cast<DrawModelFn>(g_hookPlayerDraw.original)(self, flags);
}

int __fastcall hkGhostDrawModel(void* self, void* /*edx*/, int flags) {
	if (g_hideBody && g_playerModel && sdk::vcall<const void*>(self, 9) == g_playerModel) {
		return 0; // a ghost of Chell (she's standing in a portal)
	}
	return reinterpret_cast<DrawModelFn>(g_hookGhostDraw.original)(self, flags);
}

// Scans the readable sections of `module` for the vtable of the sub-object at `offset` of the class
// whose RTTI name is `mangled` (".?AVName@@"): TypeDescriptor -> CompleteObjectLocator -> vtable.
void** findVtable(HMODULE module, const char* mangled, uint32_t offset) {
	auto* base = reinterpret_cast<uint8_t*>(module);
	auto* nt = reinterpret_cast<IMAGE_NT_HEADERS*>(base + reinterpret_cast<IMAGE_DOS_HEADER*>(base)->e_lfanew);
	IMAGE_SECTION_HEADER* sections = IMAGE_FIRST_SECTION(nt);
	int count = nt->FileHeader.NumberOfSections;
	size_t len = std::strlen(mangled) + 1;
	uint32_t want = 0; // pass 0: nothing; pass 1: the TypeDescriptor; pass 2: the locator
	__try {
		for (int pass = 0; pass < 3; pass++) {
			uint32_t found = 0;
			for (int k = 0; k < count && !found; k++) {
				if (!(sections[k].Characteristics & IMAGE_SCN_MEM_READ)) {
					continue;
				}
				uint8_t* begin = base + sections[k].VirtualAddress;
				uint32_t size = sections[k].Misc.VirtualSize;
				if (pass == 0) { // the TypeDescriptor's name, 8 bytes into it
					for (uint32_t i = 8; i + len <= size && !found; i++) {
						if (begin[i] == '.' && std::memcmp(begin + i, mangled, len) == 0) {
							found = uint32_t(reinterpret_cast<uintptr_t>(begin + i - 8));
						}
					}
					continue;
				}
				for (uint32_t i = 0; i + 20 <= size && !found; i += 4) {
					auto* d = reinterpret_cast<uint32_t*>(begin + i);
					if (pass == 1 && d[3] == want && d[0] == 0 && d[1] == offset) { // CompleteObjectLocator
						found = uint32_t(reinterpret_cast<uintptr_t>(d));
					} else if (pass == 2 && i >= 4 && d[0] == want) { // the vtable's -1 slot points at the locator
						return reinterpret_cast<void**>(d + 1);
					}
				}
			}
			if (!found) {
				return nullptr;
			}
			want = found;
		}
	} __except (EXCEPTION_EXECUTE_HANDLER) {
	}
	return nullptr;
}

void hookBody() {
	if (g_bodyState != 0) {
		return;
	}
	HMODULE client = GetModuleHandleA("client.dll");
	void** player = client ? findVtable(client, ".?AVC_Portal_Player@@", 4) : nullptr;
	void** ghost = client ? findVtable(client, ".?AVC_PortalGhostRenderable@@", 4) : nullptr;
	if (!player) {
		g_bodyState = -1;
		g_log("camera: no C_Portal_Player renderable vtable in client.dll; Chell stays visible");
		return;
	}
	patch(player, 10, reinterpret_cast<void*>(&hkPlayerDrawModel), &g_hookPlayerDraw);
	if (ghost) {
		patch(ghost, 10, reinterpret_cast<void*>(&hkGhostDrawModel), &g_hookGhostDraw);
	}
	g_bodyState = 1;
	g_log("camera: Chell's DrawModel hooked (player vtable %p, ghost vtable %p)", player, ghost);
}

// True if the function at `fn` is just `mov al, 1; ret` (ClientModeShared's "yes").
bool returnsTrue(const void* fn) {
	const uint8_t* b = static_cast<const uint8_t*>(fn);
	return b[0] == 0xB0 && b[1] == 0x01 && b[2] == 0xC3;
}

} // namespace

void init(overlay::LogFn log) {
	if (g_initState != 0) {
		return;
	}
	g_log = log;
	void* client = moduleInterface("client.dll", "VClient017");
	if (!client) {
		if (++g_tries > 600) {
			g_initState = -1;
			log("camera: no VClient017; third person off");
		}
		return;
	}
	g_entityList = moduleInterface("client.dll", "VClientEntityList003");
	g_trace = moduleInterface("engine.dll", "EngineTraceClient003");

	// HudProcessInput: push ebp; mov ebp, esp; mov ecx, [g_pClientMode]; mov eax, [ecx]; pop ebp; jmp [eax + disp8]
	const uint8_t* code = static_cast<const uint8_t*>((*static_cast<void***>(client))[10]);
	void** clientModeGlobal = nullptr;
	int processInputSlot = -1;
	for (int i = 0; i + 6 < 24 && !clientModeGlobal; i++) {
		if (code[i] == 0x8B && code[i + 1] == 0x0D) {
			std::memcpy(&clientModeGlobal, code + i + 2, 4);
			for (int j = i + 6; j + 2 < 32; j++) {
				if (code[j] == 0xFF && (code[j + 1] == 0x60 || code[j + 1] == 0x50)) {
					processInputSlot = code[j + 2] / 4;
					break;
				}
			}
		}
	}
	if (!clientModeGlobal || processInputSlot < 0) {
		g_initState = -1;
		log("camera: HudProcessInput doesn't look like g_pClientMode->ProcessInput (%02X %02X %02X %02X %02X %02X); third person off", code[0],
			code[1], code[2], code[3], code[4], code[5]);
		return;
	}
	void* clientMode = *clientModeGlobal;
	if (!clientMode) {
		return; // not created yet: next frame
	}
	if (processInputSlot != 11) {
		log("camera: note: ProcessInput is slot %d here (11 in build 19017868); hooking relative to it", processInputSlot);
	}
	void** vt = *static_cast<void***>(clientMode);
	int viewSlot = processInputSlot + 6, viewModelSlot = processInputSlot + 14, crosshairSlot = processInputSlot + 15;
	if (!patch(vt, viewSlot, reinterpret_cast<void*>(&hkOverrideView), &g_hookView)) {
		g_initState = -1;
		log("camera: can't patch the client mode's vtable");
		return;
	}
	if (returnsTrue(vt[viewModelSlot])) {
		patch(vt, viewModelSlot, reinterpret_cast<void*>(&hkShouldDrawViewModel), &g_hookViewModel);
	} else {
		log("camera: slot %d isn't a plain ShouldDrawViewModel; the gun stays visible in third person", viewModelSlot);
	}
	if (returnsTrue(vt[crosshairSlot])) {
		patch(vt, crosshairSlot, reinterpret_cast<void*>(&hkShouldDrawCrosshair), &g_hookCrosshair);
	}
	hookBody();
	g_initState = 1;
	log("camera: client mode %p, ProcessInput slot %d; hooked OverrideView %d%s%s; entity list %p, trace %p", clientMode, processInputSlot, viewSlot,
		g_hookViewModel.vtable ? ", view model" : "", g_hookCrosshair.vtable ? ", crosshair" : "", g_entityList, g_trace);
}

void shutdown() {
	unpatch(&g_hookView);
	unpatch(&g_hookViewModel);
	unpatch(&g_hookCrosshair);
	unpatch(&g_hookPlayerDraw);
	unpatch(&g_hookGhostDraw);
	g_initState = 0;
	g_bodyState = 0;
}

void setGrounded(bool grounded, float verticalSpeed) {
	// On the ground now or a moment ago (sinking a hair into a button's top and being set back on
	// it reads as off the ground for a tick), and not on the way up or down at a jump's speed.
	static DWORD lastOn = 0;
	DWORD now = GetTickCount();
	if (grounded) {
		lastOn = now;
	}
	g_grounded = now - lastOn < 150 && std::fabs(verticalSpeed) < 120.0f;
}

void setEyeHeight(float units) {
	g_wantEye = units;
}

void setViewModel(bool allowed) {
	g_viewModelAllowed = allowed;
}

void setMode(int mode, float minecraftDistance) {
	g_mcDistance = minecraftDistance;
	if (mode != g_mode && g_log) {
		g_log("camera: Minecraft camera mode %d", mode);
	}
	g_mode = mode;
}

void setHideBody(bool hide) {
	if (hide != g_hideBody && g_log) {
		g_log(hide ? "Chell hidden (Steve drawn instead)" : "Chell shown again");
	}
	g_hideBody = hide;
}

bool thirdPerson() {
	return g_third;
}

bool playerFeet(sdk::Vector* out) {
	if (!g_feetValid) {
		return false;
	}
	*out = g_feet;
	return true;
}

} // namespace camera
