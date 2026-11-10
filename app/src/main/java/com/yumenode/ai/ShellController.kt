package com.yumenode.ai

import android.app.Activity
import android.widget.FrameLayout
import android.view.View

class ShellController(private val host: ShellHost) : ShellHost {

    override val activity: Activity get() = host.activity

    override val palette: YumenodeTheme get() = host.palette

    override fun buildUi() {
        host.buildUi()
    }

    override fun buildChatPage(): View = host.buildChatPage()

    override fun buildAppBar(): View = host.buildAppBar()

    override fun buildBottomNav(): View = host.buildBottomNav()

    override fun switchTab(index: Int) {
        host.switchTab(index)
    }

    override fun buildDrawer(rootFrame: android.widget.FrameLayout) {
        host.buildDrawer(rootFrame)
    }

    override fun refreshDrawer() {
        host.refreshDrawer()
    }

    override fun openDrawer() {
        host.openDrawer()
    }

    override fun closeDrawer() {
        host.closeDrawer()
    }

    override fun switchModelsTab(index: Int) {
        host.switchModelsTab(index)
    }

    override fun highlightModelsTabs(selected: Int) {
        host.highlightModelsTabs(selected)
    }
}