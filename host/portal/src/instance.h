#pragma once

#include <windows.h>

#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <string>

#include "../../../protocol/portalcraft_protocol.h"

// Which PortalCraft pair this Portal is half of. Normally there is one (instance 0) and everything
// here is the protocol's own constant. `hl2.exe ... -pcinstance N` (N from 1 to 9) makes a second,
// independent pair on the same PC, so two test runs can go at once: its link ports are 10*N higher,
// and its shared memory, log, ini and launch stamp carry a _N suffix. The Minecraft it starts is
// told through PORTALCRAFT_INSTANCE (launcher.cpp) and derives the same names (Instance.java).
namespace instance {

constexpr int kMax = 9;

inline int number() {
	static int n = -1;
	if (n < 0) {
		const char* line = GetCommandLineA();
		const char* flag = line ? std::strstr(line, "-pcinstance ") : nullptr;
		int value = flag ? std::atoi(flag + std::strlen("-pcinstance ")) : 0;
		n = value > 0 && value <= kMax ? value : 0;
	}
	return n;
}

inline uint16_t hostPort() {
	return static_cast<uint16_t>(pcproto::kHostPort + 10 * number());
}

inline uint16_t mcPort() {
	return static_cast<uint16_t>(pcproto::kMcPort + 10 * number());
}

// "portalcraft", ".log" -> "portalcraft.log", or "portalcraft_2.log" for instance 2.
inline std::string named(const char* base, const char* extension = "") {
	std::string name = base;
	if (number() > 0) {
		name += "_" + std::to_string(number());
	}
	return name + extension;
}

} // namespace instance
