#include <winsock2.h>
#include <windows.h>

#include <cstdio>
#include <cstring>
#include <string>

#include "../../../protocol/portalcraft_protocol.h"
#include "instance.h"
#include "launcher.h"

// portalcraft.ini (install.cmd writes one pointing at this repo):
//
//   [Minecraft]
//   start_with_portal=1           ; 0: start Minecraft yourself (play-portal.cmd, gradle runClient)
//   launcher=C:\tmp\portalcraft\gradle.cmd
//   arguments=runClient --no-configuration-cache --args="--quickPlaySingleplayer PortalCraft"
//   directory=C:\tmp\portalcraft  ; where it runs
//   log=...                       ; Minecraft's log, for the messages (default: directory\run\logs\latest.log)
//
// A release's installer writes one for Prism Launcher instead (launcher=...\prismlauncher.exe,
// arguments=--launch PortalCraft --world PortalCraft).
//
// Minecraft is started with PORTALCRAFT_STARTED_BY_HOST=1, which makes it hide its window and
// quit again when Portal closes (HostLifecycle.java). Whether one is already running is whether
// Minecraft's link port (127.0.0.1:27516) is taken: the PortalCraft mod holds it while it runs.
//
// A Portal started with -pcinstance N (instance.h) reads portalcraft_N.ini instead if there is one
// (to start a Minecraft from another checkout, say), and starts its Minecraft with
// PORTALCRAFT_INSTANCE=N and PORTALCRAFT_HOST_PID. With the repo's gradle.cmd that is enough: the
// build script gives that instance its own game directory, run_N.
namespace launcher {
namespace {

LogFn g_log = nullptr;
bool g_startWithPortal = false;
std::string g_launcher, g_arguments, g_directory, g_iniPath, g_mapsDir, g_logPath;

enum class State { Unchecked, AlreadyRunning, Started, StartFailed, Off, Linked, Lost };
State g_state = State::Unchecked;
bool g_trustUnknownBuild = false;
std::string g_notice;
DWORD g_noticeUntil = 0;
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
	sockaddr_in addr{};
	addr.sin_family = AF_INET;
	addr.sin_port = htons(instance::mcPort());
	addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
	// In use: WSAEADDRINUSE, or WSAEACCES when the holder asked for exclusive use.
	bool taken = false;
	if (bind(s, reinterpret_cast<sockaddr*>(&addr), sizeof addr) != 0) {
		int error = WSAGetLastError();
		taken = error == WSAEADDRINUSE || error == WSAEACCES;
	}
	closesocket(s);
	return taken;
}

// When this PC last started Minecraft for a Portal (a file in %TEMP%, removed once it links): a
// Portal restarted while that Minecraft is still loading (before it holds its port) waits for it
// instead of starting another one.
constexpr ULONGLONG kStartingSeconds = 180;

std::string launchStampPath() {
	char dir[MAX_PATH];
	DWORD n = GetTempPathA(MAX_PATH, dir);
	return std::string(dir, n) + instance::named("portalcraft-launch", ".txt");
}

ULONGLONG nowSeconds() {
	FILETIME ft;
	GetSystemTimeAsFileTime(&ft);
	return ((ULONGLONG(ft.dwHighDateTime) << 32) | ft.dwLowDateTime) / 10000000ULL;
}

bool startedRecently() {
	FILE* f = std::fopen(launchStampPath().c_str(), "r");
	if (!f) {
		return false;
	}
	unsigned long long then = 0;
	bool read = std::fscanf(f, "%llu", &then) == 1;
	std::fclose(f);
	return read && nowSeconds() >= then && nowSeconds() - then < kStartingSeconds;
}

void stampLaunch() {
	if (FILE* f = std::fopen(launchStampPath().c_str(), "w")) {
		std::fprintf(f, "%llu", static_cast<unsigned long long>(nowSeconds()));
		std::fclose(f);
	}
}

bool endsWith(const std::string& s, const char* suffix) {
	size_t n = std::strlen(suffix);
	return s.size() >= n && _stricmp(s.c_str() + s.size() - n, suffix) == 0;
}

bool start() {
	// A script (gradle.cmd) runs in a console nobody should see; a program (Prism Launcher) starts
	// as itself, visible, since the first time it asks the player to sign in.
	bool script = endsWith(g_launcher, ".cmd") || endsWith(g_launcher, ".bat");
	std::string command = script ? "cmd.exe /c \"\"" + g_launcher + "\" " + g_arguments + "\"" : "\"" + g_launcher + "\" " + g_arguments;
	SetEnvironmentVariableA("PORTALCRAFT_STARTED_BY_HOST", "1");
	SetEnvironmentVariableA("PORTALCRAFT_MAPS", g_mapsDir.c_str()); // where Minecraft reads Portal's maps for collision
	if (instance::number() > 0) {
		// Which pair it is, and which hl2.exe is its own: with two Portals up, the other one being
		// alive mustn't keep this Minecraft running after its own Portal has closed.
		SetEnvironmentVariableA("PORTALCRAFT_INSTANCE", std::to_string(instance::number()).c_str());
		SetEnvironmentVariableA("PORTALCRAFT_HOST_PID", std::to_string(GetCurrentProcessId()).c_str());
	}
	STARTUPINFOA si{};
	si.cb = sizeof si;
	if (script) {
		si.dwFlags = STARTF_USESHOWWINDOW;
		si.wShowWindow = SW_HIDE;
	}
	PROCESS_INFORMATION pi{};
	const char* dir = g_directory.empty() ? nullptr : g_directory.c_str();
	DWORD flags = CREATE_NEW_PROCESS_GROUP | (script ? CREATE_NO_WINDOW : 0);
	// Out of Portal's job if it has one (Steam's), so Portal closing doesn't kill Minecraft before
	// it has saved: it quits by itself once Portal is gone.
	BOOL ok = CreateProcessA(nullptr, command.data(), nullptr, nullptr, FALSE, flags | CREATE_BREAKAWAY_FROM_JOB, nullptr, dir, &si, &pi);
	if (!ok) {
		ok = CreateProcessA(nullptr, command.data(), nullptr, nullptr, FALSE, flags, nullptr, dir, &si, &pi);
	}
	SetEnvironmentVariableA("PORTALCRAFT_STARTED_BY_HOST", nullptr);
	SetEnvironmentVariableA("PORTALCRAFT_MAPS", nullptr);
	if (instance::number() > 0) {
		SetEnvironmentVariableA("PORTALCRAFT_INSTANCE", nullptr);
		SetEnvironmentVariableA("PORTALCRAFT_HOST_PID", nullptr);
	}
	if (!ok) {
		g_log("launcher: couldn't start Minecraft (%lu): %s", GetLastError(), command.c_str());
		return false;
	}
	CloseHandle(pi.hThread);
	CloseHandle(pi.hProcess);
	stampLaunch();
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
		if (instance::number() > 0) {
			// Its own ini if it has one; otherwise the shared one serves every instance.
			std::string own = std::string(path, slash + 1) + instance::named("portalcraft", ".ini");
			if (own.size() < MAX_PATH && GetFileAttributesA(own.c_str()) != INVALID_FILE_ATTRIBUTES) {
				std::strcpy(path, own.c_str());
			}
		}
	}
	g_iniPath = path;
	// The plugin lives in <Portal>/portal/addons; the maps are in <Portal>/portal/maps.
	g_mapsDir = g_iniPath.substr(0, g_iniPath.find_last_of('\\'));
	g_mapsDir = g_mapsDir.substr(0, g_mapsDir.find_last_of('\\')) + "\\maps";
	g_startWithPortal = GetPrivateProfileIntA("Minecraft", "start_with_portal", 0, path) != 0;
	g_trustUnknownBuild = GetPrivateProfileIntA("PortalCraft", "trust_unknown_build", 0, path) != 0;
	g_launcher = iniString("launcher", "");
	g_arguments = iniString("arguments", "");
	g_directory = iniString("directory", "");
	g_logPath = iniString("log", (g_directory + "\\" + instance::named("run") + "\\logs\\latest.log").c_str());
	g_log("launcher: %s: start_with_portal %d, launcher \"%s\"", path, g_startWithPortal ? 1 : 0, g_launcher.c_str());
}

bool trustUnknownBuild() {
	return g_trustUnknownBuild;
}

void notice(const char* text) {
	if (g_notice.empty()) { // once: the first one says it
		g_notice = text; // its 10 s start when it's first on screen
	}
}

const char* mapsDir() {
	return g_mapsDir.c_str();
}

void frame(bool mcLinked, bool inLevel, void* engineClient) {
	if (g_state == State::Unchecked) {
		if (minecraftRunning()) {
			setState(State::AlreadyRunning, "Minecraft is already running");
		} else if (g_startWithPortal && !g_launcher.empty() && startedRecently()) {
			setState(State::Started, "Minecraft was started moments ago (still loading): waiting for it");
		} else if (!g_startWithPortal || g_launcher.empty()) {
			setState(State::Off, "Minecraft isn't running, and portalcraft.ini doesn't start it");
		} else {
			setState(start() ? State::Started : State::StartFailed, "starting Minecraft");
		}
	}
	if (mcLinked && g_state != State::Linked) {
		DeleteFileA(launchStampPath().c_str()); // it's up: the next Portal may start one again
		setState(State::Linked, "Minecraft linked");
	} else if (!mcLinked && g_state == State::Linked && inLevel) {
		setState(State::Lost, "Minecraft stopped answering");
	}
	if (!inLevel) {
		return;
	}
	if (!g_notice.empty() && g_noticeUntil == 0) {
		g_noticeUntil = GetTickCount() + 10000;
	}
	if (!g_notice.empty() && GetTickCount() < g_noticeUntil) {
		show(engineClient, 2, g_notice.c_str());
	}
	DWORD seconds = (GetTickCount() - g_since) / 1000;
	char text[512];
	switch (g_state) {
	case State::Started:
	case State::AlreadyRunning:
		if (seconds < 90) {
			std::snprintf(text, sizeof text, "PortalCraft: waiting for Minecraft... (%lus)%s", seconds,
				seconds >= 20 && !endsWith(g_launcher, ".cmd") ? " If Prism Launcher asks you to sign in, Alt-Tab to it." : "");
		} else {
			std::snprintf(text, sizeof text, "PortalCraft: Minecraft still hasn't connected after %lus. Its log: %s", seconds,
				g_logPath.c_str());
		}
		break;
	case State::StartFailed:
		std::snprintf(text, sizeof text, "PortalCraft: couldn't start Minecraft (check launcher= in %s)", g_iniPath.c_str());
		break;
	case State::Off:
		std::snprintf(text, sizeof text, "PortalCraft: Minecraft isn't running (start_with_portal=0 in portalcraft.ini): start it yourself to play as Steve.");
		break;
	case State::Linked:
		if (seconds >= 4) {
			return;
		}
		std::snprintf(text, sizeof text, "PortalCraft: Minecraft linked. You're Steve now.");
		break;
	case State::Lost:
		if (seconds < 10) {
			return; // a level change or a hitch, most likely
		}
		std::snprintf(text, sizeof text, "PortalCraft: Minecraft stopped answering (%lus). Its log: %s", seconds, g_logPath.c_str());
		break;
	default:
		return;
	}
	show(engineClient, 1, text);
}

} // namespace launcher
