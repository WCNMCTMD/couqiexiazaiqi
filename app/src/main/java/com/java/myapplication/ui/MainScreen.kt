package com.java.myapplication.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * 主界面：底部三 Tab（下载 / 任务 / 设置）。
 */
@Composable
fun MainScreen() {
    var selected by remember { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selected == 0,
                    onClick = { selected = 0 },
                    icon = { Text("📥") },
                    label = { Text("下载") }
                )
                NavigationBarItem(
                    selected = selected == 1,
                    onClick = { selected = 1 },
                    icon = { Text("🗂") },
                    label = { Text("任务") }
                )
                NavigationBarItem(
                    selected = selected == 2,
                    onClick = { selected = 2 },
                    icon = { Text("⚙️") },
                    label = { Text("设置") }
                )
            }
        }
    ) { innerPadding ->
        val modifier = Modifier.padding(innerPadding)
        when (selected) {
            0 -> DownloadTab(modifier)
            1 -> TasksTab(modifier)
            2 -> SettingsTab(modifier)
        }
    }
}