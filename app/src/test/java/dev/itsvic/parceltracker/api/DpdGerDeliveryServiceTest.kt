package dev.itsvic.parceltracker.api

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class DpdGerDeliveryServiceTest {
  private val today = LocalDate.of(2026, 11, 28)

  private val labels =
      mapOf(
          "Start" to "Paketinfo an DPD &uuml;bergeben",
          "OnTheRoad" to "Paket unterwegs",
          "DeliveryDepot" to "Paket im Paketzustellzentrum",
          "CarLoad" to "Paket in Zustellung",
          "Delivered" to "Paket zugestellt",
      )

  /** A synthetic page with the element ids of my.dpd.de's dataprotection-tracking.aspx. */
  private fun page(
      status: String,
      dates: Map<String, String>,
      parcelNo: String = "01234567890123",
      labelOverrides: Map<String, String> = emptyMap(),
  ): String = buildString {
    append("<html><body><div id=\"ContentPlaceHolder1_panParcelList\">")
    append(
        "<span id=\"ContentPlaceHolder1_repParcelList_labParcelNo_0\" class=\"parcelNo\">" +
            "$parcelNo</span>")
    append(
        "<span id=\"ContentPlaceHolder1_repParcelList_labDeliveryStatus_0\" " +
            "class=\"parcelDeliveryStatus\">$status</span></div>")
    append("<div id=\"ContentPlaceHolder1_panParcelDetails\">")
    for ((key, label) in labels) {
      append(
          "<span id=\"ContentPlaceHolder1_labStatus$key\" class=\"labSub13\">\n" +
              "  ${labelOverrides[key] ?: label}\n</span>")
      append(
          "<span id=\"ContentPlaceHolder1_labStatus${key}Date\" class=\"labSub13 lSBold\">" +
              "${dates[key] ?: ""}</span>")
    }
    append("</div></body></html>")
  }

  @Test
  fun inTransit() {
    val p =
        DpdGerDeliveryService.parsePage(
            page("Paket unterwegs", mapOf("Start" to "26.11.", "OnTheRoad" to "27.11.")), today)
    assertEquals(Status.InTransit, p.currentStatus)
    assertEquals("01234567890123", p.id)
    assertEquals(
        listOf("Paket unterwegs", "Paketinfo an DPD übergeben"), p.history.map { it.description })
    assertEquals(
        listOf(LocalDate.of(2026, 11, 27), LocalDate.of(2026, 11, 26)),
        p.history.map { it.time.toLocalDate() })
    assertEquals("", p.history[0].location)
  }

  @Test
  fun outForDelivery() {
    val p =
        DpdGerDeliveryService.parsePage(
            page(
                "Paket in Zustellung",
                mapOf(
                    "Start" to "25.11.",
                    "OnTheRoad" to "25.11.",
                    "DeliveryDepot" to "27.11.",
                    "CarLoad" to "28.11.")),
            today)
    assertEquals(Status.OutForDelivery, p.currentStatus)
    assertEquals(4, p.history.size)
  }

  @Test
  fun delivered_yearFromStatusText() {
    val all = labels.keys.associateWith { "17.07." }
    val p = DpdGerDeliveryService.parsePage(page("Paket zugestellt - 17.07.2026", all), today)
    assertEquals(Status.Delivered, p.currentStatus)
    assertEquals(5, p.history.size)
    assertEquals("Paket zugestellt", p.history[0].description)
    assertEquals(LocalDate.of(2026, 7, 17), p.history[0].time.toLocalDate())
  }

  @Test
  fun paketshop_awaitingPickup() {
    val p =
        DpdGerDeliveryService.parsePage(
            page(
                "Abgabe in Paketshop",
                mapOf(
                    "Start" to "24.11.",
                    "OnTheRoad" to "24.11.",
                    "DeliveryDepot" to "25.11.",
                    "CarLoad" to "26.11."),
                labelOverrides =
                    mapOf(
                        "CarLoad" to "Abgabe in Paketshop",
                        "Delivered" to "Abholung im Paketshop durch Empf&auml;nger")),
            today)
    assertEquals(Status.AwaitingPickup, p.currentStatus)
  }

  @Test
  fun paketshop_pickedUp_isDelivered_placeholderDatesDropped() {
    // Seen live: unreached-looking milestones carry a placeholder 01.01.
    val p =
        DpdGerDeliveryService.parsePage(
            page(
                "Abholung durch Empf&#228;nger - 16.11.2026",
                mapOf(
                    "Start" to "01.01.",
                    "OnTheRoad" to "01.01.",
                    "DeliveryDepot" to "01.01.",
                    "CarLoad" to "01.01.",
                    "Delivered" to "16.11."),
                labelOverrides = mapOf("Delivered" to "Abholung im Paketshop durch Empfänger")),
            today)
    assertEquals(Status.Delivered, p.currentStatus)
    assertEquals(listOf("Abholung im Paketshop durch Empfänger"), p.history.map { it.description })
  }

  @Test(expected = ParcelNonExistentException::class)
  fun unknownParcelPanel() {
    DpdGerDeliveryService.parsePage(
        "<div id=\"ContentPlaceHolder1_panUnknowParcel\"><span>Entschuldigung</span></div>", today)
  }

  @Test(expected = ParcelNonExistentException::class)
  fun emptyListEntry_noData() {
    DpdGerDeliveryService.parsePage(page("", emptyMap()), today)
  }

  @Test
  fun yearRollover() {
    val p =
        DpdGerDeliveryService.parsePage(
            page("Paket unterwegs", mapOf("Start" to "29.12.", "OnTheRoad" to "02.01.")),
            LocalDate.of(2026, 1, 3))
    assertEquals(
        listOf(LocalDate.of(2026, 1, 2), LocalDate.of(2025, 12, 29)),
        p.history.map { it.time.toLocalDate() })
  }

  @Test
  fun unescape() {
    assertEquals(
        "Empfänger & Co <x> 'y' ü",
        DpdGerDeliveryService.unescapeHtml("Empf&auml;nger &amp; Co &lt;x&gt; &#39;y&#39; &#xFC;"))
  }
}
