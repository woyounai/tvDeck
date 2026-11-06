#include <cstdio>
#include <cstdlib>
#include <cstring>
extern "C" int adb_commandline_porting(char **, size_t *, int, const char **);
int main() {
    const char *arguments[] = {"version"};
    char *output = nullptr;
    size_t size = 0;
    int status = adb_commandline_porting(&output, &size, 1, arguments);
    bool valid = output && std::strstr(output, "Android Debug Bridge version") != nullptr;
    if (output) std::fwrite(output, 1, size, stdout);
    std::free(output);
    return status == 0 && valid ? 0 : 1;
}
