package nl.bromnav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StopTest {
    @Test fun jsonRoundTrip() {
        val s = Stop(42, "Vismarkt 10, 9711KS Groningen", LatLon(53.2165, 6.5652), "ABCD12-008", 1_700_000_000_000,
            street = "Vismarkt", houseNo = "10", phone = "0612345678", note = "Bel 2x\n🇬🇧 Ring twice", name = "J. Jansen",
            earliest = 1_699_999_000_000, payment = "cash")
        assertEquals(s, Stop.fromJson(org.json.JSONObject(s.toJson().toString())))
        val plain = Stop(1, "Pin", LatLon(53.0, 6.0))
        val back = Stop.fromJson(plain.toJson())
        assertNull(back.deadline); assertNull(back.earliest); assertEquals(plain, back)
    }

    @Test fun addressParts() {
        val s = Stop(1, "Lange Voorbeeldstraat 33a, 9999ZZ Groningen", LatLon(53.0, 6.0))   // old data without PDOK fields
        assertEquals("Lange Voorbeeldstraat", s.streetName()); assertEquals("33a", s.number())
        val p = Stop(2, "Proefweg 8-2, 9999ZY Groningen", LatLon(53.0, 6.0), street = "Proefweg", houseNo = "8-2")
        assertEquals("Proefweg", p.streetName()); assertEquals("8-2", p.number())
        assertEquals("9999ZY 8-2", p.key())
    }

    @Test fun paymentIcon() {
        assertEquals("💶\u00A0CASH", Stop(1, "x", LatLon(0.0, 0.0), payment = "cash").paymentIcon())
        assertEquals("✅\u00A0paid", Stop(1, "x", LatLon(0.0, 0.0), payment = "online").paymentIcon())
        assertEquals("", Stop(1, "x", LatLon(0.0, 0.0)).paymentIcon())
    }
}
