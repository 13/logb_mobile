package dev.logb.android.screenshot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.logb.android.core.auth.Session
import dev.logb.android.core.network.dto.ApiToken
import dev.logb.android.core.network.dto.User
import dev.logb.android.core.db.model.Bucket
import dev.logb.android.core.db.rem
import dev.logb.android.core.design.theme.LogbTheme
import dev.logb.android.core.db.entity.ObjectTypeEntity
import dev.logb.android.core.domain.Insights
import dev.logb.android.core.domain.ReminderPresenter
import dev.logb.android.core.domain.SpendStats
import dev.logb.android.core.server.Capabilities
import dev.logb.android.core.sync.SyncStatus
import dev.logb.android.feature.lock.LockContent
import dev.logb.android.feature.objects.ObjectCard
import dev.logb.android.feature.objects.ObjectsContent
import dev.logb.android.feature.objects.ObjectsUiState
import dev.logb.android.feature.onboarding.ServerContent
import dev.logb.android.feature.onboarding.ServerUiState
import dev.logb.android.feature.reminders.DueItem
import dev.logb.android.feature.reminders.DueListContent
import dev.logb.android.feature.settings.AboutContent
import dev.logb.android.feature.settings.AboutInfo
import dev.logb.android.feature.settings.SettingsHubContent
import dev.logb.android.feature.settings.SettingsUiState
import dev.logb.android.feature.settings.tokens.TokenRow
import dev.logb.android.feature.settings.tokens.TokensContent
import dev.logb.android.feature.settings.tokens.TokensUiState
import dev.logb.android.feature.stats.FuelInsights
import dev.logb.android.feature.stats.InsightsSection
import dev.logb.android.feature.stats.ObjectInsights
import dev.logb.android.feature.stats.StatsContent
import dev.logb.android.feature.stats.StatsUiState
import dev.logb.android.feature.types.TypeForm
import dev.logb.android.feature.types.TypeRow
import dev.logb.android.feature.types.TypesContent
import dev.logb.android.feature.types.TypesUiState
import dev.logb.android.feature.update.AppVersion
import dev.logb.android.feature.update.UpdateRow
import dev.logb.android.feature.update.UpdateUiState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * Every state-driven screen at phone width, light and dark. Record with
 * `./gradlew :app:recordRoborazziDebug`, look at each PNG (a golden nobody looked at proves
 * nothing), and CI verifies with `verifyRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h800dp-xhdpi")
class ScreensScreenshotTest {
    private fun capture(name: String, content: @Composable () -> Unit) {
        for (dark in listOf(false, true)) {
            captureRoboImage("src/test/screenshots/${name}_${if (dark) "dark" else "light"}.png") {
                LogbTheme(darkTheme = dark, dynamicColor = false) { Surface(Modifier.fillMaxSize()) { content() } }
            }
        }
    }

    private val today = LocalDate.parse("2026-09-13")
    private val cards = listOf(
        ObjectCard("golf", "Golf", "car", 86_000, "km", 28_640, "2026-09-05", 1, null, tags = listOf("Winter", "Lease")),
        ObjectCard("house", "House", "home", null, null, 1_230, "2026-03-10", 0, null),
        ObjectCard("bike", "Gravel bike", "bike", 3_120, "km", 45_000, "2026-08-01", 0, null),
    )

    @Test fun objects() = capture("objects") {
        ObjectsContent(ObjectsUiState(cards, dueCount = 1, sync = SyncStatus.Offline(pending = 2), loaded = true), onOpen = {}, onOpenSync = {}, onRefresh = {}, onToggleArchived = {})
    }

    @Test fun objectsEmpty() = capture("objects_empty") {
        ObjectsContent(ObjectsUiState(emptyList(), sync = SyncStatus.Idle("2026-09-13T08:00:00.000Z"), loaded = true), onOpen = {}, onOpenSync = {}, onRefresh = {}, onToggleArchived = {})
    }

    @Test fun objectsArchived() = capture("objects_archived") {
        ObjectsContent(ObjectsUiState(emptyList(), archived = true, sync = SyncStatus.Idle("2026-09-13T08:00:00.000Z"), loaded = true), onOpen = {}, onOpenSync = {}, onRefresh = {}, onToggleArchived = {})
    }

    private val stats = SpendStats.Stats(
        totalCents = 34_230, years = listOf("2026", "2025"),
        overTime = listOf(SpendStats.Amount("2025", 20_000), SpendStats.Amount("2026", 14_230)),
        byObject = listOf(
            SpendStats.ObjectNode("car", "Golf", "car", false, 33_000, emptyList()),
            SpendStats.ObjectNode("house", "House", "home", false, 1_230, listOf(SpendStats.ObjectNode("garage", "Garage", "other", true, 230, listOf(SpendStats.ObjectNode("bulb", "Bulb", "appliance", false, 30, emptyList()))))),
        ),
        byType = listOf(SpendStats.Amount("car", 33_000), SpendStats.Amount("home", 1_000), SpendStats.Amount("other", 200), SpendStats.Amount("appliance", 30)),
        byCategory = listOf(SpendStats.Amount("maintenance", 20_200), SpendStats.Amount("fuel", 13_000), SpendStats.Amount("repair", 1_030)),
    )

    @Test fun statistics() = capture("statistics") {
        StatsContent(StatsUiState(year = null, years = stats.years, purchases = false, stats = stats), expanded = setOf("house"), onBack = {}, onYear = {}, onPurchases = {}, onToggle = {}, onOpenObject = {})
    }

    private val insights = ObjectInsights(
        byYear = listOf(Bucket("2026", 13_000, 3), Bucket("2025", 20_000, 1)),
        byCategory = listOf(Bucket("maintenance", 20_000, 1), Bucket("fuel", 13_000, 3)),
        counterSpan = 8_000L to 11_000L, costPerCounterMilli = 11_000,
        fuel = FuelInsights("l", 85_000, 5_000, 10_000, listOf(Insights.FillRate("2026-07-01", 5_000), Insights.FillRate("2026-08-01", 5_000))),
        counterPerDayMilli = 10_416,
        usageByMonth = (0 until 12).map { i -> Insights.MonthUsage("2026-%02d".format(i + 1), if (i in 5..8) 400L + i * 30 else null) }.takeLast(12),
        hasContents = false,
        ownership = SpendStats.Ownership(1_533_000, 1_500_000, "2024-05-01", 650_000),
        byMonth = (0 until 12).map { i -> SpendStats.Amount("2026-%02d".format(i + 1), listOf(0L, 0, 0, 0, 0, 5_000, 4_000, 4_000, 20_000, 0, 0, 0)[i]) },
    )

    @Test fun insights() = capture("insights") {
        Column(Modifier.padding(16.dp)) { InsightsSection(insights, "km", "EUR", includeContents = false, onIncludeContents = {}) }
    }

    private fun due(obj: String, type: String, title: String, dueDate: String?, dueCounter: Long? = null) =
        DueItem(ReminderPresenter.present(rem(title, "o", title = title, dueDate = dueDate, dueCounter = dueCounter), 86_000, null, today), "o", obj, type, "km")

    @Test fun dueList() = capture("due_list") {
        DueListContent(listOf(due("Golf", "car", "Oil change", "2026-09-01"), due("House", "home", "Boiler service", "2026-09-20"), due("Golf", "car", "Tyres", null, 90_000)), onBack = {}, onOpen = {})
    }

    @Test fun lock() = capture("lock") { LockContent(onUnlock = {}) }

    @Test fun server() = capture("server") { ServerContent(ServerUiState(url = "https://logb.example.org"), onUrlChange = {}, onSubmit = {}) }

    @Test fun settings() = capture("settings") {
        SettingsHubContent(SettingsUiState(session = Session.SignedIn("https://logb.example.org/", User(1, "ben"), "t", "EUR"), sync = SyncStatus.Idle("2026-09-13T08:00:00.000Z"), version = "0.5.0"), onOpen = {})
    }

    @Test fun about() = capture("about") {
        AboutContent(
            AboutInfo("0.7.0", 700, "2026-09-14", "1dc9ad7", debug = false, releaseKey = true, serverUrl = "https://logb.example.org/", serverVersion = "0.7.1", capabilities = Capabilities.of("0.7.1")),
            onBack = {}, onOpenUrl = {}, onCopy = {},
        )
    }

    @Test fun aboutWithUpdate() = capture("about_update") {
        AboutContent(
            AboutInfo("0.7.1", 701, "2026-09-15", "1dc9ad7", debug = false, releaseKey = true, serverUrl = "https://logb.example.org/", serverVersion = "0.8.2", capabilities = Capabilities.of("0.8.2")),
            onBack = {}, onOpenUrl = {}, onCopy = {},
            updateSection = {
                UpdateRow(
                    UpdateUiState.Available(AppVersion(0, 8, 0), 9_509_668, "https://github.com/13/logb_mobile/releases/tag/v0.8.0"),
                    onCheck = {}, onDownload = {}, onInstall = {}, onGrantPermission = {}, onRetryInstall = {}, onOpenReleasePage = {},
                )
            },
        )
    }

    @Test fun types() = capture("types") {
        TypesContent(TypesUiState(rows = listOf(TypeRow(ObjectTypeEntity("u1", 1, "Boat", "tool", """["repair","fuel","other"]""", "h", "t", "t", null), 0))), onEdit = {}, onNew = {}, onSave = {}, onCancel = {}, onDelete = {}, onFormChange = {}, onBack = {})
    }

    @Test fun typesForm() = capture("types_form") {
        TypesContent(
            TypesUiState(
                rows = listOf(TypeRow(ObjectTypeEntity("u1", 1, "Boat", "tool", """["repair","fuel","other"]""", "h", "t", "t", null), 0)),
                form = TypeForm("u1", "Boat", "tool", listOf("repair", "fuel", "other"), "h"),
            ),
            onEdit = {}, onNew = {}, onSave = {}, onCancel = {}, onDelete = {}, onFormChange = {}, onBack = {},
        )
    }

    @Test fun tokens() = capture("tokens") {
        TokensContent(
            TokensUiState(
                rows = listOf(
                    TokenRow(ApiToken(1, "This phone", "logb_pat_ab12", "2026-08-01T00:00:00Z", "2026-09-10T08:00:00Z"), isThisPhone = true, canRevoke = false),
                    TokenRow(ApiToken(2, "Backup script", "logb_pat_cd34", "2026-07-15T00:00:00Z", "2026-09-01T00:00:00Z"), isThisPhone = false, canRevoke = true),
                    TokenRow(ApiToken(3, "CI pipeline", "logb_pat_ef56", "2026-09-12T00:00:00Z", null), isThisPhone = false, canRevoke = true),
                ),
                loaded = true,
                fresh = "logb_pat_ef56_9f8a7b6c5d4e3f2a1b0c9d8e7f6a5b4c",
            ),
            onBack = {}, onName = {}, onCreate = {}, onRevoke = {}, onConfirm = {}, onDismiss = {}, onCopy = {},
        )
    }
}
