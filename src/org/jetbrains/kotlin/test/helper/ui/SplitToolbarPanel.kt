// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.test.helper.ui

import com.intellij.openapi.actionSystem.ActionToolbar
import javax.swing.GroupLayout
import javax.swing.JPanel
import javax.swing.SwingConstants

class SplitToolbarPanel(
  private val leftToolbar: ActionToolbar,
  private val rightToolbar: ActionToolbar,
) : JPanel() {

  init {
    val filler = JPanel()
    val layout = GroupLayout(this)
    val hg = layout.createSequentialGroup()
    val vg = layout.createParallelGroup(GroupLayout.Alignment.TRAILING)
    hg.apply {
      addComponent(leftToolbar.component, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE, GroupLayout.PREFERRED_SIZE)
      addComponent(filler, 0, 0, 50000)
      addComponent(rightToolbar.component, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE, GroupLayout.PREFERRED_SIZE)
    }
    vg.apply {
      addComponent(leftToolbar.component)
      addComponent(filler)
      addComponent(rightToolbar.component)
    }
    layout.setHorizontalGroup(hg)
    layout.setVerticalGroup(vg)
    layout.linkSize(SwingConstants.VERTICAL, leftToolbar.component, rightToolbar.component)
    this.layout = layout
  }

  fun refresh() {
    rightToolbar.updateActionsImmediately()
  }

}
