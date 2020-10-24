package com.dongtronic.diabot.graph

import java.awt.Color
import kotlin.reflect.KClass

data class GraphSettings (
        val plotMode: PlottingStyle,
        val theme: GraphTheme = GraphTheme.DARK,
        val highColour: Color = Color(255, 140, 0),
        val inRangeColour: Color = Color(0, 203, 255),
        val lowColour: Color = Color(255, 0, 0)
) {

    enum class GraphTheme(val clazz: KClass<DiabotTheme>) {
        DARK(DiabotTheme::class), LIGHT(DiabotTheme::class)
    }
}