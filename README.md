# LibretroDroid
LibretroDroid is a simple C++ LibRetro frontend library for Android. It's powering [Lemuroid](https://github.com/Swordfish90/Lemuroid).

### Supported features:
* 2D Cores
* GL Cores
* Audio
* Gamepad events
* Serialization and Deserialization of game states
* Serialization and Deserialization of SaveRAM
* Simple shader effects (CRT and LCD)
* Touchscreen
* Multiple disk support
* Core variables

### Tested working cores:
* Stella
* Gambatte
* mGBA
* Mupen64Plus
* Snes9x
* QuickNES
* fceumm
* nestopia
* PPSSPP
* fbneo
* picodrive
* Genesis Plus GX
* DeSmuME
* PCSXReARMed

### Setup
LibretroDroid can be added to a standard build.gradle file.
```
[app/build.gradle]
dependencies {
    ...
    implementation 'com.github.swordfish90:libretrodroid:<version>'
    ...
}
```

## Fork additions

### RetroAchievements (rcheevos)
`achievementsEnable(true)` before `create()` starts an rc_client (softcore only). The runner reads core memory
(`SET_MEMORY_MAPS` or `retro_get_memory_data`), calls `rc_client_do_frame` after each `retro_run`, and hands every
HTTP request to the host through `GLRetroView.getAchievementServerCalls()`; the host answers with
`achievementsServerResponse`. Events arrive on `getAchievementEvents()`. `LibretroAchievements.hash` identifies a game
from fds (cue/bin, m3u, CHD via libchdr) without a core loaded.
