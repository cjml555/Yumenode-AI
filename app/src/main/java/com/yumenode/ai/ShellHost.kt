package com.yumenode.ai

import android.app.Activity
import android.widget.FrameLayout
import android.view.View

interface ShellHost {
    val activity: Activity
    val palette: YumenodeTheme

    fun buildUi()
    fun buildChatPage(): View
    fun buildAppBar(): View
    fun buildBottomNav(): View
    fun switchTab(index: Int)
    fun buildDrawer(rootFrame: FrameLayout)
    fun refreshDrawer()
    fun openDrawer()
    fun closeDrawer()
    fun switchModelsTab(index: Int)
    fun highlightModelsTabs(selected: Int)
}