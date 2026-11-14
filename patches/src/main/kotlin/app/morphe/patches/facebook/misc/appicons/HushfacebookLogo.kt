/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.appicons

/**
 * Hushfacebook's logo, assets/icons/icon-1024.png, as vector paths for the Hushfacebook icon style
 * (#467). The white H stands on a navy copy of itself, shifted down and right, and a light blue copy
 * of its two bars, shifted up and left, on Hushfacebook blue. Its two slots are painted blue over
 * the copies. Measured off the 1024 px picture and scaled into the 108 by 108 viewport every
 * adaptive icon layer uses, inside the 66 dp circle no launcher mask cuts into.
 */
internal object HushfacebookLogo {
    const val BLUE = "#ff1371f1"
    const val NAVY = "#ff05255b"
    const val LIGHT_BLUE = "#ff3c92fc"
    const val WHITE = "#fff6f9fd"

    private const val NAVY_COPY =
        "M38.68,34.08L47.56,34.08A1.12,1.12 0 0 1 48.68,35.2L48.68,49.2L63.96,49.2L63.96,35.2A1.12,1.12 0 0 1 65.08,34.08L73.8,34.08A1.12,1.12 0 0 1 74.92,35.2L74.92,77.12A1.12,1.12 0 0 1 73.8,78.24L65.08,78.24A1.12,1.12 0 0 1 63.96,77.12L63.96,61.68L48.68,61.68L48.68,77.12A1.12,1.12 0 0 1 47.56,78.24L38.68,78.24A1.12,1.12 0 0 1 37.56,77.12L37.56,35.2A1.12,1.12 0 0 1 38.68,34.08Z"
    private const val LIGHT_COPY =
        "M34.2,29.76L43.08,29.76A1.12,1.12 0 0 1 44.2,30.88L44.2,72.8A1.12,1.12 0 0 1 43.08,73.92L34.2,73.92A1.12,1.12 0 0 1 33.08,72.8L33.08,30.88A1.12,1.12 0 0 1 34.2,29.76Z" +
        "M60.6,29.76L69.32,29.76A1.12,1.12 0 0 1 70.44,30.88L70.44,72.8A1.12,1.12 0 0 1 69.32,73.92L60.6,73.92A1.12,1.12 0 0 1 59.48,72.8L59.48,30.88A1.12,1.12 0 0 1 60.6,29.76Z"
    private const val H =
        "M36.44,31.84L45.32,31.84A1.12,1.12 0 0 1 46.44,32.96L46.44,46.96L61.72,46.96L61.72,32.96A1.12,1.12 0 0 1 62.84,31.84L71.56,31.84A1.12,1.12 0 0 1 72.68,32.96L72.68,74.88A1.12,1.12 0 0 1 71.56,76L62.84,76A1.12,1.12 0 0 1 61.72,74.88L61.72,59.44L46.44,59.44L46.44,74.88A1.12,1.12 0 0 1 45.32,76L36.44,76A1.12,1.12 0 0 1 35.32,74.88L35.32,32.96A1.12,1.12 0 0 1 36.44,31.84Z"
    private const val SLOTS =
        "M48.44,49.6L59.56,49.6A1.52,1.52 0 0 1 61.08,51.12L61.08,51.28A1.52,1.52 0 0 1 59.56,52.8L48.44,52.8A1.52,1.52 0 0 1 46.92,51.28L46.92,51.12A1.52,1.52 0 0 1 48.44,49.6Z" +
        "M48.44,53.84L59.56,53.84A1.52,1.52 0 0 1 61.08,55.36L61.08,55.6A1.52,1.52 0 0 1 59.56,57.12L48.44,57.12A1.52,1.52 0 0 1 46.92,55.6L46.92,55.36A1.52,1.52 0 0 1 48.44,53.84Z"

    /** The launcher icon's foreground, back to front, as fill color and path data. */
    val FOREGROUND = listOf(NAVY to NAVY_COPY, LIGHT_BLUE to LIGHT_COPY, WHITE to H, BLUE to SLOTS)

    /** The themed icon: the H alone, a little larger, its slots cut out by the even-odd fill. */
    const val MONOCHROME =
        "M35.34,30.54L44.78,30.54A1.19,1.19 0 0 1 45.97,31.73L45.97,46.6L62.2,46.6L62.2,31.73A1.19,1.19 0 0 1 63.39,30.54L72.66,30.54A1.19,1.19 0 0 1 73.85,31.73L73.85,76.27A1.19,1.19 0 0 1 72.66,77.46L63.39,77.46A1.19,1.19 0 0 1 62.2,76.27L62.2,59.87L45.97,59.87L45.97,76.27A1.19,1.19 0 0 1 44.78,77.46L35.34,77.46A1.19,1.19 0 0 1 34.15,76.27L34.15,31.73A1.19,1.19 0 0 1 35.34,30.54Z" +
        "M48.09,49.41L59.91,49.41A1.62,1.62 0 0 1 61.52,51.02L61.52,51.2A1.62,1.62 0 0 1 59.91,52.81L48.09,52.81A1.62,1.62 0 0 1 46.48,51.2L46.48,51.02A1.62,1.62 0 0 1 48.09,49.41Z" +
        "M48.09,53.91L59.91,53.91A1.62,1.62 0 0 1 61.52,55.53L61.52,55.78A1.62,1.62 0 0 1 59.91,57.4L48.09,57.4A1.62,1.62 0 0 1 46.48,55.78L46.48,55.53A1.62,1.62 0 0 1 48.09,53.91Z"
}
