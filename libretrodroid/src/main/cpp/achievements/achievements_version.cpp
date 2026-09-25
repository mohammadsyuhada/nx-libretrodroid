#include "rc_version.h"
#include "rc_client.h"
#include "rc_hash.h"
#include <libchdr/chd.h>

namespace libretrodroid {
// Referenced from libretrodroid.cpp so the linker keeps rcheevos and libchdr; replaced by the real module in Task 2.
const char* achievementsRuntimeVersion() { return RCHEEVOS_VERSION_STRING; }
}
