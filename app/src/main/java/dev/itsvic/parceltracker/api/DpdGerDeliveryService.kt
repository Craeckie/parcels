package dev.itsvic.parceltracker.api

import dev.itsvic.parceltracker.R
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.coroutines.executeAsync

/**
 * DPD Germany, via the server-rendered my.dpd.de tracking page.
 *
 * The former JSON endpoint (tracking.dpd.de/rest/plc) was retired in 2026 and resets every
 * connection. my.dpd.de resets connections that send an okhttp User-Agent, so a browser one is set,
 * and the redirect stores the parcel number in the session: cookies must be kept across it.
 */
object DpdGerDeliveryService : DeliveryService {
  override val nameResource: Int
    get() = R.string.service_dpd_ger

  override val acceptsPostCode: Boolean
    get() = false

  override val requiresPostCode: Boolean
    get() = false

  private const val USER_AGENT =
      "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
          "Chrome/130.0.0.0 Mobile Safari/537.36"

  @OptIn(ExperimentalCoroutinesApi::class)
  override suspend fun getParcel(trackingId: String, postCode: String?): Parcel {
    val id = trackingId.trim()
    val url =
        "https://my.dpd.de/redirect.aspx"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("action", "12")
            .addQueryParameter("parcelno", id)
            .build()
    val request =
        Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "de-DE,de;q=0.9")
            .build()

    // A fresh cookie jar per lookup: the session only has to survive the 302.
    val client = api_client.newBuilder().cookieJar(SessionCookieJar()).build()
    val html =
        client.newCall(request).executeAsync().use { response ->
          if (response.code == 404) throw ParcelNonExistentException()
          if (!response.isSuccessful) throw IOException("DPD Germany: HTTP ${response.code}")
          response.body.string()
        }

    val parcel = parsePage(html, LocalDate.now())
    if (parcel.currentStatus == Status.Unknown) {
      logUnknownStatus("DPD Germany", statusText(html))
    }
    return parcel.copy(id = id)
  }

  private class SessionCookieJar : CookieJar {
    private val cookies = ConcurrentHashMap<String, Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
      for (c in cookies) this.cookies["${c.domain}|${c.path}|${c.name}"] = c
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        cookies.values.filter { it.matches(url) }
  }

  /** The five milestones of the page, in order, by their element id suffix. */
  private val MILESTONES = listOf("Start", "OnTheRoad", "DeliveryDepot", "CarLoad", "Delivered")

  private const val PREFIX = "ContentPlaceHolder1_"

  /** Unreached milestones sometimes carry a placeholder date this far before the next one. */
  private const val MAX_MILESTONE_GAP_DAYS = 60L

  private fun spanText(html: String, id: String): String? {
    val m =
        Regex("""<span[^>]*\bid="${Regex.escape(PREFIX + id)}"[^>]*>([^<]*)</span>""").find(html)
            ?: return null
    return unescapeHtml(m.groupValues[1]).replace(Regex("\\s+"), " ").trim()
  }

  private fun statusText(html: String): String =
      spanText(html, "repParcelList_labDeliveryStatus_0") ?: ""

  internal fun parsePage(html: String, today: LocalDate): Parcel {
    if (html.contains("id=\"${PREFIX}panUnknowParcel\"")) throw ParcelNonExistentException()
    val parcelNo =
        spanText(html, "repParcelList_labParcelNo_0")?.takeIf { it.isNotEmpty() }
            ?: throw ParcelNonExistentException()
    val statusText = statusText(html)

    data class Milestone(val key: String, val label: String, val day: Int, val month: Int)

    val reached =
        MILESTONES.mapNotNull { key ->
          val date = spanText(html, "labStatus${key}Date") ?: return@mapNotNull null
          val m = Regex("""^(\d{1,2})\.(\d{1,2})\.?$""").find(date) ?: return@mapNotNull null
          val label = spanText(html, "labStatus$key")?.takeIf { it.isNotEmpty() } ?: key
          Milestone(key, label, m.groupValues[1].toInt(), m.groupValues[2].toInt())
        }

    // A known number DPD has no data for yet renders an empty list entry.
    if (reached.isEmpty() && statusText.isEmpty()) throw ParcelNonExistentException()

    // The status text ends with the full date of the latest event ("Paket zugestellt -
    // 17.07.2026"); it anchors the year of the dd.MM. milestones, else today does.
    val anchor =
        Regex("""(\d{1,2})\.(\d{1,2})\.(\d{4})""").findAll(statusText).lastOrNull()?.let {
          runCatching {
                LocalDate.of(
                    it.groupValues[3].toInt(), it.groupValues[2].toInt(), it.groupValues[1].toInt())
              }
              .getOrNull()
        } ?: today

    // Walk newest first; each date is the latest one not after its successor (or the anchor).
    val history = mutableListOf<ParcelHistoryItem>()
    var bound = anchor
    for (ms in reached.reversed()) {
      val date = latestOnOrBefore(ms.day, ms.month, bound) ?: continue
      if (history.isNotEmpty() && date.isBefore(bound.minusDays(MAX_MILESTONE_GAP_DAYS))) continue
      history.add(ParcelHistoryItem(ms.label, date.atStartOfDay(), ""))
      bound = date
    }

    val last = reached.lastOrNull()
    val status =
        when (last?.key) {
          null -> Status.Unknown
          "Start" -> Status.Preadvice
          "OnTheRoad" -> Status.InTransit
          "DeliveryDepot" -> Status.InWarehouse
          "CarLoad" ->
              if (isPickupPoint(last.label) || isPickupPoint(statusText)) Status.AwaitingPickup
              else Status.OutForDelivery
          "Delivered" -> Status.Delivered
          else -> Status.Unknown
        }

    return Parcel(parcelNo, history, status)
  }

  private fun isPickupPoint(text: String): Boolean =
      text.contains("Paketshop", ignoreCase = true) ||
          text.contains("Pickup", ignoreCase = true) ||
          text.contains("abhol", ignoreCase = true)

  private fun latestOnOrBefore(day: Int, month: Int, bound: LocalDate): LocalDate? {
    for (year in bound.year downTo bound.year - 1) {
      val d = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: continue
      if (!d.isAfter(bound)) return d
    }
    return null
  }

  private val ENTITIES =
      mapOf(
          "amp" to "&",
          "lt" to "<",
          "gt" to ">",
          "quot" to "\"",
          "apos" to "'",
          "nbsp" to " ",
          "auml" to "ä",
          "ouml" to "ö",
          "uuml" to "ü",
          "Auml" to "Ä",
          "Ouml" to "Ö",
          "Uuml" to "Ü",
          "szlig" to "ß",
      )

  internal fun unescapeHtml(s: String): String =
      Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);").replace(s) { m ->
        val e = m.groupValues[1]
        when {
          e.startsWith("#x") || e.startsWith("#X") ->
              e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
          e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
          else -> ENTITIES[e]
        } ?: m.value
      }
}
