package com.lumiscan.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumiscan.app.nav.LocalNav
import com.lumiscan.app.ui.common.AppTopBar
import com.lumiscan.app.ui.theme.Bg
import com.lumiscan.app.ui.theme.Ink

@Composable
fun AllToolsScreen() {
    val nav = LocalNav.current
    Column(Modifier.fillMaxSize().background(Bg)) {
        AppTopBar("全部工具")
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding()) {
            Tools.categories.forEach { (title, tools) ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).padding(top = 12.dp)) {
                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.padding(start = 14.dp))
                    ToolGrid(tools) { Tools.launch(nav, it.action) }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}
