package io.github.afuwellandscale

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

class HealthRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 48, 40, 40)
        }
        root.addView(
            TextView(this).apply {
                text = "Health Connect 同步说明"
                textSize = 24f
            },
        )
        root.addView(
            TextView(this).apply {
                text = "\n本应用只在你完成体重秤测量后写入体重、体脂率、去脂体重、体水分量和骨量。应用不会读取其他健康数据。"
                textSize = 16f
            },
        )
        setContentView(root)
    }
}
