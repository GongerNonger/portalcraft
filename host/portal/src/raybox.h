#pragma once

#include <cmath>

// A segment against an axis-aligned box (plugin.cpp's trace hook; tested by tools/raybox_test.cpp).

// Where the segment start + t * delta (t in [0, tMax)) first enters `box`: t and the axis/sign of
// the face it enters through, or false. A start inside the box doesn't count (nothing to stop).
inline bool rayEnters(const float* start, const float* delta, const float* box, float tMax, float* tHit, int* axis, float* sign) {
	float t0 = 0.0f, t1 = tMax;
	int enterAxis = -1;
	float enterSign = 0.0f;
	for (int k = 0; k < 3; k++) {
		float lo = box[k], hi = box[k + 3];
		if (std::fabs(delta[k]) < 1e-6f) {
			if (start[k] <= lo || start[k] >= hi) {
				return false;
			}
			continue;
		}
		float a = (lo - start[k]) / delta[k], b = (hi - start[k]) / delta[k];
		float s = -1.0f; // entering through the low face: its normal points -k
		if (a > b) {
			float t = a;
			a = b;
			b = t;
			s = 1.0f;
		}
		if (a > t0) {
			t0 = a;
			enterAxis = k;
			enterSign = s;
		}
		t1 = b < t1 ? b : t1;
		if (t0 >= t1) {
			return false;
		}
	}
	if (enterAxis < 0) {
		return false; // started inside
	}
	*tHit = t0;
	*axis = enterAxis;
	*sign = enterSign;
	return true;
}

