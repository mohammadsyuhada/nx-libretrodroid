#include "achievementshash.h"

#include <cstdio>
#include <cstring>
#include <mutex>
#include <strings.h>
#include <unistd.h>

#include "chdreader.h"
#include "../log.h"

namespace libretrodroid {

static std::mutex hashLock;   // one hash at a time: the readers find files through static state
static std::vector<std::pair<std::string, int>> currentFiles;

static const char* baseName(const char* p) { const char* s = strrchr(p, '/'); return s ? s + 1 : p; }

int AchievementsHash::lookup(const char* path) {
    if (!path) return -1;
    for (auto& f : currentFiles) if (f.first == path) return f.second;
    const char* base = baseName(path);
    for (auto& f : currentFiles) if (strcasecmp(baseName(f.first.c_str()), base) == 0) return f.second;
    return -1;
}

struct FdFile { int fd; int64_t pos; int64_t size; };

static void* fdOpen(const char* path) {
    int fd = AchievementsHash::lookup(path);
    if (fd < 0) { LOGW("hash: no fd for %s", path ? path : "(null)"); return nullptr; }
    off_t size = lseek(fd, 0, SEEK_END);
    return new FdFile { fd, 0, size < 0 ? 0 : (int64_t) size };
}

static void fdSeek(void* h, int64_t offset, int origin) {
    auto* f = (FdFile*) h;
    if (origin == SEEK_SET) f->pos = offset;
    else if (origin == SEEK_CUR) f->pos += offset;
    else f->pos = f->size + offset;
    if (f->pos < 0) f->pos = 0;
}

static int64_t fdTell(void* h) { return ((FdFile*) h)->pos; }

static size_t fdRead(void* h, void* buffer, size_t bytes) {
    auto* f = (FdFile*) h;
    ssize_t n = pread(f->fd, buffer, bytes, (off_t) f->pos);
    if (n <= 0) return 0;
    f->pos += n;
    return (size_t) n;
}

static void fdClose(void* h) { delete (FdFile*) h; }   // the fd itself is closed by hash()

static rc_hash_cdreader_t defaultCd;

#define WRAP_MAGIC 0x43484448u
struct CdHandle { uint32_t magic; bool chd; void* inner; };

static void* wrap(void* inner, bool chd) { return inner ? new CdHandle { WRAP_MAGIC, chd, inner } : nullptr; }
static CdHandle* unwrap(void* h) { auto* w = (CdHandle*) h; return (w && w->magic == WRAP_MAGIC) ? w : nullptr; }

static void* cdOpenTrackIt(const char* path, uint32_t track, const rc_hash_iterator_t* it) {
    void* h = chdreader_open_track(path, track);   // null unless path maps to a CHD
    if (h) return wrap(h, true);
    // rcheevos' default reader reaches its filereader through the iterator, so it needs one.
    if (it && defaultCd.open_track_iterator) {
        h = defaultCd.open_track_iterator(path, track, it);
        if (h) return wrap(h, false);
    }
    if (defaultCd.open_track) {
        h = defaultCd.open_track(path, track);
        if (h) return wrap(h, false);
    }
    return nullptr;
}

static void* cdOpenTrack(const char* path, uint32_t track) { return cdOpenTrackIt(path, track, nullptr); }

static size_t cdRead(void* h, uint32_t sector, void* buf, size_t n) {
    auto* w = unwrap(h);
    if (!w) return 0;
    if (w->chd) return chdreader_read_sector(w->inner, sector, buf, n);
    return defaultCd.read_sector ? defaultCd.read_sector(w->inner, sector, buf, n) : 0;
}

static void cdClose(void* h) {
    auto* w = unwrap(h);
    if (!w) return;
    if (w->chd) chdreader_close_track(w->inner);
    else if (defaultCd.close_track) defaultCd.close_track(w->inner);
    w->magic = 0;
    delete w;
}

static uint32_t cdFirst(void* h) {
    auto* w = unwrap(h);
    if (!w) return 0;
    if (w->chd) return chdreader_first_track_sector(w->inner);
    return defaultCd.first_track_sector ? defaultCd.first_track_sector(w->inner) : 0;
}

static rc_hash_callbacks_t hashCallbacks;
static std::once_flag callbacksOnce;

static void verbose(const char* m, const rc_hash_iterator_t*) { LOGD("rc_hash: %s", m); }
static void error(const char* m, const rc_hash_iterator_t*) { LOGW("rc_hash: %s", m); }

const rc_hash_callbacks_t* AchievementsHash::callbacks() {
    std::call_once(callbacksOnce, [] {
        memset(&hashCallbacks, 0, sizeof(hashCallbacks));
        rc_hash_get_default_cdreader(&defaultCd);
        hashCallbacks.verbose_message = verbose;
        hashCallbacks.error_message = error;
        hashCallbacks.filereader = { fdOpen, fdSeek, fdTell, fdRead, fdClose };
        hashCallbacks.cdreader = { cdOpenTrack, cdRead, cdClose, cdFirst, cdOpenTrackIt };
    });
    return &hashCallbacks;
}

std::string AchievementsHash::hash(uint32_t consoleId, std::vector<std::pair<std::string, int>> files) {
    std::lock_guard<std::mutex> lock(hashLock);
    std::string result;
    if (files.empty()) return result;
    currentFiles = std::move(files);

    rc_hash_iterator_t it;
    rc_hash_initialize_iterator(&it, currentFiles[0].first.c_str(), nullptr, 0);
    it.callbacks = *callbacks();
    char out[33] = {0};
    if (rc_hash_generate(out, consoleId, &it)) result = out;
    rc_hash_destroy_iterator(&it);

    for (auto& f : currentFiles) if (f.second >= 0) close(f.second);
    currentFiles.clear();
    return result;
}

}
