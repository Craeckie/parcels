// SPDX-License-Identifier: GPL-3.0-or-later
package dev.itsvic.parceltracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.squareup.moshi.JsonDataException
import dev.itsvic.parceltracker.api.APIKeyMissingException
import dev.itsvic.parceltracker.api.Parcel as APIParcel
import dev.itsvic.parceltracker.api.ParcelHistoryItem
import dev.itsvic.parceltracker.api.ParcelNonExistentException
import dev.itsvic.parceltracker.api.Service
import dev.itsvic.parceltracker.api.Status
import dev.itsvic.parceltracker.api.getParcel
import dev.itsvic.parceltracker.db.Parcel
import dev.itsvic.parceltracker.db.ParcelStatus
import dev.itsvic.parceltracker.db.ParcelWithStatus
import dev.itsvic.parceltracker.db.deleteParcel
import dev.itsvic.parceltracker.db.demoModeParcels
import dev.itsvic.parceltracker.ui.theme.ParcelTrackerTheme
import dev.itsvic.parceltracker.ui.views.AddEditParcelView
import dev.itsvic.parceltracker.ui.views.HomeView
import dev.itsvic.parceltracker.ui.views.ParcelView
import dev.itsvic.parceltracker.ui.views.SettingsView
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okio.IOException

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) handleNotificationPermissionStuff()

    parcelToOpen = mutableIntStateOf(intent.getIntExtra("openParcel", -1))
    deepLinkPage = mutableStateOf(parseDeepLink(intent))

    val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri
          ->
          uri ?: return@registerForActivityResult
          lifecycleScope.launch(Dispatchers.IO) {
            try {
              BackupManager.exportToUri(applicationContext, uri)
              withContext(Dispatchers.Main) {
                Toast.makeText(
                        applicationContext,
                        getString(R.string.backup_export_success),
                        Toast.LENGTH_SHORT)
                    .show()
              }
            } catch (e: Exception) {
              Log.e("MainActivity", "Export failed", e)
              withContext(Dispatchers.Main) {
                Toast.makeText(
                        applicationContext,
                        getString(R.string.backup_export_failed),
                        Toast.LENGTH_SHORT)
                    .show()
              }
            }
          }
        }

    val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
          uri ?: return@registerForActivityResult
          lifecycleScope.launch(Dispatchers.IO) {
            try {
              BackupManager.importFromUri(applicationContext, uri)
              withContext(Dispatchers.Main) {
                Toast.makeText(
                        applicationContext,
                        getString(R.string.backup_import_success),
                        Toast.LENGTH_SHORT)
                    .show()
              }
            } catch (e: Exception) {
              Log.e("MainActivity", "Import failed", e)
              withContext(Dispatchers.Main) {
                Toast.makeText(
                        applicationContext,
                        getString(R.string.backup_import_failed),
                        Toast.LENGTH_SHORT)
                    .show()
              }
            }
          }
        }

    setContent {
      val parcelToOpen by parcelToOpen

      ParcelTrackerTheme {
        Box(modifier = Modifier.background(color = MaterialTheme.colorScheme.background)) {
          ParcelAppNavigation(
              parcelToOpen,
              onExportBackup = { exportLauncher.launch("deliveries-backup.json") },
              onImportBackup = { importLauncher.launch(arrayOf("application/json", "*/*")) },
          )
        }
      }
    }
  }

  companion object {
    lateinit var parcelToOpen: MutableIntState
    lateinit var deepLinkPage: MutableState<AddParcelPage?>
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    parcelToOpen.intValue = intent.getIntExtra("openParcel", -1)
    deepLinkPage.value = parseDeepLink(intent)
  }

  private fun parseDeepLink(intent: Intent): AddParcelPage? {
    if (intent.action != Intent.ACTION_VIEW) return null
    val uri: Uri = intent.data ?: return null
    if (uri.host == "www.dhl.de" && uri.path?.contains("dhl-sendungsverfolgung") == true) {
      val piececode = uri.getQueryParameter("piececode") ?: return null
      return AddParcelPage(trackingId = piececode, serviceOrdinal = Service.DHL_DE.ordinal)
    }
    return null
  }

  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  fun handleNotificationPermissionStuff() {
    val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean
          ->
          if (isGranted) {
            Log.d("MainActivity", "Notification permissions granted")
          } else {
            Log.d("MainActivity", "Notification permissions NOT granted")
          }
        }

    // Notification checks
    when {
      ContextCompat.checkSelfPermission(
          applicationContext,
          Manifest.permission.POST_NOTIFICATIONS,
      ) == PackageManager.PERMISSION_GRANTED -> {
        // We can post notifications
      }
      // TODO: educational UI maybe?
      else -> {
        requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
      }
    }
  }
}

@Serializable object HomePage

@Serializable object SettingsPage

@Serializable data class ParcelPage(val parcelDbId: Int)

@Serializable data class AddParcelPage(val trackingId: String = "", val serviceOrdinal: Int = -1)

@Serializable data class EditParcelPage(val parcelDbId: Int)

@Composable
fun ParcelAppNavigation(
    parcelToOpen: Int,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
) {
  val db = ParcelApplication.db
  val navController = rememberNavController()
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  val demoMode by context.dataStore.data.map { it[DEMO_MODE] == true }.collectAsState(false)

  LaunchedEffect(parcelToOpen) {
    if (parcelToOpen != -1) {
      navController.navigate(route = ParcelPage(parcelToOpen)) { popUpTo(HomePage) }
    }
  }

  val deepLinkPage by MainActivity.deepLinkPage

  LaunchedEffect(deepLinkPage) {
    deepLinkPage?.let { page ->
      navController.navigate(page) { popUpTo(HomePage) }
      MainActivity.deepLinkPage.value = null
    }
  }

  val animDuration = 300

  NavHost(
      navController = navController,
      startDestination = HomePage,
      enterTransition = {
        slideIntoContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.Start,
            animationSpec = tween(animDuration),
            initialOffset = { it / 4 }) + fadeIn(tween(animDuration))
      },
      exitTransition = { fadeOut(tween(animDuration)) + scaleOut(tween(500), 0.9f) },
      popEnterTransition = { fadeIn(tween(animDuration)) + scaleIn(tween(500), 0.9f) },
      popExitTransition = {
        slideOutOfContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.Start,
            animationSpec = tween(animDuration),
            targetOffset = { -it / 4 }) + fadeOut(tween(animDuration))
      },
  ) {
    composable<HomePage> {
      val parcels =
          if (demoMode) derivedStateOf { demoModeParcels }
          else db.parcelDao().getAllWithStatus().collectAsState(initial = emptyList())

      var isRefreshing by remember { mutableStateOf(false) }
      val workManager = WorkManager.getInstance(context)

      HomeView(
          parcels = parcels.value,
          onNavigateToAddParcel = { navController.navigate(route = AddParcelPage()) },
          onNavigateToParcel = { navController.navigate(route = ParcelPage(it.id)) },
          onNavigateToSettings = { navController.navigate(route = SettingsPage) },
          isRefreshing = isRefreshing,
          onRefresh = {
            isRefreshing = true
            val request = OneTimeWorkRequestBuilder<NotificationWorker>().build()
            workManager.enqueue(request)
            scope.launch {
              workManager.getWorkInfoByIdFlow(request.id).collect { info ->
                if (info?.state?.isFinished == true) isRefreshing = false
              }
            }
          },
      )
    }

    composable<SettingsPage> {
      SettingsView(
          onBackPressed = { navController.popBackStack() },
          onExportBackup = onExportBackup,
          onImportBackup = onImportBackup,
      )
    }

    composable<ParcelPage> { backStackEntry ->
      val route: ParcelPage = backStackEntry.toRoute()
      val parcelWithStatus: ParcelWithStatus? by
          if (demoMode) derivedStateOf { demoModeParcels[route.parcelDbId] }
          else db.parcelDao().getWithStatusById(route.parcelDbId).collectAsState(null)
      val dbHistory: List<dev.itsvic.parceltracker.db.ParcelHistoryItem> by
          db.parcelHistoryDao().getAllById(route.parcelDbId).collectAsState(listOf())
      var apiParcel: APIParcel? by remember { mutableStateOf(null) }

      val dbParcel = parcelWithStatus?.parcel

      LaunchedEffect(parcelWithStatus) {
        if (dbParcel != null && !dbParcel.isArchived) {
          fun apiParcelError(description: String, status: Status): APIParcel {
            return APIParcel(
                dbParcel.parcelId,
                listOf(ParcelHistoryItem(description, LocalDateTime.now(), "")),
                status)
          }

          launch(Dispatchers.IO) {
            try {
              apiParcel =
                  context.getParcel(dbParcel.parcelId, dbParcel.postalCode, dbParcel.service)

              if (!demoMode) {
                // update parcel status
                val zone = ZoneId.systemDefault()
                val lastChange =
                    apiParcel!!.history.firstOrNull()?.time?.atZone(zone)?.toInstant()
                        ?: Instant.EPOCH
                val status =
                    ParcelStatus(
                        dbParcel.id,
                        apiParcel!!.currentStatus,
                        lastChange,
                    )
                if (parcelWithStatus?.status == null) {
                  db.parcelStatusDao().insert(status)
                } else {
                  db.parcelStatusDao().update(status)
                }
              }
            } catch (e: IOException) {
              Log.w("MainActivity", "Failed fetch: $e")
              apiParcel =
                  apiParcelError(
                      context.getString(R.string.network_failure_detail), Status.NetworkFailure)
            } catch (_: ParcelNonExistentException) {
              apiParcel =
                  apiParcelError(
                      context.getString(R.string.parcel_doesnt_exist_detail), Status.NoData)
            } catch (_: APIKeyMissingException) {
              apiParcel =
                  apiParcelError(
                      context.getString(R.string.error_no_api_key_provided), Status.NetworkFailure)
            } catch (e: JsonDataException) {
              Log.w(
                  "MainActivity",
                  "Unexpected JSON response that could not be converted: ${e.message}")
              apiParcel =
                  apiParcelError(
                      context.getString(R.string.error_json_conversion).format(e.message),
                      Status.NetworkFailure)
            } catch (e: Exception) {
              // catchall to avoid crashes
              Log.e("MainActivity", "Unexpected error", e)
              apiParcel =
                  apiParcelError(
                      context.getString(R.string.error_unexpected_detail).format(e.message),
                      Status.NetworkFailure)
            }
          }
        }
      }

      val fakeApiParcel =
          parcelWithStatus?.let {
            APIParcel(
                id = it.parcel.parcelId,
                currentStatus = if (it.status != null) it.status.status else Status.Unknown,
                history =
                    dbHistory.map { item ->
                      ParcelHistoryItem(item.description, item.time, item.location)
                    })
          }

      if (apiParcel == null && dbParcel?.isArchived == false || dbParcel == null)
          Box(
              modifier =
                  Modifier.background(color = MaterialTheme.colorScheme.background).fillMaxSize(),
              contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
              }
      else
          ParcelView(
              if (dbParcel.isArchived) fakeApiParcel!! else apiParcel!!,
              dbParcel.humanName,
              dbParcel.service,
              dbParcel.isArchived,
              dbParcel.archivePromptDismissed,
              onBackPressed = { navController.popBackStack() },
              onEdit = { navController.navigate(EditParcelPage(dbParcel.id)) },
              onDelete = {
                if (demoMode) {
                  Toast.makeText(
                          context,
                          context.getString(R.string.demo_mode_action_block),
                          Toast.LENGTH_SHORT)
                      .show()
                  return@ParcelView
                }

                scope.launch(Dispatchers.IO) {
                  deleteParcel(dbParcel)
                  scope.launch { navController.popBackStack(HomePage, false) }
                }
              },
              onArchive = {
                if (dbParcel.isArchived) return@ParcelView
                if (demoMode) {
                  Toast.makeText(
                          context,
                          context.getString(R.string.demo_mode_action_block),
                          Toast.LENGTH_SHORT)
                      .show()
                  return@ParcelView
                }
                scope.launch(Dispatchers.IO) {
                  db.parcelDao().update(dbParcel.copy(isArchived = true))
                  db.parcelHistoryDao()
                      .insert(
                          apiParcel!!.history.map {
                            dev.itsvic.parceltracker.db.ParcelHistoryItem(
                                description = it.description,
                                location = it.location,
                                time = it.time,
                                parcelId = dbParcel.id,
                            )
                          })
                }
              },
              onArchivePromptDismissal = {
                if (demoMode) {
                  Toast.makeText(
                          context,
                          context.getString(R.string.demo_mode_action_block),
                          Toast.LENGTH_SHORT)
                      .show()
                  return@ParcelView
                }
                scope.launch(Dispatchers.IO) {
                  db.parcelDao().update(dbParcel.copy(archivePromptDismissed = true))
                }
              },
          )
    }

    composable<AddParcelPage> { backStackEntry ->
      val route: AddParcelPage = backStackEntry.toRoute()
      val initialService = Service.entries.getOrNull(route.serviceOrdinal) ?: Service.UNDEFINED

      var pendingParcel by remember { mutableStateOf<dev.itsvic.parceltracker.db.Parcel?>(null) }
      var duplicateParcel by remember { mutableStateOf<dev.itsvic.parceltracker.db.Parcel?>(null) }

      duplicateParcel?.let { existing ->
        pendingParcel?.let { pending ->
          AlertDialog(
              onDismissRequest = {
                duplicateParcel = null
                pendingParcel = null
              },
              title = { Text(stringResource(R.string.duplicate_parcel_title)) },
              text = {
                Text(stringResource(R.string.duplicate_parcel_message, existing.humanName))
              },
              confirmButton = {
                TextButton(
                    onClick = {
                      duplicateParcel = null
                      pendingParcel = null
                      navController.navigate(route = ParcelPage(existing.id)) { popUpTo(HomePage) }
                    }) {
                      Text(stringResource(R.string.duplicate_view_existing))
                    }
              },
              dismissButton = {
                TextButton(
                    onClick = {
                      val p = pending
                      duplicateParcel = null
                      pendingParcel = null
                      scope.launch(Dispatchers.IO) {
                        val id = db.parcelDao().insert(p)
                        scope.launch {
                          navController.navigate(route = ParcelPage(id.toInt())) {
                            popUpTo(HomePage)
                          }
                        }
                      }
                    }) {
                      Text(stringResource(R.string.duplicate_add_anyway))
                    }
              },
          )
        }
      }

      AddEditParcelView(
          null,
          initialTrackingId = route.trackingId,
          initialService = initialService,
          onBackPressed = { navController.popBackStack() },
          onCompleted = {
            if (demoMode) {
              Toast.makeText(
                      context,
                      context.getString(R.string.demo_mode_action_block),
                      Toast.LENGTH_SHORT)
                  .show()
              return@AddEditParcelView
            }

            scope.launch(Dispatchers.IO) {
              val existing = db.parcelDao().findByTrackingIdAndService(it.parcelId, it.service)
              if (existing != null) {
                scope.launch {
                  pendingParcel = it
                  duplicateParcel = existing
                }
              } else {
                val id = db.parcelDao().insert(it)
                scope.launch {
                  navController.navigate(route = ParcelPage(id.toInt())) { popUpTo(HomePage) }
                }
              }
            }
          },
      )
    }

    composable<EditParcelPage> { backStackEntry ->
      val route: EditParcelPage = backStackEntry.toRoute()
      val parcel: Parcel? by
          if (demoMode) derivedStateOf { demoModeParcels[route.parcelDbId].parcel }
          else db.parcelDao().getById(route.parcelDbId).collectAsState(null)

      if (parcel == null)
          return@composable Box(
              modifier =
                  Modifier.background(color = MaterialTheme.colorScheme.background).fillMaxSize(),
              contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
              }

      AddEditParcelView(
          parcel,
          onBackPressed = { navController.popBackStack() },
          onCompleted = {
            if (demoMode) {
              Toast.makeText(
                      context,
                      context.getString(R.string.demo_mode_action_block),
                      Toast.LENGTH_SHORT)
                  .show()
              return@AddEditParcelView
            }

            scope.launch(Dispatchers.IO) {
              db.parcelDao().update(it)
              scope.launch { navController.popBackStack() }
            }
          },
      )
    }
  }
}
