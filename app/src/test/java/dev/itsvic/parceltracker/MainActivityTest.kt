package dev.itsvic.parceltracker

import android.content.Intent
import android.net.Uri
import dev.itsvic.parceltracker.api.Service
import org.junit.Assert.*
import org.junit.Test
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
    val intent =
        createIntentWithData(
            "https://www.dhl.de/de/privatkunden/dhl-sendungsverfolgung?piececode=12345678901234567890")
    val result = MainActivity().parseDeepLink(intent)

    assertNotNull(result)
    assertEquals("12345678901234567890", result?.trackingId)
    assertEquals(Service.DHL_DE.ordinal, result?.serviceOrdinal)
  }

  @Test
  fun parseDeepLink_newDhlFormat_extractsTrackingIdAndSelectsDhlDe() {
    val intent =
        createIntentWithData(
            "https://www.dhl.com/de-de/home/tracking/tracking-parcel.html?submit=1&tracking-id=12345678901234567890")
    val result = MainActivity().parseDeepLink(intent)

    assertNotNull(result)
    assertEquals("12345678901234567890", result?.trackingId)
    assertEquals(Service.DHL_DE.ordinal, result?.serviceOrdinal)
  }

  @Test
  fun parseDeepLink_newDhlFormatWithoutSubmitParam_stillExtractsTrackingId() {
    val intent =
        createIntentWithData(
            "https://www.dhl.com/de-de/home/tracking/tracking-parcel.html?tracking-id=ABC123")
    val result = MainActivity().parseDeepLink(intent)

    assertNotNull(result)
    assertEquals("ABC123", result?.trackingId)
  }

  @Test
  fun parseDeepLink_missingPiececode_returnsNull() {
    val intent =
        createIntentWithData(
            "https://www.dhl.de/de/privatkunden/dhl-sendungsverfolgung?other=value")
    val result = MainActivity().parseDeepLink(intent)

    assertNull(result)
  }

  @Test
  fun parseDeepLink_missingTrackingId_returnsNull() {
    val intent =
        createIntentWithData(
            "https://www.dhl.com/de-de/home/tracking/tracking-parcel.html?submit=1")
    val result = MainActivity().parseDeepLink(intent)

    assertNull(result)
  }

  @Test
  fun parseDeepLink_emptyTrackingId_returnsNull() {
    val intent =
        createIntentWithData(
            "https://www.dhl.com/de-de/home/tracking/tracking-parcel.html?tracking-id=")
    val result = MainActivity().parseDeepLink(intent)

    assertNull(result)
  }

  @Test
  fun parseDeepLink_emptyPiececode_returnsNull() {
    val intent =
        createIntentWithData("https://www.dhl.de/de/privatkunden/dhl-sendungsverfolgung?piececode=")
    val result = MainActivity().parseDeepLink(intent)

    assertNull(result)
  }

  @Test
  fun parseDeepLink_trackingIdWithSpaces_returnsNull() {
    val intent =
        createIntentWithData(
            "https://www.dhl.com/de-de/home/tracking/tracking-parcel.html?tracking-id=123 456")
    val result = MainActivity().parseDeepLink(intent)

    assertNull(result)
  }

  @Test
  fun parseDeepLink_trackingIdWithSpecialChars_returnsNull() {
    val intent =
        createIntentWithData(
            "https://www.dhl.com/de-de/home/tracking/tracking-parcel.html?tracking-id=123-456")
    val result = MainActivity().parseDeepLink(intent)

    assertNull(result)
  }

  @Test
  fun parseDeepLink_trackingIdWithValidAlphanumeric_succeeds() {
    val intent =
        createIntentWithData(
            "https://www.dhl.com/de-de/home/tracking/tracking-parcel.html?tracking-id=ABC123XYZ")
    val result = MainActivity().parseDeepLink(intent)

    assertNotNull(result)
    assertEquals("ABC123XYZ", result?.trackingId)
  }

  @Test
  fun parseDeepLink_glsFormat_extractsTrackingNumberAndPostCodeAndSelectsGls() {
    val intent =
        createIntentWithData(
            "https://www.gls-pakete.de/reach-sendungsverfolgung?match=37389152443&trackingNumber=37389152443&postCode=76133")
    val result = MainActivity().parseDeepLink(intent)

    assertNotNull(result)
    assertEquals("37389152443", result?.trackingId)
    assertEquals("76133", result?.postalCode)
    assertEquals(Service.GLS.ordinal, result?.serviceOrdinal)
  }

  @Test
  fun parseDeepLink_glsFormatWithoutPostCode_stillExtractsTrackingNumber() {
    val intent =
        createIntentWithData(
            "https://www.gls-pakete.de/reach-sendungsverfolgung?trackingNumber=37389152443")
    val result = MainActivity().parseDeepLink(intent)

    assertNotNull(result)
    assertEquals("37389152443", result?.trackingId)
    assertEquals("", result?.postalCode)
  }

  @Test
  fun parseDeepLink_glsMissingTrackingNumber_returnsNull() {
    val intent =
        createIntentWithData("https://www.gls-pakete.de/reach-sendungsverfolgung?postCode=76133")
    val result = MainActivity().parseDeepLink(intent)

    assertNull(result)
  }

  @Test
  fun parseDeepLink_glsEmptyTrackingNumber_returnsNull() {
    val intent =
        createIntentWithData(
            "https://www.gls-pakete.de/reach-sendungsverfolgung?trackingNumber=&postCode=76133")
    val result = MainActivity().parseDeepLink(intent)

    assertNull(result)
  }

  @Test
  fun parseDeepLink_hermesFormat_extractsTrackingIdFromFragmentAndSelectsHermes() {
    val intent =
        createIntentWithData(
            "https://www.myhermes.de/empfangen/sendungsverfolgung/sendungsinformation/#H1019000516")
    val result = MainActivity().parseDeepLink(intent)

    assertNotNull(result)
    assertEquals("H1019000516", result?.trackingId)
    assertEquals(Service.HERMES.ordinal, result?.serviceOrdinal)
  }

  @Test
  fun parseDeepLink_hermesMissingFragment_returnsNull() {
    val intent =
        createIntentWithData(
            "https://www.myhermes.de/empfangen/sendungsverfolgung/sendungsinformation/")
    val result = MainActivity().parseDeepLink(intent)

    assertNull(result)
  }

  @Test
  fun parseDeepLink_hermesEmptyFragment_returnsNull() {
    val intent =
        createIntentWithData(
            "https://www.myhermes.de/empfangen/sendungsverfolgung/sendungsinformation/#")
    val result = MainActivity().parseDeepLink(intent)

    assertNull(result)
  }

  @Test
  fun parseDeepLink_hermesNonMatchingHost_returnsNull() {
    val intent = createIntentWithData("https://www.example.com/#H1019000516")
    val result = MainActivity().parseDeepLink(intent)

    assertNull(result)
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
    intent.data =
        Uri.parse("https://www.dhl.com/de-de/home/tracking/tracking-parcel.html?tracking-id=123")
    val result = MainActivity().parseDeepLink(intent)

    assertNull(result)
  }

  @Test
  fun parseDeepLink_noData_returnsNull() {
    val intent = Intent(Intent.ACTION_VIEW)
    val result = MainActivity().parseDeepLink(intent)

    assertNull(result)
  }

  private fun createSendIntent(sharedText: String): Intent {
    return Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_TEXT, sharedText)
    }
  }

  @Test
  fun parseDeepLink_sharedDhlUrl_extractsPiececodeAndSelectsDhlDe() {
    val intent =
        createSendIntent(
            "https://www.dhl.de/de/privatkunden/dhl-sendungsverfolgung?piececode=12345678901234567890")
    val result = MainActivity().parseDeepLink(intent)

    assertNotNull(result)
    assertEquals("12345678901234567890", result?.trackingId)
    assertEquals(Service.DHL_DE.ordinal, result?.serviceOrdinal)
  }

  @Test
  fun parseDeepLink_sharedTextWithSurroundingWords_stillExtractsUrl() {
    val intent =
        createSendIntent(
            "Look at this: https://www.gls-pakete.de/reach-sendungsverfolgung?trackingNumber=37389152443&postCode=76133 thanks!")
    val result = MainActivity().parseDeepLink(intent)

    assertNotNull(result)
    assertEquals("37389152443", result?.trackingId)
    assertEquals("76133", result?.postalCode)
    assertEquals(Service.GLS.ordinal, result?.serviceOrdinal)
  }

  @Test
  fun parseDeepLink_sharedDhlMailLink_extractsPiececodeAndSelectsDhlDe() {
    val intent =
        createSendIntent(
            "https://custcomm.dhl.de/go/10/a1b2c3d4e5f6.html?piececode=JJD000390007912345678")
    val result = MainActivity().parseDeepLink(intent)

    assertNotNull(result)
    assertEquals("JJD000390007912345678", result?.trackingId)
    assertEquals(Service.DHL_DE.ordinal, result?.serviceOrdinal)
  }

  @Test
  fun parseDeepLink_sharedDhlMailLinkInText_stillExtractsPiececode() {
    val intent =
        createSendIntent(
            "Ihre Sendung: https://custcomm.dhl.de/go/10/a1b2c3d4e5f6.html?piececode=JJD000390007912345678 Viele Grüße")
    val result = MainActivity().parseDeepLink(intent)

    assertNotNull(result)
    assertEquals("JJD000390007912345678", result?.trackingId)
    assertEquals(Service.DHL_DE.ordinal, result?.serviceOrdinal)
  }

  @Test
  fun parseDeepLink_dhlMailLinkMissingPiececode_returnsNull() {
    val intent = createSendIntent("https://custcomm.dhl.de/go/10/a1b2c3d4e5f6.html")
    val result = MainActivity().parseDeepLink(intent)

    assertNull(result)
  }

  @Test
  fun parseDeepLink_dhlMailLinkEmptyPiececode_returnsNull() {
    val intent = createSendIntent("https://custcomm.dhl.de/go/10/a1b2c3d4e5f6.html?piececode=")
    val result = MainActivity().parseDeepLink(intent)

    assertNull(result)
  }

  @Test
  fun parseDeepLink_dhlMailLinkOutsideGoPath_returnsNull() {
    val intent =
        createSendIntent("https://custcomm.dhl.de/other/page.html?piececode=JJD000390007912345678")
    val result = MainActivity().parseDeepLink(intent)

    assertNull(result)
  }

  @Test
  fun parseDeepLink_legacyNolpDhlLink_extractsIdcAndSelectsDhlDe() {
    val intent =
        createIntentWithData(
            "http://nolp.dhl.de/nextt-online-public/set_identcodes.do?lang=de&idc=00340434161234567890")
    val result = MainActivity().parseDeepLink(intent)

    assertNotNull(result)
    assertEquals("00340434161234567890", result?.trackingId)
    assertEquals(Service.DHL_DE.ordinal, result?.serviceOrdinal)
  }

  @Test
  fun parseDeepLink_sharedLegacyNolpDhlLink_extractsIdc() {
    val intent =
        createSendIntent(
            "Sendung: https://nolp.dhl.de/nextt-online-public/set_identcodes.do?lang=de&idc=00340434161234567890")
    val result = MainActivity().parseDeepLink(intent)

    assertNotNull(result)
    assertEquals("00340434161234567890", result?.trackingId)
    assertEquals(Service.DHL_DE.ordinal, result?.serviceOrdinal)
  }

  @Test
  fun parseDeepLink_legacyNolpDhlLinkMissingIdc_returnsNull() {
    val intent =
        createIntentWithData("http://nolp.dhl.de/nextt-online-public/set_identcodes.do?lang=de")
    assertNull(MainActivity().parseDeepLink(intent))
  }

  @Test
  fun parseDeepLink_legacyNolpDhlLinkEmptyIdc_returnsNull() {
    val intent =
        createIntentWithData(
            "http://nolp.dhl.de/nextt-online-public/set_identcodes.do?lang=de&idc=")
    assertNull(MainActivity().parseDeepLink(intent))
  }

  @Test
  fun parseDeepLink_nolpDhlOtherPath_returnsNull() {
    val intent = createIntentWithData("http://nolp.dhl.de/other.do?idc=00340434161234567890")
    assertNull(MainActivity().parseDeepLink(intent))
  }

  @Test
  fun parseDeepLink_dpdGerLink_extractsParcelAndSelectsDpdGer() {
    val intent = createIntentWithData("https://tracking.dpd.de/status/de_DE/parcel/09445012345678")
    val result = MainActivity().parseDeepLink(intent)

    assertNotNull(result)
    assertEquals("09445012345678", result?.trackingId)
    assertEquals(Service.DPD_GER.ordinal, result?.serviceOrdinal)
  }

  @Test
  fun parseDeepLink_sharedDpdGerLinkOtherLocale_extractsParcel() {
    val intent =
        createSendIntent("Ihr Paket: https://tracking.dpd.de/status/en_US/parcel/09445012345678")
    val result = MainActivity().parseDeepLink(intent)

    assertNotNull(result)
    assertEquals("09445012345678", result?.trackingId)
    assertEquals(Service.DPD_GER.ordinal, result?.serviceOrdinal)
  }

  @Test
  fun parseDeepLink_dpdGerLinkMissingParcel_returnsNull() {
    val intent = createIntentWithData("https://tracking.dpd.de/status/de_DE/parcel/")
    assertNull(MainActivity().parseDeepLink(intent))
  }

  @Test
  fun parseDeepLink_dpdGerOtherPath_returnsNull() {
    val intent = createIntentWithData("https://tracking.dpd.de/status/de_DE/09445012345678")
    assertNull(MainActivity().parseDeepLink(intent))
  }

  @Test
  fun parseDeepLink_sharedTextWithoutUrl_returnsNull() {
    val intent = createSendIntent("just some random text with no link")
    val result = MainActivity().parseDeepLink(intent)

    assertNull(result)
  }

  @Test
  fun parseDeepLink_sharedNonMatchingUrl_returnsNull() {
    val intent = createSendIntent("https://www.example.com")
    val result = MainActivity().parseDeepLink(intent)

    assertNull(result)
  }

  @Test
  fun parseDeepLink_sendIntentWithWrongMimeType_returnsNull() {
    val intent =
        Intent(Intent.ACTION_SEND).apply {
          type = "image/png"
          putExtra(
              Intent.EXTRA_TEXT,
              "https://www.dhl.de/de/privatkunden/dhl-sendungsverfolgung?piececode=12345678901234567890")
        }
    val result = MainActivity().parseDeepLink(intent)

    assertNull(result)
  }

  @Test
  fun parseDeepLink_sendIntentWithoutExtraText_returnsNull() {
    val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain" }
    val result = MainActivity().parseDeepLink(intent)

    assertNull(result)
  }
}
