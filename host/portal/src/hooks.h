#pragma once

#include <windows.h>

// Every vtable slot PortalCraft patches goes through here, so Unload can put them all back: Portal
// unloads plugins on exit (and on plugin_unload), and a frame or trace after that would otherwise
// call into freed code.
namespace hooks {

struct Patch {
	void** slot;
	void* original;
	void* replacement;
};

inline Patch g_patches[64];
inline int g_patchCount = 0;

// Points vtable[slot] at `replacement`, keeping the old entry in *original. True if it's hooked.
inline bool patch(void** vtable, int slot, void* replacement, void** original) {
	void** at = &vtable[slot];
	if (*at == replacement) {
		return true;
	}
	DWORD old;
	if (!VirtualProtect(at, sizeof(void*), PAGE_READWRITE, &old)) {
		return false;
	}
	*original = *at;
	*at = replacement;
	VirtualProtect(at, sizeof(void*), old, &old);
	if (g_patchCount < int(sizeof g_patches / sizeof g_patches[0])) {
		g_patches[g_patchCount++] = {at, *original, replacement};
	}
	return true;
}

// Puts back every slot that still holds our replacement (newest first).
inline void unpatchAll() {
	for (int i = g_patchCount - 1; i >= 0; i--) {
		Patch& p = g_patches[i];
		// The DLL holding the vtable may be gone already (Portal shutting down): leave it be.
		MEMORY_BASIC_INFORMATION info;
		if (!VirtualQuery(p.slot, &info, sizeof info) || info.State != MEM_COMMIT) {
			continue;
		}
		DWORD old;
		__try {
			if (*p.slot == p.replacement && VirtualProtect(p.slot, sizeof(void*), PAGE_READWRITE, &old)) {
				*p.slot = p.original;
				VirtualProtect(p.slot, sizeof(void*), old, &old);
			}
		} __except (EXCEPTION_EXECUTE_HANDLER) {
		}
	}
	g_patchCount = 0;
}

} // namespace hooks
