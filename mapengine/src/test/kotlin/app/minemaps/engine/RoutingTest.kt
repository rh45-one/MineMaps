package app.minemaps.engine

import app.minemaps.engine.geo.LatLng
import app.minemaps.engine.routing.PolylineCodec
import app.minemaps.engine.routing.RouteParsers
import app.minemaps.engine.routing.RoutePlan
import app.minemaps.engine.routing.RouteStep
import app.minemaps.engine.routing.RouteTracker
import app.minemaps.engine.routing.TrafficSpeed
import app.minemaps.engine.routing.TurnDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutingTest {
    @Test
    fun polylineDecodesStandardGoogleSample() {
        // Classic Google polyline example: (38.5, -120.2), (40.7, -120.95), (43.252, -126.453)
        val pts = PolylineCodec.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, pts.size)
        assertEquals(38.5, pts[0].lat, 1e-4)
        assertEquals(-120.2, pts[0].lng, 1e-4)
        assertEquals(40.7, pts[1].lat, 1e-4)
        assertEquals(-120.95, pts[1].lng, 1e-4)
        assertEquals(43.252, pts[2].lat, 1e-4)
        assertEquals(-126.453, pts[2].lng, 1e-4)

        // Malformed / truncated input must not crash
        assertTrue(PolylineCodec.decode("").isEmpty())
        PolylineCodec.decode("@@@@\u0000\u00ff")
    }

    @Test
    fun parsesOsrmDrivingResponse() {
        val sample = """
            {
              "code": "Ok",
              "routes": [
                {
                  "geometry": "_p~iF~ps|U_ulLnnqC_mqNvxq`@",
                  "distance": 1450.5,
                  "duration": 210.0,
                  "legs": [
                    {
                      "steps": [
                        {
                          "distance": 450.0,
                          "duration": 60.0,
                          "name": "Via dei Fori Imperiali",
                          "maneuver": { "type": "depart", "location": [-120.2, 38.5] }
                        },
                        {
                          "distance": 1000.5,
                          "duration": 150.0,
                          "name": "Piazza del Colosseo",
                          "maneuver": { "type": "turn", "modifier": "right", "location": [-120.95, 40.7] }
                        },
                        {
                          "distance": 0.0,
                          "duration": 0.0,
                          "name": "",
                          "maneuver": { "type": "arrive", "location": [-126.453, 43.252] }
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        val plan = RouteParsers.parseOsrm(sample)
        assertNotNull(plan)
        assertEquals("OSRM", plan.providerLabel)
        assertEquals(3, plan.points.size)
        assertEquals(1450.5, plan.distanceMeters, 1e-3)
        assertEquals(210.0, plan.durationSeconds, 1e-3)
        assertEquals(3, plan.steps.size)
        assertEquals(TurnDirection.DEPART, plan.steps[0].direction)
        assertEquals(TurnDirection.RIGHT, plan.steps[1].direction)
        assertEquals("Turn right onto Piazza del Colosseo", plan.steps[1].instruction)
        assertEquals(TurnDirection.ARRIVE, plan.steps[2].direction)

        assertNull(RouteParsers.parseOsrm("not json"))
        assertNull(RouteParsers.parseOsrm("""{"routes":[]}"""))
    }

    @Test
    fun parsesGoogleRoutesV2WithTrafficIntervals() {
        val sample = """
            {
              "routes": [
                {
                  "distanceMeters": 3200,
                  "duration": "480s",
                  "polyline": { "encodedPolyline": "_p~iF~ps|U_ulLnnqC_mqNvxq`@" },
                  "travelAdvisory": {
                    "speedReadingIntervals": [
                      { "startPolylinePointIndex": 0, "endPolylinePointIndex": 1, "speed": "NORMAL" },
                      { "startPolylinePointIndex": 1, "endPolylinePointIndex": 2, "speed": "TRAFFIC_JAM" }
                    ]
                  },
                  "legs": [
                    {
                      "steps": [
                        {
                          "distanceMeters": 1200,
                          "staticDuration": "180s",
                          "startLocation": { "latLng": { "latitude": 38.5, "longitude": -120.2 } },
                          "navigationInstruction": { "maneuver": "TURN_LEFT", "instructions": "Turn left onto Main St" }
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        val plan = RouteParsers.parseGoogleRoutes(sample)
        assertNotNull(plan)
        assertEquals("Google Maps", plan.providerLabel)
        assertEquals(3200.0, plan.distanceMeters, 1e-3)
        assertEquals(480.0, plan.durationSeconds, 1e-3)
        assertEquals(2, plan.trafficIntervals.size)
        assertEquals(TrafficSpeed.NORMAL, plan.trafficIntervals[0].speed)
        assertEquals(TrafficSpeed.TRAFFIC_JAM, plan.trafficIntervals[1].speed)
        assertEquals(1, plan.steps.size)
        assertEquals(TurnDirection.LEFT, plan.steps[0].direction)
        assertEquals("Turn left onto Main St", plan.steps[0].instruction)
    }

    @Test
    fun routeTrackerComputesProgressAndArrival() {
        val p0 = LatLng(41.8900, 12.4900)
        val p1 = LatLng(41.8900, 12.4950)
        val p2 = LatLng(41.8950, 12.4950)
        val steps = listOf(
            RouteStep("Head east", "Via A", 400.0, 40.0, TurnDirection.DEPART, p0),
            RouteStep("Turn left onto Via B", "Via B", 550.0, 60.0, TurnDirection.LEFT, p1),
            RouteStep("Arrive at destination", "", 0.0, 0.0, TurnDirection.ARRIVE, p2),
        )
        val plan = RoutePlan(listOf(p0, p1, p2), steps, 950.0, 100.0)

        // Near start: next maneuver is the left turn at p1
        val startProg = RouteTracker.evaluate(plan, 41.8900, 12.4910)
        assertEquals(false, startProg.arrived)
        assertEquals(TurnDirection.LEFT, startProg.currentStep?.direction)
        assertTrue(startProg.offRouteMeters < 10.0)
        assertTrue(startProg.remainingDistanceMeters in 600.0..950.0)

        // Off route: 300 m south of the first segment
        val offProg = RouteTracker.evaluate(plan, 41.8860, 12.4920)
        assertTrue(offProg.offRouteMeters > RouteTracker.OFF_ROUTE_THRESHOLD_M)

        // At destination: arrived = true
        val endProg = RouteTracker.evaluate(plan, 41.8950, 12.4950)
        assertEquals(true, endProg.arrived)
        assertEquals(0.0, endProg.remainingDistanceMeters, 1e-3)
    }

    @Test
    fun parsesOverpassRoadAlertsAndComputesDynamicZoomAndRecenterCycle() {
        val sample = """
            {
              "elements": [
                {
                  "type": "node",
                  "id": 101,
                  "lat": 41.8902,
                  "lon": 12.4922,
                  "tags": { "highway": "speed_camera", "maxspeed": "50" }
                },
                {
                  "type": "way",
                  "id": 202,
                  "center": { "lat": 41.8910, "lon": 12.4950 },
                  "tags": { "highway": "construction" }
                }
              ]
            }
        """.trimIndent()
        val alerts = app.minemaps.engine.routing.RoadAlertsParser.parseOverpass(sample)
        assertEquals(2, alerts.size)
        assertEquals(app.minemaps.engine.routing.RoadAlertKind.SPEED_CAMERA, alerts[0].kind)
        assertEquals("Limit: 50 km/h", alerts[0].detail)
        assertEquals(app.minemaps.engine.routing.RoadAlertKind.HAZARD, alerts[1].kind)

        val nominatimSample = """
            [
              {"osm_id": 992007215, "lat": "40.4166836", "lon": "-3.6592351", "category": "highway", "type": "speed_camera", "extratags": {"maxspeed": "90", "description": "M-30 Km 7.8"}},
              {"osm_id": 441126464, "lat": "40.3534995", "lon": "-3.8524511", "category": "highway", "type": "construction", "name": "A-5 Obras"}
            ]
        """.trimIndent()
        val nomCams = app.minemaps.engine.routing.RoadAlertsParser.parseNominatim(nominatimSample, app.minemaps.engine.routing.RoadAlertKind.SPEED_CAMERA)
        assertEquals(2, nomCams.size)
        assertEquals(app.minemaps.engine.routing.RoadAlertKind.SPEED_CAMERA, nomCams[0].kind)
        assertEquals("Limit: 90 km/h \u00b7 M-30 Km 7.8", nomCams[0].detail)
        assertEquals(app.minemaps.engine.routing.RoadAlertKind.HAZARD, nomCams[1].kind)
        assertEquals("A-5 Obras", nomCams[1].detail)

        val postPassSample = """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "type": "Feature",
                  "properties": {
                    "osm_id": 992006682,
                    "tags": { "note": "F120 JAAM A-5 Km 22.99", "highway": "speed_camera", "maxspeed": "120" }
                  },
                  "geometry": { "type": "Point", "coordinates": [-3.9141802, 40.3038166] }
                },
                {
                  "type": "Feature",
                  "properties": {
                    "osm_id": 777001,
                    "tags": { "enforcement": "average_speed", "maxspeed": "90" }
                  },
                  "geometry": { "type": "Point", "coordinates": [-3.80, 40.40] }
                }
              ]
            }
        """.trimIndent()
        val ppAlerts = app.minemaps.engine.routing.RoadAlertsParser.parsePostPass(postPassSample)
        assertEquals(2, ppAlerts.size)
        assertEquals("Speed Camera / Radar", ppAlerts[0].label)
        assertEquals("Limit: 120 km/h \u00b7 F120 JAAM A-5 Km 22.99", ppAlerts[0].detail)
        assertEquals("Section Speed Radar", ppAlerts[1].label)

        // Recenter zoom cycling
        assertEquals(16.5, RouteTracker.nextRecenterZoom(14.5), 1e-3)
        assertEquals(18.5, RouteTracker.nextRecenterZoom(16.5), 1e-3)
        assertEquals(14.5, RouteTracker.nextRecenterZoom(18.5), 1e-3)

        // Dynamic driving zoom
        assertNull(RouteTracker.targetDrivingZoom(0.5f, "minor"))
        val highwayZ = RouteTracker.targetDrivingZoom(30f, "motorway")!!
        val streetZ = RouteTracker.targetDrivingZoom(6f, "minor")!!
        assertTrue(highwayZ < streetZ, "highway zoom ($highwayZ) should be more zoomed-out than city street ($streetZ)")
    }
}
