package dev.itsvic.parceltracker

import android.content.Intent
import android.net.Uri
import dev.itsvic.parceltracker.api.Service
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class MainActivityTest {

  private fun createIntentWithData(uri: String): Intent {
    return Intent(Intent.ACTION_VIEW, Uri.parse(uri))
  }

  @Test
  fun parseDeepLink_oldDhlFormat_extractsPiececodeAndSelectsDhlDe() {
    val intent = createIntentWithData(
      "https://www.dhl.de/de/privatkunden/dhl-sendungsverfolgung?piececode=12345678901234567890"
    )
    val result = MainActivity().parseDeepLink(intent)

    assertNotNull(result)
    assertEquals("12345678901234567890", result?.trackingId)
    assertEquals(Service.DHL_DE.ordinal, result?.serviceOrdinal)
  }

  @Test
  fun parseDeepLink_newDhlFormat_extractsTrackingIdAndSelectsDhlDe() {
    val intent = createIntentWithData(
      "https://www.dhl.com/de-de/home/tracking/tracking-parcel.html?submit=1&tracking-id=12345678901234567890"
    )
    val result = MainActivity().parseDeepLink(intent)

    assertNotNull(result)
    assertEquals("12345678901234567890", result?.trackingId)
    assertEquals(Service.DHL_DE.ordinal, result?.serviceOrdinal)
  }

  @Test
  fun parseDeepLink_newDhlFormatWithoutSubmitParam_stillExtractsTrackingId() {
    val intent = createIntentWithData(
      "https://www.dhl.com/de-de/home/tracking/tracking-parcel.html?tracking-id=ABC123"
    )
    val result = MainActivity().parseDeepLink(intent)

    assertNotNull(result)
    assertEquals("ABC123", result?.trackingId)
  }

  @Test
  fun parseDeepLink_missingPiececode_returnsNull() {
    val intent = createIntentWithData(
      "https://www.dhl.de/de/privatkunden/dhl-sendungsverfolgung?other=value"
    )
    val result = MainActivity().parseDeepLink(intent)

    assertNull(result)
  }

  @Test
  fun parseDeepLink_missingTrackingId_returnsNull() {
    val intent = createIntentWithData(
      "https://www.dhl.com/de-de/home/tracking/tracking-parcel.html?submit=1"
    )
    val result = MainActivity().parseDeepLink(intent)

    assertNull(result)
  }

  @Test
  fun parseDeepLink_emptyTrackingId_returnsNull() {
    val intent = createIntentWithData(
      "https://www.dhl.com/de-de/home/tracking/tracking-parcel.html?tracking-id="
    )
    val result = MainActivity().parseDeepLink(intent)

    assertNull(result)
  }

  @Test
  fun parseDeepLink_emptyPiececode_returnsNull() {
    val intent = createIntentWithData(
      "https://www.dhl.de/de/privatkunden/dhl-sendungsverfolgung?piececode="
    )
    val result = MainActivity().parseDeepLink(intent)

    assertNull(result)
  }

  @Test
  fun parseDeepLink_trackingIdWithSpaces_returnsNull() {
    val intent = createIntentWithData(
      "https://www.dhl.com/de-de/home/tracking/tracking-parcel.html?tracking-id=123 456"
    )
    val result = MainActivity().parseDeepLink(intent)

    assertNull(result)
  }

  @Test
  fun parseDeepLink_trackingIdWithSpecialChars_returnsNull() {
    val intent = createIntentWithData(
      "https://www.dhl.com/de-de/home/tracking/tracking-parcel.html?tracking-id=123-456"
    )
    val result = MainActivity().parseDeepLink(intent)

    assertNull(result)
  }

  @Test
  fun parseDeepLink_trackingIdWithValidAlphanumeric_succeeds() {
    val intent = createIntentWithData(
      "https://www.dhl.com/de-de/home/tracking/tracking-parcel.html?tracking-id=ABC123XYZ"
    )
    val result = MainActivity().parseDeepLink(intent)

    assertNotNull(result)
    assertEquals("ABC123XYZ", result?.trackingId)
  }

  @Test
  fun parseDeepLink_nonDhlUrl_returnsNull() {
    val intent = createIntentWithData("https://www.google.com")
    val result = MainActivity().parseDeepLink(intent)

    assertNull(result)
  }

  @Test
  fun parseDeepLink_nonViewAction_returnsNull() {
    val intent = Intent(Intent.ACTION_SEND)
    intent.data = Uri.parse("https://www.dhl.com/de-de/home/tracking/tracking-parcel.html?tracking-id=123")
    val result = MainActivity().parseDeepLink(intent)

    assertNull(result)
  }

  @Test
  fun parseDeepLink_noData_returnsNull() {
    val intent = Intent(Intent.ACTION_VIEW)
    val result = MainActivity().parseDeepLink(intent)

    assertNull(result)
  }
}
