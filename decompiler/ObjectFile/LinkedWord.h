#pragma once
// clang-format off
// TODO - clang formatting is off in this file due to 
// differences in newer versions of clang-format which we have not updates to yet

#if defined(__GNUC__)
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"
#elif defined(__clang__)
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
#endif

/*!
 * @file LinkedWord.h
 * A word (4 bytes), possibly with some linking info.
 */

#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include "common/common_types.h"
#include "common/util/Assert.h"

namespace decompiler {
/*!
 * A word and its kind in 8 bytes: there's one for every word of every object file, which adds up to
 * gigabytes for the later games. Symbol names are stored once, in a table shared by every word.
 */
class LinkedWord {
 public:
  enum Kind : u8 {
    PLAIN_DATA,      // just plain data
    PTR,             // pointer to a location
    HI_PTR,          // lower 16-bits of this data are the upper 16 bits of a pointer
    LO_PTR,          // lower 16-bits of this data are the lower 16 bits of a pointer
    SYM_PTR,         // this is a pointer to a symbol
    EMPTY_PTR,       // this is a pointer to the empty list
    SYM_OFFSET,      // this is an offset of a symbol in the symbol table
    SYM_VAL_OFFSET,  // offset to the value of the symbol (different in jak 2)
    TYPE_PTR         // this is a pointer to a type
  };

  u32 data;

  explicit LinkedWord(uint32_t _data) : data(_data) {}

  bool holds_string() const {
    const auto k = kind();
    return k == SYM_PTR || k == SYM_OFFSET || k == TYPE_PTR || k == SYM_VAL_OFFSET;
  }

  void set_to_empty_ptr() { m_kind_and_value = pack(EMPTY_PTR, 0); }

  void set_to_symbol(Kind kind, const std::string& name) {
    m_kind_and_value = pack(kind, symbol_index(name));
  }

  void set_to_pointer(Kind kind, u32 label_id) { m_kind_and_value = pack(kind, label_id); }

  void set_to_plain_data() { m_kind_and_value = pack(PLAIN_DATA, 0); }

  u8 get_byte(int idx) const {
    ASSERT(kind() == PLAIN_DATA);
    switch (idx) {
      case 0:
        return data & 0xff;
      case 1:
        return (data >> 8) & 0xff;
      case 2:
        return (data >> 16) & 0xff;
      case 3:
        return (data >> 24) & 0xff;
      default:
        ASSERT(false);
        return 0;
    }
  }

  // kind, label_id, symbol_name
  Kind kind() const { return (Kind)(m_kind_and_value >> kValueBits); }

  u32 label_id() const {
    ASSERT(kind() == PTR || kind() == LO_PTR || kind() == HI_PTR);
    return m_kind_and_value & kValueMask;
  }

  std::string symbol_name() const {
    ASSERT(holds_string());
    auto& table = symbol_table();
    std::lock_guard<std::mutex> lock(table.mutex);
    return table.names.at(m_kind_and_value & kValueMask);
  }

 private:
  // The kind in the top 4 bits, and a label id or an index into the symbol table below them.
  static constexpr int kValueBits = 28;
  static constexpr u32 kValueMask = (1u << kValueBits) - 1;
  u32 m_kind_and_value = 0;

  static u32 pack(Kind kind, u32 value) {
    ASSERT(value <= kValueMask);
    return ((u32)kind << kValueBits) | value;
  }

  struct SymbolTable {
    std::mutex mutex;
    std::vector<std::string> names;
    std::unordered_map<std::string, u32> indices;
  };

  static SymbolTable& symbol_table() {
    static SymbolTable table;
    return table;
  }

  static u32 symbol_index(const std::string& name) {
    auto& table = symbol_table();
    std::lock_guard<std::mutex> lock(table.mutex);
    const auto [it, added] = table.indices.try_emplace(name, (u32)table.names.size());
    if (added) {
      table.names.push_back(name);
    }
    return it->second;
  }
};

static_assert(sizeof(LinkedWord) == 8);

}  // namespace decompiler

#if defined(__GNUC__)
#pragma GCC diagnostic pop
#elif defined(__clang__)
#pragma clang diagnostic pop
#endif
// clang-format on