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
} // namespace overlay
