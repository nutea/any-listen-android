package io.github.nutea.anylisten

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nutea.anylisten.ui.ConnectUiState
import io.github.nutea.anylisten.ui.screens.ConnectScreen
import io.github.nutea.anylisten.ui.theme.AnyListenTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ConnectScreenTest {
    @get:Rule val compose = createComposeRule()

    // Exercises real Compose input and button semantics without a private server/session.
    @Test fun credentialsEnableLoginAndReachCallback() {
        var submitted: ConnectUiState? = null
        compose.setContent {
            var state by remember { mutableStateOf(ConnectUiState()) }
            AnyListenTheme {
                ConnectScreen(state, { state = state.copy(url = it) },
                    { state = state.copy(password = it) }, {}, { submitted = state })
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val login = compose.onNode(hasText(context.getString(R.string.connect_action)) and hasClickAction())
        val hello = compose.onNode(hasText(context.getString(R.string.connect_test)) and hasClickAction())
        login.assertIsNotEnabled()
        hello.assertIsNotEnabled()
        compose.onNodeWithContentDescription("Server URL").performTextInput("https://example.invalid")
        hello.assertIsEnabled()
        login.assertIsNotEnabled()
        compose.onNodeWithContentDescription("Password").performTextInput("test-only")
        login.assertIsEnabled().performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("https://example.invalid", submitted?.url)
            assertEquals("test-only", submitted?.password)
        }
    }

    @Test fun busyConnectionPreventsDuplicateRequestsAndShowsError() {
        compose.setContent {
            AnyListenTheme {
                ConnectScreen(ConnectUiState(url = "https://example.invalid", password = "test-only",
                    busy = true, error = "Connection unavailable"), {}, {}, {}, {})
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNode(hasText(context.getString(R.string.connect_action)) and hasClickAction()).assertIsNotEnabled()
        compose.onNode(hasText(context.getString(R.string.connect_test)) and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithText("Connection unavailable").assertIsDisplayed()
    }
}
