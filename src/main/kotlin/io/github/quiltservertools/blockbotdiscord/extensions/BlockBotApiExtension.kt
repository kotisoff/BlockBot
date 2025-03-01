package io.github.quiltservertools.blockbotdiscord.extensions

import com.kotlindiscord.kord.extensions.checks.inGuild
import com.kotlindiscord.kord.extensions.checks.isNotBot
import com.kotlindiscord.kord.extensions.extensions.Extension
import com.kotlindiscord.kord.extensions.extensions.event
import com.kotlindiscord.kord.extensions.utils.ensureWebhook
import com.kotlindiscord.kord.extensions.utils.getTopRole
import com.kotlindiscord.kord.extensions.utils.hasPermission
import com.vdurmont.emoji.EmojiParser
import dev.kord.common.entity.AllowedMentionType
import dev.kord.common.entity.Permission
import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.behavior.execute
import dev.kord.core.entity.Member
import dev.kord.core.entity.Message
import dev.kord.core.entity.Webhook
import dev.kord.core.entity.channel.TopGuildMessageChannel
import dev.kord.core.event.message.MessageCreateEvent
import dev.kord.rest.builder.message.AllowedMentionsBuilder
import dev.kord.rest.builder.message.EmbedBuilder
import dev.kord.rest.builder.message.embed
import dev.vankka.mcdiscordreserializer.discord.DiscordSerializer
import dev.vankka.mcdiscordreserializer.discord.DiscordSerializerOptions
import dev.vankka.mcdiscordreserializer.minecraft.MinecraftSerializer
import dev.vankka.mcdiscordreserializer.minecraft.MinecraftSerializerOptions
import io.github.quiltservertools.blockbotapi.Bot
import io.github.quiltservertools.blockbotapi.Channels
import io.github.quiltservertools.blockbotapi.event.RelayMessageEvent
import io.github.quiltservertools.blockbotapi.sender.MessageSender
import io.github.quiltservertools.blockbotapi.sender.PlayerMessageSender
import io.github.quiltservertools.blockbotapi.sender.RelayMessageSender
import io.github.quiltservertools.blockbotdiscord.BlockBotDiscord
import io.github.quiltservertools.blockbotdiscord.MentionToMinecraftRenderer
import io.github.quiltservertools.blockbotdiscord.config.*
import io.github.quiltservertools.blockbotdiscord.utility.*
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.drex.vanish.api.VanishEvents
import net.fabricmc.loader.api.FabricLoader
import net.kyori.adventure.text.KeybindComponent
import net.kyori.adventure.text.NBTComponent
import net.kyori.adventure.text.TranslatableComponent
import net.minecraft.advancement.Advancement
import net.minecraft.client.item.TooltipContext
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.nbt.NbtElement
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtList
import net.minecraft.nbt.NbtOps
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.*
import net.minecraft.util.ActionResult
import net.minecraft.util.Formatting
import net.minecraft.util.math.MathHelper.clamp
import org.koin.core.component.inject
import java.net.URL
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.max


class BlockBotApiExtension : Extension(), Bot {
    override val name = "BlockBot Api Impl"
    private lateinit var chatWebhook: Webhook

    private val minecraftSerializer = MinecraftSerializer(
        MinecraftSerializerOptions.defaults()
            .addRenderer(MentionToMinecraftRenderer(bot))
    )
    private val discordSerializer = DiscordSerializer(
        DiscordSerializerOptions.defaults().withKeybindProvider { Text.translatable(it.keybind()).string }
    )
    private val server: MinecraftServer by inject()
    private val mentions = AllowedMentionsBuilder()

    override suspend fun setup() {
        val channel = config.getChannel(Channels.CHAT, bot)

        chatWebhook = ensureWebhook(channel as TopGuildMessageChannel, config[ChatRelaySpec.WebhookSpec.webhookName])
        mentions.add(AllowedMentionType.UserMentions)
        mentions.roles.addAll(config.getGuild(bot).roles.filter { it.mentionable }.map { it.id }
            .toList())

        event<MessageCreateEvent> {
            check { isNotBot() }
            check { inGuild(Snowflake(config[BotSpec.guild])) }
            check { failIfNot(config.getChannelsBi().containsValue(event.message.channelId.value)) }

            action {
                val sender = event.message.getAuthorAsMemberOrNull() ?: return@action
                val configChannel = config.getChannelsBi().inverse()[event.message.channelId.value]!!
                val result = RelayMessageEvent.EVENT.invoker().message(
                    RelayMessageSender(
                        sender.username,
                        sender.nickname,
                        sender.tag,
                        sender.hasPermission(Permission.Administrator)
                    ),
                    configChannel,
                    event.message.content
                )

                if (result == ActionResult.PASS) {
                    if (configChannel == Channels.CHAT) {
                        val messages = getChatMessage(sender, event.message)

                        server.submit {
                            for (message in messages) {
                                server.playerManager.broadcast(
                                    message,
                                    false
                                )
                            }
                        }
                    }
                }
            }
        }

        // Vanish fake join/leave messages
        if (FabricLoader.getInstance().isModLoaded("melius-vanish")) {
            VanishEvents.VANISH_EVENT.register {player, vanished ->
                if (vanished) sendPlayerLeaveMessage(player)
                else sendPlayerJoinMessage(player)
            }
        }

        // Send server started message if the server has already started. isLoading should be named isRunning
        if (server.isLoading) onServerStart(server)
    }

    public suspend fun getChatMessage(sender: Member, message: Message): List<Text> {
        val emojiString = EmojiParser.parseToAliases(message.content)
        var content: MutableText =
            if (config[ChatRelaySpec.convertMarkdown]) minecraftSerializer.serialize(emojiString)
                .toNative(server.registryManager) else emojiString.literal()
        content = convertEmojiToTranslatable(content)
        if (message.referencedMessage != null) {
            val reply = config.getReplyMsg(
                message.referencedMessage!!.getAuthorAsMemberOrNull()?.effectiveName ?: message.referencedMessage!!.data.author.username,
                message.referencedMessage!!,
                server
            )
            content = "".literal().append(reply).append("\n").append(content)
        }

        val attachments = mutableListOf<Text>()

        if (message.attachments.isNotEmpty()) {
            val appendImages = config[ChatRelaySpec.MinecraftFormatSpec.appendImages]
            val interpolateImages = config[ChatRelaySpec.MinecraftFormatSpec.imageInterpolation]

            for (attachment in message.attachments) {
                var hoverEvent: HoverEvent? = null

                if (appendImages && attachment.isImage && attachment.size < 8 * 1024 * 1024) {
                    // The entire message (serialized in json) can at most be 65535 bytes,
                    // see DataOutputStream.writeUTF(String str, DataOutput out)

                    // Each pixel adds ~40 characters to the final string
                    // We want to use 64000 characters for the message lore. So we can have at most 1600 pixels.
                    // The easiest implementation is to just limit the width and height to 40 pixels.
                    val image = ImageIO.read(URL(attachment.data.proxyUrl))

                    val stepSizeWidth = (ceil(image.width.toDouble() / 40).toInt()).coerceAtLeast(1)
                    val stepSizeHeight = (ceil(image.height.toDouble() / 40).toInt()).coerceAtLeast(1)
                    val stepSize = max(stepSizeWidth, stepSizeHeight)
                    val stepSquared = stepSize * stepSize
                    val width = image.width
                    val height = image.height

                    var x = 0
                    var y = 0

                    val list = mutableListOf<Text>()

                    while (y < height) {
                        val text = Text.empty().setStyle(Style.EMPTY.withItalic(false))
                        while (x < width) {
                            var rgb: Int

                            if (interpolateImages && stepSize != 1) {
                                var r = 0
                                var g = 0
                                var b = 0

                                for (x2 in 0 until stepSize) {
                                    for (y2 in 0 until stepSize) {
                                        val color =
                                            image.getRGB(clamp(x + x2, 0, width - 1), clamp(y + y2, 0, height - 1))
                                        r += color.and(0xff0000).shr(16)
                                        g += color.and(0x00ff00).shr(8)
                                        b += color.and(0x0000ff)
                                    }
                                }

                                rgb = r / stepSquared
                                rgb = (rgb.shl(8)) + g / stepSquared
                                rgb = (rgb.shl(8)) + b / stepSquared
                            } else {
                                rgb = image.getRGB(x, y).and(0xffffff)
                            }
                            val pixel = Text.literal("█").setStyle(Style.EMPTY.withColor(rgb))
                            text.append(pixel)
                            x += stepSize
                        }

                        list.add(text)
                        y += stepSize
                        x = 0
                    }

                    val stack = ItemStack(Items.STICK)
                    stack.setCustomName(Text.empty())
                    stack.item.appendTooltip(stack, null, list, TooltipContext.ADVANCED)
                    hoverEvent = HoverEvent(HoverEvent.Action.SHOW_ITEM, HoverEvent.ItemStackContent(stack))
                }

                attachments.add("[${attachment.filename}]".literal().styled {
                    it.withColor(Formatting.BLUE)
                        .withClickEvent(ClickEvent(ClickEvent.Action.OPEN_URL, attachment.url))
                        .withHoverEvent(hoverEvent)
                })
            }
        }

        for (stickerItem in message.stickers) {
            val sticker = stickerItem.getSticker()
            attachments.add("[Sticker: ${sticker.name}]".literal().styled {
                it.withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, (sticker.description ?: "").literal()))
            })
        }

        val topRole = sender.getTopRole()
        val topColor = sender.getDisplayColor()
        var topRoleMessage: MutableText =
            topRole?.data?.name?.literal() ?: "".literal()
        if (topColor != null) topRoleMessage = topRoleMessage.styled { it.withColor(topColor.rgb) }
        var username: MutableText = sender.effectiveName.literal()
        if (topColor != null) {
            username = username.styled {
                it.withColor(topColor.rgb)
            }
        }

        return listOf(
            config.getMinecraftChatRelayMsg(username, topRoleMessage, content, server),
            *attachments.toTypedArray()
        )
    }

    suspend fun createDiscordEmbed(builder: EmbedBuilder.() -> Unit) {
        if (config[ChatRelaySpec.WebhookSpec.useWebhook]) {
            chatWebhook.execute(chatWebhook.token!!) {
                avatarUrl = config[ChatRelaySpec.WebhookSpec.webhookAvatar]
                allowedMentions = mentions
                embeds = mutableListOf(EmbedBuilder().apply(builder))
            }
        } else {
            val messageChannel = config.getChannel(Channels.CHAT, bot)
            messageChannel.createMessage {
                allowedMentions = mentions
                embed(builder)
            }
        }
    }

    suspend fun createDiscordMessage(content: String) {
        if (config[ChatRelaySpec.WebhookSpec.useWebhook]) {
            chatWebhook.execute(chatWebhook.token!!) {
                allowedMentions = mentions
                this.content = content
            }
        } else {
            val messageChannel = config.getChannel(Channels.CHAT, bot)
            messageChannel.createMessage {
                allowedMentions = mentions
                this.content = content
            }
        }
    }

    override fun onChatMessage(sender: MessageSender, message: Text) {
        BlockBotDiscord.launch {
            var content = discordSerializer.serialize(message.toAdventure(sender.wrapperLookup), DiscordSerializerOptions(false, false, KeybindComponent::keybind, TranslatableComponent::key))
            if (config[ChatRelaySpec.escapeIngameMarkdown]) {
                content = MinecraftSerializer.INSTANCE.escapeMarkdown(content)
            }
            if (config[ChatRelaySpec.allowMentions]) {
                content = convertStringToMention(content, config.getGuild(bot))
            }
            content = convertStringToEmoji(content, config.getGuild(bot))

            if (config[ChatRelaySpec.WebhookSpec.useWebhook]) {
                if (sender.formatWebhookContent(content).isEmpty()) return@launch
                chatWebhook.execute(chatWebhook.token!!) {
                    this.allowedMentions = mentions
                    this.username = config.formatWebhookAuthor(sender)
                    this.content = sender.formatWebhookContent(content)
                    this.avatarUrl = sender.getAvatar()
                }
            } else {
                if (sender.formatMessageContent(content).isEmpty()) return@launch

                val messageChannel = config.getChannel(Channels.CHAT, bot)
                messageChannel.createMessage {
                    allowedMentions = mentions
                    this.content = sender.formatMessageContent(content)
                }
            }
        }
    }

    override fun onPlayerJoinMessage(player: ServerPlayerEntity) {
        if (!player.isVanished()) sendPlayerJoinMessage(player)
    }

    private fun sendPlayerJoinMessage(player: ServerPlayerEntity) {
        if (config.formatPlayerJoinMessage(player).isEmpty()) return
        BlockBotDiscord.launch {
            createDiscordEmbed {
                author {
                    name = config.formatPlayerJoinMessage(player)
                    icon = config.getWebhookChatRelayAvatar(player.gameProfile)
                }
                color = Colors.green
            }
        }
    }

    override fun onPlayerLeaveMessage(player: ServerPlayerEntity) {
        if (!player.isVanished()) sendPlayerLeaveMessage(player)
    }

    private fun sendPlayerLeaveMessage(player: ServerPlayerEntity) {
        if (config.formatPlayerLeaveMessage(player).isEmpty()) return
        BlockBotDiscord.launch {
            createDiscordEmbed {
                author {
                    name = config.formatPlayerLeaveMessage(player)
                    icon = config.getWebhookChatRelayAvatar(player.gameProfile)
                }
                color = Colors.red
            }
        }
    }

    override fun onPlayerDeath(player: ServerPlayerEntity, message: Text) {
        if (config.formatPlayerDeathMessage(player, message).isEmpty() || player.isVanished()) return
        BlockBotDiscord.launch {
            createDiscordEmbed {
                author {
                    name = config.formatPlayerDeathMessage(player, message)
                    icon = config.getWebhookChatRelayAvatar(player.gameProfile)
                }
                color = Colors.orange
            }
        }
    }

    override fun onAdvancementGrant(player: ServerPlayerEntity, advancement: Advancement) {
        if (config.formatPlayerAdvancementMessage(player, advancement).isEmpty() || player.isVanished()) return
        BlockBotDiscord.launch {
            createDiscordEmbed {
                author {
                    name = config.formatPlayerAdvancementMessage(player, advancement)
                    icon = config.getWebhookChatRelayAvatar(player.gameProfile)
                }
                footer {
                    text = advancement.display?.description?.string ?: "Not Provided"
                }
                color = Colors.blue
            }
        }
    }

    override fun onServerStart(server: MinecraftServer) {
        if (config.formatServerStartMessage(server).isEmpty()) return
        BlockBotDiscord.launch {
            createDiscordEmbed {
                title = config.formatServerStartMessage(server)
                color = Colors.green
            }
        }
    }

    override fun onServerStop(server: MinecraftServer) {
        if (config.formatServerStopMessage(server).isEmpty()) return
        runBlocking {
            createDiscordEmbed {
                title = config.formatServerStopMessage(server)
                color = Colors.red
            }
            kord.shutdown()
        }
    }

    override fun onServerTick(server: MinecraftServer) {
        if (server.ticks % 400 == 0) {
            BlockBotDiscord.launch {
                kord.editPresence {
                    when (config[PresenceSpec.activityType]) {
                        ActivityType.Game -> playing(config.formatPresenceText(server))
                        ActivityType.Listening -> listening(config.formatPresenceText(server))
                        ActivityType.Watching -> watching(config.formatPresenceText(server))
                        ActivityType.Competing -> competing(config.formatPresenceText(server))
                        ActivityType.Disabled -> Unit
                    }
                }
            }
        }
    }


    override fun sendRelayMessage(content: String, channel: String) {
    }

    override fun onRelayMessage(content: String, channel: String) {
    }
}

fun MessageSender.getAvatar(): String {
    return if (this is PlayerMessageSender) config.getWebhookChatRelayAvatar(this.profile)
    else config[ChatRelaySpec.WebhookSpec.webhookAvatar]
}

fun MessageSender.formatMessageContent(content: String): String {
    return when (this.type) {
        MessageSender.MessageType.REGULAR -> config.formatDiscordMessage(this, content)
        MessageSender.MessageType.EMOTE -> config.formatDiscordEmote(this, content)
        MessageSender.MessageType.ANNOUNCEMENT -> config.formatDiscordAnnouncement(this, content)
    }
}

fun MessageSender.formatWebhookContent(content: String): String {
    return when (this.type) {
        MessageSender.MessageType.REGULAR -> config.formatWebhookMessage(this, content)
        MessageSender.MessageType.EMOTE -> config.formatWebhookEmote(this, content)
        MessageSender.MessageType.ANNOUNCEMENT -> config.formatWebhookAnnouncement(this, content)
    }
}

suspend fun Member.getDisplayColor() =
    this.roles.toList().sortedByDescending { it.rawPosition }.firstOrNull { it.color.rgb != 0 }?.color
