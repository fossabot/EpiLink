/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * This Source Code Form is "Incompatible With Secondary Licenses", as
 * defined by the Mozilla Public License, v. 2.0.
 */
package org.epilink.bot.discord

import io.mockk.*
import kotlinx.coroutines.delay
import org.epilink.bot.KoinBaseTest
import org.epilink.bot.db.LinkUser
import org.epilink.bot.web.declareNoOpI18n
import org.epilink.bot.discord.*
import org.epilink.bot.discord.cmd.UpdateCommand
import org.epilink.bot.mockHere
import org.epilink.bot.softMockHere
import org.koin.dsl.module
import kotlin.test.*

class UpdateCommandTest : KoinBaseTest<Command>(
    Command::class,
    module {
        single<Command> { UpdateCommand() }
    }
) {
    @Test
    fun `Test wrong target cannot be parsed`() {
        mockHere<LinkDiscordTargets> { every { parseDiscordTarget("HELLO THERE") } returns TargetParseResult.Error }
        val embed = mockk<DiscordEmbed>()
        declareNoOpI18n()
        mockHere<LinkDiscordMessages> { every { getWrongTargetCommandReply(any(), "HELLO THERE") } returns embed }
        val f = mockHere<LinkDiscordClientFacade> { coEvery { sendChannelMessage("channel", embed) } returns "" }
        test {
            run(
                fullCommand = "",
                commandBody = "HELLO THERE",
                // Unused
                sender = mockk(),
                senderId = "",
                channelId = "channel",
                guildId = ""
            )
        }
        coVerify { f.sendChannelMessage("channel", embed) }
    }

    @Test
    fun `Test non existent role update`() {
        val parsedTarget = TargetParseResult.Success.RoleByName("Rooole")
        val embed = mockk<DiscordEmbed>()
        mockHere<LinkDiscordTargets> {
            every { parseDiscordTarget("HELLO I AM COMMAND BODY") } returns parsedTarget
            coEvery { resolveDiscordTarget(parsedTarget, "guild") } returns TargetResult.RoleNotFound("Rooole")
        }
        declareNoOpI18n()
        mockHere<LinkDiscordMessages> {
            every {
                getWrongTargetCommandReply(
                    any(),
                    "HELLO I AM COMMAND BODY"
                )
            } returns embed
        }
        val f = mockHere<LinkDiscordClientFacade> { coEvery { sendChannelMessage("channel", embed) } returns "" }
        test {
            run(
                fullCommand = "",
                commandBody = "HELLO I AM COMMAND BODY",
                sender = +"user",
                senderId = "user",
                channelId = "channel",
                guildId = "guild" // Unused
            )
        }
        coVerify { f.sendChannelMessage("channel", embed) }
    }

    @Test
    fun `Test role update`() {
        // Target resolution
        val parsedTarget = TargetParseResult.Success.RoleByName("Rooole")
        mockHere<LinkDiscordTargets> {
            every { parseDiscordTarget("HELLO I AM COMMAND BODY") } returns parsedTarget
            coEvery { resolveDiscordTarget(parsedTarget, "guild") } returns TargetResult.Role("Rooole ID")
        }
        // Role update
        val fakeList = listOf("a", "b", "c")
        mockHere<LinkDiscordClientFacade> {
            coEvery { getMembersWithRole("Rooole ID", "guild") } returns fakeList
        }
        val rm = mockHere<LinkRoleManager> {
            coEvery { invalidateAllRoles(any()) } just runs
        }
        // Message reply
        val embed = mockk<DiscordEmbed>()
        declareNoOpI18n()
        mockHere<LinkDiscordMessages> { every { getSuccessCommandReply(any(), any(), "Rooole ID", 3) } returns embed }
        val f = softMockHere<LinkDiscordClientFacade> {
            // (soft mock because already defined above)
            coEvery { sendChannelMessage("channel", embed) } returns "sentMessage"
            coEvery { addReaction("channel", "sentMessage", "✅") } just runs
        }
        test {
            run(
                fullCommand = "",
                commandBody = "HELLO I AM COMMAND BODY",
                sender = +"user",
                senderId = "user",
                channelId = "channel",
                guildId = "guild" // unused
            )
            delay(2000)
        }
        coVerify {
            f.sendChannelMessage("channel", embed)
            rm.invalidateAllRoles("a")
            rm.invalidateAllRoles("b")
            rm.invalidateAllRoles("c")
            f.addReaction("channel", "sentMessage", "✅")
        }
    }

    @Test
    fun `Test user update`() {
        // Target resolution
        val parsedTarget = TargetParseResult.Success.UserById("targetid")
        mockHere<LinkDiscordTargets> {
            every { parseDiscordTarget("HELLO I AM COMMAND BODY") } returns parsedTarget
            coEvery { resolveDiscordTarget(parsedTarget, "guild") } returns TargetResult.User("targetid")
        }
        val rm = mockHere<LinkRoleManager> {
            coEvery { invalidateAllRoles("targetid") } just runs
        }
        // Message reply
        val embed = mockk<DiscordEmbed>()
        declareNoOpI18n()
        mockHere<LinkDiscordMessages> { every { getSuccessCommandReply(any(), any()) } returns embed }
        val f = mockHere<LinkDiscordClientFacade> {
            coEvery { sendChannelMessage("channel", embed) } returns "sentMessage"
            coEvery { addReaction("channel", "sentMessage", "✅") } just runs
        }
        test {
            run(
                fullCommand = "",
                commandBody = "HELLO I AM COMMAND BODY",
                sender = +"user",
                senderId = "user",
                channelId = "channel",
                guildId = "guild" // unused
            )
            delay(2000)
        }
        coVerify {
            f.sendChannelMessage("channel", embed)
            f.addReaction("channel", "sentMessage", "✅")
            rm.invalidateAllRoles("targetid")
        }
    }

    @Test
    fun `Test everyone update`() {
        // Target resolution
        val parsedTarget = TargetParseResult.Success.Everyone
        mockHere<LinkDiscordTargets> {
            every { parseDiscordTarget("HELLO I AM COMMAND BODY") } returns parsedTarget
            coEvery { resolveDiscordTarget(parsedTarget, "guild") } returns TargetResult.Everyone
        }
        // Role update
        val fakeList = listOf("a", "b", "c")
        mockHere<LinkDiscordClientFacade> {
            coEvery { getMembers("guild") } returns fakeList
        }
        val rm = mockHere<LinkRoleManager> {
            coEvery { invalidateAllRoles(any()) } just runs
        }
        // Message reply
        val embed = mockk<DiscordEmbed>()
        declareNoOpI18n()
        mockHere<LinkDiscordMessages> { every { getSuccessCommandReply(any(), any(), 3) } returns embed }
        val f = softMockHere<LinkDiscordClientFacade> { // (soft mock because already defined above)
            coEvery { sendChannelMessage("channel", embed) } returns "sentMessage"
            coEvery { addReaction("channel", "sentMessage", "✅") } just runs
        }
        test {
            run(
                fullCommand = "",
                commandBody = "HELLO I AM COMMAND BODY",
                sender = +"user",
                senderId = "user",
                channelId = "channel",
                guildId = "guild" // unused
            )
            delay(2000)
        }
        coVerify {
            f.sendChannelMessage("channel", embed)
            rm.invalidateAllRoles("a")
            rm.invalidateAllRoles("b")
            rm.invalidateAllRoles("c")
            f.addReaction("channel", "sentMessage", "✅")
        }
    }

    @Test
    fun `Test one update crash does not crash everything else`() {
        // Target resolution
        val parsedTarget = TargetParseResult.Success.Everyone
        mockHere<LinkDiscordTargets> {
            every { parseDiscordTarget("HELLO I AM COMMAND BODY") } returns parsedTarget
            coEvery { resolveDiscordTarget(parsedTarget, "guild") } returns TargetResult.Everyone
        }
        // Role update
        val fakeList = List(26) { ('a' + it).toString() }
        mockHere<LinkDiscordClientFacade> {
            coEvery { getMembers("guild") } returns fakeList
        }
        var eFailed = false
        val rm = mockHere<LinkRoleManager> {
            coEvery { invalidateAllRoles(any()) } just runs
            coEvery { invalidateAllRoles("e") } answers { eFailed = true; error("oh no") }
        }
        // Message reply
        val embed = mockk<DiscordEmbed>()
        declareNoOpI18n()
        mockHere<LinkDiscordMessages> { every { getSuccessCommandReply(any(), any(), 26) } returns embed }
        val f = softMockHere<LinkDiscordClientFacade> { // (soft mock because already defined above)
            coEvery { sendChannelMessage("channel", embed) } returns "sentMessage"
            coEvery { addReaction("channel", "sentMessage", "✅") } just runs
            coEvery { addReaction("channel", "sentMessage", "⚠") } just runs
        }
        test {
            run(
                fullCommand = "",
                commandBody = "HELLO I AM COMMAND BODY",
                sender = +"user",
                senderId = "user",
                channelId = "channel",
                guildId = "guild" // unused
            )
            delay(2000)
        }
        assertTrue(eFailed, "The e discordId did not throw")
        coVerify {
            f.sendChannelMessage("channel", embed)
            for (c in 'a'..'z')
                rm.invalidateAllRoles(c.toString())
            f.addReaction("channel", "sentMessage", "✅")
            f.addReaction("channel", "sentMessage", "⚠")
        }
    }

    private operator fun String.unaryPlus(): LinkUser = mockk {
        every { discordId } returns this@unaryPlus
    }
}
