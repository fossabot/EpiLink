/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * This Source Code Form is "Incompatible With Secondary Licenses", as
 * defined by the Mozilla Public License, v. 2.0.
 */
package org.epilink.bot.discord

import org.epilink.bot.config.LinkDiscordConfig
import org.epilink.bot.config.isMonitored
import org.epilink.bot.db.AdminStatus.*
import org.epilink.bot.db.AdminStatus.NotAdmin
import org.epilink.bot.db.LinkDatabaseFacade
import org.epilink.bot.db.LinkPermissionChecks
import org.epilink.bot.db.LinkUser
import org.epilink.bot.db.UsesTrueIdentity
import org.epilink.bot.debug
import org.epilink.bot.discord.MessageAcceptStatus.*
import org.koin.core.component.KoinApiExtension
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import org.koin.core.qualifier.named
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Component for receiving and handling Discord commands
 */
interface LinkDiscordCommands {
    /**
     * Handle the given message:
     *
     * - Determine if it should be ran or not
     * - Run it if everything looks good
     */
    suspend fun handleMessage(message: String, senderId: String, channelId: String, serverId: String?)
}

/**
 * Indication on whether a message should be accepted or not
 */
sealed class MessageAcceptStatus {
    /**
     * The message is not a command and should therefore not be accepted. Should be silent.
     */
    object NotACommand : MessageAcceptStatus()

    /**
     * The sender is not an admin even though the command requires admin status and the message should therefore not be
     * accepted.
     */
    object NotAdmin : MessageAcceptStatus()

    /**
     * The sender is not registered and the message should therefore not be accepted
     */
    object NotRegistered : MessageAcceptStatus()

    /**
     * The sender is an admin but does not have their identity recorded and the message should therefore not be accepted
     */
    object AdminNoIdentity : MessageAcceptStatus()

    /**
     * The server the message was sent on is not monitored when the command requires a monitored server and the message
     * should therefore not be accepted.
     */
    object ServerNotMonitored : MessageAcceptStatus()

    /**
     * The command with the given name does not exist
     *
     * @property name The name that doesn't exist
     */
    class UnknownCommand(val name: String) : MessageAcceptStatus()

    /**
     * The message should be accepted.
     *
     * @property user The sender, as a [LinkUser] database object.
     * @property command The command
     * @property commandBody The command body
     */
    class Accept(val user: LinkUser?, val command: Command, val commandBody: String) : MessageAcceptStatus()
}

/**
 * The level of permission required to run the command
 */
enum class PermissionLevel {
    /**
     * The user must be a registered admin
     */
    Admin,

    /**
     * The user must be registered
     */
    User,

    /**
     * Anyone can use this, including unregistered users
     */
    Anyone
}

/**
 * An EpiLink Discord command
 */
interface Command {
    /**
     * The name of the command
     */
    val name: String

    /**
     * The level of permission required to use this command
     */
    val permissionLevel: PermissionLevel

    /**
     * True if the command can only be ran in monitored servers, false if it can be ran anywhere where EpiLink can see
     * it (including DMs)
     */
    val requireMonitoredServer: Boolean

    /**
     * Run the command
     *
     * @param fullCommand The full command message. Contains the prefix and the command name
     * @param commandBody The body of the command: that is, the command message without the prefix and command name. For
     * example, if the full command is `"e!hello world hi"`, commandBody would be `"world hi"`.
     * @param sender The "sender", the user who sent the command, or null if [permissionLevel] is set to
     * [Anyone][PermissionLevel.Anyone]
     * @param channelId The ID of the channel where the message was sent.
     * @param guildId The ID of the guild where the message was sent, or null if not on a monitored guild and
     * [requireMonitoredServer] is false. If not null, this guild is monitored if [requireMonitoredServer]
     * is true.
     */
    suspend fun run(
        fullCommand: String,
        commandBody: String,
        sender: LinkUser?,
        senderId: String,
        channelId: String,
        guildId: String?
    )
}

@OptIn(KoinApiExtension::class)
internal class LinkDiscordCommandsImpl : LinkDiscordCommands, KoinComponent {
    private val discordCfg: LinkDiscordConfig by inject()
    private val admins: List<String> by inject(named("admins"))
    private val db: LinkDatabaseFacade by inject()
    private val permission: LinkPermissionChecks by inject()
    private val client: LinkDiscordClientFacade by inject()
    private val msg: LinkDiscordMessages by inject()
    private val logger = LoggerFactory.getLogger("epilink.discord.cmd")
    private val i18n: LinkDiscordMessagesI18n by inject()

    private val commands by lazy {
        get<List<Command>>(named("discord.commands")).associateBy { it.name }
    }

    override suspend fun handleMessage(message: String, senderId: String, channelId: String, serverId: String?) {
        when (val a = shouldAcceptMessage(message, senderId, serverId)) {
            NotACommand -> {
                // return silently
            }
            MessageAcceptStatus.NotAdmin ->
                client.sendChannelMessage(channelId, msg.getErrorCommandReply(i18n.getLanguage(senderId), "cr.nan"))
                    .also { logger.debugReject("not an admin", message, senderId, channelId, serverId) }
            NotRegistered ->
                client.sendChannelMessage(channelId, msg.getErrorCommandReply(i18n.getLanguage(senderId), "cr.nr"))
                    .also { logger.debugReject("not registered", message, senderId, channelId, serverId) }
            AdminNoIdentity ->
                client.sendChannelMessage(channelId, msg.getErrorCommandReply(i18n.getLanguage(senderId), "cr.awni"))
                    .also { logger.debugReject("admin with no identity", message, senderId, channelId, serverId) }
            ServerNotMonitored ->
                client.sendChannelMessage(channelId, msg.getErrorCommandReply(i18n.getLanguage(senderId), "cr.snm"))
                    .also { logger.debugReject("server not monitored", message, senderId, channelId, serverId) }
            is UnknownCommand ->
                client.sendChannelMessage(
                    channelId,
                    msg.getErrorCommandReply(i18n.getLanguage(senderId), "cr.ic", a.name, titleObjects = listOf(a.name))
                ).also { logger.debugReject("unknown command", message, senderId, channelId, serverId) }
            is Accept -> {
                // Do the thing
                a.command.run(message, a.commandBody, a.user, senderId, channelId, serverId).also {
                    logger.debug {
                        "Accepted command '$message' from $senderId @ channel $channelId server $serverId (command name '${a.command.name}' body '${a.commandBody}')"
                    }
                }
            }
        }
    }

    // TODO put that logic in a separate class and test it independently
    @OptIn(UsesTrueIdentity::class)
    private suspend fun shouldAcceptMessage(message: String, senderId: String, serverId: String?): MessageAcceptStatus {
        if (!message.startsWith(discordCfg.commandsPrefix))
            return NotACommand
        // Get the command
        val withoutPrefix = message.substring(discordCfg.commandsPrefix.length)
        val (name, body) = withoutPrefix.split(' ', limit = 2).coerceAtLeast(2, "")
        val command = commands[name] ?: return UnknownCommand(name)
        if (command.requireMonitoredServer && (serverId == null || !discordCfg.isMonitored(serverId)))
            return ServerNotMonitored
        return when (command.permissionLevel) {
            PermissionLevel.Admin -> {
                // Quick admin check (avoids checking the database for a user object for obviously-not-an-admin cases)
                if (senderId !in admins)
                    return MessageAcceptStatus.NotAdmin
                val user = db.getUser(senderId) ?: return NotRegistered
                when (permission.canPerformAdminActions(user)) {
                    NotAdmin -> MessageAcceptStatus.NotAdmin
                    AdminNotIdentifiable -> AdminNoIdentity
                    Admin -> {
                        Accept(user, command, body)
                    }
                }
            }
            PermissionLevel.User -> {
                val user = db.getUser(senderId) ?: return NotRegistered
                // TODO add check if user is banned (right now not a huge problem)
                Accept(user, command, body)
            }
            PermissionLevel.Anyone -> Accept(db.getUser(senderId), command, body)
        }
    }
}

private fun Logger.debugReject(
    reason: String,
    message: String,
    senderId: String,
    channelId: String,
    serverId: String?
) = debug { "Rejecting $message from $senderId @ channel $channelId server $serverId because $reason" }

private fun <E> List<E>.coerceAtLeast(i: Int, e: E): List<E> =
    if (size >= i) {
        this
    } else {
        val list = ArrayList<E>(this)
        repeat(i - size) {
            list += e
        }
        list
    }

