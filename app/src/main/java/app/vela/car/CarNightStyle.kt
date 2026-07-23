package app.vela.car

import org.json.JSONObject

/**
 * The REAL dark map for the car at night: [darken] applies the same Liberty recolouring the phone's
 * dark theme uses (`applyDark` in VelaMapView) - but as a STYLE-JSON transform, because the
 * MapSnapshotter loads a style once and exposes no live [org.maplibre.android.maps.Style] to
 * mutate. Every colour below is applyDark's, kept in lockstep by the comment there.
 *
 * Ladder in [CarMapRenderer]: night + style JSON in hand -> this transform (no tint filter);
 * night + no JSON yet (MapFonts cache cold) -> the old darkening colour-filter as fallback;
 * day -> untouched. A transform failure returns null and the caller falls back to the tint, so
 * the worst case is exactly yesterday's behaviour.
 */
object CarNightStyle {

    private const val LAND = "#242f3e"
    private const val WATER = "#17263c"

    private val MINOR_ROADS = setOf(
        "road_minor", "road_secondary_tertiary", "road_link", "road_service_track",
        "bridge_street", "bridge_secondary_tertiary", "bridge_link", "bridge_service_track",
    )
    private val PRIMARY_ROADS = setOf("road_trunk_primary", "bridge_trunk_primary")
    private val MOTORWAYS = setOf("road_motorway", "road_motorway_link", "bridge_motorway", "bridge_motorway_link")
    private val CASINGS = setOf(
        "road_motorway_casing", "road_motorway_link_casing", "road_trunk_primary_casing",
        "road_secondary_tertiary_casing", "road_minor_casing", "road_link_casing", "road_service_track_casing",
        "bridge_motorway_casing", "bridge_trunk_primary_casing", "bridge_secondary_tertiary_casing",
        "bridge_street_casing", "bridge_link_casing",
    )
    private val GREENS = setOf("park", "landcover_grass", "landcover_wood")

    /** Transform a Liberty style JSON into its night palette. Null on any parse failure. */
    fun darken(styleJson: String): String? = runCatching {
        val root = JSONObject(styleJson)
        val layers = root.optJSONArray("layers") ?: return null
        for (i in 0 until layers.length()) {
            val layer = layers.optJSONObject(i) ?: continue
            val id = layer.optString("id")
            val type = layer.optString("type")
            val paint = layer.optJSONObject("paint") ?: JSONObject().also { layer.put("paint", it) }
            when {
                id == "background" -> paint.put("background-color", LAND)
                id == "water" -> paint.put("fill-color", WATER)
                id == "waterway_river" -> paint.put("line-color", WATER)
                id == "park" -> { paint.put("fill-color", "#1c3326"); paint.put("fill-opacity", 0.7) }
                id == "landcover_grass" -> { paint.put("fill-color", "#1c3326"); paint.put("fill-opacity", 0.5) }
                id == "landcover_wood" -> { paint.put("fill-color", "#1a3023"); paint.put("fill-opacity", 0.6) }
                id in MINOR_ROADS -> paint.put("line-color", "#49536a")
                id in PRIMARY_ROADS -> paint.put("line-color", "#5e6a85")
                id in MOTORWAYS -> paint.put("line-color", "#6f7a96")
                id in CASINGS -> paint.put("line-color", LAND) // casings melt into the land, Google-style
                id == "building" -> {
                    paint.put("fill-color", "#323f54")
                    paint.put("fill-outline-color", "#3f4e66")
                    layer.put("minzoom", 14); layer.put("maxzoom", 24)
                }
                id == "building-3d" -> {
                    paint.put("fill-extrusion-color", "#323f54")
                    paint.put("fill-extrusion-opacity", 0.9)
                    layer.put("minzoom", 16)
                }
                id == "landcover_wetland" -> {
                    paint.put("fill-color", "#1c3326"); paint.remove("fill-pattern")
                }
                id == "road_area_pattern" -> {
                    paint.put("fill-color", "#2a3546"); paint.remove("fill-pattern")
                }
                type == "symbol" -> {
                    paint.put("text-color", "#c3cad6")
                    paint.put("text-halo-color", "#1a2230")
                    paint.put("text-halo-width", if (id.startsWith("highway-name")) 1.9 else 1.1)
                }
                type == "fill" && id !in GREENS &&
                    (id.startsWith("landuse") || id.startsWith("landcover")) -> {
                    paint.put("fill-color", "#2a3546"); paint.put("fill-opacity", 0.5)
                }
            }
        }
        root.toString()
    }.getOrNull()
}
