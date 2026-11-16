package nl.bromnav

/** The region the tests were written for: Groningen, restaurant Wabi-Sabi (Schuitendiep 60). */
object TestRegion {
    fun wabiSabi() = Region.setForTest(Cities.byName("Groningen"), "Wabi-Sabi", LatLon(53.2182246, 6.5734317),
        "9711RE", "60", "Schuitendiep", "2043069", "wabisabi")
}
