package com.opentasker.core.actions

import com.opentasker.core.actions.WorkspaceQuery.Item
import com.opentasker.core.actions.WorkspaceQuery.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a typed name, a typed filter and a long list are read — the parts of the remote commands with no database in them. */
class WorkspaceQueryTest {

    private val projects = listOf(Project(1, "携帯"), Project(2, "日本語 -- [227]"), Project(3, "Home"))

    private val tasks = listOf(
        Item(10, "起動", 1, true),
        Item(11, "起動", 2, true),
        Item(12, "中継 命令", 1, true),
        Item(13, "mail @ work", 3, false),
        Item(14, "loose", null, true),
    )

    private fun found(raw: String) = WorkspaceQuery.find("task", raw, tasks, projects)

    private fun WorkspaceQuery.Found.id() = (this as WorkspaceQuery.Found.One).item.id

    private fun WorkspaceQuery.Found.message() = (this as WorkspaceQuery.Found.Refused).message

    @Test
    fun theSpacesAroundTheAtAreOptional() {
        listOf("起動 @ 携帯", "起動@携帯", "起動  @携帯", " 起動 @ 携帯 ").forEach { raw ->
            assertEquals(raw, 10L, found(raw).id())
        }
        assertEquals(11L, found("起動 @ 日本語 -- [227]").id())
    }

    @Test
    fun aNameThatOccursTwiceIsRefusedWithItsProjects() {
        val message = found("起動").message()

        assertTrue(message, message.contains("携帯") && message.contains("日本語 -- [227]") && message.contains("@ project"))
    }

    @Test
    fun aNameWithItsOwnAtStaysWholeAndCaseDoesNotMatter() {
        assertEquals(13L, found("mail @ work").id())
        assertEquals(13L, found("MAIL @ WORK @ home").id())
        assertEquals(12L, found("中継 命令").id())
    }

    @Test
    fun aMissingNameSaysWhatIsCloseAndAMissingProjectSaysSo() {
        assertTrue(found("命令").message().contains("中継 命令"))
        assertEquals("no project is named 'nowhere'", found("中継 命令 @ nowhere").message())
        assertEquals("no task name given", found("  ").message())
        assertEquals(14L, found("loose @ unfiled").id())
    }

    @Test
    fun aFilterIsAProjectAWordOrBoth() {
        fun filter(raw: String) = WorkspaceQuery.filter(raw, projects).let { it.scope?.name to it.word }

        assertEquals(null to "", filter(""))
        assertEquals("携帯" to "", filter("携帯"))
        assertEquals("携帯" to "", filter("@携帯"))
        assertEquals("携帯" to "中継", filter("中継 @ 携帯"))
        assertEquals("日本語 -- [227]" to "島", filter("日本語 -- [227] 島"))
        assertEquals("Home" to "", filter("home"))
        assertEquals(null to "中継", filter("中継"))
        assertNull(WorkspaceQuery.filter("x @ nowhere", projects).scope)
    }

    @Test
    fun aListingGroupsByProjectAndCountsWhatItShows() {
        val profiles = listOf(Item(20, "見張り", 1, true), Item(21, "心拍", 1, false, needsReview = true))
        val all = WorkspaceQuery.Filter(null, "")

        val byProject = WorkspaceQuery.listing("projects", all, projects, tasks, profiles)
        assertEquals(4, byProject.count)
        assertEquals("携帯 — tasks 2 · profiles 2 (on 1)", byProject.lines.first())
        assertEquals("Unfiled — tasks 1 · profiles 0 (on 0)", byProject.lines.last())

        val shown = WorkspaceQuery.listing("profiles", all, projects, tasks, profiles)
        assertEquals(listOf("▸ 携帯 (2, on 1)", "● 見張り", "○ 心拍 (needs review)"), shown.lines)

        val narrowed = WorkspaceQuery.listing("tasks", WorkspaceQuery.Filter(null, "MAIL"), projects, tasks, profiles)
        assertEquals(listOf("▸ Home (1)", "· mail @ work (disabled)"), narrowed.lines)
    }

    @Test
    fun anAppIsFoundByPackageByNameOrByTheOnlyNameContainingTheWord() {
        val apps = listOf("shiroikuma.jami" to "白い熊 GNU Jami", "shiroikuma.messeji" to "白い熊 メッセージ", "a.b" to "Notes", "c.d" to "notes")
        fun pick(query: String) = AppPick.find(query, apps)

        assertEquals(AppPick.Found.One("shiroikuma.jami"), pick("shiroikuma.jami"))
        assertEquals(AppPick.Found.One("shiroikuma.jami"), pick(" jami "))
        assertEquals(AppPick.Found.One("shiroikuma.messeji"), pick("白い熊 メッセージ"))
        assertEquals(AppPick.Found.One("a.b"), pick("Notes"))
        assertTrue((pick("NOTES") as AppPick.Found.Refused).message.contains("2 apps match"))
        assertTrue((pick("白い熊") as AppPick.Found.Refused).message.contains("shiroikuma.messeji"))
        assertEquals("no installed app is named 'zzz'", (pick("zzz") as AppPick.Found.Refused).message)
    }

    @Test
    fun aLongListIsSplitAndNeverCutOff() {
        val lines = (1..40).map { "line number $it" }

        val pages = WorkspaceQuery.pages("head", lines, 200)

        assertTrue(pages.size > 1)
        assertTrue(pages.all { it.length <= 200 + "(10/10)\n".length })
        assertTrue(pages.first().startsWith("head\nline number 1\n"))
        assertTrue(pages[1].startsWith("(2/${pages.size})\n"))
        assertEquals(lines, pages.flatMap { it.lines() }.filter { it.startsWith("line") })
        assertEquals(listOf("head\none"), WorkspaceQuery.pages("head", listOf("one"), 200))
    }
}
