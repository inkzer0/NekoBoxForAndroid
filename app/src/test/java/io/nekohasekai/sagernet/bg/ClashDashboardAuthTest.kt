package io.nekohasekai.sagernet.bg

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class, manifest = Config.NONE)
class ClashDashboardAuthTest {
    private fun protect(controller: String, secret: Any?, managed: Boolean): JSONObject {
        val options = JSONObject().put("external_controller", controller)
            .put("default_mode", "Rule").put("secret", secret)
        val input = JSONObject().put("experimental", JSONObject().put("clash_api", options))
        return JSONObject(ClashDashboardAuth.protectRuntime(input.toString(), managed) {
            assertTrue(managed)
            assertEquals(ClashDashboardAuth.CONTROLLER, controller)
            "test-credential"
        }).getJSONObject("experimental").getJSONObject("clash_api")
    }

    @Test fun managedControllerHasAppAuthenticationAndRestrictedOrigin() {
        val result = protect(ClashDashboardAuth.CONTROLLER, "", true)
        assertEquals("test-credential", result.getString("secret"))
        assertEquals(ClashDashboardAuth.ORIGIN, result.getJSONArray("access_control_allow_origin").getString(0))
        assertFalse(result.getBoolean("access_control_allow_private_network"))
    }

    @Test fun unauthenticatedCustomListenerIsNotEnabled() {
        for (secret in listOf(null, JSONObject.NULL, "", " ")) {
            val result = protect("127.0.0.1:9091", secret, false)
            assertFalse(result.has("external_controller"))
            assertEquals("Rule", result.getString("default_mode"))
        }
    }

    @Test fun authenticatedCustomListenerRetainsItsOwnCredentials() {
        val result = protect("127.0.0.1:9091", "custom-credential", true)
        assertEquals("127.0.0.1:9091", result.getString("external_controller"))
        assertEquals("custom-credential", result.getString("secret"))
    }
}
