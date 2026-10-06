// Read GGUF chat metadata without allocating or changing model weights.
#include "gguf.h"
#include <cstdio>

int main(int argc, char ** argv) {
    if (argc != 2) return 2;
    gguf_init_params params{true, nullptr};
    auto gguf = gguf_init_from_file(argv[1], params);
    if (!gguf) return 2;
    const auto key = gguf_find_key(gguf, "tokenizer.chat_template");
    if (key < 0) return 3;
    std::puts(gguf_get_val_str(gguf, key));
    gguf_free(gguf);
}
