// Minecraft's placed blocks drawn inside Portal's 3D scene (see worldrender.cpp).
#pragma once

#include "overlay.h"
#include "sdk.h"

namespace worldrender {
// Creates the world mapping and hooks IVRenderView::SceneEnd. Call after overlay::init.
bool init(overlay::LogFn log, sdk::CreateInterfaceFn engineFactory);
// Once per presented frame (from the overlay's Present hook).
void frameDone();
// A new map: forget the cached lighting.
void levelChanged();

// Seconds spent drawing Minecraft's world since the last call (for the perf: log line).
double takeDrawSeconds();
// Dev: how bright Portal's lighting makes Minecraft's geometry (0 = lighting off).
void setExposure(float exposure);
// Before a device reset.
void releaseDeviceObjects();
} // namespace worldrender
