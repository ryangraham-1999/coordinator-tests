package com.streamlinemyjob.inspect.photo

import android.Manifest
import android.content.Context
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P3 (designs/stage-2-camera.md 4.9): does the managed emulator have a back camera that CameraX can bind
 * and take one shot from, into memory (no file)? Also compiles `ResolutionSelector` and `ImageProxy.toBitmap()`,
 * which the camera screen (S2.6) will use.
 */
@RunWith(AndroidJUnit4::class)
class CameraProbeTest {
    @get:Rule
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun oneShotIsTakenIntoMemory() {
        val result = probe()
        println("CAMERA_PROBE $result")
        assertTrue("CAMERA_PROBE $result", result.startsWith("OK"))
    }

    /** Binds, takes one shot into memory and returns `OK <w>x<h> rotation=.. format=..` or the exact error (asserts only on setup). */
    fun probe(): String {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val provider = ProcessCameraProvider.getInstance(context).get(20, TimeUnit.SECONDS)
        val hasBack = provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
        val ids = provider.availableCameraInfos.size
        assertTrue("CAMERA_PROBE no back camera (availableCameraInfos=$ids)", hasBack)

        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(2048, 1536),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    )
                    .build(),
            )
            .build()
        val owner = Owner()
        var bindError: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                owner.registry.currentState = Lifecycle.State.RESUMED
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, capture)
            } catch (t: Throwable) {
                bindError = t
            }
        }
        assertTrue("CAMERA_PROBE bind failed: $bindError", bindError == null)

        val latch = CountDownLatch(1)
        val result = AtomicReference<String>()
        capture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val bmp = image.toBitmap()
                        result.set("OK ${bmp.width}x${bmp.height} rotation=${image.imageInfo.rotationDegrees} format=${image.format}")
                        bmp.recycle()
                    } catch (t: Throwable) {
                        result.set("ERR toBitmap ${t::class.java.name}: ${t.message}")
                    } finally {
                        image.close()
                        latch.countDown()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    result.set("ERR capture code=${exception.imageCaptureError} ${exception.message}")
                    latch.countDown()
                }
            },
        )
        val finished = latch.await(30, TimeUnit.SECONDS)
        instrumentation.runOnMainSync { provider.unbindAll() }
        assertTrue("CAMERA_PROBE no result in 30 s", finished)
        return result.get()
    }
}
