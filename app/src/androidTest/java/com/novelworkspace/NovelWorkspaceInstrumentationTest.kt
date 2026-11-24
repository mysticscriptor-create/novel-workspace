package com.novelworkspace

import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.novelworkspace.core.security.VaultConfiguration
import com.novelworkspace.core.sync.VaultSyncEngine
import com.novelworkspace.core.vault.InMemoryVaultGateway
import com.novelworkspace.data.database.AppDatabase
import com.novelworkspace.data.repositories.NovelRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NovelWorkspaceInstrumentationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun onboardingScreenRendersOnFirstLaunch() {
        composeRule.onNodeWithText("Novel Workspace").assertIsDisplayed()
        composeRule.onNodeWithText("Select Obsidian Vault Folder").assertIsDisplayed()
    }

    @Test
    fun roomIndexIncrementalSyncAndQueriesWorkOnApi28() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        val dao = db.vaultDao()
        val sync = VaultSyncEngine(dao)
        val repo = NovelRepository(dao, sync)

        val gw = InMemoryVaultGateway(
            mapOf(
                "Characters/Aria Vance.md" to "---\ntype: character\nname: Aria Vance\nrank: E+\n---\nScout in [[Ironhaven]]".toByteArray(),
                "000 A System/Traits/Iron Carapace.md" to "---\nrank: E+\nrank_value: 37\nlayer: Body\nstat_power: 8\n---\nDermal armor".toByteArray()
            )
        )
        val cfg = VaultConfiguration()
        val r1 = sync.syncIncremental(gw, cfg)
        assertEquals(2, r1.scannedFiles)
        assertEquals(2, r1.updatedFiles)

        val chars = repo.observeCharacters(flowOf(cfg)).first()
        assertEquals(1, chars.size)
        assertEquals("Aria Vance", chars.first().name)

        val traits = repo.observeTraits(flowOf(cfg)).first()
        assertEquals(1, traits.size)
        assertEquals(37.0, traits.first().rankValue, 0.01)

        // Incremental second scan skips unchanged files
        val r2 = sync.syncIncremental(gw, cfg)
        assertEquals(0, r2.updatedFiles)
        db.close()
    }

    @Test
    fun dataviewJsShimExecutesInAndroidWebView() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val shimJs = ctx.assets.open("runtime/obsidian_dataview_shim.js").bufferedReader().readText()
        val latch = CountDownLatch(1)
        var resultText = ""

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val wv = WebView(ctx)
            wv.settings.javaScriptEnabled = true
            val script = """
                window.__nwNativeBridge = { postMessage: function() {} };
                $shimJs
                window.__nwHydrateContext({
                  pages: [
                    { rank_value: 37, file: { name: 'Iron Carapace', path: '000 A System/Traits/Iron Carapace.md', folder: '000 A System/Traits', tags: ['#trait'] } },
                    { rank_value: 15, file: { name: 'Weak Trait', path: '000 A System/Traits/Weak.md', folder: '000 A System/Traits', tags: ['#trait'] } }
                  ],
                  abstractFiles: [],
                  localStorage: {}
                });
                const res = dv.pages('"000 A System/Traits"').where(p => p.rank_value >= 30).sort(p => p.rank_value, 'desc');
                window.__testResult = res.length + ':' + res[0].file.name;
            """.trimIndent()
            wv.evaluateJavascript(script) {
                wv.evaluateJavascript("window.__testResult") { v ->
                    resultText = v.trim('"')
                    latch.countDown()
                }
            }
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals("1:Iron Carapace", resultText)
    }
}
