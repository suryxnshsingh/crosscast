package dev.suryansh.airspeaker.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.suryansh.airspeaker.core.DeviceIds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun TestScope.repository() = SettingsRepository(
        PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.root.resolve("settings.preferences_pb") }
    )

    @Test
    fun `device id is generated once and then stable`() = runTest {
        val repo = repository()
        val first = repo.identity.first()
        assertTrue(DeviceIds.isValid(first.deviceId))
        assertEquals(first.deviceId, repo.identity.first().deviceId)
    }

    @Test
    fun `friendly name defaults and updates without changing the id`() = runTest {
        val repo = repository()
        val before = repo.identity.first()
        assertEquals("Room Speaker", before.friendlyName)

        repo.setFriendlyName("  Desk  ")
        val after = repo.identity.first()
        assertEquals("Desk", after.friendlyName)
        assertEquals(before.deviceId, after.deviceId)

        repo.setFriendlyName(" ")
        assertEquals("Room Speaker", repo.identity.first().friendlyName)
    }
}
