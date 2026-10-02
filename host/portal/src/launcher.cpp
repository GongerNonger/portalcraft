#include <winsock2.h>
#include <windows.h>

#include <cstdio>
#include <cstring>
#include <string>

#include "../../../protocol/portalcraft_protocol.h"
#include "launcher.h"

// portalcraft.ini (install.cmd writes one pointing at this repo):
//
//   [Minecraft]
//   start_with_portal=1           ; 0: start Minecraft yourself (play-portal.cmd, gradle runClient)
//   launcher=C:\tmp\portalcraft\gradle.cmd
//   arguments=runClient --no-configuration-cache --args="--quickPlaySingleplayer PortalCraft"
//   directory=C:\tmp\portalcraft  ; where it runs; its run\logs\latest.log is Minecraft's log
//
// Minecraft is started with PORTALCRAFT_STARTED_BY_HOST=1, which makes it hide its window and
// quit again when Portal closes (HostLifecycle.java). Whether one is already running is whether
// Minecraft's link port (127.0.0.1:27516) is taken: the PortalCraft mod holds it while it runs.
namespace launcher {
namespace {

LogFn g_log = nullptr;
bool g_startWithPortal = false;
std::string g_launcher, g_arguments, g_directory, g_iniPath, g_mapsDir;

enum class State { Unchecked, AlreadyRunning, Started, StartFailed, Off, Linked, Lost };
State g_state = State::Unchecked;
DWORD g_since = 0;

std::string iniString(const char* key, const char* fallback) {
	char buf[1024];
	GetPrivateProfileStringA("Minecraft", key, fallback, buf, sizeof buf, g_iniPath.c_str());
	return buf;
}

bool minecraftRunning() {
	SOCKET s = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
	if (s == INVALID_SOCKET) {
		return false;
	}
	BOOL exclusive = TRUE;
	setsockopt(s, SOL_SOCKET, SO_EXCLUSIVEADDRUSE, reinterpret_cast<const char*>(&exclusive), sizeof exclusive);
	sockaddr_in addr{};
	addr.sin_family = AF_INET;
	addr.sin_port = htons(pcproto::kMcPort);
	addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
	bool taken = bind(s, reinterpret_cast<sockaddr*>(&addr), sizeof addr) != 0 && WSAGetLastError() == WSAEADDRINUSE;
	closesocket(s);
	return taken;
}

bool start() {
	std::string command = "cmd.exe /c \"\"" + g_launcher + "\" " + g_arguments + "\"";
	SetEnvironmentVariableA("PORTALCRAFT_STARTED_BY_HOST", "1");
	SetEnvironmentVariableA("PORTALCRAFT_MAPS", g_mapsDir.c_str()); // where Minecraft reads Portal's maps for collision
	STARTUPINFOA si{};
	si.cb = sizeof si;
	si.dwFlags = STARTF_USESHOWWINDOW;
	si.wShowWindow = SW_HIDE;
	PROCESS_INFORMATION pi{};
	const char* dir = g_directory.empty() ? nullptr : g_directory.c_str();
	// Out of Portal's job if it has one (Steam's), so Portal closing doesn't kill Minecraft before
	// it has saved: it quits by itself once Portal is gone.
	BOOL ok = CreateProcessA(nullptr, command.data(), nullptr, nullptr, FALSE, CREATE_NO_WINDOW | CREATE_NEW_PROCESS_GROUP | CREATE_BREAKAWAY_FROM_JOB,
		nullptr, dir, &si, &pi);
	if (!ok) {
		ok = CreateProcessA(nullptr, command.data(), nullptr, nullptr, FALSE, CREATE_NO_WINDOW | CREATE_NEW_PROCESS_GROUP, nullptr, dir, &si, &pi);
	}
	SetEnvironmentVariableA("PORTALCRAFT_STARTED_BY_HOST", nullptr);
	SetEnvironmentVariableA("PORTALCRAFT_MAPS", nullptr);
	if (!ok) {
		g_log("launcher: couldn't start Minecraft (%lu): %s", GetLastError(), command.c_str());
		return false;
	}
	CloseHandle(pi.hThread);
	CloseHandle(pi.hProcess);
	g_log("launcher: started Minecraft: %s", command.c_str());
	return true;
}

// IVEngineClient013 slot 29: Con_NPrintf(int pos, const char* fmt, ...), a line in the top-left
// corner for a moment. Variadic, so __cdecl with `this` first.
void show(void* engineClient, int line, const char* text) {
	if (!engineClient) {
		return;
	}
	using Fn = void(__cdecl*)(void* self, int pos, const char* fmt, ...);
	Fn fn = (*reinterpret_cast<Fn**>(engineClient))[29];
	fn(engineClient, line, "%s", text);
}

void setState(State s, const char* why) {
	g_state = s;
	g_since = GetTickCount();
	g_log("launcher: %s", why);
}

} // namespace

void init(LogFn log, HMODULE self) {
	g_log = log;
	char path[MAX_PATH];
	GetModuleFileNameA(self, path, MAX_PATH);
	if (char* slash = std::strrchr(path, '\\')) {
		std::strcpy(slash + 1, "portalcraft.ini");
	}
	g_iniPath = path;
	// The plugin lives in <Portal>/portal/addons; the maps are in <Portal>/portal/maps.
	g_mapsDir = g_iniPath.substr(0, g_iniPath.find_last_of('\\'));
	g_mapsDir = g_mapsDir.substr(0, g_mapsDir.find_last_of('\\')) + "\\maps";
	g_startWithPortal = GetPrivateProfileIntA("Minecraft", "start_with_portal", 0, path) != 0;
	g_launcher = iniString("launcher", "");
	g_arguments = iniString("arguments", "");
	g_directory = iniString("directory", "");
	g_log("launcher: %s: start_with_portal %d, launcher \"%s\"", path, g_startWithPortal ? 1 : 0, g_launcher.c_str());
}

void frame(bool mcLinked, bool inLevel, void* engineClient) {
	if (g_state == State::Unchecked) {
		if (minecraftRunning()) {
			setState(State::AlreadyRunning, "Minecraft is already running");
		} else if (!g_startWithPortal || g_launcher.empty()) {
			setState(State::Off, "Minecraft isn't running, and portalcraft.ini doesn't start it");
		} else {
			setState(start() ? State::Started : State::StartFailed, "starting Minecraft");
		}
	}
	if (mcLinked && g_state != State::Linked) {
		setState(State::Linked, "Minecraft linked");
	} else if (!mcLinked && g_state == State::Linked && inLevel) {
		setState(State::Lost, "Minecraft stopped answering");
	}
	if (!inLevel) {
		return;
	}
	DWORD seconds = (GetTickCount() - g_since) / 1000;
	char text[512];
	switch (g_state) {
	case State::Started:
	case State::AlreadyRunning:
		if (seconds < 90) {
			std::snprintf(text, sizeof text, "PortalCraft: waiting for Minecraft... (%lus)", seconds);
		} else {
			std::snprintf(text, sizeof text, "PortalCraft: Minecraft still hasn't connected after %lus. Its log: %s\\run\\logs\\latest.log", seconds,
				g_directory.c_str());
		}
		break;
	case State::StartFailed:
		std::snprintf(text, sizeof text, "PortalCraft: couldn't start Minecraft (check launcher= in %s)", g_iniPath.c_str());
		break;
	case State::Off:
		std::snprintf(text, sizeof text, "PortalCraft: Minecraft isn't running. Start it with play-portal.cmd to play as Steve.");
		break;
	case State::Linked:
		if (seconds >= 4) {
			return;
		}
		std::snprintf(text, sizeof text, "PortalCraft: Minecraft linked. You're Steve now.");
		break;
	case State::Lost:
		if (seconds < 3) {
			return; // a hitch, most likely
		}
		std::snprintf(text, sizeof text, "PortalCraft: Minecraft stopped answering (%lus). Its log: %s\\run\\logs\\latest.log", seconds,
			g_directory.c_str());
		break;
	default:
		return;
	}
	show(engineClient, 1, text);
}

} // namespace launcher
