package com.example.myrustapp.ui.main

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myrustapp.NativeLib
import com.example.myrustapp.theme.MyRustAppTheme

@Composable
fun CounterScreen(modifier: Modifier = Modifier) {
    var count by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "$count",
            fontSize = 72.sp,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (NativeLib.isLoaded()) "✓ Rust JNI" else "✗ Kotlin fallback",
            fontSize = 12.sp,
            color = if (NativeLib.isLoaded()) androidx.compose.ui.graphics.Color(0xFF4CAF50)
                    else androidx.compose.ui.graphics.Color(0xFFFF5722),
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                val newCount = NativeLib.increment(count)
                Log.d("CounterScreen", "increment: $count -> $newCount")
                count = newCount
            },
            modifier = Modifier.size(width = 200.dp, height = 64.dp),
        ) {
            Text(text = "+1", fontSize = 24.sp)
        }
    }
}

@Preview(showBackground = true)
@Composable
fun CounterScreenPreview() {
    MyRustAppTheme { CounterScreen() }
}
