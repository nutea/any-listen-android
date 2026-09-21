package io.github.nutea.anylisten

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nutea.anylisten.core.data.repo.CommentRepository
import io.github.nutea.anylisten.core.data.gateway.MusicComments
import io.github.nutea.anylisten.core.model.*
import io.github.nutea.anylisten.ui.screens.CommentsSheet
import io.github.nutea.anylisten.ui.theme.AnyListenTheme
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test

class CommentsFeatureTest {
    @get:Rule val compose = createComposeRule()
    private fun label(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private val track = Track(TrackIdentity("fixture", "comments"), "评论测试歌曲", "歌手", "", 60000)
    @Test fun switchesSortPagesAndRetries() {
        var fail = true
        val service = MusicComments { method, args ->
            when (method) {
                "getResourceList" -> Json.parseToJsonElement("""{"resources":{"musicComment":[{"id":"source","extensionId":"extension","name":"测试音源"}]}}""")
                "findMusic" -> Json.parseToJsonElement("""{"id":"matched"}""")
                else -> {
                    if (fail) { fail = false; error("fixture failure") }
                    val p = args.single().jsonObject
                    val page = p["page"]!!.jsonPrimitive.int
                    val type = p["type"]!!.jsonPrimitive.content
                    Json.parseToJsonElement("""{"list":[{"id":"$page","userName":"听众","text":"$type page $page","reply":[{"id":"reply","userName":"回复者","text":"一起听歌"}]}],"total":21,"page":$page,"limit":20}""")
                }
            }
        }
        compose.setContent { AnyListenTheme { CommentsSheet(track, CommentRepository(service)) {} } }
        compose.onNodeWithText(label(R.string.comments_retry)).performClick()
        compose.onNodeWithText("hot page 1").assertExists()
        compose.onNodeWithText("一起听歌").assertExists()
        compose.onNodeWithText(label(R.string.comments_next)).performClick()
        compose.onNodeWithText("hot page 2").assertExists()
        compose.onNodeWithText(label(R.string.comments_next)).assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.comments_new)).performClick()
        compose.onNodeWithText("new page 1").assertExists()
        compose.onNodeWithContentDescription(label(R.string.comments_refresh)).performClick()
        compose.onNodeWithText("new page 1").assertExists()
    }
    @Test fun emptySecondPageCanReturnToFirst() = secondPageCanReturn(false)
    @Test fun failedSecondPageCanReturnToFirst() = secondPageCanReturn(true)

    private fun secondPageCanReturn(fails: Boolean) {
        val service = MusicComments { method, args ->
            when (method) {
                "getResourceList" -> Json.parseToJsonElement("""{"resources":{"musicComment":[{"id":"source","extensionId":"extension","name":"测试音源"}]}}""")
                "findMusic" -> Json.parseToJsonElement("""{"id":"matched"}""")
                else -> {
                    val page = args.single().jsonObject["page"]!!.jsonPrimitive.int
                    if (page == 2 && fails) error("fixture page failure")
                    if (page == 2) Json.parseToJsonElement("""{"list":[],"total":21,"page":2,"limit":20}""")
                    else Json.parseToJsonElement("""{"list":[{"id":"first","text":"first page"}],"total":21,"page":1,"limit":20}""")
                }
            }
        }
        compose.setContent { AnyListenTheme { CommentsSheet(track, CommentRepository(service)) {} } }
        compose.onNodeWithText("first page").assertExists()
        compose.onNodeWithText(label(R.string.comments_next)).performClick()
        compose.onNodeWithText(label(R.string.comments_previous)).assertIsEnabled().performClick()
        compose.onNodeWithText("first page").assertExists()
    }

    @Test fun noSourceHasAnExplicitMessage() {
        compose.setContent { AnyListenTheme { CommentsSheet(track, CommentRepository(MusicComments { _, _ -> JsonObject(emptyMap()) })) {} } }
        compose.onNodeWithText(label(R.string.comments_no_source)).assertExists()
    }
}
