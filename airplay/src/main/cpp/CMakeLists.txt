cmake_minimum_required(VERSION 3.22.1)
project(airplay_native)

find_package(openssl REQUIRED CONFIG)

file(GLOB UXPLAY_SOURCES CONFIGURE_DEPENDS
	"uxplay/*.c"
	"uxplay/llhttp/*.c"
	"uxplay/mdnsd/*.c"
	"uxplay/playfair/*.c"
)
file(GLOB PLIST_SOURCES CONFIGURE_DEPENDS
	"plist/src/*.c"
	"plist/libcnary/node.c"
	"plist/libcnary/node_list.c"
)

add_library(airplay_native SHARED airplay_native.cpp ${UXPLAY_SOURCES} ${PLIST_SOURCES})

target_include_directories(airplay_native PRIVATE
	uxplay
	uxplay/llhttp
	uxplay/mdnsd
	uxplay/playfair
	plist/src
	plist/include
	plist/libcnary/include
)

target_compile_definitions(airplay_native PRIVATE
	STANDALONE
	__STDC_CONSTANT_MACROS
	__STDC_LIMIT_MACROS
	TARGET_POSIX
	_REENTRANT
	HAVE_STRNDUP
	PACKAGE_VERSION=\"2.7.0\"
	PLIST_210
	PLIST_230
)

target_compile_features(airplay_native PRIVATE cxx_std_17)
target_link_libraries(airplay_native PRIVATE android log openssl::libcrypto.so)
