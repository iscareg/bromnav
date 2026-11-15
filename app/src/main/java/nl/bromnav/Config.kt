package nl.bromnav

object Config {
    /** Wabi-Sabi, Schuitendiep, Groningen — start and end of every run (hard-coded for now). */
    val RESTAURANT = LatLon(53.2182246, 6.5734317)
    const val RESTAURANT_NAME = "Wabi-Sabi"

    /** Address search is restricted to this municipality first (PDOK gemeentenaam). */
    const val MUNICIPALITY = "Groningen"

    /** Handover time at each door, used in ETA/urgency. */
    const val SERVICE_S = 60.0

    /** Exact-time orders: arriving up to this many minutes after the requested time is still on time. */
    const val SCHEDULED_GRACE_MIN = 10

    /** Urgency thresholds (minutes of slack). */
    const val SLACK_GREEN_MIN = 10
}
