package com.example.myrustapp

import android.util.Log

object NativeLib {
    private var loaded = false

    init {
        try {
            System.loadLibrary("native_lib")
            loaded = true
            Log.d("NativeLib", "native_lib loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("NativeLib", "Failed to load native_lib: ${e.message}")
            loaded = false
        }
    }

    fun isLoaded(): Boolean = loaded

    fun increment(count: Int): Int {
        return if (loaded) {
            try {
                nativeIncrement(count)
            } catch (e: Exception) {
                Log.e("NativeLib", "JNI call failed: ${e.message}")
                count  // fallback: return unchanged
            }
        } else {
            Log.w("NativeLib", "Library not loaded, using fallback")
            count + 1  // pure Kotlin fallback so app still works
        }
    }

    @JvmStatic
    private external fun nativeIncrement(count: Int): Int
}
