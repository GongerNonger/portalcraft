// Minecraft's placed blocks drawn inside Portal's 3D scene (see worldrender.cpp).
#pragma once

#include "overlay.h"
#include "sdk.h"

namespace worldrender {
// Creates the world mapping and hooks IVRenderView::SceneEnd. Call after overlay::init.
bool init(overlay::LogFn log, sdk::CreateInterfaceFn engineFactory);
// Once per presented frame (from the overlay's Present hook).
void frameDone();
// Before a device reset.
void releaseDeviceObjects();
} // namespace worldrender
