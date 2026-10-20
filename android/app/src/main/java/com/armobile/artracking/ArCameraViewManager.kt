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

            override fun onStatus(state: String, message: String?) {
                emit("ArStatus", Arguments.createMap().apply {
                    putString("state", state)
                    if (message != null) putString("message", message)
                })
            }
        }

        val lifecycle = object : LifecycleEventListener {
            override fun onHostResume() = view.onHostResume()
            override fun onHostPause() = view.onHostPause()
            override fun onHostDestroy() = view.onHostDestroy()
        }
        reactContext.addLifecycleEventListener(lifecycle)
        // Gỡ listener khi React unmount view (trước đây listener rò rỉ và session không bao giờ được đóng)
        view.onDispose = { reactContext.removeLifecycleEventListener(lifecycle) }

        return view
    }

    override fun onDropViewInstance(view: ArCameraView) {
        super.onDropViewInstance(view)
        view.dispose()
    }

    /** JSON mảng [{"name": "<projectId>", "url": "<triggerImageUrl>"}] — danh sách ảnh để nhận diện */
    @ReactProp(name = "targetsJson")
    fun setTargetsJson(view: ArCameraView, json: String?) {
        view.targetsJson = json
    }

    /** {"target": "<projectId>", "assets": [...]} — overlay của project đang active */
    @ReactProp(name = "overlayJson")
    fun setOverlayJson(view: ArCameraView, json: String?) {
        view.overlayJson = json
    }

    @ReactProp(name = "reloadToken", defaultInt = 0)
    fun setReloadToken(view: ArCameraView, token: Int) {
        view.reloadToken = token
    }

    @ReactProp(name = "physicalWidth", defaultFloat = 0.15f)
    fun setPhysicalWidth(view: ArCameraView, width: Float) {
        view.physicalWidthMeters = width
    }

    @ReactProp(name = "emitPose", defaultBoolean = false)
    fun setEmitPose(view: ArCameraView, emit: Boolean) {
        view.emitPose = emit
    }
}