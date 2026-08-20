package dev.itsvic.parceltracker.api

import android.content.Context
import com.squareup.moshi.JsonClass
import dev.itsvic.parceltracker.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Path

object HermesDeliveryService : DeliveryService {
  override val nameResource: Int
    get() = R.string.service_hermes

  override val acceptsPostCode: Boolean
    get() = false

  override val requiresPostCode: Boolean
    get() = false

  override fun trackingUrl(trackingId: String, postalCode: String?) =
      "https://www.myhermes.de/empfangen/sendungsverfolgung/sendungsinformation/#$trackingId"

  override suspend fun getParcel(
      context: Context,
      trackingId: String,
      postalCode: String?
  ): Parcel {

    val resp =
        try {
          service.getShipments(trackingId)[0]
        } catch (_: HttpException) {
          throw ParcelNonExistentException()
        } catch (_: IndexOutOfBoundsException) {
          throw ParcelNonExistentException()
        }

    val status =
        when (resp.parcelProgress.firstOrNull()?.parcelStatus) {
          "ANNOUNCED" -> Status.Preadvice
          "TAKEN_OVER_BY_HERMES" -> Status.PickedUpByCourier
          "ARRIVED_IN_DESTINATION_REGION" -> Status.InTransit
          "DELIVERY_TOUR_STARTED" -> Status.OutForDelivery
          "DELIVERED_HOMEDELIVERY" -> Status.Delivered
          "SORTED" -> Status.InWarehouse
          "RETURN_DELIVERED_TO_SENDER" -> Status.ReturnedToSender
          null -> Status.NoData
          else -> logUnknownStatus("Hermes", resp.parcelProgress.first().parcelStatus)
        }

    val statusReached = resp.parcelProgress.filter { it.timestamp != null }

    val history =
        statusReached.map {
          ParcelHistoryItem(
              it.historyText!!,
              Instant.parse(it.timestamp).atZone(ZoneId.systemDefault()).toLocalDateTime(),
              "")
        }

    val properties = mutableMapOf<Int, String>()
    val fmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

    resp.forecast?.let { f ->
      val from =
          f.deliveryTimeFromUTC?.let {
            Instant.parse(it).atZone(ZoneId.systemDefault()).toLocalDate()
          }
      val to =
          f.deliveryTimeToUTC?.let {
            Instant.parse(it).atZone(ZoneId.systemDefault()).toLocalDate()
          }
      val text: String? =
          when {
            from != null && to != null && from != to -> "${from.format(fmt)} – ${to.format(fmt)}"
            from != null -> from.format(fmt)
            to != null -> to.format(fmt)
            else -> null
          }
      if (text != null) {
        val key =
            if (status == Status.Delivered) R.string.property_delivery_time
            else R.string.property_eta
        properties[key] = text
      }
    }

    return Parcel(trackingId, history, status, properties)
  }

  private val retrofit =
      Retrofit.Builder()
          .baseUrl("https://api.my-deliveries.de/tnt/v2/shipments/")
          .client(api_client)
          .addConverterFactory(api_factory)
          .build()

  private val service = retrofit.create(API::class.java)

  private interface API {
    @GET("search/{id}")
    suspend fun getShipments(@Path("id") trackingId: String): List<HermesParcelData>
  }

  @JsonClass(generateAdapter = true)
  internal data class HermesParcelData(
      val barcode: String,
      val parcelProgress: List<HermesParcelHistory>,
      val forecast: HermesForecast?
  )

  @JsonClass(generateAdapter = true)
  internal data class HermesParcelHistory(
      val timestamp: String?,
      val status: String,
      val parcelStatus: String,
      val historyText: String?
  )

  @JsonClass(generateAdapter = true)
  internal data class HermesForecast(
      val deliveryTimeFromUTC: String?,
      val deliveryTimeToUTC: String?
  )
}
