package com.armobile.artracking

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.annotations.ReactProp

@ReactModule(name = ArCameraViewManager.NAME)
class ArCameraViewManager : SimpleViewManager<ArCameraView>() {

    companion object {
        const val NAME = "ArCameraView"
    }

    override fun getName() = NAME

    override fun createViewInstance(reactContext: ThemedReactContext): ArCameraView {
        val view = ArCameraView(reactContext)

        fun emit(eventName: String, params: com.facebook.react.bridge.WritableMap) {
            reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(eventName, params)
        }

        view.listener = object : ArTrackingListener {
            override fun onAnchorFound(name: String) {
                emit("ArAnchorFound", Arguments.createMap().apply { putString("name", name) })
            }

            override fun onAnchorUpdated(
                name: String,
                tx: Float, ty: Float, tz: Float,
                qx: Float, qy: Float, qz: Float, qw: Float,
            ) {
                val map = Arguments.createMap().apply {
                    putString("name", name)
                    putDouble("tx", tx.toDouble())
                    putDouble("ty", ty.toDouble())
                    putDouble("tz", tz.toDouble())
                    putDouble("qx", qx.toDouble())
                    putDouble("qy", qy.toDouble())
                    putDouble("qz", qz.toDouble())
                    putDouble("qw", qw.toDouble())
                }
                emit("ArAnchorUpdated", map)
            }

            override fun onAnchorRemoved(name: String) {
                emit("ArAnchorRemoved", Arguments.createMap().apply { putString("name", name) })
            }
        }

        reactContext.addLifecycleEventListener(object : LifecycleEventListener {
            override fun onHostResume() = view.onHostResume()
            override fun onHostPause() = view.onHostPause()
            override fun onHostDestroy() = view.onHostDestroy()
        })

        return view
    }

    @ReactProp(name = "targetAssetName")
    fun setTargetAssetName(view: ArCameraView, name: String?) {
        view.targetAssetName = name
    }

    @ReactProp(name = "targetName")
    fun setTargetName(view: ArCameraView, name: String?) {
        view.targetName = name ?: "target"
    }

    @ReactProp(name = "physicalWidth", defaultFloat = 0.15f)
    fun setPhysicalWidth(view: ArCameraView, width: Float) {
        view.physicalWidthMeters = width
    }

    @ReactProp(name = "overlayBoxSize", defaultFloat = 0.05f)
    fun setOverlayBoxSize(view: ArCameraView, size: Float) {
        view.overlayBoxSizeMeters = size
    }
}
