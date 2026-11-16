package nl.bromnav

/**
 * Values that depend on where you work come from [Region] (chosen city + your restaurant, set up on
 * the phone); the rest are fixed.
 */
object Config {
    /** your restaurant: start and end of every run (the city centre until it is set up) */
    val RESTAURANT: LatLon get() = Region.restaurantPos ?: Region.city?.center ?: LatLon(52.37, 4.89)
    val RESTAURANT_NAME: String get() = Region.restaurantName.ifEmpty { "the restaurant" }
    /** the restaurant's address as printed in every receipt header (never a customer address); "" = unknown */
    val RESTAURANT_POSTCODE: String get() = Region.restaurantPostcode
    val RESTAURANT_HOUSENO: String get() = Region.restaurantHouseNo
    val RESTAURANT_STREET: String get() = Region.restaurantStreet
    val RESTAURANT_PHONE_DIGITS: String get() = Region.restaurantPhoneDigits
    val RESTAURANT_WEB: String get() = Region.restaurantWeb

    /** Address search is restricted to this municipality first (PDOK gemeentenaam); "" = all of NL. */
    val MUNICIPALITY: String get() = Region.city?.municipality ?: ""

    /** Handover time at each door, used in ETA/urgency. */
    const val SERVICE_S = 60.0

    /** Exact-time orders: arriving up to this many minutes after the requested time is still on time. */
    const val SCHEDULED_GRACE_MIN = 10
}
