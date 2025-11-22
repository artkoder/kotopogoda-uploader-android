package com.kotopogoda.uploader.feature.viewer.enhance

fun interface NativeTileProgressCallback {
    fun onTileProgress(stage: String, tilesCompleted: Int, tileCount: Int)
}
