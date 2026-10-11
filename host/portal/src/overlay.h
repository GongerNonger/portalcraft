// Minecraft's hand + HUD drawn over the host frame (see overlay.cpp).
#pragma once

namespace overlay {
using LogFn = void (*)(const char* fmt, ...);
// Creates the shared-memory mapping and hooks D3D9 presents. Call once Portal's device exists.
bool init(LogFn log);
// Portal's IDirect3DDevice9 once init found it (else null).
void* device();
// Minecraft is publishing frames right now (linked and in game).
bool overlayFresh();
// One of Portal's menus with its title on it (the title screen, the pause screen) is up: "CRAFT" is
// drawn after the title, in Minecraft's letters.
void setTitle(bool shown);
} // namespace overlay
