package io.github.soclear.oneuix.hook.systemui

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.soclear.oneuix.common.ONE_UI_VERSION
import io.github.soclear.oneuix.hook.util.afterAttachTry
import java.lang.reflect.Method

object MultiSimBar {
    private const val TAG = "OneUIX-MultiSim"
    private const val ITEMS = "com.android.systemui.samsung.quicksetting.domain.model.items"
    private const val GRID = "com.android.systemui.samsung.quicksetting.ui.base.spannablegrid"
    private const val VALIDATE = "validateTileSpanSize-2NWLvuI"

    // IntSize packs width in the high 32 bits and height in the low 32 bits.
    private fun pack(w: Int, h: Int) = (w.toLong() shl 32) or (h.toLong() and 0xFFFFFFFFL)

    // Make the multi-SIM info bar resizable like the other quick panel components,
    // in both portrait and landscape. Width can reach the full grid column count.
    // MultiSimPreferredBar inherits these as interface default methods. ART copies
    // default methods into the implementing class, so hooking only the interface
    // method is not enough: hook whatever the concrete class actually resolves to.
    context(xposedModule: XposedModule)
    fun resizableBar() = afterAttachTry(ONE_UI_VERSION >= 80500) {
        Log.i(TAG, "resizableBar() v3 start")
        val barClass = classLoader.loadClass("$ITEMS.MultiSimPreferredBar")
        val itemClass = classLoader.loadClass("$ITEMS.GridTileItem")

        val names = setOf("isResizeEnabled", "resizeConstraints", VALIDATE)
        val methods = LinkedHashSet<Method>()
        barClass.methods.filter { it.name in names }.forEach { methods.add(it) }
        itemClass.declaredMethods.filter { it.name in names }.forEach { methods.add(it) }
        Log.i(TAG, "bar declared: " + barClass.declaredMethods.joinToString { it.name })
        methods.forEach {
            Log.i(TAG, "target ${it.name} declaring=${it.declaringClass.name} mods=${it.modifiers}")
        }

        val constraintsCtor = runCatching {
            classLoader.loadClass(
                "com.android.systemui.samsung.quicksetting.domain.model.SpanSizeConstraints"
            ).getDeclaredConstructor(
                Long::class.javaPrimitiveType, Long::class.javaPrimitiveType
            ).apply { isAccessible = true }
        }.onFailure { Log.e(TAG, "constraints ctor failed", it) }.getOrNull()

        val box = runCatching {
            classLoader.loadClass("androidx.compose.ui.unit.IntSize")
                .getDeclaredMethod("box-impl", Long::class.javaPrimitiveType)
        }.onFailure { Log.e(TAG, "IntSize.box-impl failed", it) }.getOrNull()

        methods.forEach { m ->
            runCatching {
                xposedModule.hook(m).intercept { chain ->
                    if (!barClass.isInstance(chain.thisObject)) {
                        chain.proceed()
                    } else when (m.name) {
                        "isResizeEnabled" -> {
                            Log.i(TAG, "isResizeEnabled -> true (via ${m.declaringClass.simpleName})")
                            true
                        }
                        "resizeConstraints" -> {
                            val columns = chain.args[0] as Int
                            Log.i(TAG, "resizeConstraints columns=$columns")
                            constraintsCtor!!.newInstance(
                                pack(minOf(3, columns), 1), pack(columns, 1)
                            )
                        }
                        else -> {
                            val columns = chain.args[0] as Int
                            val width = ((chain.args[1] as Long) shr 32).toInt()
                            val startColumn = ((chain.args[2] as Long) shr 32).toInt()
                            val w = width.coerceAtLeast(3).coerceAtMost(maxOf(columns - startColumn, 1))
                            Log.i(TAG, "validate columns=$columns width=$width start=$startColumn -> $w")
                            box!!.invoke(null, pack(w, 1))
                        }
                    }
                }
                Log.i(TAG, "hooked ${m.name} on ${m.declaringClass.simpleName}")
            }.onFailure { Log.e(TAG, "hook ${m.name} failed", it) }
        }

        // The call sites may have inlined these, so deoptimize them as well.
        listOf(
            "$GRID.SpannableGridTileKt\$\$ExternalSyntheticLambda3",
            "$GRID.ResizeHandleKt\$\$ExternalSyntheticLambda3",
            "$GRID.ResizeHandleKt\$\$ExternalSyntheticLambda4",
        ).forEach { name ->
            runCatching {
                classLoader.loadClass(name).declaredMethods
                    .filter { it.name == "invoke" }
                    .forEach { xposedModule.deoptimize(it) }
            }.onFailure { Log.e(TAG, "deoptimize $name failed", it) }
        }
    }
}
