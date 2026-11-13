package io.github.soclear.oneuix.hook.systemui

import io.github.libxposed.api.XposedModule
import io.github.soclear.oneuix.common.ONE_UI_VERSION
import io.github.soclear.oneuix.hook.util.afterAttachTry

object MultiSimBar {
    private const val ITEMS = "com.android.systemui.samsung.quicksetting.domain.model.items"

    // IntSize packs width in the high 32 bits and height in the low 32 bits.
    private fun pack(w: Int, h: Int) = (w.toLong() shl 32) or (h.toLong() and 0xFFFFFFFFL)

    // Make the multi-SIM info bar resizable like the other quick panel components,
    // in both portrait and landscape. Width can reach the full grid column count.
    context(xposedModule: XposedModule)
    fun resizableBar() = afterAttachTry(ONE_UI_VERSION >= 80500) {
        val barClass = classLoader.loadClass("$ITEMS.MultiSimPreferredBar")
        val itemClass = classLoader.loadClass("$ITEMS.GridTileItem")

        // 1. allow the resize handles
        xposedModule.hook(itemClass.getDeclaredMethod("isResizeEnabled")).intercept { chain ->
            if (barClass.isInstance(chain.thisObject)) true else chain.proceed()
        }

        // 2. min/max span size shown while dragging
        val constraintsClass = classLoader.loadClass(
            "com.android.systemui.samsung.quicksetting.domain.model.SpanSizeConstraints"
        )
        val constraintsCtor = constraintsClass.getDeclaredConstructor(
            Long::class.javaPrimitiveType, Long::class.javaPrimitiveType
        ).apply { isAccessible = true }
        xposedModule.hook(
            itemClass.getDeclaredMethod("resizeConstraints", Int::class.javaPrimitiveType)
        ).intercept { chain ->
            if (barClass.isInstance(chain.thisObject)) {
                val columns = chain.args[0] as Int
                constraintsCtor.newInstance(pack(minOf(3, columns), 1), pack(columns, 1))
            } else {
                chain.proceed()
            }
        }

        // 3. final size validation: width 3..(columns - start column), height fixed at 1
        val sizeClass = classLoader.loadClass("androidx.compose.ui.unit.IntSize")
        val box = sizeClass.getDeclaredMethod("box-impl", Long::class.javaPrimitiveType)
        val validate = itemClass.getDeclaredMethod(
            "validateTileSpanSize-2NWLvuI",
            Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType
        )
        xposedModule.hook(validate).intercept { chain ->
            if (barClass.isInstance(chain.thisObject)) {
                val columns = chain.args[0] as Int
                val width = ((chain.args[1] as Long) shr 32).toInt()
                val startColumn = ((chain.args[2] as Long) shr 32).toInt()
                val w = width.coerceAtLeast(3).coerceAtMost(maxOf(columns - startColumn, 1))
                box.invoke(null, pack(w, 1))
            } else {
                chain.proceed()
            }
        }
    }
}
