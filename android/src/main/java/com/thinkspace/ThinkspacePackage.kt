package com.thinkspace

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider
import com.facebook.react.uimanager.ViewManager

class ThinkspaceViewPackage : BaseReactPackage() {
  override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<*, *>> {
    return listOf(ThinkspaceViewManager())
  }

  override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? {
    return when (name) {
      PdfEngineModule.NAME -> PdfEngineModule(reactContext)
      else -> null
    }
  }

  override fun getReactModuleInfoProvider() = ReactModuleInfoProvider {
    mapOf(
      PdfEngineModule.NAME to ReactModuleInfo(
        /* name           */ PdfEngineModule.NAME,
        /* className      */ PdfEngineModule.NAME,
        /* canOverrideExistingModule */ false,
        /* needsEagerInit */ false,
        /* isCxxModule    */ false,
        /* isTurboModule  */ false
      )
    )
  }
}
