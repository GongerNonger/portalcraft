#pragma once

#include <windows.h>

// Starts Minecraft with Portal (as SkyCraft's plugin starts it with Skyrim) and shows, in Portal's
// top-left corner, what it's up to until the two have linked.
namespace launcher {

using LogFn = void (*)(const char* fmt, ...);

// Reads portalcraft.ini next to the plugin DLL.
void init(LogFn log, HMODULE self);

// Once a frame from the main thread. The first call starts Minecraft if it isn't running (and the
// ini says to); later calls only update the on-screen status. engineClient is VEngineClient013.
void frame(bool mcLinked, bool inLevel, void* engineClient);

} // namespace launcher
