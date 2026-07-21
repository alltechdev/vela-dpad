# The app's only native module: a mallopt() purge shim (see velamem.cpp for why).
# Deliberately tiny and STL-free, so it adds a few KB per ABI rather than a runtime.
cmake_minimum_required(VERSION 3.22.1)
project(velamem LANGUAGES CXX)

add_library(velamem SHARED velamem.cpp)

# -Os and no exceptions/RTTI: this is three lines of code calling libc, none of which needs them.
target_compile_options(velamem PRIVATE -Os -fno-exceptions -fno-rtti -fvisibility=hidden)

# Only libc is needed; mallopt lives there. No log lib, the Kotlin side does the logging.
target_link_libraries(velamem)
