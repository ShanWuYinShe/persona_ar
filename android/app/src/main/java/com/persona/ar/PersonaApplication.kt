package com.persona.ar

import android.app.Application
import android.util.Log
import com.persona.ar.character.filament.FilamentEngineHolder

class PersonaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 预热 Filament 引擎：提前加载原生库并完成 GL 后端初始化，避免首次进入 AR 视图时卡顿；
        // 任何失败（极端机型无可用后端）只记录，不让应用启动崩溃 —— AR 层会自动留空并退回贴地引导
        runCatching { FilamentEngineHolder.engine() }
            .onFailure { Log.w("PersonaApplication", "Filament engine warm-up failed: ${it.message}") }
    }
}
