#pragma once

#include <cstdint>
#include <cstdio>
#include <optional>

#include "common/util/FileUtil.h"

/*!
 * Opens a disc image for reading as a stream of 2048-byte sectors: an ISO as it is, or a CHD
 * (MAME's compressed format, which emulators use), decompressed as it's read. Returns nullptr if
 * the file can't be opened or the CHD can't be read. Close the result with fclose.
 */
FILE* open_disc_image(const fs::path& path);

/*!
 * The size of the disc's data: an ISO's size, or the size of the ISO a CHD holds.
 */
std::optional<uint64_t> disc_image_size(const fs::path& path);

/*!
 * Whether the file is a CHD, by its header.
 */
bool is_chd_file(const fs::path& path);
