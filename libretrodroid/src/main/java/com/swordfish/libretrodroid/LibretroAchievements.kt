package com.swordfish.libretrodroid

import android.os.ParcelFileDescriptor

/** Host-side helpers that need no GLRetroView. */
object LibretroAchievements {
    /**
     * The RetroAchievements hash of [files] (the game first, then anything its sheet names) for the rcheevos console
     * [consoleId], or null when rcheevos cannot identify it. The descriptors are duplicated, so the caller keeps its own.
     */
    fun hash(consoleId: Int, files: List<VirtualFile>): String? {
        val detached = ArrayList<DetachedVirtualFile>(files.size)
        try {
            files.forEach {
                detached += DetachedVirtualFile(it.virtualPath, ParcelFileDescriptor.dup(it.fileDescriptor.fileDescriptor).detachFd())
            }
        } catch (e: Exception) {
            // A dup failed part way: the native call never sees these, so close them here.
            detached.forEach { runCatching { ParcelFileDescriptor.adoptFd(it.fileDescriptor).close() } }
            throw e
        }
        return LibretroDroid.achievementsHash(consoleId, detached)
    }
}
