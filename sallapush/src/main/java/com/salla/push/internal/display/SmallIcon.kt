package com.salla.push.internal.display

import android.content.Context
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.core.content.ContextCompat
import com.salla.push.R
import com.salla.push.internal.ManifestConfig
import com.salla.push.internal.log.LogCode
import com.salla.push.internal.log.Logger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The notification small icon: com.salla.push.default_notification_icon, then Firebase's default icon,
 * then the SDK's bundled monochrome icon (WARN once per process, saying why each meta-data icon was not used). Never
 * the launcher icon: on API 26 an adaptive small icon makes the system throw an uncatchable exception in the host, so
 * one is replaced by the SDK's icon there.
 */
internal class SmallIcon(
    private val logger: Logger,
    private val loadDrawable: (Context, Int) -> Drawable? = { context, id -> ContextCompat.getDrawable(context, id) },
) {

    private val defaultWarned = AtomicBoolean(false)

    fun resolve(context: Context, manifest: ManifestConfig): Int {
        val sdkIcon = assess(context, manifest.smallIcon)
        if (sdkIcon is Check.Usable) return sdkIcon.resourceId
        val firebaseIcon = assess(context, manifest.firebaseSmallIcon)
        if (firebaseIcon is Check.Usable) return firebaseIcon.resourceId
        if (defaultWarned.compareAndSet(false, true)) {
            logger.log(LogCode.DISPLAY_DEFAULT_ICON) {
                val firebase = if (firebaseIcon is Check.Missing) "" else "; Firebase's default_notification_icon ${firebaseIcon.problem}"
                "The ${ManifestConfig.KEY_SMALL_ICON} meta-data ${sdkIcon.problem}$firebase; using SallaPush's default icon. " +
                    "Add a monochrome drawable there."
            }
        }
        return R.drawable.sallapush_ic_stat_default
    }

    /** Whether a meta-data icon can be the small icon, and if not, why. */
    private sealed class Check(val problem: String) {
        class Usable(val resourceId: Int) : Check("")

        object Missing : Check("is not set")

        class WrongType(type: String?) : Check(type?.let { "is a $it resource, not a drawable" } ?: "is not a drawable resource")

        object Adaptive : Check("is an adaptive icon, which Android 8.0 cannot show as a small icon")

        object Unloadable : Check("could not be loaded")
    }

    private fun assess(context: Context, resourceId: Int): Check {
        if (resourceId == 0) return Check.Missing
        val type = try {
            context.resources.getResourceTypeName(resourceId)
        } catch (e: Exception) {
            null
        }
        if (type != "drawable" && type != "mipmap") return Check.WrongType(type)
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.O) {
            val drawable = try {
                loadDrawable(context, resourceId)
            } catch (e: Exception) {
                null
            } ?: return Check.Unloadable
            if (drawable is AdaptiveIconDrawable) return Check.Adaptive
        }
        return Check.Usable(resourceId)
    }
}
