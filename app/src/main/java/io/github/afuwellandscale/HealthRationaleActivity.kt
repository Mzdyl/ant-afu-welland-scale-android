package io.github.afuwellandscale

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.afuwellandscale.ui.theme.AfuScaleTheme

class HealthRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AfuScaleTheme { HealthRationaleScreen() }
        }
    }
}

@Composable
private fun HealthRationaleScreen() {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Icon(
                Icons.Rounded.HealthAndSafety,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text("Health Connect 同步", style = MaterialTheme.typography.headlineLarge)
            Text(
                "本应用只写入体重、体脂率、去脂体重、体水分量和骨量，不读取其他健康数据。",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "只有完成体脂和阻抗测量后才会同步；你可以随时在 Health Connect 中撤销权限或删除数据。",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
