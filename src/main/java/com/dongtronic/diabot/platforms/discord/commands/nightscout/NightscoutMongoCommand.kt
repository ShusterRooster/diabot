package com.dongtronic.diabot.platforms.discord.commands.nightscout

import com.dongtronic.diabot.data.mongodb.ChannelDAO
import com.dongtronic.diabot.data.mongodb.ChannelDTO
import com.dongtronic.diabot.data.mongodb.NightscoutDAO
import com.dongtronic.diabot.data.mongodb.NightscoutUserDTO
import com.dongtronic.diabot.data.redis.NightscoutDTO
import com.dongtronic.diabot.exceptions.*
import com.dongtronic.diabot.logic.nightscout.NightscoutCommunicator.getEntries
import com.dongtronic.diabot.logic.nightscout.NightscoutCommunicator.getSettings
import com.dongtronic.diabot.logic.nightscout.NightscoutCommunicator.processPebble
import com.dongtronic.diabot.nameOf
import com.dongtronic.diabot.platforms.discord.commands.DiscordCommand
import com.dongtronic.diabot.submitMono
import com.dongtronic.diabot.util.logger
import com.google.gson.stream.MalformedJsonException
import com.jagrosh.jdautilities.command.Command
import com.jagrosh.jdautilities.command.CommandEvent
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.ChannelType
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.SynchronousSink
import reactor.core.scheduler.Schedulers
import reactor.kotlin.core.publisher.switchIfEmpty
import reactor.kotlin.core.publisher.toMono
import reactor.util.function.Tuple2
import java.awt.Color
import java.net.UnknownHostException
import java.time.ZonedDateTime

@Suppress("DuplicatedCode")
class NightscoutMongoCommand(category: Command.Category) : DiscordCommand(category, null) {

    private val logger = logger()
    private val trendArrows: Array<String> = arrayOf("", "↟", "↑", "↗", "→", "↘", "↓", "↡", "↮", "↺")

    init {
        this.name = "nightscout"
        this.help = "Get the most recent info from any Nightscout site"
        this.arguments = "Partial Nightscout url (part before .herokuapp.com)"
        this.guildOnly = false
        this.aliases = arrayOf("ns", "bg", "bs")
        this.examples = arrayOf("diabot nightscout casscout", "diabot ns", "diabot ns set https://casscout.herokuapp.com", "diabot ns public false")
        this.children = arrayOf(
                NightscoutSetUrlCommand(category, this),
                NightscoutDeleteCommand(category, this),
                NightscoutPublicCommand(category, this),
                NightscoutSetTokenCommand(category, this),
                NightscoutSetDisplayCommand(category, this)
        )
    }

    override fun execute(event: CommandEvent) {
        val args = event.args.trim()
        // grab the necessary data
        val embed = if (args.isBlank()) {
            getStoredData(event)
        } else {
            getUnstoredData(event)
        }.flatMap { data ->
            // send the message
            event.channel.sendMessage(data.t2)
                    .submitMono()
                    .doOnSuccess { addReactions(data.t1, it) }
        }.subscribeOn(Schedulers.boundedElastic())

        embed.subscribe({
            logger.debug("Sent Nightscout embed: $it")
        }, {
            handleError(it, event)
        })
    }

    /**
     * Handles errors which occur either:
     * - before fetching data from a Nightscout instance
     * or
     * - when replying
     *
     * @param ex The error which was thrown
     * @param event The command event which called this command
     */
    private fun handleError(ex: Throwable, event: CommandEvent) {
        when (ex) {
            is NightscoutDataException -> {
                if (ex.message != null) {
                    event.replyError(ex.message)
                } else {
                    event.replyError("Nightscout data could not be read")
                }
            }
            is UnconfiguredNightscoutException -> event.reply("Please set your Nightscout hostname using `diabot nightscout set <hostname>`")
            is IllegalArgumentException -> event.reply("Error: " + ex.message)
            is InsufficientPermissionException -> {
                logger.info("Couldn't reply with nightscout data due to missing permission: ${ex.permission}")
                event.replyError("Couldn't perform requested action due to missing permission: `${ex.permission}`")
            }
            is UnknownHostException -> {
                event.reactError()
                logger.info("No host found: ${ex.message}")
            }
            else -> {
                event.reactError()
                logger.warn("Unexpected error: " + ex.message, ex)
            }
        }
    }

    /**
     * Handles errors which occur while grabbing Nightscout data.
     *
     * @param ex The [Throwable] which was given
     * @param event Command event which caused the bot to grab this Nightscout data
     * @param userDTO The user data which was used for fetching
     */
    private fun handleGrabError(ex: Throwable, event: CommandEvent, userDTO: NightscoutUserDTO) {
        when (ex) {
            is NoNightscoutDataException -> {
                event.reactError()
                logger.info("No nightscout data from ${userDTO.url}")
            }
            is MalformedJsonException -> {
                event.reactError()
                logger.warn("Malformed JSON from ${userDTO.url}")
            }
            is NightscoutStatusException -> {
                if (ex.status == 401) {
                    if (userDTO.jdaUser != null) {
                        if (userDTO.jdaUser == event.author) {
                            event.replyError("Could not authenticate to Nightscout. Please set an authentication token with `diabot nightscout token <token>`")
                        } else {
                            event.replyError("Nightscout data for ${event.nameOf(userDTO.jdaUser)} is unreadable due to missing token.")
                        }
                    } else {
                        event.replyError("Nightscout data is unreadable due to missing token.")
                    }
                } else {
                    event.replyError("Could not connect to Nightscout instance.")
                    logger.warn("Connection status ${ex.status} from ${userDTO.url}")
                }
            }
        }
    }

    /**
     * Grabs data for the command sender and builds a Nightscout response.
     */
    private fun getStoredData(event: CommandEvent): Mono<Tuple2<NightscoutDTO, MessageEmbed>> {
        return getUserDto(event.author)
                .flatMap { buildNightscoutResponse(it, event) }
    }

    /**
     * Grabs data for another user/URL (depending on the arguments) and builds a Nightscout response.
     */
    private fun getUnstoredData(event: CommandEvent): Mono<Tuple2<NightscoutDTO, MessageEmbed>> {
        val args = event.args.split("\\s+".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()

        val namedMembers = event.event.guild.members.filter {
            it.effectiveName.equals(event.args, true)
                    || it.user.name.equals(event.args, true)
        }
        val mentionedMembers = event.event.message.mentionedMembers

        val endpoint: Mono<NightscoutUserDTO> = when {
            mentionedMembers.size > 1 ->
                IllegalArgumentException("Too many mentioned users.").toMono()
            event.event.message.mentionsEveryone() ->
                IllegalArgumentException("Cannot handle mentioning everyone.").toMono()

            mentionedMembers.size == 1 -> {
                val user = mentionedMembers[0].user
                val exception = IllegalArgumentException("User does not have a configured Nightscout URL.")

                getUserDto(user, exception)
                        .handle { t, u: SynchronousSink<NightscoutUserDTO> ->
                            if (!t.isNightscoutPublic(event.guild.id)) {
                                u.error(NightscoutPrivateException(event.nameOf(user)))
                            } else {
                                u.next(t)
                            }
                        }
            }
            args.isNotEmpty() && args[0].matches("^https?://.*".toRegex()) -> {
                // is a URL
                val url = NightscoutSetUrlCommand.validateNightscoutUrl(args[0])
                getDataFromDomain(url, event)
            }
            else -> {
                // Try to get nightscout data from username/nickname, otherwise just try to get from hostname
                val user = namedMembers.getOrNull(0)?.user
                val domain = "https://${args[0]}.herokuapp.com"
                val fallbackDto = NightscoutUserDTO(url = domain).toMono()

                if (user == null) {
                    fallbackDto
                } else {
                    getUserDto(user)
                            .switchIfEmpty { fallbackDto }
                            .handle { userDTO, sink: SynchronousSink<NightscoutUserDTO> ->
                                if (!userDTO.isNightscoutPublic(event.guild.id)) {
                                    sink.error(NightscoutPrivateException(event.nameOf(user)))
                                } else {
                                    sink.next(userDTO)
                                }
                            }
                }
            }
        }

        return endpoint.flatMap { buildNightscoutResponse(it, event) }
    }

    private fun getDataFromDomain(domain: String, event: CommandEvent): Mono<NightscoutUserDTO> {
        val userDtos = getUsersForDomain(domain)

        return userDtos
                .flatMap { userDTO ->
                    val user = event.jda.getUserById(userDTO.userId)

                    val mutual = user?.mutualGuilds?.contains(event.guild) == true
                    val publicForGuild = userDTO.isNightscoutPublic(event.guild.id)

                    if (!mutual) {
                        // strip all personal info
                        return@flatMap Mono.empty<NightscoutUserDTO>()
                    }

                    if (!publicForGuild) {
                        return@flatMap if (user != null)
                            NightscoutPrivateException(event.nameOf(user)).toMono<NightscoutUserDTO>()
                        else
                            NightscoutPrivateException().toMono()
                    }

                    userDTO.copy(jdaUser = user).toMono()
                }
                .singleOrEmpty()
                .switchIfEmpty { NightscoutUserDTO(url = domain).toMono() }
    }

    /**
     * Loads all the necessary data from a Nightscout instance and replies with an embed of it.
     *
     * @param userDTO Data necessary for loading/rendering
     * @param event [CommandEvent]
     */
    private fun buildNightscoutResponse(userDTO: NightscoutUserDTO, event: CommandEvent): Mono<Tuple2<NightscoutDTO, MessageEmbed>> {
        return Mono.defer {
            // todo: this is probably not great threading :\
            val nsDto = NightscoutDTO()

            try {
                // blocking operations
                getSettings(userDTO.apiEndpoint, userDTO.token, nsDto)
                getEntries(userDTO.apiEndpoint, userDTO.token, nsDto)
                processPebble(userDTO.apiEndpoint, userDTO.token, nsDto)
            } catch (exception: Exception) {
                if (exception is NightscoutStatusException
                        || exception is MalformedJsonException
                        || exception is NoNightscoutDataException) {
                    handleGrabError(exception, event, userDTO)
                    return@defer Mono.empty<NightscoutDTO>()
                } else {
                    return@defer exception.toMono<NightscoutDTO>()
                }
            }

            nsDto.toMono()
        }.zipWhen { nsDto ->
            val channelType = event.channelType
            var shortReply = false.toMono()
            if (channelType == ChannelType.TEXT) {
                shortReply = if (userDTO.displayOptions.contains("simple")) {
                    true.toMono()
                } else {
                    ChannelDAO.instance.hasAttribute(event.channel.id, ChannelDTO.ChannelAttribute.NIGHTSCOUT_SHORT)
                }
            }

            shortReply.map { buildResponse(nsDto, userDTO.jdaUser?.avatarUrl, userDTO.displayOptions, it).build() }
        }
    }

    private fun buildResponse(
            dto: NightscoutDTO,
            avatarUrl: String?,
            displayOptions: List<String>,
            short: Boolean,
            builder: EmbedBuilder = EmbedBuilder()
    ): EmbedBuilder {
        if (displayOptions.contains("title")) builder.setTitle(dto.title)

        val (mmolString: String, mgdlString: String) = buildGlucoseStrings(dto)

        val trendString = trendArrows[dto.trend]
        builder.addField("mmol/L", mmolString, true)
        builder.addField("mg/dL", mgdlString, true)
        if (displayOptions.contains("trend")) builder.addField("trend", trendString, true)
        if (dto.iob != 0.0F && displayOptions.contains("iob")) {
            builder.addField("iob", dto.iob.toString(), true)
        }
        if (dto.cob != 0 && displayOptions.contains("cob")) {
            builder.addField("cob", dto.cob.toString(), true)
        }

        setResponseColor(dto, builder)

        if (avatarUrl != null && displayOptions.contains("avatar") && !short) {
            builder.setThumbnail(avatarUrl)
        }

        builder.setTimestamp(dto.dateTime)
        builder.setFooter("measured", "https://github.com/nightscout/cgm-remote-monitor/raw/master/static/images/large.png")

        if (dto.dateTime!!.plusMinutes(15).isBefore(ZonedDateTime.now())) {
            builder.setDescription("**BG data is more than 15 minutes old**")
        }

        return builder
    }

    private fun buildGlucoseStrings(dto: NightscoutDTO): Pair<String, String> {
        val mmolString: String
        val mgdlString: String
        if (dto.delta != null) {
            mmolString = buildGlucoseString(dto.glucose!!.mmol.toString(), dto.delta!!.mmol.toString(), dto.deltaIsNegative)
            mgdlString = buildGlucoseString(dto.glucose!!.mgdl.toString(), dto.delta!!.mgdl.toString(), dto.deltaIsNegative)
        } else {
            mmolString = buildGlucoseString(dto.glucose!!.mmol.toString(), "999.0", false)
            mgdlString = buildGlucoseString(dto.glucose!!.mgdl.toString(), "999.0", false)
        }
        return Pair(mmolString, mgdlString)
    }

    /**
     * Formats a glucose value and delta (if available)
     *
     * @param glucose The glucose value.
     * @param delta The current delta.
     * @param negative Whether the delta is falling.
     * @return Formatted glucose and delta
     */
    private fun buildGlucoseString(glucose: String, delta: String, negative: Boolean): String {
        val builder = StringBuilder()

        builder.append(glucose)

        if (delta != "999.0") {
            // 999L is placeholder for absent delta
            builder.append(" (")

            if (negative) {
                builder.append("-")
            } else {
                builder.append("+")
            }

            builder.append(delta)
            builder.append(")")
        }

        return builder.toString()
    }

    /**
     * Adds reactions to a message based on the glucose value.
     *
     * @param dto The Nightscout DTO holding the glucose data.
     * @param response The message to react to.
     */
    private fun addReactions(dto: NightscoutDTO, response: Message) {
        // #20: Reply with :smirk: when value is 69 mg/dL or 6.9 mmol/L
        if (dto.glucose!!.mgdl == 69 || dto.glucose!!.mmol == 6.9) {
            response.addReaction("\uD83D\uDE0F").queue()
        }
        // #36 and #60: Reply with :100: when value is 100 mg/dL, 5.5 mmol/L, or 10.0 mmol/L
        if (dto.glucose!!.mgdl == 100
                || dto.glucose!!.mmol == 5.5
                || dto.glucose!!.mmol == 10.0) {
            response.addReaction("\uD83D\uDCAF").queue()
        }
    }

    /**
     * Adjust an embed's color based on the current glucose and ranges.
     *
     * @param dto The Nightscout DTO holding the glucose and range data.
     * @param builder The embed to set colors on.
     */
    private fun setResponseColor(dto: NightscoutDTO, builder: EmbedBuilder = EmbedBuilder()): EmbedBuilder {
        val glucose = dto.glucose!!.mgdl.toDouble()

        if (glucose >= dto.high || glucose <= dto.low) {
            builder.setColor(Color.red)
        } else if (glucose >= dto.top && glucose < dto.high || glucose > dto.low && glucose <= dto.bottom) {
            builder.setColor(Color.orange)
        } else {
            builder.setColor(Color.green)
        }

        return builder
    }

    /**
     * Finds users in the database matching with the Nightscout domain given
     */
    private fun getUsersForDomain(domain: String): Flux<NightscoutUserDTO> {
        return NightscoutDAO.instance.getUsersForURL(domain)
                .onErrorResume {
                    if (it is NoSuchElementException)
                        return@onErrorResume Mono.empty()

                    it.toMono()
                }
    }

    /**
     * Sets the data inside the given NightscoutUserDTO for the given user
     */
    private fun getUserDto(user: User, throwable: Throwable = UnconfiguredNightscoutException()): Mono<NightscoutUserDTO> {
        return NightscoutDAO.instance.getUser(user.id)
                .onErrorResume {
                    if (it is NoSuchElementException) {
                        return@onErrorResume throwable.toMono()
                    }

                    it.toMono()
                }.flatMap {
                    if (it.url != null) {
                        it.copy(jdaUser = user).toMono()
                    } else {
                        throwable.toMono()
                    }
                }
    }
}
