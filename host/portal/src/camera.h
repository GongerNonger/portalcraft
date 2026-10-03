// Third-person camera (Minecraft's F5) inside Portal (see camera.cpp).
#pragma once

#include "overlay.h"
#include "sdk.h"

namespace camera {
// Finds Portal's client mode and hooks its view override. Call once the client is loaded; safe
// to call again (does nothing once done or once it has given up).
void init(overlay::LogFn log);
// Undoes the hooks (plugin unload).
void shutdown();
// Minecraft's camera mode (McState.cameraMode: 0 first person, 1 behind, 2 in front) and how far its
// own camera gets before its blocks stop it (McState.cameraDistance, host units).
void setMode(int mode, float minecraftDistance);
// While true, Portal doesn't draw its own player (Chell), through portals or as a portal ghost.
// Whether Portal may draw its viewmodel (its portal gun) at all.
void setViewModel(bool allowed);

void setHideBody(bool hide);
// True while the camera is pulled out of the player's head this frame.
bool thirdPerson();
// Where Portal draws its own player this frame (feet, host units): the avatar is drawn here.
bool playerFeet(sdk::Vector* out);
} // namespace camera
