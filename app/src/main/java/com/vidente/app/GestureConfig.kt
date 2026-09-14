package com.vidente.app

import android.accessibilityservice.AccessibilityService

/**
 * Configuración de gestos: única fuente de verdad, compartida entre el
 * servicio (que ejecuta las acciones) y Ajustes (que las muestra para
 * reasignarlas). Así las dos listas no pueden quedar desincronizadas.
 *
 * Los nombres de acción son texto y coinciden con los del enum GestureAction
 * del servicio: eso es lo que se guarda en preferencias (ver
 * VidentePreferences.getGestureActionMap), para que el archivo guardado siga
 * siendo legible y no dependa de números internos.
 */
object GestureConfig {

    const val ACTION_ACTIVATE = "ACTIVATE"
    const val ACTION_NEXT = "NEXT"
    const val ACTION_PREVIOUS = "PREVIOUS"
    const val ACTION_CYCLE_NAV_MODE = "CYCLE_NAV_MODE"
    const val ACTION_START_CONTINUOUS_READING = "START_CONTINUOUS_READING"
    const val ACTION_REPEAT_LAST_PHRASE = "REPEAT_LAST_PHRASE"
    const val ACTION_GO_HOME = "GO_HOME"
    const val ACTION_GO_BACK = "GO_BACK"
    const val ACTION_GO_RECENTS = "GO_RECENTS"
    const val ACTION_CURSOR_TO_FIELD_START = "CURSOR_TO_FIELD_START"
    const val ACTION_CURSOR_TO_FIELD_END = "CURSOR_TO_FIELD_END"

    /** Una acción que Vidente sabe ejecutar por un gesto, con su nombre visible. */
    data class ActionInfo(val name: String, val labelRes: Int)

    /** Un gesto de un dedo que Android sabe reconocer, con su nombre visible. */
    data class GestureInfo(val id: Int, val labelRes: Int)

    val ACTIONS = listOf(
        ActionInfo(ACTION_ACTIVATE, R.string.gesture_action_activate),
        ActionInfo(ACTION_NEXT, R.string.gesture_action_next),
        ActionInfo(ACTION_PREVIOUS, R.string.gesture_action_previous),
        ActionInfo(ACTION_CYCLE_NAV_MODE, R.string.gesture_action_cycle_nav_mode),
        ActionInfo(ACTION_START_CONTINUOUS_READING, R.string.gesture_action_start_continuous_reading),
        ActionInfo(ACTION_REPEAT_LAST_PHRASE, R.string.gesture_action_repeat_last_phrase),
        ActionInfo(ACTION_GO_HOME, R.string.gesture_action_go_home),
        ActionInfo(ACTION_GO_BACK, R.string.gesture_action_go_back),
        ActionInfo(ACTION_GO_RECENTS, R.string.gesture_action_go_recents),
        ActionInfo(ACTION_CURSOR_TO_FIELD_START, R.string.gesture_action_cursor_to_field_start),
        ActionInfo(ACTION_CURSOR_TO_FIELD_END, R.string.gesture_action_cursor_to_field_end)
    )

    /**
     * Gestos ofrecidos para reasignar. Es a propósito una lista más corta que
     * todos los gestos que Android define: se incluyen solo los de un dedo
     * que ya sabemos que el detector del sistema reconoce de forma confiable
     * en esta app. Quedan afuera los de "reversión en el mismo eje"
     * (izquierda-y-derecha, derecha-y-izquierda), que en las pruebas no se
     * reconocieron, y el doble toque y mantener, que Vidente deja pasar a
     * propósito para poder sostener una tecla del teclado.
     */
    val GESTURES = listOf(
        GestureInfo(AccessibilityService.GESTURE_DOUBLE_TAP, R.string.gesture_double_tap),
        GestureInfo(AccessibilityService.GESTURE_SWIPE_RIGHT, R.string.gesture_swipe_right),
        GestureInfo(AccessibilityService.GESTURE_SWIPE_LEFT, R.string.gesture_swipe_left),
        GestureInfo(AccessibilityService.GESTURE_SWIPE_UP, R.string.gesture_swipe_up),
        GestureInfo(AccessibilityService.GESTURE_SWIPE_DOWN, R.string.gesture_swipe_down),
        GestureInfo(AccessibilityService.GESTURE_SWIPE_DOWN_AND_UP, R.string.gesture_swipe_down_and_up),
        GestureInfo(AccessibilityService.GESTURE_SWIPE_UP_AND_DOWN, R.string.gesture_swipe_up_and_down),
        GestureInfo(AccessibilityService.GESTURE_SWIPE_DOWN_AND_LEFT, R.string.gesture_swipe_down_and_left),
        GestureInfo(AccessibilityService.GESTURE_SWIPE_DOWN_AND_RIGHT, R.string.gesture_swipe_down_and_right),
        GestureInfo(AccessibilityService.GESTURE_SWIPE_UP_AND_LEFT, R.string.gesture_swipe_up_and_left),
        GestureInfo(AccessibilityService.GESTURE_SWIPE_UP_AND_RIGHT, R.string.gesture_swipe_up_and_right)
    )

    /** El reparto de siempre: lo que Vidente usó desde antes de que esto fuera configurable. */
    val DEFAULT_MAP: Map<Int, String> = mapOf(
        AccessibilityService.GESTURE_DOUBLE_TAP to ACTION_ACTIVATE,
        AccessibilityService.GESTURE_SWIPE_RIGHT to ACTION_NEXT,
        AccessibilityService.GESTURE_SWIPE_LEFT to ACTION_PREVIOUS,
        AccessibilityService.GESTURE_SWIPE_DOWN to ACTION_CYCLE_NAV_MODE,
        AccessibilityService.GESTURE_SWIPE_DOWN_AND_UP to ACTION_START_CONTINUOUS_READING,
        AccessibilityService.GESTURE_SWIPE_UP_AND_DOWN to ACTION_REPEAT_LAST_PHRASE,
        AccessibilityService.GESTURE_SWIPE_UP to ACTION_GO_HOME,
        AccessibilityService.GESTURE_SWIPE_DOWN_AND_LEFT to ACTION_GO_BACK,
        AccessibilityService.GESTURE_SWIPE_DOWN_AND_RIGHT to ACTION_GO_RECENTS,
        AccessibilityService.GESTURE_SWIPE_UP_AND_LEFT to ACTION_CURSOR_TO_FIELD_START,
        AccessibilityService.GESTURE_SWIPE_UP_AND_RIGHT to ACTION_CURSOR_TO_FIELD_END
    )

    /** Gesto asignado hoy a una acción, o null si quedó sin asignar. */
    fun gestureForAction(map: Map<Int, String>, actionName: String): Int? =
        map.entries.firstOrNull { it.value == actionName }?.key

    /**
     * Asigna [gestureId] a [actionName]. Si ese gesto ya disparaba otra
     * acción, esa otra queda sin asignar: un mismo gesto no puede disparar
     * dos cosas a la vez. Con gestureId null, la acción queda sin asignar.
     */
    fun withAssignment(map: Map<Int, String>, actionName: String, gestureId: Int?): Map<Int, String> {
        val result = map.filterValues { it != actionName }.toMutableMap()
        if (gestureId != null) result[gestureId] = actionName
        return result
    }
}
