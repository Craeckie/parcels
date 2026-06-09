// SPDX-License-Identifier: GPL-3.0-or-later
package dev.itsvic.parceltracker.api

import com.squareup.moshi.JsonClass
import dev.itsvic.parceltracker.R
import java.io.IOException
import java.time.ZoneId
import java.time.ZonedDateTime
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query

// Tracks parcels through the public dhl.de tracking data endpoint (int-verfolgen/data/search).
// Unlike DhlDeliveryService (api-eu.dhl.com), this requires no API key.
object DhlDeDeliveryService : DeliveryService {
  override val nameResource: Int = R.string.service_dhl_de
  override val acceptsPostCode: Boolean = true
  override val requiresPostCode: Boolean = false

  // The status strings are localized by the `language` query param. We force English so the
  // text-narrowing in mapStatus() below stays stable.
  private const val API_LANGUAGE = "en"

  private val digits20Format = """^\d{20}$""".toRegex()

  override fun acceptsFormat(trackingId: String): Boolean {
    return digits20Format.accepts(trackingId)
  }

  override fun trackingUrl(trackingId: String) =
      "https://www.dhl.de/en/privatkunden/pakete-empfangen/verfolgen.html?piececode=$trackingId"

  override suspend fun getParcel(trackingId: String, postalCode: String?): Parcel {
    val resp =
        try {
          service.search(trackingId, API_LANGUAGE, postalCode?.ifBlank { null })
        } catch (e: HttpException) {
          if (e.code() == 404) throw ParcelNonExistentException() else throw IOException()
        }

    // rateLimited can be true even on an HTTP 200. The parcel likely exists; signal a
    // transient failure rather than telling the user it's gone.
    if (resp.rateLimited == true) throw IOException("DHL rate limited the request")

    // No `sendungen` (just a redirectUrl) means the tracking number is unknown.
    val sendung = resp.sendungen?.firstOrNull() ?: throw ParcelNonExistentException()
    val details = sendung.sendungsdetails
    val verlauf = details.sendungsverlauf

    // Events are oldest-first in the response; the app shows newest-first.
    val history =
        verlauf.events.reversed().map {
          ParcelHistoryItem(
              it.status,
              ZonedDateTime.parse(it.datum)
                  .withZoneSameInstant(ZoneId.systemDefault())
                  .toLocalDateTime(),
              // The endpoint carries no per-event location data.
              "")
        }

    return Parcel(sendung.id, history, mapStatus(verlauf, details))
  }

  // Only `istZugestellt` / `fortschritt == max` (delivered) is confirmed from real data.
  // The text matches and the progress split are best-effort and easy to extend as more
  // samples surface. We deliberately default to InTransit (not Unknown): a parcel that
  // exists and isn't delivered is almost always in transit.
  private fun mapStatus(v: Sendungsverlauf, d: Sendungsdetails): Status {
    if (d.istZugestellt == true) return Status.Delivered
    if (d.ruecksendung == true || v.events.lastOrNull()?.ruecksendung == true)
        return Status.ReturningToSender

    val text = v.status?.lowercase().orEmpty()
    when {
      text.contains("out for delivery") || text.contains("loaded onto the delivery vehicle") ->
          return Status.OutForDelivery
      text.contains("not be delivered") || text.contains("delivery was not possible") ->
          return Status.DeliveryFailure
      text.contains("ready for collection") || text.contains("retail outlet") ->
          return Status.AwaitingPickup
      text.contains("instruction data") || text.contains("electronically") ->
          return Status.Preadvice
    }

    return when {
      (v.fortschritt ?: 0) <= 1 -> Status.Preadvice
      else -> Status.InTransit
    }
  }

  private val retrofit =
      Retrofit.Builder()
          .baseUrl("https://www.dhl.de/")
          .client(api_client)
          .addConverterFactory(api_factory)
          .build()

  private val service = retrofit.create(API::class.java)

  private interface API {
    @GET("int-verfolgen/data/search")
    // Send an explicit User-Agent header.
    @Headers("User-Agent: Mozilla/5.0")
    suspend fun search(
        @Query("piececode") piececode: String,
        @Query("language") language: String,
        @Query("postCode") postCode: String? = null,
    ): SearchResponse
  }

  @JsonClass(generateAdapter = true)
  internal data class SearchResponse(
      val sendungen: List<Sendung>? = null,
      val rateLimited: Boolean? = null,
      val redirectUrl: String? = null,
  )

  @JsonClass(generateAdapter = true)
  internal data class Sendung(
      val id: String,
      val sendungsdetails: Sendungsdetails,
  )

  @JsonClass(generateAdapter = true)
  internal data class Sendungsdetails(
      val sendungsverlauf: Sendungsverlauf,
      val istZugestellt: Boolean? = null,
      val ruecksendung: Boolean? = null,
  )

  @JsonClass(generateAdapter = true)
  internal data class Sendungsverlauf(
      val status: String? = null,
      val fortschritt: Int? = null,
      val maximalFortschritt: Int? = null,
      val events: List<Event> = emptyList(),
  )

  @JsonClass(generateAdapter = true)
  internal data class Event(
      val datum: String, // ISO-8601 with offset, e.g. 2026-04-17T12:01:27+02:00
      val status: String,
      val ruecksendung: Boolean? = null,
  )
}
