/*!
 * @file kmemcard.cpp
 * Memory card interface. Very messy code. Most of it is commented out now, as we've switched away
 * from memory cards to just raw saves.
 *
 * Not checked carefully for differences in jak 2.
 */

#include "kmemcard.h"

#include <array>
#include <cstdio>
#include <cstring>

#include "common/util/Assert.h"
#include "common/util/FileUtil.h"
#include "common/util/Timer.h"

#include "game/sce/sif_ee.h"
#include "game/sce/sif_ee_memcard.h"

#include "fmt/format.h"

static constexpr bool memcard_debug = false;

using McCallbackFunc = void (*)(s32);

McCallbackFunc callback;

static s32 language;
static MemoryCardOperation op;
// instead of two memory cards we just simulate the 4 save files (8 banks).
static MemoryCardFile mc_files[4];
// keep track of latest file selected. this is only used in an auto-save mode thats not used
static int mc_last_file = -1;

// a random value we will use as the memory card "handle" for the pc port, which has no memcards.
constexpr u32 PC_MEM_CARD_HANDLE = 0x6C616F67;

constexpr u32 MEM_CARD_MAGIC = 0x12345678;

struct McHeader {
  u32 save_count;
  u32 checksum;
  u32 magic;
  u8 preview_data[64];
  u8 data[944];
  u32 save_count2;
};
static_assert(sizeof(McHeader) == 0x400, "McHeader size");

static McHeader header;

// these are the return value for sceMcGetInfo.
static s32 p1, p2, p3, p4;
using namespace ee;

template <typename... Args>
void mc_print(const std::string& str, Args&&... args) {
  if (memcard_debug) {
    fmt::print("[MC] ");
    if (!str.empty() && str.back() == '\n') {
      fmt::print(fmt::runtime(str), std::forward<Args>(args)...);
    } else {
      fmt::print(fmt::runtime(str + '\n'), std::forward<Args>(args)...);
    }
  }
}

const char* filename_jak1[12] = {
    "BASCUS-97124AYBABTU!",           "BASCUS-97124AYBABTU!/icon.sys",
    "BASCUS-97124AYBABTU!/icon.ico",  "BASCUS-97124AYBABTU!/BASCUS-97124AYBABTU!",
    "BASCUS-97124AYBABTU!/bank0.bin", "BASCUS-97124AYBABTU!/bank1.bin",
    "BASCUS-97124AYBABTU!/bank2.bin", "BASCUS-97124AYBABTU!/bank3.bin",
    "BASCUS-97124AYBABTU!/bank4.bin", "BASCUS-97124AYBABTU!/bank5.bin",
    "BASCUS-97124AYBABTU!/bank6.bin", "BASCUS-97124AYBABTU!/bank7.bin"};

const char* filename_jak2[12] = {
    "BASCUS-97265AYBABTU!",           "BASCUS-97265AYBABTU!/icon.sys",
    "BASCUS-97265AYBABTU!/icon.ico",  "BASCUS-97265AYBABTU!/BASCUS-97265AYBABTU!",
    "BASCUS-97265AYBABTU!/bank0.bin", "BASCUS-97265AYBABTU!/bank1.bin",
    "BASCUS-97265AYBABTU!/bank2.bin", "BASCUS-97265AYBABTU!/bank3.bin",
    "BASCUS-97265AYBABTU!/bank4.bin", "BASCUS-97265AYBABTU!/bank5.bin",
    "BASCUS-97265AYBABTU!/bank6.bin", "BASCUS-97265AYBABTU!/bank7.bin"};

const char* filename_jak3[12] = {
    "BASCUS-97330AYBABTU!",           "BASCUS-97330AYBABTU!/icon.sys",
    "BASCUS-97330AYBABTU!/icon.ico",  "BASCUS-97330AYBABTU!/BASCUS-97330AYBABTU!",
    "BASCUS-97330AYBABTU!/bank0.bin", "BASCUS-97330AYBABTU!/bank1.bin",
    "BASCUS-97330AYBABTU!/bank2.bin", "BASCUS-97330AYBABTU!/bank3.bin",
    "BASCUS-97330AYBABTU!/bank4.bin", "BASCUS-97330AYBABTU!/bank5.bin",
    "BASCUS-97330AYBABTU!/bank6.bin", "BASCUS-97330AYBABTU!/bank7.bin"};

const char* mc_get_filename_no_dir(GameVersion version, int ndx) {
  const char** filenames = nullptr;
  switch (version) {
    case GameVersion::Jak1:
      filenames = filename_jak1;
      break;
    case GameVersion::Jak2:
      filenames = filename_jak2;
      break;
    case GameVersion::Jak3:
      filenames = filename_jak3;
      break;
  }
  return filenames[ndx];
}

inline fs::path mc_get_filename(GameVersion version, int ndx) {
  return file_util::get_user_memcard_dir(version) / mc_get_filename_no_dir(version, ndx);
}

int mc_get_total_bank_size(GameVersion) {
  return BANK_SIZE[g_game_version] + sizeof(McHeader) * 2;
}

#ifdef __ANDROID__
/*
 * Android keeps each game's saves as the PS2 game does on a memory card: its folder holds the files
 * the PS2 game writes, and each bank file is as long as the PS2 game makes it. The saves directory
 * is a PS2 folder memory card (--memcard-path), so frontends sync these folders like a PS2
 * emulator's, and the same folder works in PocketGOAL, a PS2 emulator, or the PS2 game.
 */

// The PS2 game leaves 4 KB of zeros after each bank's footer. Checked against Jak 1's PS2 saves,
// not yet Jak II's or Jak 3's.
constexpr int PS2_BANK_PADDING = 0x1000;

int mc_get_ps2_bank_file_size() {
  return mc_get_total_bank_size(g_game_version) + PS2_BANK_PADDING;
}

/*!
 * icon.sys, which a PS2's browser reads for the save's title and how to light its icon. These are
 * the values Jak 1 writes, with the game's name.
 */
std::vector<u8> mc_make_icon_sys() {
  std::vector<u8> icon_sys(964, 0);
  memcpy(icon_sys.data(), "PS2D", 4);
  const u16 title_line_break = 32;
  memcpy(icon_sys.data() + 6, &title_line_break, sizeof(title_line_break));
  const float light_directions[12] = {0.612f,  0.5f, 0.612f, 0, -0.696f, 0.174f,
                                      -0.696f, 0,    0,      0, 0,       0};
  const float light_colors[12] = {0.5f, 0.5f, 0.5f, 0, 0.25f, 0.3f, 1.0f, 0, 0, 0, 0, 0};
  const float ambient[4] = {0.2f, 0.2f, 0.2f, 0};
  memcpy(icon_sys.data() + 0x50, light_directions, sizeof(light_directions));
  memcpy(icon_sys.data() + 0x80, light_colors, sizeof(light_colors));
  memcpy(icon_sys.data() + 0xb0, ambient, sizeof(ambient));

  // The title in full-width Shift-JIS characters
  const char* title = "";
  switch (g_game_version) {
    case GameVersion::Jak1:
      title = "Jak and Daxter";
      break;
    case GameVersion::Jak2:
      title = "Jak II";
      break;
    case GameVersion::Jak3:
      title = "Jak 3";
      break;
    default:
      break;
  }
  size_t offset = 0xc0;
  for (const char* c = title; *c && offset + 2 <= 0x104; c++) {
    u16 code = 0x8140;  // space
    if (*c >= 'A' && *c <= 'Z') {
      code = 0x8260 + (*c - 'A');
    } else if (*c >= 'a' && *c <= 'z') {
      code = 0x8281 + (*c - 'a');
    } else if (*c >= '0' && *c <= '9') {
      code = 0x824f + (*c - '0');
    }
    icon_sys[offset++] = code >> 8;
    icon_sys[offset++] = code & 0xff;
  }

  // The icon's model for the save as listed, copied, and deleted
  for (size_t name_offset : {0x104, 0x144, 0x184}) {
    memcpy(icon_sys.data() + name_offset, "icon.ico", 8);
  }
  return icon_sys;
}

/*!
 * Writes the files the PS2 game puts in its folder, where they're missing: icon.sys, the icon from
 * the disc, the file that names the folder, and all eight banks, empty until saved to.
 */
void mc_write_ps2_files() {
  file_util::create_dir_if_needed(mc_get_filename(g_game_version, 0));

  auto icon_sys = mc_get_filename(g_game_version, 1);
  if (!fs::exists(icon_sys)) {
    auto data = mc_make_icon_sys();
    file_util::write_binary_file(icon_sys, data.data(), data.size());
  }

  // The installer keeps the disc's icon with the game's disc files
  auto icon = mc_get_filename(g_game_version, 2);
  auto disc_icon = file_util::get_jak_project_dir() / "out" / game_version_names[g_game_version] /
                   "iso" / "SAVEGAME.ICO";
  if (!fs::exists(icon) && fs::exists(disc_icon)) {
    // Written, not copied, so it doesn't keep the private file's permissions
    auto data = file_util::read_binary_file(disc_icon);
    file_util::write_binary_file(icon, data.data(), data.size());
  }

  auto folder_file = mc_get_filename(g_game_version, 3);
  if (!fs::exists(folder_file)) {
    const char text[] = "Nope, the save game data isn't in this file!\n";
    file_util::write_binary_file(folder_file, text, sizeof(text) - 1);
  }

  std::vector<u8> empty_bank(mc_get_ps2_bank_file_size(), 0);
  for (int bank = 4; bank < 12; bank++) {
    auto bank_file = mc_get_filename(g_game_version, bank);
    if (!fs::exists(bank_file)) {
      file_util::write_binary_file(bank_file, empty_bank.data(), empty_bank.size());
    }
  }
}

/*!
 * Whether a bank file holds a save. The PS2 game makes all eight bank files before saving to any,
 * so a bank with no save is a file of zeros, which the game would read as a corrupt save.
 */
bool mc_bank_has_save(const fs::path& bank_file) {
  McHeader bank_header;
  auto fp = file_util::open_file(bank_file, "rb");
  if (!fp) {
    return false;
  }
  bool read = fread(&bank_header, sizeof(McHeader), 1, fp) == 1;
  fclose(fp);
  return read && bank_header.magic == MEM_CARD_MAGIC;
}
#endif

void kmemcard_init_globals() {
  // next = 0;
  language = 0;
  op = {};
  // mc[0] = {};
  // mc[1] = {};
  mc_files[0] = {};
  mc_files[1] = {};
  mc_files[2] = {};
  mc_files[3] = {};
  callback = nullptr;
  p1 = 0;
  p2 = 0;
  p3 = 0;
  p4 = 0;
  // memset(&dirent, 0, sizeof(sceMcTblGetDir));
  memset(&header, 0, sizeof(McHeader));
}

/*!
 * A questionable checksum used on memory card data.
 */
u32 mc_checksum(Ptr<u8> data, s32 size) {
  if (size < 0) {
    size += 3;
  }

  u32 result = 0;
  u32* data_u32 = (u32*)data.c();
  for (s32 i = 0; i < size / 4; i++) {
    result = result << 1 ^ (s32)result >> 0x1f ^ data_u32[i] ^ MEM_CARD_MAGIC;
  }

  return result ^ 0xedd1e666;
}

/*!
 * PC port function that returns whether a given bank ID's file exists or not.
 */
bool file_is_present(int id, int bank = 0) {
  auto bankname = mc_get_filename(g_game_version, 4 + id * 2 + bank);
  if (!fs::exists(bankname) ||
      int(fs::file_size(bankname)) < mc_get_total_bank_size(g_game_version)) {
    // file doesn't exist, or size is bad. we do not want to open files that will crash on read!
    return false;
  }
#ifdef __ANDROID__
  if (!mc_bank_has_save(bankname)) {
    return false;
  }
#endif
  // avoid file check here tbh. there shouldn't be any saves with a save count of zero anyway.
  // the file check is quite slow and ultimately not very useful.
  return true;

  /*
  // file exists. but let's see if it's an empty one.
  // this prevents the game from reading a bank but classifying it as corrupt data.
  // which a file full of zeros logically is.
  auto fp = file_util::open_file(bankname.c_str(), "rb");

  // we can actually just check if the save count is over zero...
  u32 savecount = 0;
  fread(&savecount, sizeof(u32), 1, fp);
  fclose(fp);
  return savecount > 0;
  */
}

/*!
 * PC port function to set memcard info. We don't use a memory card, instead just the raw savefiles.
 */
void pc_update_card() {
  // int highest_save_count = 0;
  mc_last_file = -1;
  for (s32 file = 0; file < 4; file++) {
    auto bankname = mc_get_filename(g_game_version, 4 + file * 2);
    mc_files[file].present = file_is_present(file);
    if (mc_files[file].present) {
      auto bankdata = file_util::read_binary_file(bankname.string());
      auto header1 = reinterpret_cast<McHeader*>(bankdata.data());
      if (file_is_present(file, 1)) {
        auto bankname2 = mc_get_filename(g_game_version, 1 + 4 + file * 2);
        auto bankdata2 = file_util::read_binary_file(bankname2.string());
        auto header2 = reinterpret_cast<McHeader*>(bankdata2.data());

        if (header2->save_count > header1->save_count) {
          // use most recent bank here.
          header1 = header2;
        }

        // banks chosen and checked. copy data and set info.
        mc_files[file].last_saved_bank = header1 == header2;
        mc_files[file].most_recent_save_count = header1->save_count;

        memcpy(mc_files[file].data, header1->preview_data, 64);
      } else {
        // banks chosen and checked. copy data and set info.
        mc_files[file].last_saved_bank = 0;
        mc_files[file].most_recent_save_count = header1->save_count;

        memcpy(mc_files[file].data, header1->preview_data, 64);
      }

      // if (mc_files[file].most_recent_save_count > highest_save_count) {
      //  mc_last_file = file;
      //  highest_save_count = mc_files[file].most_recent_save_count;
      // }
    }
  }
}

/*!
 * PC port function to save a file. This does the whole saving at once, synchronously.
 */
void pc_game_save_synch() {
  Timer mc_timer;
  mc_timer.start();
  pc_update_card();
  auto path = mc_get_filename(g_game_version, 0);
  file_util::create_dir_if_needed_for_file(path.string());
#ifdef __ANDROID__
  mc_write_ps2_files();
#endif

  // cd_reprobe_save //
  if (!file_is_present(op.param2)) {
    mc_print("reprobe save: first time!");
    // first time saving!
    p2 = 0;  // save count 0
    p4 = 0;  // first bank for file
  } else {
    p2 = mc_files[op.param2].most_recent_save_count + 1;  // increment save count
    p4 = mc_files[op.param2].last_saved_bank ^ 1;         // use the other bank
  }

  // reserve 0 as "I never saved" and use 1 instead.
  if (p2 == 0) {
    p2 = 1;
  }

  // file*2 + p4 is the bank (2 banks per file, p4 is 0 or 1 to select the bank)
  // 4 is the first bank file
  mc_print("open {} for saving", mc_get_filename_no_dir(g_game_version, op.param2 * 2 + 4 + p4));
  auto save_path = mc_get_filename(g_game_version, op.param2 * 2 + 4 + p4);
  file_util::create_dir_if_needed_for_file(save_path.string());
  auto fd = file_util::open_file(save_path.string().c_str(), "wb");
  mc_print("synchronous save file open took {:.2f}ms\n", mc_timer.getMs());
  if (fd) {
    // cb_openedsave //
    mc_print("save file opened, writing header...");
    memset(&header, 0, sizeof(McHeader));
    header.save_count = p2;
    header.checksum = mc_checksum(op.data_ptr, BANK_SIZE[g_game_version]);
    header.magic = MEM_CARD_MAGIC;
    header.save_count2 = p2;
    memcpy(header.preview_data, op.data_ptr2.c(), 64);
    if (fwrite(&header, sizeof(McHeader), 1, fd) == 1) {
      // cb_savedheader //
      mc_print("save file writing main data");
      if (fwrite(op.data_ptr.c(), BANK_SIZE[g_game_version], 1, fd) == 1) {
        // cb_saveddata //
        mc_print("save file writing footer");
        if (fwrite(&header, sizeof(McHeader), 1, fd) == 1) {
          // cb_savedfooter //
          if (fclose(fd) == 0) {
            // cb_closedsave //
#ifdef __ANDROID__
            std::error_code ec;
            fs::resize_file(save_path, mc_get_ps2_bank_file_size(), ec);
#endif
            mc_print("All done with saving!!");
            op.operation = MemoryCardOperationKind::NO_OP;
            op.result = McStatusCode::OK;
            mc_files[op.param2].present = 1;
            mc_files[op.param2].most_recent_save_count = p2;
            mc_files[op.param2].last_saved_bank = p4;
            memcpy(mc_files[op.param2].data, op.data_ptr2.c(), 64);
            mc_last_file = op.param2;
          } else {
            op.operation = MemoryCardOperationKind::NO_OP;
            op.result = McStatusCode::INTERNAL_ERROR;
          }
        } else {
          fclose(fd);
          op.operation = MemoryCardOperationKind::NO_OP;
          op.result = McStatusCode::INTERNAL_ERROR;
        }
      } else {
        fclose(fd);
        op.operation = MemoryCardOperationKind::NO_OP;
        op.result = McStatusCode::INTERNAL_ERROR;
      }
    } else {
      fclose(fd);
      op.operation = MemoryCardOperationKind::NO_OP;
      op.result = McStatusCode::INTERNAL_ERROR;
    }
  } else {
    fmt::print("[MC] Error opening file, errno - {}", errno);
    op.operation = MemoryCardOperationKind::NO_OP;
    op.result = McStatusCode::INTERNAL_ERROR;
  }

  mc_print("[MC] synchronous save took {:.2f}ms\n", mc_timer.getMs());
}

void pc_game_load_open_file(FILE* fd) {
  if (fd) {
    // cb_openedload //
    size_t read_size = mc_get_total_bank_size(g_game_version);
    mc_print("reading save file...");
    if (fread(op.data_ptr.c() + p2 * read_size, read_size, 1, fd) == 1) {
      // cb_readload //
      mc_print("closing save file..");
      if (fclose(fd) == 0) {
        // cb_closedload //
        // added : check if aux bank exists
        if (p2 < 1 && fs::exists(mc_get_filename(g_game_version, op.param2 * 2 + 4 + p2 + 1))) {
          p2++;
          mc_print("reading next save bank {}",
                   mc_get_filename_no_dir(g_game_version, op.param2 * 2 + 4 + p2));
          auto new_bankname = mc_get_filename(g_game_version, op.param2 * 2 + 4 + p2);
          auto new_fd = file_util::open_file(new_bankname.string().c_str(), "rb");
          pc_game_load_open_file(new_fd);
        } else {
          // let's verify the data.
          McHeader* headers[2];
          McHeader* footers[2];
          bool ok[2];

          headers[0] = (McHeader*)(op.data_ptr.c());
          footers[0] = (McHeader*)(op.data_ptr.c() + sizeof(McHeader) + BANK_SIZE[g_game_version]);
          headers[1] = (McHeader*)(op.data_ptr.c() + mc_get_total_bank_size(g_game_version));
          footers[1] = (McHeader*)(op.data_ptr.c() + mc_get_total_bank_size(g_game_version) +
                                   sizeof(McHeader) + BANK_SIZE[g_game_version]);
          // static_assert(mc_get_total_bank_size(g_game_version) * 2 == 0x21000, "save layout");
          ok[0] = true;
          ok[1] = p2 == 1;

          for (int idx = 0; idx < 2; idx++) {
            u32 expected_save_count = headers[idx]->save_count;
            if (headers[idx]->save_count2 == expected_save_count &&
                footers[idx]->save_count == expected_save_count &&
                footers[idx]->save_count2 == expected_save_count) {
              // save count is okay!
              if (headers[idx]->magic == MEM_CARD_MAGIC && footers[idx]->magic == MEM_CARD_MAGIC) {
                // magic numbers okay!
                if (headers[idx]->checksum == footers[idx]->checksum) {
                  // checksum
                  auto expected_checksum = headers[idx]->checksum;
                  if (mc_checksum(make_u8_ptr(headers[idx] + 1), BANK_SIZE[g_game_version]) !=
                      expected_checksum) {
                    mc_print("failed checksum");
                    ok[idx] = false;
                  }
                } else {
                  mc_print("corrupted checksum");
                  ok[idx] = false;
                }
              } else {
                mc_print("bad magic");
                ok[idx] = false;
              }
            } else {
              mc_print("bad save count");
              ok[idx] = false;
            }
          }

          mc_print("checking loaded banks");

          //
          if (!ok[0] && !ok[1]) {
            // no good data.
            if (headers[0]->save_count == 0 && headers[0]->checksum == 0 &&
                headers[0]->magic == 0 && headers[0]->save_count2 == 0 &&
                headers[1]->save_count == 0 && headers[1]->checksum == 0 &&
                headers[1]->magic == 0 && headers[1]->save_count2 == 0) {
              // this is a fresh file that you tried to load from...
              mc_print("new game result");
              op.operation = MemoryCardOperationKind::NO_OP;
              op.result = McStatusCode::NEW_GAME;
              mc_last_file = op.param2;
            } else {
              mc_print("corrupted data");
              op.operation = MemoryCardOperationKind::NO_OP;
              op.result = McStatusCode::READ_ERROR;
            }
          } else {
            // pick the bank
            int bank = 0;

            if (!ok[0] || !ok[1]) {
              if (ok[1]) {
                bank = 1;
              }
            } else {
              bank = headers[0]->save_count <= headers[1]->save_count;
            }

            mc_print(fmt::format("loading bank {}", bank));
            u32 current_save_count = headers[bank]->save_count;
            memmove(
                op.data_ptr.c(),
                op.data_ptr.c() + bank * mc_get_total_bank_size(g_game_version) + sizeof(McHeader),
                BANK_SIZE[g_game_version]);
            mc_last_file = op.param2;
            mc_files[op.param2].most_recent_save_count = current_save_count;
            mc_files[op.param2].last_saved_bank = bank;
            op.operation = MemoryCardOperationKind::NO_OP;
            op.result = McStatusCode::OK;
            mc_print("load succeeded");
          }
        }
      } else {
        op.operation = MemoryCardOperationKind::NO_OP;
        op.result = McStatusCode::INTERNAL_ERROR;
      }
    } else {
      fclose(fd);
      op.operation = MemoryCardOperationKind::NO_OP;
      op.result = McStatusCode::INTERNAL_ERROR;
    }
  } else {
    op.operation = MemoryCardOperationKind::NO_OP;
    op.result = McStatusCode::INTERNAL_ERROR;
  }
}

/*!
 * PC port function to load a file. This does the whole loading at once, synchronously.
 */
void pc_game_load_synch() {
  Timer mc_timer;
  mc_timer.start();
  pc_update_card();

  // cb_reprobe_load //
  p2 = 0;
  mc_print("opening save file {}", mc_get_filename_no_dir(g_game_version, op.param2 * 2 + 4));

  auto path = mc_get_filename(g_game_version, op.param2 * 2 + 4);
  auto fd = file_util::open_file(path.string().c_str(), "rb");
  pc_game_load_open_file(fd);

  mc_print("synchronous load took {:.2f}ms\n", mc_timer.getMs());
}

/*!
 * Run the Memory Card state machine.  This is called once per frame in GOAL.
 * It:
 *  - does nothing if there is an in-progress memory card operation
 *  - if async memory card functions are done, runs their callbacks
 *  - if there is a requested operation, starts running sony functions.
 *  - if there is none of the above, and unknown cards, finds out about them.
 *  - every now and then, recheck cards.
 */
void MC_run() {
  // if we have an in-progress operation, it will have set a callback.
  if (callback) {
    s32 sony_cmd, sony_status;
    // check the status
    s32 status = sceMcSync(1, &sony_cmd, &sony_status);
    McCallbackFunc callback_for_sync = callback;
    if (status == sceMcExecRun) {
      // busy, return.
      return;
    }

    if (status == sceMcExecFinish) {
      // sony function is done. do the callback.
      callback = nullptr;
      (*callback_for_sync)(sony_status);
    } else {
      // sony function is done, but failed.
      callback = nullptr;
      (*callback_for_sync)(0);
    }

    if (callback) {
      // if we got another callback, it means there's another op started by the prev callback.
      // and this case, we want to wait for that operation to finish.
      return;
    }
  }

  // if we got here, there is no in-progress sony function. So start the next one, if we should
  if (op.operation == MemoryCardOperationKind::FORMAT) {
    // format memory card. Not used in PC port, so lets move on.
    return;
  } else if (op.operation == MemoryCardOperationKind::UNFORMAT) {
    // unformat memory card.
    return;
  } else if (op.operation == MemoryCardOperationKind::CREATE_FILE) {
    // create the game file.
    // there's no cards, keep in mind.
    return;
  } else if (op.operation == MemoryCardOperationKind::SAVE) {
    // write game save.
    // there's no cards, keep in mind.
    pc_game_save_synch();
    // allow some number of errors.
    op.retry_count--;
    if (op.retry_count == 0) {
      op.operation = MemoryCardOperationKind::NO_OP;
      op.result = McStatusCode::INTERNAL_ERROR;
    }
  } else if (op.operation == MemoryCardOperationKind::LOAD) {
    // load game save.
    // potato.
    if (!file_is_present(op.param2)) {
      // tried to load, but there's no save data in the file.
      op.operation = MemoryCardOperationKind::NO_OP;
      op.result = McStatusCode::NO_MEMORY;
    } else {
      pc_game_load_synch();
      op.retry_count--;
      if (op.retry_count == 0) {
        op.operation = MemoryCardOperationKind::NO_OP;
        op.result = McStatusCode::INTERNAL_ERROR;
      }
    }
  }
}

/////////////////////////
// Memory Card Functions
/////////////////////////

// These functions are called from GOAL to start memory card operations.

/*!
 * Set the language or something.
 * Why is this a memory card func?
 */
void MC_set_language(s32 l) {
  printf("Language set to %d\n", l);
  language = l;
}

/*!
 * Set the current memory card operation to FORMAT the given card.
 * Doesn't do anything in the port because we don't use memory cards.
 */
u64 MC_format(s32 /*card_idx*/) {
  return u64(McStatusCode::OK);
  // u64 can_add = op.operation == MemoryCardOperationKind::NO_OP;
  // mc_print("requested format");
  // if (can_add) {
  //  mc_print("setting op to format");
  //  op.operation = MemoryCardOperationKind::FORMAT;
  //  op.result = McStatusCode::BUSY;
  //  op.retry_count = 100;
  //  op.param = card_idx;
  //}
  // return can_add;
}

/*!
 * Set the current memory card operation to UNFORMAT the given card.
 * You get the idea.
 */
u64 MC_unformat(s32 /*card_idx*/) {
  return u64(McStatusCode::OK);
  // u64 can_add = op.operation == MemoryCardOperationKind::NO_OP;
  // mc_print("requested unformat");
  // if (can_add) {
  //  mc_print("setting op to unformat");
  //  op.operation = MemoryCardOperationKind::UNFORMAT;
  //  op.result = McStatusCode::BUSY;
  //  op.retry_count = 100;
  //  op.param = card_idx;
  //}
  // return can_add;
}

/*!
 * Set the current memory card operation to create the save file.
 * The data I believe is just an empty buffer used as temporary storage.
 */
u64 MC_createfile(s32 /*param*/, Ptr<u8> /*data*/) {
  return u64(McStatusCode::OK);
  // u64 can_add = op.operation == MemoryCardOperationKind::NO_OP;
  // mc_print("requested createfile");
  // if (can_add) {
  //  mc_print("setting op to create file");
  //  op.operation = MemoryCardOperationKind::CREATE_FILE;
  //  op.result = McStatusCode::BUSY;
  //  op.retry_count = 100;
  //  op.param = param;
  //  op.data_ptr = data;
  //}
  // return can_add;
}

/*!
 * Set the current operation to SAVE.
 * The "summary data" is data that will be used when previewing save files (number of orbs etc)
 * TODO put synchronous call here
 */
u64 MC_save(s32 card_idx, s32 file_idx, Ptr<u8> save_data, Ptr<u8> save_summary_data) {
  mc_print("requested save");
  u64 can_add = op.operation == MemoryCardOperationKind::NO_OP;
  if (can_add) {
    mc_print("setting op to save");
    op.operation = MemoryCardOperationKind::SAVE;
    op.result = McStatusCode::BUSY;
    op.retry_count = 100;
    op.param = card_idx;
    op.param2 = file_idx;
    op.data_ptr = save_data;
    op.data_ptr2 = save_summary_data;
  }
  return can_add;
}

/*!
 * Set the current operation to LOAD.
 * TODO put synchronous call here
 */
u64 MC_load(s32 card_idx, s32 file_idx, Ptr<u8> data) {
  mc_print("requested load");
  u64 can_add = op.operation == MemoryCardOperationKind::NO_OP;
  if (can_add) {
    mc_print("setting op to load");
    op.operation = MemoryCardOperationKind::LOAD;
    op.result = McStatusCode::BUSY;
    op.retry_count = 100;
    op.param = card_idx;
    op.param2 = file_idx;
    op.data_ptr = data;
  }
  return can_add;
}

/*!
 * Some sort of test function for memory card stuff.
 * This is exported as a GOAL function, but nothing calls it.
 */
void MC_makefile(s32 port, s32 size) {
  sceMcMkdir(port, 0, "/BASCUS-00000XXXXXXXX");
  // wait for operation to complete
  s32 cmd, result, fd;
  sceMcSync(0, &cmd, &result);

  if (result == sceMcResSucceed || result == sceMcResNoEntry) {
    // it worked, or the folder already exists...

    // open file
    sceMcOpen(port, 0, "/BASCUS-00000XXXXXXXX/BASCUS-00000XXXXXXXX", SCE_CREAT | SCE_WRONLY);
    sceMcSync(0, &cmd, &fd);

    if (result < 0) {
      printf("Can't open file on memcard [%d]\n", result);
    } else {
      // write some random crap into the memory card.
      sceMcWrite(fd, Ptr<u8>(0x1000000).c(), size);
      sceMcSync(0, &cmd, &result);
      if (result != size) {
        printf("Only written %d bytes\n", result);
      }
      sceMcClose(fd);
      sceMcSync(0, &cmd, &result);
    }
  } else {
    printf("Can\'t create garbage folder [%d]\n", result);
  }
}

/*!
 * Get the result of the currently executing (or most recently executed) command
 */
u32 MC_check_result() {
  return (u32)op.result;
}

/*!
 * Update the info for the given slot.
 * You can call this at any time.
 * The slot includes the four save slots (8 banks), and a few other files.
 */
void MC_get_status(s32 /*slot*/, Ptr<mc_slot_info> info) {
  // slot is ignored, so you'll get the same thing regardless of what slot you pick

  info->handle = 0;
  info->known = 0;
  info->formatted = 0;
  info->initted = 0;
  for (s32 i = 0; i < 4; i++) {
    info->files[i].present = 0;
  }
  info->last_file = 0xffffffff;
  info->mem_required = SAVE_SIZE[g_game_version];
  info->mem_actual = 0;

  pc_update_card();
  info->known = 1;
  info->handle = PC_MEM_CARD_HANDLE;
  info->formatted = 1;
  info->mem_actual = SAVE_SIZE[g_game_version];  // idk TODO does this matter?
  info->initted = 1;
  // copy over the preview data.
  for (s32 file = 0; file < 4; file++) {
    info->files[file].present = mc_files[file].present;
    for (s32 i = 0; i < 64; i++) {  // actually a loop over u32's
      info->files[file].data[i] = mc_files[file].data[i];
    }
  }
  info->last_file = mc_last_file;
}
