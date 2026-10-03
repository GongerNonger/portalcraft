// Tests for raybox.h. Build and run: cl /nologo /EHsc /std:c++17 raybox_test.cpp && raybox_test.exe
#include <cstdio>
#include "../src/raybox.h"

static int failures = 0;
#define CHECK(c) do { if (!(c)) { std::printf("FAIL line %d: %s\n", __LINE__, #c); failures++; } } while (0)

int main() {
	const float box[6] = {0, 0, 0, 40, 40, 40}; // one block
	float t, sign;
	int axis;
	{ // straight at its -x face from x = -40: enters at t = 0.5 of an 80-unit ray, normal -x
		float s[3] = {-40, 20, 20}, d[3] = {80, 0, 0};
		CHECK(rayEnters(s, d, box, 1.0f, &t, &axis, &sign));
		CHECK(std::fabs(t - 0.5f) < 1e-6f && axis == 0 && sign == -1.0f);
	}
	{ // from above, straight down: enters the top face, normal +z
		float s[3] = {20, 20, 100}, d[3] = {0, 0, -100};
		CHECK(rayEnters(s, d, box, 1.0f, &t, &axis, &sign));
		CHECK(std::fabs(t - 0.6f) < 1e-6f && axis == 2 && sign == 1.0f);
	}
	{ // passing beside it: no hit
		float s[3] = {-40, 60, 20}, d[3] = {200, 0, 0};
		CHECK(!rayEnters(s, d, box, 1.0f, &t, &axis, &sign));
	}
	{ // stopping short of it (Portal's own trace hit a wall first, fraction 0.4): no hit
		float s[3] = {-40, 20, 20}, d[3] = {80, 0, 0};
		CHECK(!rayEnters(s, d, box, 0.4f, &t, &axis, &sign));
	}
	{ // starting inside: not a hit (nothing to stop)
		float s[3] = {20, 20, 20}, d[3] = {100, 0, 0};
		CHECK(!rayEnters(s, d, box, 1.0f, &t, &axis, &sign));
	}
	{ // grazing along a face exactly: not a hit
		float s[3] = {-40, 40, 20}, d[3] = {200, 0, 0};
		CHECK(!rayEnters(s, d, box, 1.0f, &t, &axis, &sign));
	}
	{ // pointing away from it: no hit
		float s[3] = {-40, 20, 20}, d[3] = {-80, 0, 0};
		CHECK(!rayEnters(s, d, box, 1.0f, &t, &axis, &sign));
	}
	{ // diagonal into the +y face
		float s[3] = {20, 80, 20}, d[3] = {0, -80, 0};
		CHECK(rayEnters(s, d, box, 1.0f, &t, &axis, &sign));
		CHECK(std::fabs(t - 0.5f) < 1e-6f && axis == 1 && sign == 1.0f);
	}
	std::printf(failures ? "%d FAILED\n" : "all passed\n", failures);
	return failures ? 1 : 0;
}
