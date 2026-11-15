package nl.bromnav

object Config {
    /** Wabi-Sabi, Schuitendiep, Groningen — start and end of every run (hard-coded for now). */
    val RESTAURANT = LatLon(53.2182246, 6.5734317)
    const val RESTAURANT_NAME = "Wabi-Sabi"
    /** the restaurant's address as printed in every receipt header (never a customer address) */
    const val RESTAURANT_POSTCODE = "9711RE"
    const val RESTAURANT_HOUSENO = "60"
    const val RESTAURANT_STREET = "Schuitendiep"
    const val RESTAURANT_PHONE_DIGITS = "2043069"
    const val RESTAURANT_WEB = "wabisabi"

    /** Address search is restricted to this municipality first (PDOK gemeentenaam). */
    const val MUNICIPALITY = "Groningen"

    /** Handover time at each door, used in ETA/urgency. */
    const val SERVICE_S = 60.0

    /** Exact-time orders: arriving up to this many minutes after the requested time is still on time. */
    const val SCHEDULED_GRACE_MIN = 10
}
