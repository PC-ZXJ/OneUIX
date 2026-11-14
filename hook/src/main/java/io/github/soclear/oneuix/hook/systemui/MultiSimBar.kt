package io.github.soclear.oneuix.hook.systemui

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.soclear.oneuix.common.ONE_UI_VERSION
import io.github.soclear.oneuix.hook.util.afterAttachTry

object MultiSimBar {
    private const val TAG = "OneUIX-MultiSim"
    private const val ITEMS = "com.android.systemui.samsung.quicksetting.domain.model.items"
    private const val GRID = "com.android.systemui.samsung.quicksetting.ui.base.spannablegrid"

    // IntSize packs width in the high 32 bits and height in the low 32 bits.
    private fun pack(w: Int, h: Int) = (w.toLong() shl 32) or (h.toLong() and 0xFFFFFFFFL)

    // Make the multi-SIM info bar resizable like the other quick panel components,
    // in both portrait and landscape. Width can reach the full grid column count.
    context(xposedModule: XposedModule)
    fun resizableBar() = afterAttachTry(ONE_UI_VERSION >= 80500) {
        Log.i(TAG, "resizableBar() start")
        val barClass = classLoader.loadClass("$ITEMS.MultiSimPreferredBar")
        val itemClass = classLoader.loadClass("$ITEMS.GridTileItem")

        // 1. allow the resize handles
        val isResize = itemClass.getDeclaredMethod("isResizeEnabled")
        xposedModule.hook(isResize).intercept { chain ->
            if (barClass.isInstance(chain.thisObject)) {
                Log.i(TAG, "isResizeEnabled -> true")
                true
            } else {
                chain.proceed()
            }
        }
        // The call sites may have inlined the method, so deoptimize them.
        runCatching { xposedModule.deoptimize(isResize) }
            .onFailure { Log.e(TAG, "deoptimize isResizeEnabled failed", it) }
        listOf(
            "$GRID.SpannableGridTileKt\$\$ExternalSyntheticLambda3",
            "$GRID.ResizeHandleKt\$\$ExternalSyntheticLambda3",
            "$GRID.ResizeHandleKt\$\$ExternalSyntheticLambda4",
        ).forEach { name ->
            runCatching {
                classLoader.loadClass(name).declaredMethods
                    .filter { it.name == "invoke" }
                    .forEach { xposedModule.deoptimize(it) }
                Log.i(TAG, "deoptimized $name")
            }.onFailure { Log.e(TAG, "deoptimize $name failed", it) }
        }

        // 2. min/max span size shown while dragging
        runCatching {
            val constraintsClass = classLoader.loadClass(
                "com.android.systemui.samsung.quicksetting.domain.model.SpanSizeConstraints"
            )
            val constraintsCtor = constraintsClass.getDeclaredConstructor(
                Long::class.javaPrimitiveType, Long::class.javaPrimitiveType
            ).apply { isAccessible = true }
            val resizeConstraints = itemClass.getDeclaredMethod(
                "resizeConstraints", Int::class.javaPrimitiveType
            )
            xposedModule.hook(resizeConstraints).intercept { chain ->
                if (barClass.isInstance(chain.thisObject)) {
                    val columns = chain.args[0] as Int
                    Log.i(TAG, "resizeConstraints columns=$columns")
                    constraintsCtor.newInstance(pack(minOf(3, columns), 1), pack(columns, 1))
                } else {
                    chain.proceed()
                }
            }
            Log.i(TAG, "hooked resizeConstraints")
        }.onFailure { Log.e(TAG, "hook resizeConstraints failed", it) }

        // 3. final size validation: width 3..(columns - start column), height fixed at 1
        runCatching {
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
                    Log.i(TAG, "validate columns=$columns width=$width start=$startColumn -> $w")
                    box.invoke(null, pack(w, 1))
                } else {
                    chain.proceed()
                }
            }
            Log.i(TAG, "hooked validateTileSpanSize")
        }.onFailure { Log.e(TAG, "hook validateTileSpanSize failed", it) }
    }
}
