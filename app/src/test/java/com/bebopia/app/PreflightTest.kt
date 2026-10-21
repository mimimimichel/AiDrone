package com.bebopia.app

import com.bebopia.app.core.ai.Preflight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreflightTest {
    private val zonesAirport = """{"type":"FeatureCollection","features":[
      {"properties":{"limite":"Vol interdit *","remarque":"Altitude de référence de l'aérodrome : 152 m"}},
      {"properties":{"limite":"Hauteur maximale de vol de 30 m *","remarque":"Altitude de référence de l'aérodrome : 152 m"}}]}"""
    private val zones50 = """{"features":[{"properties":{"limite":"Hauteur maximale de vol de 50 m *","remarque":""}}]}"""
    private val calm = """{"current":{"temperature_2m":18.2,"precipitation":0.0,"weather_code":1,"wind_speed_10m":2.1,"wind_gusts_10m":4.0}}"""
    private val windy = """{"current":{"temperature_2m":12.0,"precipitation":0.0,"weather_code":2,"wind_speed_10m":6.5,"wind_gusts_10m":11.0}}"""
    private val rain = """{"current":{"temperature_2m":10.0,"precipitation":1.2,"weather_code":61,"wind_speed_10m":3.0,"wind_gusts_10m":5.0}}"""

    private fun p(z: String, w: String) = Preflight { url -> if (url.contains("geopf")) z else w }

    @Test fun forbiddenNearAirport() {
        val r = p(zonesAirport, calm).check(43.63, 1.36, "Mavic Mini", 8.0)
        println(r.text)
        assertEquals("non", r.verdict)
        assertEquals(30, r.maxHeightM)
    }

    @Test fun heightLimitedZoneIsCareful() {
        val r = p(zones50, calm).check(43.6, 1.44, "Mavic Mini", 8.0)
        println(r.text)
        assertEquals("prudence", r.verdict)
        assertEquals(50, r.maxHeightM)
    }

    @Test fun freeZoneCalmWeather() {
        val r = p("""{"features":[]}""", calm).check(45.0, 2.0, "Mavic Mini", 8.0)
        println(r.text)
        assertEquals("ok", r.verdict)
    }

    @Test fun tooMuchWindOrRain() {
        val w = p("""{"features":[]}""", windy).check(45.0, 2.0, "Mavic Mini", 8.0)
        println(w.text)
        assertEquals("non", w.verdict)
        assertTrue(w.text.contains("40 km/h"))
        assertEquals("non", p("""{"features":[]}""", rain).check(45.0, 2.0, "Mavic Mini", 8.0).verdict)
    }

    @Test fun noNetworkIsCareful() {
        val r = Preflight { throw java.io.IOException("hors ligne") }.check(45.0, 2.0, "Mavic Mini", 8.0)
        println(r.text)
        assertEquals("prudence", r.verdict)
    }
}
