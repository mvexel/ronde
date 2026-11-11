package net.mvexel.intervalswatch.mobile.feature

import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import net.mvexel.intervalswatch.mobile.AuthType
import net.mvexel.intervalswatch.mobile.RecordingItem
import net.mvexel.intervalswatch.mobile.Settings
import net.mvexel.intervalswatch.mobile.UploadState
import net.mvexel.intervalswatch.mobile.app.AppViewModel
import net.mvexel.intervalswatch.mobile.data.activity.InMemoryActivityRepository
import net.mvexel.intervalswatch.mobile.data.db.ActivityEntity
import net.mvexel.intervalswatch.mobile.data.db.PlannedWorkoutPrefsEntity
import net.mvexel.intervalswatch.mobile.data.session.SessionState
import net.mvexel.intervalswatch.mobile.fakes.FakePrefsDao
import net.mvexel.intervalswatch.mobile.fakes.FakeRecordings
import net.mvexel.intervalswatch.mobile.fakes.FakeScreenSettings
import net.mvexel.intervalswatch.mobile.fakes.FakeSession
import net.mvexel.intervalswatch.mobile.fakes.MainDispatcherRule
import net.mvexel.intervalswatch.mobile.data.activity.ActivityRepository
import net.mvexel.intervalswatch.mobile.feature.history.HistoryViewModel
import net.mvexel.intervalswatch.mobile.feature.history.RunResult
import net.mvexel.intervalswatch.shared.Verdict
import net.mvexel.intervalswatch.mobile.feature.login.LoginViewModel
import net.mvexel.intervalswatch.mobile.feature.plan.PlanViewModel
import net.mvexel.intervalswatch.mobile.feature.plan.PlannedWorkoutDetailViewModel
import net.mvexel.intervalswatch.mobile.feature.settings.SettingsViewModel
import net.mvexel.intervalswatch.mobile.feature.watch.RecordingsViewModel
import net.mvexel.intervalswatch.mobile.feature.watch.WatchScreensViewModel
import net.mvexel.intervalswatch.mobile.feature.watch.WatchViewModel
import net.mvexel.intervalswatch.shared.BuzzStrength
import net.mvexel.intervalswatch.shared.Samples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val workouts = Samples.workouts
    private val signedIn = SessionState(loaded = true, settings = Settings(token = "x"), workouts = workouts)

    @Test fun appViewModelStartsTheSessionAndForwardsRedirects() {
        val s = FakeSession()
        val vm = AppViewModel(s)
        vm.handleRedirect("net.mvexel.intervalswatch:/oauth?code=c")
        assertEquals(listOf("start", "redirect:net.mvexel.intervalswatch:/oauth?code=c"), s.calls)
    }

    @Test fun planListsWorkoutsInFetchOrderWithoutPrefs() {
        val vm = PlanViewModel(FakeSession(signedIn), FakePrefsDao())
        assertEquals(workouts.map { it.id }, vm.state.value.visible.map { it.workout.id })
        assertTrue(vm.state.value.hidden.isEmpty())
    }

    @Test fun pinMovesToTopAndPersists() {
        val dao = FakePrefsDao()
        val vm = PlanViewModel(FakeSession(signedIn), dao)
        val last = vm.state.value.visible.last()
        vm.togglePinned(last)
        assertEquals(last.workout.id, vm.state.value.visible.first().workout.id)
        assertTrue(vm.state.value.visible.first().pinned)
        assertTrue(dao.rows.value.getValue(last.workout.id).pinned)

        vm.togglePinned(vm.state.value.visible.first())
        assertEquals(workouts.map { it.id }, vm.state.value.visible.map { it.workout.id })
    }

    @Test fun pinnedKeepPinOrder() {
        val dao = FakePrefsDao()
        val vm = PlanViewModel(FakeSession(signedIn), dao)
        val (a, b) = vm.state.value.visible.takeLast(2)
        vm.togglePinned(b)
        vm.togglePinned(a)
        assertEquals(listOf(b.workout.id, a.workout.id), vm.state.value.visible.take(2).map { it.workout.id })
    }

    @Test fun hideMovesToHiddenAndResendsToWatch() {
        val session = FakeSession(signedIn)
        val dao = FakePrefsDao()
        val vm = PlanViewModel(session, dao)
        val first = vm.state.value.visible.first()
        vm.toggleHidden(first)
        assertFalse(vm.state.value.visible.any { it.workout.id == first.workout.id })
        assertEquals(listOf(first.workout.id), vm.state.value.hidden.map { it.workout.id })
        assertTrue("pushToWatch" in session.calls)
        assertTrue(dao.rows.value.getValue(first.workout.id).hidden)

        vm.toggleHidden(vm.state.value.hidden.single())
        assertTrue(vm.state.value.hidden.isEmpty())
        assertEquals(2, session.calls.count { it == "pushToWatch" })
    }

    @Test fun planAppliesStoredPrefsToANewSync() {
        val session = FakeSession(SessionState(loaded = true, settings = Settings(token = "x")))
        val dao = FakePrefsDao()
        val target = workouts.last().id
        dao.rows.value = mapOf(target to PlannedWorkoutPrefsEntity(target, hidden = true))
        val vm = PlanViewModel(session, dao)
        assertTrue(vm.state.value.visible.isEmpty())
        session.mutable.update { it.copy(workouts = workouts) }
        assertEquals(listOf(target), vm.state.value.hidden.map { it.workout.id })
        assertEquals(workouts.size - 1, vm.state.value.visible.size)
    }

    @Test fun plannedWorkoutDetailFindsByIdAndToggles() {
        val session = FakeSession(signedIn)
        val dao = FakePrefsDao()
        val id = workouts[1].id
        val vm = PlannedWorkoutDetailViewModel(id, session, dao)
        assertEquals(id, vm.state.value.item?.workout?.id)
        vm.togglePinned()
        assertTrue(vm.state.value.item!!.pinned)
        vm.toggleHidden()
        assertTrue(vm.state.value.item!!.hidden)
        assertTrue("pushToWatch" in session.calls)
    }

    @Test fun plannedWorkoutDetailForUnknownIdIsEmpty() {
        val vm = PlannedWorkoutDetailViewModel(-42, FakeSession(signedIn), FakePrefsDao())
        assertNull(vm.state.value.item)
        assertTrue(vm.state.value.loaded)
        vm.togglePinned() // no-op, no crash
    }

    @Test fun historyIsEmptyThenShowsStoredActivitiesNewestFirst() = runTest {
        val repo = InMemoryActivityRepository()
        val vm = HistoryViewModel(repo, compute = main.dispatcher)
        assertFalse(vm.state.value.loading)
        assertTrue(vm.state.value.activities.isEmpty())
        assertEquals(1, repo.refreshes)

        repo.put(activity("i1", "2026-11-01T07:00:00"))
        repo.put(activity("i2", "2026-11-03T07:00:00", ronde = true))
        assertEquals(listOf("i2", "i1"), vm.state.value.activities.map { it.id })
        assertTrue(vm.state.value.activities.first().isRonde)
    }

    /** Records the force flag of each refresh. */
    private class ForceRecordingRepo(val inner: InMemoryActivityRepository = InMemoryActivityRepository()) : ActivityRepository by inner {
        val forces = mutableListOf<Boolean>()
        override suspend fun refresh(force: Boolean) {
            forces += force
        }
    }

    @Test fun historyOpensWithAThrottledRefreshAndPullForcesOne() {
        val repo = ForceRecordingRepo()
        val vm = HistoryViewModel(repo, compute = main.dispatcher)
        vm.refresh()
        assertEquals(listOf(false, true), repo.forces)
        assertFalse(vm.state.value.refreshing)
    }

    @Test fun historyFillsRondeRingsFromCachedResultsOnly() {
        val repo = InMemoryActivityRepository()
        val steps = Samples.run.steps
        val asked = mutableListOf<String>()
        val cached = mutableMapOf("i2" to RunResult(steps, steps.map { Verdict.IN }))
        val vm = HistoryViewModel(repo, { a -> asked += a.id; cached[a.id] }, compute = main.dispatcher)
        repo.put(activity("i1", "2026-11-01T07:00:00"))
        repo.put(activity("i2", "2026-11-03T07:00:00", ronde = true))
        repo.put(activity("i3", "2026-11-04T07:00:00", ronde = true))
        val rows = vm.state.value.rows.associateBy { it.activity.id }
        assertNull(rows.getValue("i1").result) // not Ronde: never asked
        assertFalse("i1" in asked)
        assertEquals(cached["i2"], rows.getValue("i2").result)
        assertNull(rows.getValue("i3").result) // detail never opened: plain ring

        // i3's detail gets cached (opened meanwhile); History shown again picks it up, i2 comes from the memo.
        cached["i3"] = RunResult(steps, steps.map { null })
        val askedBefore = asked.count { it == "i2" }
        vm.recheckResults()
        assertEquals(cached["i3"], vm.state.value.rows.single { it.activity.id == "i3" }.result)
        assertEquals(askedBefore, asked.count { it == "i2" })
    }

    @Test fun watchMirrorsSessionAndSyncs() {
        val session = FakeSession(signedIn.copy(watches = listOf("Pixel Watch 3"), lastSynced = "07:12", status = "Sent 3"))
        val vm = WatchViewModel(session)
        assertEquals(listOf("Pixel Watch 3"), vm.state.value.watches)
        assertEquals("07:12", vm.state.value.lastSynced)
        vm.sync()
        session.mutable.update { it.copy(busy = true) }
        assertTrue(vm.state.value.busy)
        assertEquals(listOf("sync"), session.calls)
    }

    @Test fun recordingsLoadAndRetry() {
        val item = RecordingItem("1_a.fit", "Tempo", 1, 10, UploadState.FAILED, "x")
        val src = FakeRecordings(listOf(item))
        val vm = RecordingsViewModel(src)
        assertEquals(listOf(item), vm.items.value) // initial changes emission refreshes
        vm.retry(item)
        assertEquals(listOf("1_a.fit"), src.retried)
        src.items = emptyList()
        src.changes.value = 1
        assertTrue(vm.items.value.isEmpty())
    }

    @Test fun watchScreensEditsAndBuzzes() {
        val screens = FakeScreenSettings()
        var buzzed: BuzzStrength? = null
        val session = FakeSession(signedIn)
        val vm = WatchScreensViewModel(screens, { buzzed = it }, session)
        assertFalse(vm.state.value.watchConnected)
        session.mutable.update { it.copy(watches = listOf("w")) }
        assertTrue(vm.state.value.watchConnected)
        vm.edit { it.copy(buzzStrength = BuzzStrength.STRONG) }
        assertEquals(BuzzStrength.STRONG, vm.state.value.settings.buzzStrength)
        vm.testBuzz()
        assertEquals(BuzzStrength.STRONG, buzzed)
    }

    @Test fun settingsEditSaveAndLogout() {
        val session = FakeSession(signedIn)
        val vm = SettingsViewModel(session)
        vm.editSync(true)
        assertTrue(vm.state.value.editingSync)
        vm.save(vm.state.value.settings.copy(daysAhead = 14))
        assertFalse(vm.state.value.editingSync)
        assertEquals(14, session.saved?.daysAhead)
        vm.logout()
        assertFalse(vm.state.value.settings.configured)
    }

    @Test fun loginSavesApiKeyWithDefaultAthlete() {
        val session = FakeSession()
        val vm = LoginViewModel(session)
        vm.openApiKey()
        assertTrue(vm.state.value.apiKeyOpen)
        vm.saveApiKey("", "   ")
        assertNull(session.saved) // blank key is ignored
        vm.saveApiKey("", "key")
        assertEquals(Settings(athleteId = "0", token = "key", authType = AuthType.API_KEY), session.saved)
        assertFalse(vm.state.value.apiKeyOpen)
    }

    @Test fun loginShowsSessionStatus() {
        val session = FakeSession()
        val vm = LoginViewModel(session)
        session.showStatus("Login cancelled")
        assertEquals("Login cancelled", vm.state.value.status)
    }

    private fun activity(id: String, start: String, ronde: Boolean = false) =
        ActivityEntity(id, "A $id", "Run", start, isRonde = ronde, fetchedAt = 0)
}
