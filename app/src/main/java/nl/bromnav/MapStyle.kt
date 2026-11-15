package nl.bromnav

import java.io.File

/**
 * A light MapLibre style for the offline Protomaps (v4 schema) extract. Kept deliberately small
 * so a low-end phone GPU renders it smoothly. Route, stops and position layers are included with
 * empty GeoJSON sources that the activity fills in.
 */
object MapStyle {
    fun json(pmtiles: File): String = """
{
  "version": 8,
  "glyphs": "asset://fonts/{fontstack}/{range}.pbf",
  "sources": {
    "base": { "type": "vector", "url": "pmtiles://file://${pmtiles.absolutePath}",
              "attribution": "© OpenStreetMap contributors, Protomaps" },
    "route": { "type": "geojson", "data": { "type": "FeatureCollection", "features": [] } },
    "nogos": { "type": "geojson", "data": { "type": "FeatureCollection", "features": [] } },
    "stops": { "type": "geojson", "data": { "type": "FeatureCollection", "features": [] } },
    "me":    { "type": "geojson", "data": { "type": "FeatureCollection", "features": [] } }
  },
  "layers": [
    { "id": "bg", "type": "background", "paint": { "background-color": "#eceae4" } },
    { "id": "earth", "type": "fill", "source": "base", "source-layer": "earth", "paint": { "fill-color": "#f2f0ea" } },
    { "id": "landuse-green", "type": "fill", "source": "base", "source-layer": "landuse",
      "filter": ["in", ["get", "kind"], ["literal", ["park", "grass", "forest", "wood", "garden", "meadow", "farmland", "cemetery", "golf_course", "nature_reserve", "playground", "recreation_ground", "pitch"]]],
      "paint": { "fill-color": "#d9e8c8" } },
    { "id": "landuse-ind", "type": "fill", "source": "base", "source-layer": "landuse",
      "filter": ["in", ["get", "kind"], ["literal", ["industrial", "railway", "commercial"]]],
      "paint": { "fill-color": "#e6e1e6" } },
    { "id": "water", "type": "fill", "source": "base", "source-layer": "water", "filter": ["==", ["geometry-type"], "Polygon"],
      "paint": { "fill-color": "#a9cbe8" } },
    { "id": "water-line", "type": "line", "source": "base", "source-layer": "water", "filter": ["==", ["geometry-type"], "LineString"],
      "paint": { "line-color": "#a9cbe8", "line-width": 1.5 } },
    { "id": "buildings", "type": "fill", "source": "base", "source-layer": "buildings", "minzoom": 15,
      "paint": { "fill-color": "#dcd7cf", "fill-outline-color": "#cbc4ba" } },

    { "id": "paths", "type": "line", "source": "base", "source-layer": "roads", "minzoom": 13,
      "filter": ["==", ["get", "kind"], "path"],
      "paint": { "line-color": ["match", ["get", "kind_detail"], "cycleway", "#c8a2d6", "#bdb6ad"],
                 "line-width": ["interpolate", ["linear"], ["zoom"], 13, 0.6, 18, 3],
                 "line-dasharray": [3, 1.5] } },
    { "id": "roads-case", "type": "line", "source": "base", "source-layer": "roads",
      "filter": ["in", ["get", "kind"], ["literal", ["highway", "major_road", "medium_road", "minor_road", "other"]]],
      "layout": { "line-cap": "round", "line-join": "round" },
      "paint": { "line-color": "#c4bcb0",
                 "line-width": ["interpolate", ["exponential", 1.6], ["zoom"], 11, 1, 18,
                   ["match", ["get", "kind"], "highway", 22, "major_road", 18, "medium_road", 14, 10]] } },
    { "id": "roads", "type": "line", "source": "base", "source-layer": "roads",
      "filter": ["in", ["get", "kind"], ["literal", ["highway", "major_road", "medium_road", "minor_road", "other"]]],
      "layout": { "line-cap": "round", "line-join": "round" },
      "paint": { "line-color": ["match", ["get", "kind"], "highway", "#f0b37a", "major_road", "#fbe3a8", "#ffffff"],
                 "line-width": ["interpolate", ["exponential", 1.6], ["zoom"], 11, 0.5, 18,
                   ["match", ["get", "kind"], "highway", 19, "major_road", 15, "medium_road", 11.5, 8]] } },

    { "id": "route-case", "type": "line", "source": "route",
      "layout": { "line-cap": "round", "line-join": "round" },
      "paint": { "line-color": "#ffffff", "line-width": ["interpolate", ["linear"], ["zoom"], 12, 6, 18, 16] } },
    { "id": "route", "type": "line", "source": "route",
      "layout": { "line-cap": "round", "line-join": "round" },
      "paint": { "line-color": ["match", ["get", "cls"], "path", "#e8a000", "next", "#9aa4b1", "#1f6fe5"],
                 "line-width": ["interpolate", ["linear"], ["zoom"], 12, 3.5, 18, 11] } },

    { "id": "road-labels", "type": "symbol", "source": "base", "source-layer": "roads", "minzoom": 14,
      "filter": ["has", "name"],
      "layout": { "symbol-placement": "line", "text-field": ["get", "name"], "text-font": ["Noto Sans Regular"],
                  "text-size": 12 },
      "paint": { "text-color": "#555049", "text-halo-color": "#ffffff", "text-halo-width": 1.5 } },
    { "id": "places", "type": "symbol", "source": "base", "source-layer": "places",
      "filter": ["in", ["get", "kind"], ["literal", ["locality", "neighbourhood", "macrohood"]]],
      "layout": { "text-field": ["get", "name"], "text-font": ["Noto Sans Medium"],
                  "text-size": ["match", ["get", "kind"], "locality", 15, 12] },
      "paint": { "text-color": "#3e3a35", "text-halo-color": "#ffffff", "text-halo-width": 1.5 } },

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
