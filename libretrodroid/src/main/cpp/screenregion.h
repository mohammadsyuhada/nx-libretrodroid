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

#ifndef LIBRETRODROID_SCREENREGION_H
#define LIBRETRODROID_SCREENREGION_H

#include <array>
#include <cstddef>
#include <vector>

namespace libretrodroid {

// A part of the core frame drawn at a place in the view: src is 0..1 of the frame, dst is 0..1 of the view's viewport,
// both y down. Shaded regions go through the shader preset (or the built-in shader); the others through the plain
// default shader, nearest. touch is only carried for the Kotlin side, which maps touches. Drawn in list order.
struct ScreenRegion {
    float srcLeft, srcTop, srcRight, srcBottom;
    float dstLeft, dstTop, dstRight, dstBottom;
    bool shaded;
    bool touch;

    static constexpr size_t PACKED_SIZE = 10;

    // Regions from JNI's packed floats: src l,t,r,b, dst l,t,r,b, shaded, touch (0/1) per region; a trailing partial
    // region is dropped.
    static std::vector<ScreenRegion> unpack(const float* data, size_t count);
};

// One region as a GL quad: clip-space vertices and texture coordinates, six vertices in VideoLayout's corner order.
struct RegionQuad {
    std::array<float, 12> vertices;
    std::array<float, 12> coordinates;
    bool shaded;
    float srcWidth;   // of the frame (0..1): the preset chain's input size is this times the texture size
    float srcHeight;
};

// vpX, vpY, vpW, vpH: the viewport in 0..1 of the view, y down (VideoLayout's viewportRect).
RegionQuad buildRegionQuad(const ScreenRegion& region, float vpX, float vpY, float vpW, float vpH, bool bottomLeftOrigin);

}

#endif //LIBRETRODROID_SCREENREGION_H
