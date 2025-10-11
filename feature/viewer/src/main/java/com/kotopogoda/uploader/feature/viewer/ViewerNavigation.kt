package com.kotopogoda.uploader.feature.viewer

const val VIEWER_ROUTE = "viewer"
const val VIEWER_START_INDEX_ARG = "startIndex"
const val VIEWER_ROUTE_PATTERN = "$VIEWER_ROUTE?$VIEWER_START_INDEX_ARG={$VIEWER_START_INDEX_ARG}"

fun viewerRoute(startIndex: Int = 0): String =
    "$VIEWER_ROUTE?$VIEWER_START_INDEX_ARG=$startIndex"
