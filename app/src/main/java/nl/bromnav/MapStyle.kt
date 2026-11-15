package nl.bromnav

import java.io.File

/**
 * A light MapLibre style for the offline Protomaps (v4 schema) extract. Kept deliberately small
 * so a low-end phone GPU renders it smoothly. Route, stops and position layers are included with
 * empty GeoJSON sources that the activity fills in.
 */
object MapStyle {
    private val LIGHT = mapOf(
        "bg" to "#eceae4", "earth" to "#f2f0ea", "green" to "#d9e8c8", "ind" to "#e6e1e6", "water" to "#a9cbe8",
        "bld" to "#dcd7cf", "bldline" to "#cbc4ba", "path" to "#bdb6ad", "cycle" to "#c8a2d6", "case" to "#c4bcb0",
        "hwy" to "#f0b37a", "major" to "#fbe3a8", "minor" to "#ffffff", "label" to "#555049", "place" to "#3e3a35",
        "halo" to "#ffffff", "routecase" to "#ffffff")
    private val DARK = mapOf(
        "bg" to "#15181c", "earth" to "#1b1f24", "green" to "#1f2b24", "ind" to "#24232a", "water" to "#173248",
        "bld" to "#2a2e34", "bldline" to "#353a41", "path" to "#4a4f57", "cycle" to "#6d5578", "case" to "#0e1013",
        "hwy" to "#8a6236", "major" to "#6b5f3e", "minor" to "#3a4048", "label" to "#b8bec7", "place" to "#d5dae1",
        "halo" to "#15181c", "routecase" to "#0b0d10")

    fun json(pmtiles: File, dark: Boolean = false): String {
        val c = if (dark) DARK else LIGHT
        return template(pmtiles).replace(Regex("""\{\{(\w+)\}\}""")) { c[it.groupValues[1]]!! }
    }

    private fun template(pmtiles: File): String = """
{
  "version": 8,
  "glyphs": "asset://fonts/{fontstack}/{range}.pbf",
  "sources": {
    "base": { "type": "vector", "url": "pmtiles://file://${pmtiles.absolutePath}",
              "attribution": "© OpenStreetMap contributors, Protomaps" },
    "route": { "type": "geojson", "data": { "type": "FeatureCollection", "features": [] } },
    "legal": { "type": "geojson", "data": { "type": "FeatureCollection", "features": [] } },
    "overrides": { "type": "geojson", "data": { "type": "FeatureCollection", "features": [] } },
    "selroad": { "type": "geojson", "data": { "type": "FeatureCollection", "features": [] } },
    "nogos": { "type": "geojson", "data": { "type": "FeatureCollection", "features": [] } },
    "stops": { "type": "geojson", "data": { "type": "FeatureCollection", "features": [] } },
    "me":    { "type": "geojson", "data": { "type": "FeatureCollection", "features": [] } }
  },
  "layers": [
    { "id": "bg", "type": "background", "paint": { "background-color": "{{bg}}" } },
    { "id": "earth", "type": "fill", "source": "base", "source-layer": "earth", "paint": { "fill-color": "{{earth}}" } },
    { "id": "landuse-green", "type": "fill", "source": "base", "source-layer": "landuse",
      "filter": ["in", ["get", "kind"], ["literal", ["park", "grass", "forest", "wood", "garden", "meadow", "farmland", "cemetery", "golf_course", "nature_reserve", "playground", "recreation_ground", "pitch"]]],
      "paint": { "fill-color": "{{green}}" } },
    { "id": "landuse-ind", "type": "fill", "source": "base", "source-layer": "landuse",
      "filter": ["in", ["get", "kind"], ["literal", ["industrial", "railway", "commercial"]]],
      "paint": { "fill-color": "{{ind}}" } },
    { "id": "water", "type": "fill", "source": "base", "source-layer": "water", "filter": ["==", ["geometry-type"], "Polygon"],
      "paint": { "fill-color": "{{water}}" } },
    { "id": "water-line", "type": "line", "source": "base", "source-layer": "water", "filter": ["==", ["geometry-type"], "LineString"],
      "paint": { "line-color": "{{water}}", "line-width": 1.5 } },
    { "id": "buildings", "type": "fill", "source": "base", "source-layer": "buildings", "minzoom": 15,
      "paint": { "fill-color": "{{bld}}", "fill-outline-color": "{{bldline}}" } },

    { "id": "paths", "type": "line", "source": "base", "source-layer": "roads", "minzoom": 13,
      "filter": ["==", ["get", "kind"], "path"],
      "paint": { "line-color": ["match", ["get", "kind_detail"], "cycleway", "{{cycle}}", "{{path}}"],
                 "line-width": ["interpolate", ["linear"], ["zoom"], 13, 0.6, 18, 3],
                 "line-dasharray": [3, 1.5] } },
    { "id": "roads-case", "type": "line", "source": "base", "source-layer": "roads",
      "filter": ["in", ["get", "kind"], ["literal", ["highway", "major_road", "medium_road", "minor_road", "other"]]],
      "layout": { "line-cap": "round", "line-join": "round" },
      "paint": { "line-color": "{{case}}",
                 "line-width": ["interpolate", ["exponential", 1.6], ["zoom"], 11, 1, 18,
                   ["match", ["get", "kind"], "highway", 22, "major_road", 18, "medium_road", 14, 10]] } },
    { "id": "roads", "type": "line", "source": "base", "source-layer": "roads",
      "filter": ["in", ["get", "kind"], ["literal", ["highway", "major_road", "medium_road", "minor_road", "other"]]],
      "layout": { "line-cap": "round", "line-join": "round" },
      "paint": { "line-color": ["match", ["get", "kind"], "highway", "{{hwy}}", "major_road", "{{major}}", "{{minor}}"],
                 "line-width": ["interpolate", ["exponential", 1.6], ["zoom"], 11, 0.5, 18,
                   ["match", ["get", "kind"], "highway", 19, "major_road", 15, "medium_road", 11.5, 8]] } },

    { "id": "legal", "type": "line", "source": "legal",
      "layout": { "line-cap": "round", "line-join": "round" },
      "paint": { "line-color": ["match", ["get", "c"], 0, "#e53935", 2, "#f2a900", 3, "#1e88e5", 4, "#8e24aa", "#2e9d4f"],
                 "line-opacity": 0.85,
                 "line-width": ["interpolate", ["linear"], ["zoom"], 13, ["match", ["get", "c"], 0, 1.2, 2.2], 18, ["match", ["get", "c"], 0, 4, 7]] } },
    { "id": "overrides", "type": "line", "source": "overrides",
      "layout": { "line-cap": "round" },
      "paint": { "line-color": ["match", ["get", "c"], 4, "#8e24aa", "#1e88e5"],
                 "line-width": ["interpolate", ["linear"], ["zoom"], 12, 2.5, 18, 8], "line-dasharray": [1.5, 1] } },
    { "id": "selroad", "type": "line", "source": "selroad",
      "layout": { "line-cap": "round" },
      "paint": { "line-color": "#00e5ff", "line-width": ["interpolate", ["linear"], ["zoom"], 12, 5, 18, 14], "line-opacity": 0.7 } },
    { "id": "route-case", "type": "line", "source": "route",
      "layout": { "line-cap": "round", "line-join": "round" },
      "paint": { "line-color": "{{routecase}}", "line-width": ["interpolate", ["linear"], ["zoom"], 12, 6, 18, 16] } },
    { "id": "route", "type": "line", "source": "route",
      "layout": { "line-cap": "round", "line-join": "round" },
      "paint": { "line-color": ["match", ["get", "cls"], "path", "#e8a000", "next", "#9aa4b1", "#1f6fe5"],
                 "line-width": ["interpolate", ["linear"], ["zoom"], 12, 3.5, 18, 11] } },

    { "id": "road-labels", "type": "symbol", "source": "base", "source-layer": "roads", "minzoom": 14,
      "filter": ["has", "name"],
      "layout": { "symbol-placement": "line", "text-field": ["get", "name"], "text-font": ["Noto Sans Regular"],
                  "text-size": 12 },
      "paint": { "text-color": "{{label}}", "text-halo-color": "{{halo}}", "text-halo-width": 1.5 } },
    { "id": "places", "type": "symbol", "source": "base", "source-layer": "places",
      "filter": ["in", ["get", "kind"], ["literal", ["locality", "neighbourhood", "macrohood"]]],
      "layout": { "text-field": ["get", "name"], "text-font": ["Noto Sans Medium"],
                  "text-size": ["match", ["get", "kind"], "locality", 15, 12] },
      "paint": { "text-color": "{{place}}", "text-halo-color": "{{halo}}", "text-halo-width": 1.5 } },

    { "id": "nogos", "type": "circle", "source": "nogos",
      "paint": { "circle-radius": 9, "circle-color": "#d93025", "circle-opacity": 0.35,
                 "circle-stroke-color": "#d93025", "circle-stroke-width": 2 } },
    { "id": "stops", "type": "circle", "source": "stops",
      "paint": { "circle-radius": 13, "circle-color": ["coalesce", ["get", "color"], "#1f6fe5"],
                 "circle-stroke-color": "#ffffff", "circle-stroke-width": 2.5 } },
    { "id": "stops-label", "type": "symbol", "source": "stops",
      "layout": { "text-field": ["get", "n"], "text-font": ["Noto Sans Medium"], "text-size": 14,
                  "text-allow-overlap": true, "text-ignore-placement": true },
      "paint": { "text-color": "#ffffff" } },
    { "id": "me-halo", "type": "circle", "source": "me",
      "paint": { "circle-radius": 26, "circle-color": "#1f6fe5", "circle-opacity": 0.15 } },
    { "id": "me", "type": "symbol", "source": "me",
      "layout": { "icon-image": "scooter", "icon-size": 0.55, "icon-rotate": ["get", "bearing"],
                  "icon-rotation-alignment": "map", "icon-pitch-alignment": "map",
                  "icon-allow-overlap": true, "icon-ignore-placement": true } }
  ]
}
"""
}
