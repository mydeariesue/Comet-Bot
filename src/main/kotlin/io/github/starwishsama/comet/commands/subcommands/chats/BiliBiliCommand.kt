package io.github.starwishsama.comet.commands.subcommands.chats

import io.github.starwishsama.comet.api.bilibili.BiliBiliApi
import io.github.starwishsama.comet.api.bilibili.FakeClientApi
import io.github.starwishsama.comet.commands.CommandProps
import io.github.starwishsama.comet.commands.interfaces.ChatCommand
import io.github.starwishsama.comet.enums.UserLevel
import io.github.starwishsama.comet.managers.GroupConfigManager
import io.github.starwishsama.comet.objects.BotUser
import io.github.starwishsama.comet.objects.MessageWrapper
import io.github.starwishsama.comet.utils.BotUtil
import io.github.starwishsama.comet.utils.StringUtil.convertToChain
import io.github.starwishsama.comet.utils.StringUtil.isNumeric
import kotlinx.coroutines.delay
import net.mamoe.mirai.contact.MemberPermission
import net.mamoe.mirai.contact.isOperator
import net.mamoe.mirai.message.GroupMessageEvent
import net.mamoe.mirai.message.MessageEvent
import net.mamoe.mirai.message.data.EmptyMessageChain
import net.mamoe.mirai.message.data.MessageChain
import org.apache.commons.lang3.Validate

class BiliBiliCommand : ChatCommand {
    override suspend fun execute(event: MessageEvent, args: List<String>, user: BotUser): MessageChain {
        if (BotUtil.hasNoCoolDown(user.id) && event is GroupMessageEvent) {
            if (args.isEmpty()) {
                return getHelp().convertToChain()
            } else {
                when (args[0]) {
                    "sub", "订阅" -> return advancedSubscribe(user, args, event)
                    "unsub", "取消订阅" -> {
                        return unsubscribe(args, event.group.id)
                    }
                    "list" -> {
                        event.reply("请稍等...")
                        return getLiveStatus(event)
                    }
                    "info", "查询", "cx" -> {
                        return if (args.size > 1) {
                            event.quoteReply("请稍等...")
                            val item = FakeClientApi.getUser(args[1])
                            if (item != null) {
                                val before = item.title + "\n粉丝数: " + item.fans +
                                        "\n最近视频: " + (if (!item.avItems.isNullOrEmpty()) item.avItems[0].title else "没有投稿过视频") +
                                        "\n直播状态: " + (if (item.liveStatus == 1) "✔" else "✘") + "\n"
                                val dynamic = BiliBiliApi.getDynamic(item.mid)
                                before.convertToChain() + getDynamicText(dynamic, event)
                            } else {
                                BotUtil.sendMessage("找不到对应的B站用户")
                            }
                        } else getHelp().convertToChain()
                    }
                    "push" -> {
                        return if (user.isBotAdmin() || event.sender.isOperator()) {
                            val cfg = GroupConfigManager.getConfigSafely(event.group.id)
                            cfg.biliPushEnabled = !cfg.biliPushEnabled
                            BotUtil.sendMessage("B站开播提醒功能已${if (cfg.biliPushEnabled) "开启" else "关闭"}")
                        } else {
                            BotUtil.sendMessage(BotUtil.getLocalMessage("msg.no-permission"))
                        }
                    }
                    else -> return getHelp().convertToChain()
                }
            }
        }
        return EmptyMessageChain
    }

    override fun getProps(): CommandProps =
            CommandProps("bili", arrayListOf(), "订阅B站主播/查询用户动态", "nbot.commands.bili", UserLevel.USER)

    override fun getHelp(): String = """
        /bili sub [用户名] 订阅用户相关信息
        /bili unsub [用户名] 取消订阅用户相关信息
        /bili info [用户名] 查看用户的动态
        /bili push 开启/关闭本群开播推送
    """.trimIndent()

    override fun hasPermission(botUser: BotUser, e: MessageEvent): Boolean {
        val level = getProps().level
        if (botUser.compareLevel(level)) return true
        if (e is GroupMessageEvent && e.sender.permission >= MemberPermission.MEMBER) return true
        return false
    }

    private suspend fun advancedSubscribe(user: BotUser, args: List<String>, event: MessageEvent): MessageChain {
        try {
            Validate.isTrue(args.size > 1, getHelp())
            Validate.isTrue((user.isBotAdmin() || (event is GroupMessageEvent && event.sender.isOperator())), BotUtil.getLocalMessage("msg.no-permission"))

            return if (args[1].contains("|")) {
                val users = args[1].split("|")
                val id = if (event is GroupMessageEvent) event.group.id else args[2].toLong()
                subscribeUsers(users, id) ?: BotUtil.sendMessage("订阅多个直播间成功 你好D啊", true)
            } else {
                val id = if (event is GroupMessageEvent) event.group.id else args[2].toLong()
                val result = subscribe(args[1], id)
                if (result is EmptyMessageChain) {
                    BotUtil.sendMessage("账号不存在")
                } else {
                    result
                }
            }
        } catch (e: IllegalArgumentException) {
            return BotUtil.sendMessage(e.message, true)
        }
    }

    private suspend fun subscribeUsers(users: List<String>, id: Long): MessageChain? {
        users.forEach {
            val result = subscribe(it, id)
            delay(500)
            if (result is EmptyMessageChain) {
                return BotUtil.sendMessage("账号 $it 不存在")
            }
        }
        return null
    }

    private suspend fun unsubscribe(args: List<String>, groupId: Long): MessageChain {
        if (args.size > 1) {
            val cfg = GroupConfigManager.getConfigSafely(groupId)
            var roomId = 0L
            if (args[1].isNumeric()) {
                roomId = args[1].toLong()
            } else {
                if (args[1] == "all" || args[1] == "全部") {
                    cfg.twitterSubscribers.clear()
                    return BotUtil.sendMessage("退订全部成功")
                }

                val item = FakeClientApi.getUser(args[1])
                if (item != null) {
                    roomId = item.roomid
                }
            }


            return if (!cfg.biliSubscribers.contains(roomId)) {
                BotUtil.sendMessage("你还没订阅直播间 ${args[1]}")
            } else {
                cfg.biliSubscribers.remove(args[1].toLong())
                BotUtil.sendMessage("取消订阅直播间 ${args[1]} 成功")
            }
        } else {
            return getHelp().convertToChain()
        }
    }

    private suspend fun getLiveStatus(event: MessageEvent): MessageChain {
        if (event !is GroupMessageEvent) return BotUtil.sendMessage("只能在群里查看订阅列表")

        val subs = StringBuilder("监控室列表:\n")
        val info = ArrayList<com.hiczp.bilibili.api.live.model.RoomInfo>()

        val cfg = GroupConfigManager.getConfigSafely(event.group.id)

        if (cfg.biliSubscribers.isNotEmpty()) {
            for (roomId in cfg.biliSubscribers) {
                val room = FakeClientApi.getLiveRoom(roomId)
                if (room != null) {
                    info.add(room)
                }
            }

            info.sortByDescending { it.data.liveStatus == 1 }

            for (roomInfo in info) {
                subs.append(
                    "${BiliBiliApi.getUserNameByMid(roomInfo.data.uid)} " +
                            "${if (roomInfo.data.liveStatus == 1) "✔" else "✘"}\n"
                )
            }

            return subs.toString().trim().convertToChain()
        }
        return BotUtil.sendMessage("未订阅任何用户")
    }

    private suspend fun getDynamicText(dynamic: MessageWrapper?, event: MessageEvent): MessageChain {
        return if (dynamic == null) {
            ("\n无最近动态").convertToChain()
        } else {
            if (dynamic.text != null) {
                dynamic.toMessageChain(event.subject)
            } else {
                ("\n无最近动态").convertToChain()
            }
        }
    }

    private suspend fun subscribe(roomId: String, groupId: Long): MessageChain {
        val cfg = GroupConfigManager.getConfigSafely(groupId)
        val rid: Long
        var name = ""
        rid = if (roomId.isNumeric()) {
            roomId.toLong()
        } else {
            val item = FakeClientApi.getUser(roomId)
            val title = item?.title
            if (title != null) name = title
            item?.roomid ?: return EmptyMessageChain
        }

        if (!cfg.biliSubscribers.contains(rid)) {
            cfg.biliSubscribers.add(rid)
        }

        return BotUtil.sendMessage("订阅 ${if (name.isNotBlank()) name else BiliBiliApi.getUserNameByMid(rid)}($rid) 成功")
    }

}