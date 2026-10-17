package com.autoclip;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CharMatcher;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.inject.Provides;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.Player;
import net.runelite.api.ScriptID;
import net.runelite.api.annotations.Component;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.Notifier;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.Notification;
import net.runelite.client.config.RuneScapeProfileType;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.Filepath;
import net.runelite.client.util.HotkeyListener;
import net.runelite.client.util.ImageCapture;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.QuantityFormatter;
import net.runelite.client.util.Text;
import org.apache.commons.lang3.StringUtils;

@PluginDescriptor(
	name = "Auto Clip",
	description = "Enable the manual and automatic saving of gameplay replay clips",
	tags = {"clip", "replay", "video", "record", "screenshot", "notifications"},
	internalName = "auto-clip"
)
@Slf4j
public class AutoClipPlugin extends Plugin
{
	private static final DateFormat TIME_FORMAT = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
	private static final String COLLECTION_LOG_TEXT = "New item added to your collection log: ";
	private static final String CHEST_LOOTED_MESSAGE = "You find some treasure in the chest!";
	private static final Map<Integer, String> CHEST_LOOT_EVENTS = ImmutableMap.of(12127, "The Gauntlet");
	private static final int GAUNTLET_REGION = 7512;
	private static final int CORRUPTED_GAUNTLET_REGION = 7768;
	private static final Pattern NUMBER_PATTERN = Pattern.compile("([,0-9]+)");
	private static final Pattern LEVEL_UP_PATTERN = Pattern.compile(".*Your ([a-zA-Z]+) (?:level is|are)? now (\\d+)\\.");
	private static final Pattern LEVEL_UP_MESSAGE_PATTERN = Pattern.compile("Congratulations, you've (just advanced your (?<skill>[a-zA-Z]+) level\\. You are now level (?<level>\\d+)|reached the highest possible (?<skill99>[a-zA-Z]+) level of 99)\\.");
	private static final Pattern BOSSKILL_MESSAGE_PATTERN = Pattern.compile("Your (.+) (?:kill|success) count is: ?<col=[0-9a-f]{6}>([0-9,]+)</col>");
	private static final Pattern VALUABLE_DROP_PATTERN = Pattern.compile(".*Valuable drop: ([^<>]+?\\(((?:\\d+,?)+) coins\\))(?:</col>)?");
	private static final Pattern UNTRADEABLE_DROP_PATTERN = Pattern.compile(".*Untradeable drop: ([^<>]+)(?:</col>)?");
	private static final Pattern DUEL_END_PATTERN = Pattern.compile("You have now (won|lost) ([0-9,]+) duels?\\.");
	private static final Pattern QUEST_PATTERN_1 = Pattern.compile(".+?ve\\.*? (?<verb>been|rebuilt|.+?ed)? ?(?:the )?'?(?<quest>.+?)'?(?: [Qq]uest)?[!.]?$");
	private static final Pattern QUEST_PATTERN_2 = Pattern.compile("'?(?<quest>.+?)'?(?: [Qq]uest)? (?<verb>[a-z]\\w+?ed)?(?: f.*?)?[!.]?$");
	private static final Pattern COMBAT_ACHIEVEMENTS_PATTERN = Pattern.compile("Congratulations, you've completed an? (?<tier>\\w+) combat task: @.+?@(?<task>(.+))</col>");
	private static final ImmutableList<String> RFD_TAGS = ImmutableList.of("Another Cook", "freed", "defeated", "saved");
	private static final ImmutableList<String> WORD_QUEST_IN_NAME_TAGS = ImmutableList.of("Another Cook", "Doric", "Heroes", "Legends", "Observatory", "Olaf", "Waterfall");
	private static final ImmutableList<String> PET_MESSAGES = ImmutableList.of(
		"You have a funny feeling like you're being followed",
		"You feel something weird sneaking into your backpack",
		"You have a funny feeling like you would have been followed"
	);
	private static final Pattern BA_HIGH_GAMBLE_REWARD_PATTERN = Pattern.compile("(?<reward>.+)!<br>High level gamble count: <col=7f0000>(?<gambleCount>.+)</col>");

	private static final String SD_KINGDOM_REWARDS = "Kingdom Rewards";
	private static final String SD_BOSS_KILLS = "Boss Kills";
	private static final String SD_CLUE_SCROLL_REWARDS = "Clue Scroll Rewards";
	private static final String SD_FRIENDS_CHAT_KICKS = "Friends Chat Kicks";
	private static final String SD_PETS = "Pets";
	private static final String SD_CHEST_LOOT = "Chest Loot";
	private static final String SD_VALUABLE_DROPS = "Valuable Drops";
	private static final String SD_UNTRADEABLE_DROPS = "Untradeable Drops";
	private static final String SD_DUELS = "Duels";
	private static final String SD_COLLECTION_LOG = "Collection Log";
	private static final String SD_PVP_KILLS = "PvP Kills";
	private static final String SD_DEATHS = "Deaths";
	private static final String SD_COMBAT_ACHIEVEMENTS = "Combat Achievements";
	private static final String SD_WILDERNESS_LOOT_CHEST = "Wilderness Loot Chest";
	private static final String SD_LEVELS = "Levels";
	private static final String SD_LEAGUE_TASKS = "League Tasks";
	private static final String SD_QUESTS = "Quests";
	private static final String SD_BA_HIGH_GAMBLES = "BA High Gambles";

	private static final CharMatcher BAD_FILENAME_CHARS = CharMatcher.inRange('\0', '\u001f')
		.or(CharMatcher.anyOf("<>:\"/\\|?*~"));
	private static final Set<String> DOS_DEVICES = ImmutableSet.of(
		"con", "conin$", "conout$", "prn", "aux", "nul",
		"com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9", "com\u00b9", "com\u00b2", "com\u00b3",
		"lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9", "lpt\u00b9", "lpt\u00b2", "lpt\u00b3"
	);

	enum KillType
	{
		BARROWS,
		COX,
		COX_CM,
		MOONS_OF_PERIL,
		TOB,
		TOB_SM,
		TOB_HM,
		TOA_ENTRY_MODE,
		TOA,
		TOA_EXPERT_MODE,
		FORTIS_COLOSSEUM
	}

	@Getter(AccessLevel.PACKAGE)
	private String clueType;
	private Integer clueNumber;

	@Getter(AccessLevel.PACKAGE)
	private KillType killType;
	private Integer killCountNumber;

	private boolean shouldTakeClip;
	private boolean notificationStarted;
	private String kickPlayerName;
	private int lastValuableDropTick = -1;
	private int lastUntradeableDropTick = -1;
	private int lastLevelUpTick = -1;

	private volatile boolean recordingAllowed;
	private volatile String cachedPlayerFolder;
	private volatile long lastCaptureTimeNs;

	private final AtomicBoolean frameCaptureInFlight = new AtomicBoolean(false);
	private final Set<Future<?>> pendingFutures = ConcurrentHashMap.newKeySet();
	private final Runnable everyFrameListener = this::onEveryFrame;

	@Inject
	private AutoClipConfig config;

	@Inject
	private Client client;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private DrawManager drawManager;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private KeyManager keyManager;

	@Inject
	private ImageCapture imageCapture;

	@Inject
	private ItemManager itemManager;

	@Inject
	private Notifier notifier;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private ClipRecorder clipRecorder;

	private NavigationButton titleBarButton;

	private final HotkeyListener hotkeyListener = new HotkeyListener(() -> config.hotkey())
	{
		@Override
		public void hotkeyPressed()
		{
			manualClip();
		}
	};

	@Provides
	AutoClipConfig getConfig(ConfigManager configManager)
	{
		return configManager.getConfig(AutoClipConfig.class);
	}

	@Override
	protected void startUp() throws Exception
	{
		getPluginDirectory().createDirectories();
		keyManager.registerKeyListener(hotkeyListener);
		drawManager.registerEveryFrameListener(everyFrameListener);

		final BufferedImage iconImage = ImageUtil.loadImageResource(getClass(), "autoclip.png");

		titleBarButton = NavigationButton.builder()
			.tooltip("Save clip")
			.icon(iconImage)
			.onClick(this::manualClip)
			.popup(ImmutableMap.<String, Runnable>builder()
				.put("Open clips folder...", this::openClipsFolder)
				.build())
			.build();

		clientToolbar.addNavigation(titleBarButton);
		log.debug("Auto Clip plugin started");
	}

	@Override
	protected void shutDown() throws Exception
	{
		recordingAllowed = false;
		drawManager.unregisterEveryFrameListener(everyFrameListener);
		clientToolbar.removeNavigation(titleBarButton);
		keyManager.unregisterKeyListener(hotkeyListener);

		for (Future<?> future : pendingFutures)
		{
			future.cancel(true);
		}
		pendingFutures.clear();
		frameCaptureInFlight.set(false);
		clipRecorder.clear();

		kickPlayerName = null;
		notificationStarted = false;
		shouldTakeClip = false;
		lastValuableDropTick = -1;
		lastUntradeableDropTick = -1;
		lastLevelUpTick = -1;
		log.debug("Auto Clip plugin stopped");
	}

	private void openClipsFolder()
	{
		try
		{
			Filepath dir = getPluginDirectory();
			dir.createDirectories();
			LinkBrowser.open(dir.toString());
		}
		catch (IOException e)
		{
			log.debug("Unable to open clips folder", e);
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() != GameState.LOGGED_IN)
		{
			recordingAllowed = false;
		}
	}

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		updateRecordingAndPlayerState();
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		int groupId = event.getGroupId();
		if (groupId == InterfaceID.BANKPIN_KEYPAD
			|| groupId == InterfaceID.BANKPIN_SETTINGS
			|| groupId == InterfaceID.WELCOME_SCREEN)
		{
			updateRecordingAndPlayerState();
		}
	}

	private void updateRecordingAndPlayerState()
	{
		boolean sensitive = isSensitiveScreenVisible();
		recordingAllowed = !sensitive;

		Player localPlayer = client.getLocalPlayer();
		if (localPlayer != null && localPlayer.getName() != null)
		{
			String folder = localPlayer.getName();
			RuneScapeProfileType profileType = RuneScapeProfileType.getCurrent(client);
			if (profileType != RuneScapeProfileType.STANDARD)
			{
				folder = folder + " (" + Text.titleCase(profileType) + ")";
			}
			cachedPlayerFolder = folder;
		}
	}

	@VisibleForTesting
	boolean isSensitiveScreenVisible()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return true;
		}

		return isWidgetVisible(InterfaceID.BankpinKeypad.UNIVERSE)
			|| isWidgetVisible(InterfaceID.BankpinSettings.SETTINGS)
			|| isWidgetVisible(InterfaceID.WelcomeScreen.UNIVERSE);
	}

	private boolean isWidgetVisible(@Component int componentId)
	{
		Widget widget = client.getWidget(componentId);
		return widget != null && !widget.isHidden();
	}

	private void onEveryFrame()
	{
		if (!recordingAllowed)
		{
			return;
		}

		int fps = Math.max(5, Math.min(30, config.frameRate()));
		long intervalNs = 1_000_000_000L / fps;
		long nowNs = System.nanoTime();
		if (nowNs - lastCaptureTimeNs < intervalNs)
		{
			return;
		}

		if (frameCaptureInFlight.compareAndSet(false, true))
		{
			lastCaptureTimeNs = nowNs;
			drawManager.requestNextFrameListener(this::onCapturedFrame);
		}
	}

	private void onCapturedFrame(Image image)
	{
		if (!recordingAllowed || image == null)
		{
			frameCaptureInFlight.set(false);
			return;
		}

		Future<?>[] holder = new Future<?>[1];
		Future<?> future = executor.submit(() ->
		{
			try
			{
				if (!recordingAllowed)
				{
					return;
				}
				BufferedImage bufferedImage = config.includeFrame()
					? imageCapture.addClientFrame(image)
					: ImageUtil.bufferedImageFromImage(image);
				if (!recordingAllowed)
				{
					return;
				}
				clipRecorder.recordFrame(
					bufferedImage,
					System.currentTimeMillis(),
					config.clipDuration(),
					config.frameRate()
				);
			}
			catch (Exception e)
			{
				log.debug("Error processing frame for Auto Clip buffer", e);
			}
			finally
			{
				frameCaptureInFlight.set(false);
				if (holder[0] != null)
				{
					pendingFutures.remove(holder[0]);
				}
			}
		});
		holder[0] = future;
		if (!future.isDone())
		{
			pendingFutures.add(future);
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (!shouldTakeClip)
		{
			return;
		}

		shouldTakeClip = false;
		String clipSubDir = null;
		String fileName = null;

		if (client.getWidget(InterfaceID.LevelupDisplay.TEXT2) != null)
		{
			fileName = parseLevelUpWidget(InterfaceID.LevelupDisplay.TEXT2);
			clipSubDir = SD_LEVELS;
		}
		else if (client.getWidget(InterfaceID.Objectbox.TEXT) != null)
		{
			String text = client.getWidget(InterfaceID.Objectbox.TEXT).getText();
			if (Text.removeTags(text).contains("High level gamble"))
			{
				if (config.clipHighGamble())
				{
					fileName = parseBAHighGambleWidget(text);
					clipSubDir = SD_BA_HIGH_GAMBLES;
				}
			}
			else
			{
				if (config.clipLevels())
				{
					fileName = parseLevelUpWidget(InterfaceID.Objectbox.TEXT);
					clipSubDir = SD_LEVELS;
				}
			}
		}
		else if (client.getWidget(InterfaceID.Questscroll.QUEST_TITLE) != null)
		{
			String text = client.getWidget(InterfaceID.Questscroll.QUEST_TITLE).getText();
			fileName = parseQuestCompletedWidget(text);
			clipSubDir = SD_QUESTS;
		}

		if (fileName != null)
		{
			if (SD_LEVELS.equals(clipSubDir))
			{
				int currentTick = client.getTickCount();
				if (currentTick != 0 && currentTick == lastLevelUpTick)
				{
					return;
				}
				lastLevelUpTick = currentTick;
			}
			takeClip(fileName, clipSubDir);
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath actorDeath)
	{
		Actor actor = actorDeath.getActor();
		if (actor instanceof Player)
		{
			Player player = (Player) actor;
			if (player == client.getLocalPlayer() && config.clipPlayerDeath())
			{
				takeClip("Death", SD_DEATHS);
			}
			else if (player != client.getLocalPlayer()
				&& player.getCanvasTilePoly() != null
				&& (((player.isFriendsChatMember() || player.isFriend()) && config.clipFriendDeath())
					|| (player.isClanMember() && config.clipClanDeath())))
			{
				takeClip("Death " + player.getName(), SD_DEATHS);
			}
		}
	}

	@Subscribe
	public void onAnimationChanged(AnimationChanged animationChanged)
	{
		Actor actor = animationChanged.getActor();
		if (actor == client.getLocalPlayer()
			&& actor.getAnimation() == AnimationID.HUMAN_DOOM_SCORPION_01_PLAYER_DEATH_01
			&& config.clipPlayerDeath())
		{
			takeClip("Doom Death", SD_DEATHS);
		}
	}

	@Subscribe
	public void onPlayerLootReceived(PlayerLootReceived playerLootReceived)
	{
		if (config.clipKills())
		{
			final Player player = playerLootReceived.getPlayer();
			final String name = player.getName();
			String fileName = "Kill " + name;
			takeClip(fileName, SD_PVP_KILLS);
		}

		processLootDrops(playerLootReceived.getItems());
	}

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived npcLootReceived)
	{
		processLootDrops(npcLootReceived.getItems());
	}

	@Subscribe
	public void onServerNpcLoot(ServerNpcLoot serverNpcLoot)
	{
		processLootDrops(serverNpcLoot.getItems());
	}

	private void processLootDrops(Collection<ItemStack> items)
	{
		if ((!config.clipValuableDrop() && !config.clipUntradeableDrop())
			|| items == null
			|| items.isEmpty()
			|| itemManager == null)
		{
			return;
		}

		long bestValuablePrice = -1;
		String bestValuableDescription = null;
		String untradeableName = null;
		int threshold = config.valuableDropThreshold();

		for (ItemStack item : items)
		{
			int itemId = item.getId();
			int qty = item.getQuantity();
			if (qty <= 0)
			{
				continue;
			}

			ItemComposition comp = itemManager.getItemComposition(itemId);
			if (comp == null)
			{
				continue;
			}

			int realItemId = comp.getNote() != -1 ? comp.getLinkedNoteId() : itemId;
			long gePrice = itemManager.getItemPrice(realItemId);
			long haPrice = comp.getHaPrice();
			long unitPrice = Math.max(gePrice, haPrice);
			if (realItemId == ItemID.COINS && unitPrice <= 0)
			{
				unitPrice = 1;
			}

			long totalValue = unitPrice * qty;
			String itemName = comp.getMembersName();
			if (Strings.isNullOrEmpty(itemName) || "null".equalsIgnoreCase(itemName))
			{
				itemName = comp.getName();
			}

			if (config.clipValuableDrop() && totalValue >= threshold && totalValue > bestValuablePrice)
			{
				bestValuablePrice = totalValue;
				String qtyPart = qty > 1 ? " (" + QuantityFormatter.formatNumber(qty) + ")" : "";
				bestValuableDescription = itemName + qtyPart + " (" + QuantityFormatter.formatNumber(totalValue) + " coins)";
			}

			if (config.clipUntradeableDrop()
				&& !isInsideGauntlet()
				&& !comp.isTradeable()
				&& realItemId != ItemID.COINS
				&& untradeableName == null)
			{
				untradeableName = itemName;
			}
		}

		int currentTick = client.getTickCount();

		if (bestValuableDescription != null && (currentTick == 0 || currentTick != lastValuableDropTick))
		{
			lastValuableDropTick = currentTick;
			takeClip("Valuable drop " + bestValuableDescription, SD_VALUABLE_DROPS);
		}

		if (untradeableName != null && (currentTick == 0 || currentTick != lastUntradeableDropTick))
		{
			lastUntradeableDropTick = currentTick;
			takeClip("Untradeable drop " + untradeableName, SD_UNTRADEABLE_DROPS);
		}
	}

	@Subscribe
	public void onScriptCallbackEvent(ScriptCallbackEvent e)
	{
		if (!"confirmFriendsChatKick".equals(e.getEventName()))
		{
			return;
		}

		final Object[] objectStack = client.getObjectStack();
		final int objectStackSize = client.getObjectStackSize();
		kickPlayerName = (String) objectStack[objectStackSize - 1];
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE
			&& event.getType() != ChatMessageType.SPAM
			&& event.getType() != ChatMessageType.TRADE
			&& event.getType() != ChatMessageType.FRIENDSCHATNOTIFICATION)
		{
			return;
		}

		String chatMessage = event.getMessage();

		if (chatMessage.contains("You have completed") && chatMessage.contains("Treasure"))
		{
			Matcher m = NUMBER_PATTERN.matcher(Text.removeTags(chatMessage));
			if (m.find())
			{
				clueNumber = Integer.valueOf(m.group().replace(",", ""));
				clueType = chatMessage.substring(
					chatMessage.lastIndexOf(m.group()) + m.group().length() + 1,
					chatMessage.indexOf("Treasure") - 1
				);
				return;
			}
		}

		if (chatMessage.startsWith("Your Barrows chest count is"))
		{
			Matcher m = NUMBER_PATTERN.matcher(Text.removeTags(chatMessage));
			if (m.find())
			{
				killType = KillType.BARROWS;
				killCountNumber = Integer.valueOf(m.group().replace(",", ""));
				return;
			}
		}

		if (chatMessage.startsWith("Your completed Chambers of Xeric count is:"))
		{
			Matcher m = NUMBER_PATTERN.matcher(Text.removeTags(chatMessage));
			if (m.find())
			{
				killType = KillType.COX;
				killCountNumber = Integer.valueOf(m.group().replace(",", ""));
				return;
			}
		}

		if (chatMessage.startsWith("Your completed Chambers of Xeric Challenge Mode count is:"))
		{
			Matcher m = NUMBER_PATTERN.matcher(Text.removeTags(chatMessage));
			if (m.find())
			{
				killType = KillType.COX_CM;
				killCountNumber = Integer.valueOf(m.group().replace(",", ""));
				return;
			}
		}

		if (chatMessage.startsWith("Your completed Theatre of Blood"))
		{
			Matcher m = NUMBER_PATTERN.matcher(Text.removeTags(chatMessage));
			if (m.find())
			{
				killType = chatMessage.contains("Hard Mode") ? KillType.TOB_HM
					: (chatMessage.contains("Story Mode") ? KillType.TOB_SM : KillType.TOB);
				killCountNumber = Integer.valueOf(m.group().replace(",", ""));
				return;
			}
		}

		if (chatMessage.startsWith("Your completed Tombs of Amascut"))
		{
			Matcher m = NUMBER_PATTERN.matcher(Text.removeTags(chatMessage));
			if (m.find())
			{
				killType = chatMessage.contains("Expert Mode") ? KillType.TOA_EXPERT_MODE
					: (chatMessage.contains("Entry Mode") ? KillType.TOA_ENTRY_MODE : KillType.TOA);
				killCountNumber = Integer.valueOf(m.group().replace(",", ""));
				return;
			}
		}

		if (chatMessage.startsWith("Your Lunar Chest count is"))
		{
			Matcher m = NUMBER_PATTERN.matcher(Text.removeTags(chatMessage));
			if (m.find())
			{
				killType = KillType.MOONS_OF_PERIL;
				killCountNumber = Integer.valueOf(m.group().replace(",", ""));
				return;
			}
		}

		if (chatMessage.contains("Search the chest nearby to retrieve your earned rewards!"))
		{
			killType = KillType.FORTIS_COLOSSEUM;
			return;
		}

		if (chatMessage.equals("Your request to kick/ban this user was successful.") && config.clipKick())
		{
			if (kickPlayerName == null)
			{
				return;
			}

			takeClip("Kick " + kickPlayerName, SD_FRIENDS_CHAT_KICKS);
			kickPlayerName = null;
		}

		if (PET_MESSAGES.stream().anyMatch(chatMessage::contains) && config.clipPet())
		{
			String fileName = "Pet";
			takeClip(fileName, SD_PETS);
		}

		if (config.clipBossKills())
		{
			Matcher m = BOSSKILL_MESSAGE_PATTERN.matcher(chatMessage);
			if (m.find())
			{
				String bossName = Text.removeTags(m.group(1));
				String bossKillcount = m.group(2).replace(",", "");
				String fileName = bossName + "(" + bossKillcount + ")";
				takeClip(fileName, SD_BOSS_KILLS);
			}
		}

		if (chatMessage.equals(CHEST_LOOTED_MESSAGE) && config.clipRewards())
		{
			final int regionID = client.getLocalPlayer().getWorldLocation().getRegionID();
			String eventName = CHEST_LOOT_EVENTS.get(regionID);
			if (eventName != null)
			{
				takeClip(eventName, SD_CHEST_LOOT);
			}
		}

		if (config.clipValuableDrop())
		{
			Matcher m = VALUABLE_DROP_PATTERN.matcher(chatMessage);
			if (m.matches())
			{
				int valuableDropValue = Integer.parseInt(m.group(2).replace(",", ""));
				if (valuableDropValue >= config.valuableDropThreshold())
				{
					int currentTick = client.getTickCount();
					if (currentTick == 0 || currentTick != lastValuableDropTick)
					{
						lastValuableDropTick = currentTick;
						String valuableDropName = m.group(1);
						String fileName = "Valuable drop " + valuableDropName;
						takeClip(fileName, SD_VALUABLE_DROPS);
					}
				}
			}
		}

		if (config.clipUntradeableDrop() && !isInsideGauntlet())
		{
			Matcher m = UNTRADEABLE_DROP_PATTERN.matcher(chatMessage);
			if (m.matches())
			{
				int currentTick = client.getTickCount();
				if (currentTick == 0 || currentTick != lastUntradeableDropTick)
				{
					lastUntradeableDropTick = currentTick;
					String untradeableDropName = m.group(1);
					String fileName = "Untradeable drop " + untradeableDropName;
					takeClip(fileName, SD_UNTRADEABLE_DROPS);
				}
			}
		}

		if (config.clipDuels())
		{
			Matcher m = DUEL_END_PATTERN.matcher(chatMessage);
			if (m.find())
			{
				String result = m.group(1);
				String count = m.group(2).replace(",", "");
				String fileName = "Duel " + result + " (" + count + ")";
				takeClip(fileName, SD_DUELS);
			}
		}

		if (chatMessage.startsWith(COLLECTION_LOG_TEXT)
			&& client.getVarbitValue(VarbitID.OPTION_COLLECTION_NEW_ITEM) == 1
			&& config.clipCollectionLogEntries())
		{
			String entry = Text.removeTags(chatMessage).substring(COLLECTION_LOG_TEXT.length());
			String fileName = "Collection log (" + entry + ")";
			takeClip(fileName, SD_COLLECTION_LOG);
		}

		if (chatMessage.contains("combat task")
			&& config.clipCombatAchievements()
			&& client.getVarbitValue(VarbitID.CA_TASK_POPUP) == 1)
		{
			String fileName = parseCombatAchievementWidget(chatMessage);
			if (!fileName.isEmpty())
			{
				takeClip(fileName, SD_COMBAT_ACHIEVEMENTS);
			}
		}

		if (config.clipLevels())
		{
			Matcher m = LEVEL_UP_MESSAGE_PATTERN.matcher(chatMessage);
			if (m.matches())
			{
				int currentTick = client.getTickCount();
				if (currentTick == 0 || currentTick != lastLevelUpTick)
				{
					lastLevelUpTick = currentTick;
					String skillName = StringUtils.capitalize(
						m.group("skill") != null ? m.group("skill") : m.group("skill99")
					);
					String skillLevel = m.group("level") != null ? m.group("level") : "99";
					String fileName = skillName + "(" + skillLevel + ")";
					takeClip(fileName, SD_LEVELS);
				}
			}
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		String fileName;
		String clipSubDir;
		int groupId = event.getGroupId();

		if (groupId == InterfaceID.BANKPIN_KEYPAD
			|| groupId == InterfaceID.BANKPIN_SETTINGS
			|| groupId == InterfaceID.WELCOME_SCREEN)
		{
			recordingAllowed = false;
			return;
		}

		switch (groupId)
		{
			case InterfaceID.QUESTSCROLL:
			case InterfaceID.TRAIL_REWARDSCREEN:
			case InterfaceID.RAIDS_REWARDS:
			case InterfaceID.TOB_CHESTS:
			case InterfaceID.TOA_CHESTS:
			case InterfaceID.BARROWS_REWARD:
			case InterfaceID.PMOON_REWARD:
			case InterfaceID.COLOSSEUM_REWARD_CHEST_2:
				if (!config.clipRewards())
				{
					return;
				}
				break;
			case InterfaceID.LEVELUP_DISPLAY:
				if (!config.clipLevels())
				{
					return;
				}
				break;
			case InterfaceID.OBJECTBOX:
				if (!(config.clipLevels() || config.clipHighGamble()))
				{
					return;
				}
				break;
			case InterfaceID.MISC_COLLECTION:
				if (!config.clipKingdom())
				{
					return;
				}
				break;
			case InterfaceID.WILDY_LOOT_CHEST:
				if (!config.clipWildernessLootChest())
				{
					return;
				}
				break;
		}

		switch (groupId)
		{
			case InterfaceID.MISC_COLLECTION:
				fileName = "Kingdom " + LocalDate.now();
				clipSubDir = SD_KINGDOM_REWARDS;
				break;
			case InterfaceID.RAIDS_REWARDS:
				if (killType == KillType.COX)
				{
					fileName = "Chambers of Xeric(" + killCountNumber + ")";
					clipSubDir = SD_BOSS_KILLS;
					killType = null;
					killCountNumber = 0;
					break;
				}
				else if (killType == KillType.COX_CM)
				{
					fileName = "Chambers of Xeric Challenge Mode(" + killCountNumber + ")";
					clipSubDir = SD_BOSS_KILLS;
					killType = null;
					killCountNumber = 0;
					break;
				}
				return;
			case InterfaceID.TOB_CHESTS:
				if (killType != KillType.TOB && killType != KillType.TOB_SM && killType != KillType.TOB_HM)
				{
					return;
				}
				switch (killType)
				{
					case TOB:
						fileName = "Theatre of Blood(" + killCountNumber + ")";
						break;
					case TOB_SM:
						fileName = "Theatre of Blood Story Mode(" + killCountNumber + ")";
						break;
					case TOB_HM:
						fileName = "Theatre of Blood Hard Mode(" + killCountNumber + ")";
						break;
					default:
						throw new IllegalStateException();
				}
				clipSubDir = SD_BOSS_KILLS;
				killType = null;
				killCountNumber = 0;
				break;
			case InterfaceID.TOA_CHESTS:
				if (killType != KillType.TOA && killType != KillType.TOA_ENTRY_MODE && killType != KillType.TOA_EXPERT_MODE)
				{
					return;
				}
				switch (killType)
				{
					case TOA:
						fileName = "Tombs of Amascut(" + killCountNumber + ")";
						break;
					case TOA_ENTRY_MODE:
						fileName = "Tombs of Amascut Entry Mode(" + killCountNumber + ")";
						break;
					case TOA_EXPERT_MODE:
						fileName = "Tombs of Amascut Expert Mode(" + killCountNumber + ")";
						break;
					default:
						throw new IllegalStateException();
				}
				clipSubDir = SD_BOSS_KILLS;
				killType = null;
				killCountNumber = 0;
				break;
			case InterfaceID.BARROWS_REWARD:
				if (killType != KillType.BARROWS)
				{
					return;
				}
				fileName = "Barrows(" + killCountNumber + ")";
				clipSubDir = SD_BOSS_KILLS;
				killType = null;
				killCountNumber = 0;
				break;
			case InterfaceID.PMOON_REWARD:
				if (killType != KillType.MOONS_OF_PERIL)
				{
					return;
				}
				fileName = "Moons of Peril(" + killCountNumber + ")";
				clipSubDir = SD_BOSS_KILLS;
				killType = null;
				killCountNumber = 0;
				break;
			case InterfaceID.LEVELUP_DISPLAY:
			case InterfaceID.OBJECTBOX:
			case InterfaceID.QUESTSCROLL:
				shouldTakeClip = true;
				return;
			case InterfaceID.TRAIL_REWARDSCREEN:
				if (clueType == null || clueNumber == null)
				{
					return;
				}
				fileName = Character.toUpperCase(clueType.charAt(0)) + clueType.substring(1) + "(" + clueNumber + ")";
				clipSubDir = SD_CLUE_SCROLL_REWARDS;
				clueType = null;
				clueNumber = null;
				break;
			case InterfaceID.WILDY_LOOT_CHEST:
				fileName = "Loot key";
				clipSubDir = SD_WILDERNESS_LOOT_CHEST;
				break;
			case InterfaceID.COLOSSEUM_REWARD_CHEST_2:
				if (killType != KillType.FORTIS_COLOSSEUM)
				{
					return;
				}
				fileName = "Fortis Colosseum Chest";
				clipSubDir = SD_CHEST_LOOT;
				killType = null;
				break;
			default:
				return;
		}

		takeClip(fileName, clipSubDir);
	}

	@Subscribe
	public void onScriptPreFired(ScriptPreFired scriptPreFired)
	{
		switch (scriptPreFired.getScriptId())
		{
			case ScriptID.NOTIFICATION_START:
				notificationStarted = true;
				break;
			case ScriptID.NOTIFICATION_DELAY:
				if (!notificationStarted)
				{
					return;
				}

				String topText = client.getVarcStrValue(VarClientID.NOTIFICATION_TITLE);
				String bottomText = client.getVarcStrValue(VarClientID.NOTIFICATION_MAIN);

				log.debug("Notification: top: {} bottom: {}", topText, bottomText);

				if (topText.equalsIgnoreCase("Collection log") && config.clipCollectionLogEntries())
				{
					String entry = Text.removeTags(bottomText).substring("New item:".length());
					String fileName = "Collection log (" + entry + ")";
					takeClip(fileName, SD_COLLECTION_LOG);
				}

				if (topText.equalsIgnoreCase("Combat Task Completed!")
					&& config.clipCombatAchievements()
					&& client.getVarbitValue(VarbitID.CA_TASK_POPUP) == 0)
				{
					String[] s = bottomText.split("<.*?>");
					if (s.length > 1)
					{
						String task = s[1].replaceAll("[:?]", "");
						String fileName = "Combat task (" + task + ")";
						takeClip(fileName, SD_COMBAT_ACHIEVEMENTS);
					}
				}

				if (topText.equalsIgnoreCase("League Task Complete!") && config.clipLeagueTasks())
				{
					String[] s = bottomText.split("<.*?>");
					if (s.length > 1)
					{
						String task = s[1].replaceAll("[:?]", "");
						String fileName = "League task (" + task + ")";
						takeClip(fileName, SD_LEAGUE_TASKS);
					}
				}

				notificationStarted = false;
				break;
		}
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired scriptPostFired)
	{
		if (scriptPostFired.getScriptId() == ScriptID.DOM_LOOT_CLAIM && config.clipRewards())
		{
			takeClip("Doom of Mokhaiotl", SD_CHEST_LOOT);
		}
	}

	private void manualClip()
	{
		takeClip("", null, true);
	}

	@VisibleForTesting
	String parseLevelUpWidget(@Component int levelUpLevel)
	{
		Widget levelChild = client.getWidget(levelUpLevel);
		if (levelChild == null)
		{
			return null;
		}

		Matcher m = LEVEL_UP_PATTERN.matcher(levelChild.getText());
		if (!m.matches())
		{
			return null;
		}

		String skillName = m.group(1);
		String skillLevel = m.group(2);
		return skillName + "(" + skillLevel + ")";
	}

	@VisibleForTesting
	static String parseQuestCompletedWidget(final String text)
	{
		final Matcher questMatch1 = QUEST_PATTERN_1.matcher(text);
		final Matcher questMatch2 = QUEST_PATTERN_2.matcher(text);
		final Matcher questMatchFinal = questMatch1.matches() ? questMatch1 : questMatch2;
		if (!questMatchFinal.matches())
		{
			return "Quest(quest not found)";
		}

		String quest = questMatchFinal.group("quest");
		String verb = questMatchFinal.group("verb") != null ? questMatchFinal.group("verb") : "";

		if (verb.contains("kind of"))
		{
			quest += " partial completion";
		}
		else if (verb.contains("completely"))
		{
			quest += " II";
		}

		if (RFD_TAGS.stream().anyMatch((quest + verb)::contains))
		{
			quest = "Recipe for Disaster - " + quest;
		}

		if (WORD_QUEST_IN_NAME_TAGS.stream().anyMatch(quest::contains))
		{
			quest += " Quest";
		}

		return "Quest(" + quest + ")";
	}

	@VisibleForTesting
	static String parseBAHighGambleWidget(final String text)
	{
		final Matcher highGambleMatch = BA_HIGH_GAMBLE_REWARD_PATTERN.matcher(text);
		if (highGambleMatch.find())
		{
			String gambleCount = highGambleMatch.group("gambleCount");
			return String.format("High Gamble(%s)", gambleCount);
		}

		return "High Gamble(count not found)";
	}

	@VisibleForTesting
	static String parseCombatAchievementWidget(final String text)
	{
		final Matcher m = COMBAT_ACHIEVEMENTS_PATTERN.matcher(text);
		if (m.find())
		{
			String task = m.group("task").replaceAll("[:?]", "");
			return "Combat task (" + task + ")";
		}
		return "";
	}

	@VisibleForTesting
	void takeClip(String fileName, String subDir)
	{
		takeClip(fileName, subDir, false);
	}

	private void takeClip(String fileName, String subDir, boolean manual)
	{
		if (client.getGameState() == GameState.LOGIN_SCREEN)
		{
			log.debug("Login clip prevented");
			return;
		}

		final long triggerTimeMs = System.currentTimeMillis();
		final int clipDurationSec = Math.max(3, config.clipDuration());
		final int delaySec = manual ? 0 : Math.max(0, config.postEventDelay());
		final String playerFolder = cachedPlayerFolder;

		ScheduledFuture<?>[] holder = new ScheduledFuture<?>[1];
		ScheduledFuture<?> scheduled = executor.schedule(() ->
		{
			try
			{
				long endTimeMs = System.currentTimeMillis();
				long startTimeMs = triggerTimeMs - clipDurationSec * 1000L;
				saveClipToDisk(fileName, subDir, playerFolder, triggerTimeMs, startTimeMs, endTimeMs);
			}
			catch (Exception e)
			{
				log.debug("Error saving Auto Clip recording", e);
			}
			finally
			{
				if (holder[0] != null)
				{
					pendingFutures.remove(holder[0]);
				}
			}
		}, delaySec, TimeUnit.SECONDS);

		holder[0] = scheduled;
		if (!scheduled.isDone())
		{
			pendingFutures.add(scheduled);
		}
	}

	@VisibleForTesting
	void saveClipToDisk(
		String fileName,
		String subDir,
		String playerFolder,
		long triggerTimeMs,
		long startTimeMs,
		long endTimeMs
	) throws IOException
	{
		Filepath targetDir = getPluginDirectory();
		if (!Strings.isNullOrEmpty(playerFolder))
		{
			targetDir = targetDir.joinSegment(sanitizeFileName(playerFolder));
		}
		if (!Strings.isNullOrEmpty(subDir))
		{
			targetDir = targetDir.joinSegment(sanitizeFileName(subDir));
		}
		targetDir.createDirectories();

		String baseName = (Strings.isNullOrEmpty(fileName) ? "" : fileName + " ") + formatTime(new Date(triggerTimeMs));
		String safeBaseName = sanitizeFileName(baseName);

		String clipFileName = safeBaseName + ".mp4";
		Filepath clipFile = targetDir.joinSegment(clipFileName);
		int suffix = 1;
		while (clipFile.exists())
		{
			clipFileName = safeBaseName + String.format(" (%d).mp4", suffix++);
			clipFile = targetDir.joinSegment(clipFileName);
		}

		boolean exported;
		try (OutputStream out = clipFile.openOutputStream())
		{
			exported = clipRecorder.exportClip(out, startTimeMs, endTimeMs, config.frameRate());
		}

		if (!exported)
		{
			clipFile.deleteIfExists();
			log.debug("Skipped saving clip {} because replay buffer was empty", clipFile);
			return;
		}

		log.debug("Saved Auto Clip recording to {}", clipFile);
		if (config.notifyWhenSaved())
		{
			String message = "Saved clip: " + clipFileName;
			if (chatMessageManager != null)
			{
				String formatted = new ChatMessageBuilder()
					.append(ChatColorType.HIGHLIGHT)
					.append("[Auto Clip] " + message)
					.build();
				chatMessageManager.queue(QueuedMessage.builder()
					.type(ChatMessageType.CONSOLE)
					.runeLiteFormattedMessage(formatted)
					.build());
			}
			notifier.notify(Notification.ON, message);
		}
	}

	private boolean isInsideGauntlet()
	{
		return client.isInInstancedRegion()
			&& client.getMapRegions().length > 0
			&& (client.getMapRegions()[0] == GAUNTLET_REGION
				|| client.getMapRegions()[0] == CORRUPTED_GAUNTLET_REGION);
	}

	private static String formatTime(Date date)
	{
		synchronized (TIME_FORMAT)
		{
			return TIME_FORMAT.format(date);
		}
	}

	@VisibleForTesting
	static String sanitizeFileName(String input)
	{
		if (input == null)
		{
			return "clip";
		}

		String cleaned = BAD_FILENAME_CHARS.replaceFrom(input, "_").trim();
		while (cleaned.endsWith(".") || cleaned.endsWith(" "))
		{
			cleaned = cleaned.substring(0, cleaned.length() - 1);
		}

		if (cleaned.isEmpty())
		{
			return "clip";
		}

		String lower = cleaned.toLowerCase(Locale.ROOT);
		int dotIndex = lower.indexOf('.');
		if (dotIndex != -1)
		{
			lower = lower.substring(0, dotIndex);
		}
		if (DOS_DEVICES.contains(lower.trim()))
		{
			cleaned = "_" + cleaned;
		}

		return cleaned;
	}

	@VisibleForTesting
	int getClueNumber()
	{
		return clueNumber;
	}

	@VisibleForTesting
	int getKillCountNumber()
	{
		return killCountNumber;
	}
}
