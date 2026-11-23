#include "disc_image.h"

#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include "common/log/log.h"

#include "third-party/libchdr/include/libchdr/chd.h"

namespace {

bool has_chd_magic(FILE* file) {
  char magic[8] = {};
  const bool chd = fread(magic, 1, sizeof(magic), file) == sizeof(magic) &&
                   memcmp(magic, "MComprHD", sizeof(magic)) == 0;
  fseek(file, 0, SEEK_SET);
  return chd;
}

#if defined(__linux__) || defined(__APPLE__)
constexpr uint32_t kSectorBytes = 2048;
// A CD CHD stores each sector as a frame of 2352 bytes of sector data and 96 of subcode
constexpr uint32_t kCdFrameBytes = 2352 + 96;

/*!
 * Where a CHD keeps the disc's 2048-byte sectors: DVD CHDs (chdman createdvd) keep them one after
 * another, and CD CHDs (chdman createcd) keep each in a frame, after the sector's header if the
 * track is raw.
 */
struct ChdLayout {
  uint32_t frame_bytes = kSectorBytes;
  uint32_t data_offset = 0;
  uint64_t sectors = 0;
};

std::optional<ChdLayout> chd_layout(chd_file* chd) {
  const chd_header* header = chd_get_header(chd);
  char metadata[512] = {};
  if (chd_get_metadata(chd, DVD_METADATA_TAG, 0, metadata, sizeof(metadata), nullptr, nullptr,
                       nullptr) == CHDERR_NONE) {
    return ChdLayout{kSectorBytes, 0, header->logicalbytes / kSectorBytes};
  }
  // The data is in the first track. PS2 CDs have one data track.
  if (chd_get_metadata(chd, CDROM_TRACK_METADATA2_TAG, 0, metadata, sizeof(metadata), nullptr,
                       nullptr, nullptr) != CHDERR_NONE &&
      chd_get_metadata(chd, CDROM_TRACK_METADATA_TAG, 0, metadata, sizeof(metadata), nullptr,
                       nullptr, nullptr) != CHDERR_NONE) {
    lg::error("CHD is neither a DVD nor a CD image");
    return std::nullopt;
  }
  char type[32] = {};
  unsigned frames = 0;
  const char* type_field = strstr(metadata, "TYPE:");
  const char* frames_field = strstr(metadata, "FRAMES:");
  if (!type_field || !frames_field || sscanf(type_field, "TYPE:%31s", type) != 1 ||
      sscanf(frames_field, "FRAMES:%u", &frames) != 1) {
    lg::error("CHD has unreadable track metadata: {}", metadata);
    return std::nullopt;
  }
  const std::string track_type = type;
  uint32_t data_offset;
  if (track_type == "MODE1" || track_type == "MODE2_FORM1") {
    data_offset = 0;
  } else if (track_type == "MODE1_RAW") {
    data_offset = 16;  // sync pattern and header
  } else if (track_type == "MODE2_RAW") {
    data_offset = 24;  // sync pattern, header and subheader
  } else {
    lg::error("CHD's first track is {}, which has no 2048-byte sectors", track_type);
    return std::nullopt;
  }
  return ChdLayout{kCdFrameBytes, data_offset, frames};
}

class ChdStream {
 public:
  ChdStream(FILE* file, chd_file* chd, ChdLayout layout)
      : m_file(file),
        m_chd(chd),
        m_layout(layout),
        m_hunk_bytes(chd_get_header(chd)->hunkbytes),
        m_hunk(m_hunk_bytes) {}

  ~ChdStream() {
    chd_close(m_chd);
    fclose(m_file);
  }

  uint64_t size() const { return m_layout.sectors * kSectorBytes; }

  ssize_t read(char* out, size_t bytes) {
    size_t done = 0;
    while (done < bytes && m_position < size()) {
      const uint64_t sector = m_position / kSectorBytes;
      const uint32_t in_sector = m_position % kSectorBytes;
      const uint64_t stored = sector * m_layout.frame_bytes + m_layout.data_offset + in_sector;
      const uint32_t hunk = stored / m_hunk_bytes;
      const uint32_t in_hunk = stored % m_hunk_bytes;
      if (hunk != m_cached_hunk) {
        if (chd_error err = chd_read(m_chd, hunk, m_hunk.data()); err != CHDERR_NONE) {
          lg::error("Failed to read CHD hunk {}: {}", hunk, chd_error_string(err));
          errno = EIO;
          return done ? (ssize_t)done : -1;
        }
        m_cached_hunk = hunk;
      }
      // Copy to whichever ends first: the request, the sector or the hunk
      size_t count = std::min<uint64_t>(bytes - done, kSectorBytes - in_sector);
      count = std::min<size_t>(count, m_hunk_bytes - in_hunk);
      memcpy(out + done, m_hunk.data() + in_hunk, count);
      done += count;
      m_position += count;
    }
    return done;
  }

  int64_t seek(int64_t offset, int whence) {
    int64_t base = whence == SEEK_SET ? 0 : whence == SEEK_CUR ? m_position : size();
    int64_t target = base + offset;
    if (target < 0) {
      errno = EINVAL;
      return -1;
    }
    m_position = target;
    return target;
  }

 private:
  FILE* m_file;
  chd_file* m_chd;
  ChdLayout m_layout;
  uint32_t m_hunk_bytes;
  std::vector<uint8_t> m_hunk;
  uint32_t m_cached_hunk = UINT32_MAX;
  uint64_t m_position = 0;
};

/*!
 * Opens a CHD that's open as file, taking ownership of file.
 */
std::unique_ptr<ChdStream> open_chd(FILE* file) {
  chd_file* chd = nullptr;
  if (chd_error err = chd_open_file(file, CHD_OPEN_READ, nullptr, &chd); err != CHDERR_NONE) {
    lg::error("Failed to open CHD: {}", chd_error_string(err));
    fclose(file);
    return nullptr;
  }
  auto layout = chd_layout(chd);
  if (!layout) {
    chd_close(chd);
    fclose(file);
    return nullptr;
  }
  return std::make_unique<ChdStream>(file, chd, *layout);
}

// A FILE that reads through a ChdStream: glibc's fopencookie, or the BSDs' funopen on Android and
// macOS
#if defined(__ANDROID__) || defined(__APPLE__)
int read_chd(void* cookie, char* out, int bytes) {
  return static_cast<ChdStream*>(cookie)->read(out, bytes);
}

#if defined(__ANDROID__)
fpos64_t seek_chd(void* cookie, fpos64_t offset, int whence) {
#else
fpos_t seek_chd(void* cookie, fpos_t offset, int whence) {
#endif
  return static_cast<ChdStream*>(cookie)->seek(offset, whence);
}
#else
ssize_t read_chd(void* cookie, char* out, size_t bytes) {
  return static_cast<ChdStream*>(cookie)->read(out, bytes);
}

int seek_chd(void* cookie, off64_t* offset, int whence) {
  const int64_t position = static_cast<ChdStream*>(cookie)->seek(*offset, whence);
  if (position < 0) {
    return -1;
  }
  *offset = position;
  return 0;
}
#endif

int close_chd(void* cookie) {
  delete static_cast<ChdStream*>(cookie);
  return 0;
}
#else
// Reading a CHD as a FILE needs fopencookie or funopen
constexpr const char* kNoChd =
    "CHD images are only supported on Linux, Android and macOS. Convert it to an ISO with chdman.";
#endif

}  // namespace

bool is_chd_file(const fs::path& path) {
  FILE* file = file_util::open_file(path, "rb");
  if (!file) {
    return false;
  }
  const bool chd = has_chd_magic(file);
  fclose(file);
  return chd;
}

std::optional<uint64_t> disc_image_size(const fs::path& path) {
  FILE* file = file_util::open_file(path, "rb");
  if (!file) {
    return std::nullopt;
  }
  if (!has_chd_magic(file)) {
    fclose(file);
    std::error_code error;
    const auto size = fs::file_size(path, error);
    return error ? std::nullopt : std::make_optional<uint64_t>(size);
  }
#if defined(__linux__) || defined(__APPLE__)
  auto chd = open_chd(file);
  return chd ? std::make_optional(chd->size()) : std::nullopt;
#else
  lg::error(kNoChd);
  fclose(file);
  return std::nullopt;
#endif
}

FILE* open_disc_image(const fs::path& path) {
  FILE* file = file_util::open_file(path, "rb");
  if (!file || !has_chd_magic(file)) {
    return file;
  }
#if defined(__linux__) || defined(__APPLE__)
  auto chd = open_chd(file);
  if (!chd) {
    return nullptr;
  }
  ChdStream* stream = chd.release();
#if defined(__ANDROID__)
  FILE* result = funopen64(stream, read_chd, nullptr, seek_chd, close_chd);
#elif defined(__APPLE__)
  FILE* result = funopen(stream, read_chd, nullptr, seek_chd, close_chd);
#else
  cookie_io_functions_t functions = {};
  functions.read = read_chd;
  functions.seek = seek_chd;
  functions.close = close_chd;
  FILE* result = fopencookie(stream, "rb", functions);
#endif
  if (!result) {
    delete stream;
  }
  return result;
#else
  lg::error(kNoChd);
  fclose(file);
  return nullptr;
#endif
}
