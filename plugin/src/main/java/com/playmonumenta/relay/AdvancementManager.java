package com.playmonumenta.relay;

import java.io.File;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.Map;
import java.util.Set;

import com.playmonumenta.relay.network.SocketManager;
import com.playmonumenta.relay.utils.DataPackUtils;
import com.playmonumenta.relay.utils.FileUtils;

import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Team;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class AdvancementManager implements Listener {
	private static AdvancementManager INSTANCE = null;
	private static Plugin mPlugin = null;
	private static File mConfigFile;
	private static Map<String, AdvancementRecord> mRecords = new HashMap<String, AdvancementRecord>();

	private AdvancementManager(Plugin plugin) {
		INSTANCE = this;
		mPlugin = plugin;
		mConfigFile = new File(plugin.getDataFolder(), "advancementRecords.json");
	}

	public static AdvancementManager getInstance() {
		return INSTANCE;
	}

	public static AdvancementManager getInstance(Plugin plugin) {
		if (INSTANCE == null) {
			INSTANCE = new AdvancementManager(plugin);
		}
		return INSTANCE;
	}

	public void reload() {
		// Replace current state with previously saved state.
		mRecords.clear();

		// Load all records as json
		JsonObject allRecords;
		try {
			allRecords = FileUtils.readJson(mConfigFile.getPath());
		} catch (Exception e) {
			mPlugin.getLogger().warning("No advancement records could be loaded - assuming the plugin was not previously installed.");
			return;
		}

		// Load the json into our local copy of advancement records
		try {
			for (Map.Entry<String, JsonElement> entry : allRecords.entrySet()) {
				String advancementId = entry.getKey();

				JsonObject recordJsonObject = entry.getValue().getAsJsonObject();
				AdvancementRecord record = new AdvancementRecord(recordJsonObject);

				mRecords.put(advancementId, record);
			}
		} catch (Exception e) {
			mPlugin.getLogger().log(Level.SEVERE, "Failed to load at least one advancement record. Aborting load.");
			return;
		}

		broadcastAllAdvancementRecords();

		try {
			SocketManager.broadcastAdvancementRecordRequest(mPlugin);
		} catch (Exception e) {
			mPlugin.getLogger().log(Level.SEVERE, "Failed to request remote records.");
		}
	}

	public void saveState() {
		JsonObject allRecords = new JsonObject();
		for (Map.Entry<String, AdvancementRecord> entry : mRecords.entrySet()) {
			String advancementId = entry.getKey();

			AdvancementRecord record = entry.getValue();
			JsonObject recordObject = record.toJson();

			allRecords.add(advancementId, recordObject);
		}
		try {
			FileUtils.writeJson(mConfigFile.getPath(), allRecords);
		} catch (Exception e) {
			mPlugin.getLogger().log(Level.SEVERE, "Failed to save advancement records");
		}
	}

	public void broadcastAllAdvancementRecords() {
		// Broadcast local advancement records; remote servers will ignore non-changes
		for (Map.Entry<String, AdvancementRecord> recordPair : mRecords.entrySet()) {
			String advancementId = recordPair.getKey();
			AdvancementRecord record = recordPair.getValue();

			try {
				SocketManager.broadcastAdvancementRecord(mPlugin, advancementId, record);
			} catch (Exception e) {
				mPlugin.getLogger().warning("Failed to broadcast record");
			}
		}
	}

	@EventHandler(priority = EventPriority.LOW)
	public void worldSaveEvent(WorldSaveEvent event) {
		saveState();
	}

	@EventHandler(priority = EventPriority.LOW)
	public void playerAdvancementDoneEvent(PlayerAdvancementDoneEvent event) throws Exception {
		Player player = event.getPlayer();

		Advancement advancement = event.getAdvancement();
		String advancementId = advancement.getKey().toString();

		if (!DataPackUtils.isAnnouncedToChat(advancement)) {
			return;
		}

		// Announce that the player earned the advancement to other shards
		String announcementCommand = null;
		for (JsonObject advancementJson : DataPackUtils.getAdvancementJsonObjects(advancement)) {
			announcementCommand = DataPackUtils.getChatAnnouncement(player, advancementJson);
			if (announcementCommand != null) {
				break;
			}
		}
		if (announcementCommand == null || announcementCommand.isEmpty()) {
			return;
		}

		String announceElsewhereCommand = "execute unless entity " + player.getName() + " run " + announcementCommand;
		SocketManager.broadcastCommand(mPlugin, announceElsewhereCommand);

		// Process the advancement records
		AdvancementRecord newRecord = new AdvancementRecord(player, advancement);
		AdvancementRecord oldRecord = mRecords.get(advancementId);
		if (oldRecord == null) {
			// First time this advancement was earned! As far as we know anyways.
			mRecords.put(advancementId, newRecord);
			runRecordChangeFunctions(advancementId, newRecord, null);
			SocketManager.broadcastAdvancementRecord(mPlugin, advancementId, newRecord);
		} else {
			// Not the first, but credit where it's due.
			AdvancementRecord updatedRecord = oldRecord.cloneAndUpdate(newRecord);
			mRecords.put(advancementId, updatedRecord);
			runRecordChangeFunctions(advancementId, newRecord, oldRecord);
			SocketManager.broadcastAdvancementRecord(mPlugin, advancementId, updatedRecord);
		}

		saveState();
	}

	public void addRemoteRecord(String advancementId, AdvancementRecord remoteRecord) {
		if (advancementId == null || remoteRecord == null) {
			return;
		}

		AdvancementRecord localRecord = mRecords.get(advancementId);
		if (localRecord == null) {
			// The other server got it first! I'm sure we'll go first next time.
			mRecords.put(advancementId, remoteRecord);
			runRecordChangeFunctions(advancementId, remoteRecord, null);
		} else {
			AdvancementRecord updatedRecord = localRecord.cloneAndUpdate(remoteRecord);
			mRecords.put(advancementId, updatedRecord);
			runRecordChangeFunctions(advancementId, remoteRecord, localRecord);
		}

		saveState();
	}

	private void runRecordChangeFunctions(String advancementId, AdvancementRecord newRecord, AdvancementRecord oldRecord) {
		if (advancementId == null || newRecord == null) {
			return;
		}

		applyFunctionsToPlayerTeams(advancementId, newRecord.getNewlyFirstPlayers(oldRecord), "rivals", "advancement/first_player", true);
		applyFunctionsToPlayerTeams(advancementId, newRecord.getNewlyLaterPlayers(oldRecord), "rivals", "advancement/later_player", true);
		applyFunctionsToPlayerTeams(advancementId, newRecord.getCorrectedPlayers(oldRecord), "rivals", "advancement/correct_player", true);
		applyFunctionsToTeams(advancementId, newRecord.getNewlyFirstTeams(oldRecord), "rivals", "advancement/first_team", true);
		applyFunctionsToTeams(advancementId, newRecord.getNewlyLaterTeams(oldRecord), "rivals", "advancement/later_team", true);
		applyFunctionsToTeams(advancementId, newRecord.getCorrectedTeams(oldRecord), "rivals", "advancement/correct_team", true);
	}

	private void applyFunctionsToPlayerTeams(String advancementId, Set<Map.Entry<String, String>> playerTeams, String namespace, String functionKey, boolean functionTag) {
		for (Map.Entry<String, String> entry : playerTeams) {
			DataPackUtils.runFunctionWithReplacements(namespace,
			                                          functionKey,
			                                          functionTag,
			                                          getCommandReplacements(advancementId, entry));
		}
	}

	private void applyFunctionsToTeams(String advancementId, Set<String> teams, String namespace, String functionKey, boolean functionTag) {
		for (String playerTeam : teams) {
			DataPackUtils.runFunctionWithReplacements(namespace,
			                                          functionKey,
			                                          functionTag,
			                                          getCommandReplacements(advancementId, playerTeam));
		}
	}

	private Map<String, String> getCommandReplacements(String advancementId, Map.Entry<String, String> playerTeamPair) {
		String playerName = playerTeamPair.getKey();
		String playerTeamId = playerTeamPair.getValue();

		Team playerTeam = DataPackUtils.getTeam(playerTeamId);
		String teamColor = null;
		String teamDisplayName = null;
		String teamPrefix = null;
		String teamSuffix = null;
		if (playerTeam != null) {
			if (playerTeam.getColor() != null) {
				teamColor = playerTeam.getColor().name().toLowerCase();
			}
			teamDisplayName = playerTeam.getDisplayName();
			teamPrefix = playerTeam.getPrefix();
			teamSuffix = playerTeam.getSuffix();
		}
		if (teamColor == null) {
			teamColor = "reset";
		}
		if (teamDisplayName == null) {
			teamDisplayName = playerTeamId;
		}
		if (teamPrefix == null) {
			teamPrefix = "";
		}
		if (teamSuffix == null) {
			teamSuffix = "";
		}

		Map<String, String> commandReplacements = new HashMap<String, String>();
		commandReplacements.put("__advancement__", advancementId);
		commandReplacements.put("__player__", playerName);
		commandReplacements.put("__team_id__", playerTeamId);
		commandReplacements.put("__team_color__", teamColor);
		commandReplacements.put("__team_display_name__", teamDisplayName);
		commandReplacements.put("__team_prefix__", teamPrefix);
		commandReplacements.put("__team_suffix__", teamSuffix);

		return commandReplacements;
	}

	private Map<String, String> getCommandReplacements(String advancementId, String playerTeamId) {
		Team playerTeam = DataPackUtils.getTeam(playerTeamId);
		String teamColor = null;
		String teamDisplayName = null;
		String teamPrefix = null;
		String teamSuffix = null;
		if (playerTeam != null) {
			if (playerTeam.getColor() != null) {
				teamColor = playerTeam.getColor().name().toLowerCase();
			}
			teamDisplayName = playerTeam.getDisplayName();
			teamPrefix = playerTeam.getPrefix();
			teamSuffix = playerTeam.getSuffix();
		}
		if (teamColor == null) {
			teamColor = "reset";
		}
		if (teamDisplayName == null) {
			teamDisplayName = playerTeamId;
		}
		if (teamPrefix == null) {
			teamPrefix = "";
		}
		if (teamSuffix == null) {
			teamSuffix = "";
		}

		Map<String, String> commandReplacements = new HashMap<String, String>();
		commandReplacements.put("__advancement__", advancementId);
		commandReplacements.put("__team_id__", playerTeamId);
		commandReplacements.put("__team_color__", teamColor);
		commandReplacements.put("__team_display_name__", teamDisplayName);
		commandReplacements.put("__team_prefix__", teamPrefix);
		commandReplacements.put("__team_suffix__", teamSuffix);

		return commandReplacements;
	}
}
