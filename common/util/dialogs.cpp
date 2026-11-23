#include "dialogs.h"

#ifdef __ANDROID__
#include "third-party/SDL/include/SDL3/SDL_messagebox.h"
#endif

namespace dialogs {
// char const * const aDialogType , /* "ok" "okcancel" "yesno" "yesnocancel" */
// char const * const aIconType , /* "info" "warning" "error" "question" */
// int const aDefaultButton ) /* 0 for cancel/no , 1 for ok/yes , 2 for no in yesnocancel */
void create_error_message_dialog(const std::string& title, const std::string& message) {
#ifdef __ANDROID__
  // tinyfiledialogs forks a shell to look for desktop dialog tools, which Android doesn't have
  SDL_ShowSimpleMessageBox(SDL_MESSAGEBOX_ERROR, title.c_str(), message.c_str(), nullptr);
#else
  tinyfd_messageBox(title.data(), message.data(), "ok", "error", 1);
#endif
}
}  // namespace dialogs
