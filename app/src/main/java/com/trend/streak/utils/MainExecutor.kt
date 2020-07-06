package com.trend.streak.camerax.utils

import android.os.Handler
import android.os.Looper
import com.robertlevonyan.demo.camerax.utils.ThreadExecutor


class MainExecutor : ThreadExecutor(Handler(Looper.getMainLooper())) {

    override fun execute(runnable: Runnable) {
        handler.post(runnable)
    }
}