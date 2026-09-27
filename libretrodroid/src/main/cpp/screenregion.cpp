/*
 *     Copyright (C) 2026  Mohammad Syuhada
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

#include "screenregion.h"

namespace libretrodroid {

std::vector<ScreenRegion> ScreenRegion::unpack(const float* data, size_t count) {
    std::vector<ScreenRegion> out;
    if (data == nullptr) return out;
    for (size_t i = 0; i + PACKED_SIZE <= count; i += PACKED_SIZE) {
        const float* d = data + i;
        out.push_back({ d[0], d[1], d[2], d[3], d[4], d[5], d[6], d[7], d[8] != 0.0F, d[9] != 0.0F });
    }
    return out;
}

RegionQuad buildRegionQuad(const ScreenRegion& r, float vpX, float vpY, float vpW, float vpH, bool bottomLeftOrigin) {
    // View position (0..1, y down) to clip space (-1..1, y up).
    auto clipX = [&](float x) { return 2.0F * (vpX + x * vpW) - 1.0F; };
    auto clipY = [&](float y) { return 1.0F - 2.0F * (vpY + y * vpH); };
    // Frame row (y down) to texture v: a GL core's framebuffer has its origin at the bottom.
    auto texV = [&](float y) { return bottomLeftOrigin ? 1.0F - y : y; };

    float left = clipX(r.dstLeft), right = clipX(r.dstRight), top = clipY(r.dstTop), bottom = clipY(r.dstBottom);
    float u0 = r.srcLeft, u1 = r.srcRight, vTop = texV(r.srcTop), vBottom = texV(r.srcBottom);
    return RegionQuad {
        { left, top,  left, bottom,  right, top,  right, top,  left, bottom,  right, bottom },
        { u0, vTop,  u0, vBottom,  u1, vTop,  u1, vTop,  u0, vBottom,  u1, vBottom },
        r.shaded,
        r.srcRight - r.srcLeft,
        r.srcBottom - r.srcTop,
    };
}

}
