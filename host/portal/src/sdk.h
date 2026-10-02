// Minimal hand-written mirrors of the Source engine interfaces PortalCraft touches.
// Slot numbers and field offsets match the single-player Source SDK 2013 branch that Steam
// Portal (build 19017868) is built from: VEngineServer021, VEngineClient013, GameMovement001,
// PlayerInfoManager002, ISERVERPLUGINCALLBACKS003. Only what we call is declared.
#pragma once

#include <cstdint>
#include <cstring>

namespace sdk {

struct Vector {
	float x, y, z;
};
using QAngle = Vector; // pitch, yaw, roll

using CreateInterfaceFn = void* (*)(const char* name, int* returnCode);

// Calls virtual slot `index` on `self` (MSVC x86 __thiscall).
template <typename R, typename... A>
inline R vcall(void* self, int index, A... args) {
	using Fn = R(__thiscall*)(void*, A...);
	return (*reinterpret_cast<Fn**>(self))[index](self, args...);
}

// A virtual that returns a 12-byte struct by value: MSVC passes a hidden result pointer first.
inline Vector vcallVector(void* self, int index) {
	Vector out{};
	using Fn = Vector*(__thiscall*)(void*, Vector*);
	(*reinterpret_cast<Fn**>(self))[index](self, &out);
	return out;
}

// ---- edicts and entities -------------------------------------------------------------
// struct edict_t (PC): int m_fStateFlags; short serial; short index; IServerNetworkable*;
// IServerUnknown*; float freetime.  sizeof == 20.
constexpr int kEdictSize = 20;
constexpr int kEdictNetworkable = 8;
constexpr int kEdictUnknown = 12;

inline void* edictNetworkable(void* edict) {
	return edict ? *reinterpret_cast<void**>(static_cast<uint8_t*>(edict) + kEdictNetworkable) : nullptr;
}
inline void* edictUnknown(void* edict) {
	return edict ? *reinterpret_cast<void**>(static_cast<uint8_t*>(edict) + kEdictUnknown) : nullptr;
}

// IServerNetworkable: 1 GetServerClass, 3 GetClassName, 7 GetBaseEntity.
inline void* networkableServerClass(void* n) { return vcall<void*>(n, 1); }
inline const char* networkableClassName(void* n) { return vcall<const char*>(n, 3); }
inline void* networkableBaseEntity(void* n) { return vcall<void*>(n, 7); }

// ServerClass { const char* name; SendTable* table; ServerClass* next; int id; int baseline; }
struct ServerClass {
	const char* name;
	struct SendTable* table;
	ServerClass* next;
	int classId;
	int baselineIndex;
};

// SendTable { SendProp* props; int count; const char* netTableName; ... }
struct SendTable {
	uint8_t* props; // array of SendProp, kSendPropSize apart
	int count;
	const char* name;
};

// SendProp has a vtable; the fields we read:
constexpr int kSendPropSize = 80;
constexpr int kSendPropVarName = 48;
constexpr int kSendPropDataTable = 68;
constexpr int kSendPropOffset = 72;

// ---- player movement -----------------------------------------------------------------
// CMoveData offsets.
constexpr int kMvButtons = 36;
constexpr int kMvForwardMove = 44;
constexpr int kMvSideMove = 48;
constexpr int kMvUpMove = 52;
constexpr int kMvVelocity = 64;
constexpr int kMvAbsOrigin = 152;

// in_buttons.h
constexpr int IN_ATTACK = 1 << 0;
constexpr int IN_JUMP = 1 << 1;
constexpr int IN_DUCK = 1 << 2;
constexpr int IN_ATTACK2 = 1 << 11;

// IGameMovement: 1 ProcessMovement(CBasePlayer*, CMoveData*)
constexpr int kProcessMovementSlot = 1;

// IPlayerInfoManager: 0 GetPlayerInfo(edict_t*).  IPlayerInfo: 15 GetAbsOrigin, 16 GetAbsAngles.
inline void* playerInfo(void* manager, void* edict) { return vcall<void*>(manager, 0, edict); }
inline Vector playerAbsOrigin(void* info) { return vcallVector(info, 15); }

// IVEngineServer (021): 36 ServerCommand(const char*)
inline void serverCommand(void* engine, const char* cmd) { vcall<void>(engine, 36, cmd); }

// IVEngineClient (013): 11 Con_IsVisible, 19 GetViewAngles(QAngle&), 20 SetViewAngles(QAngle&), 84 IsPaused
inline bool clientConsoleVisible(void* ec) { return vcall<bool>(ec, 11); }
inline void clientGetViewAngles(void* ec, QAngle* out) { vcall<void>(ec, 19, out); }
inline void clientSetViewAngles(void* ec, QAngle* in) { vcall<void>(ec, 20, in); }
inline bool clientIsPaused(void* ec) { return vcall<bool>(ec, 84); }

} // namespace sdk
