package com.bencodez.votingplugin.bungee;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Collection;
import java.util.List;

import lombok.Getter;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;

public class Config {
	private VotingPluginBungee bungee;
	@Getter
	private Configuration data;

	public Config(VotingPluginBungee bungee) {
		this.bungee = bungee;
	}

	public boolean getAllowUnJoined() {
		return getData().getBoolean("AllowUnJoined", false);
	}

	public List<String> getBlockedServers() {
		return getData().getStringList("BlockedServers");
	}

	public boolean getBroadcast() {
		return getData().getBoolean("Broadcast", false);
	}

	public boolean getRedisSupport() {
		return getData().getBoolean("RedisSupport", false);
	}

	public String getBungeeHost() {
		return getData().getString("BungeeServer.Host", "");
	}

	public boolean getBungeeManageTotals() {
		return getData().getBoolean("BungeeManageTotals", true);
	}

	public String getBungeeMethod() {
		return getData().getString("BungeeMethod", "SOCKETS");
	}

	public int getBungeePort() {
		return getData().getInt("BungeeServer.Port", 1297);
	}

	public boolean getDebug() {
		return getData().getBoolean("Debug", false);
	}

	public String getFallBack() {
		return getData().getString("FallBackServer", "");
	}

	public int getPointsOnVote() {
		return getData().getInt("PointsOnVote", 1);
	}

	public boolean getSendVotesToAllServers() {
		return getData().getBoolean("SendVotesToAllServers");
	}

	public Configuration getSpigotServerConfiguration(String s) {
		return getData().getSection("SpigotServers." + s);
	}

	public Collection<String> getSpigotServers() {
		return getData().getSection("SpigotServers").getKeys();
	}

	public boolean getWaitForUserOnline() {
		return getData().getBoolean("WaitForUserOnline", false);
	}

	public boolean getVotePartyEnabled() {
		return getData().getBoolean("VoteParty.Enabled", false);
	}

	public boolean getVotePartySendToAllServers() {
		return getData().getBoolean("VoteParty.SendToAllServers", true);
	}

	public int getVotePartyVotesRequired() {
		return getData().getInt("VoteParty.VotesRequired", 100);
	}

	public int getVotePartyIncreaseVotesRequired() {
		return getData().getInt("VoteParty.IncreaseVotesRequired", 0);
	}

	public String getVotePartyBroadcast() {
		return getData().getString("VoteParty.Broadcast", "");
	}

	public List<String> getVotePartyServersToSend() {
		return getData().getStringList("VoteParty.ServersToSend");
	}

	public List<String> getVotePartyBungeeCommands() {
		return getData().getStringList("VoteParty.BungeeCommands");
	}

	public void load() {
		if (!bungee.getDataFolder().exists()) {
			bungee.getDataFolder().mkdir();
		}

		File file = new File(bungee.getDataFolder(), "bungeeconfig.yml");

		if (!file.exists()) {
			try (InputStream in = bungee.getResourceAsStream("bungeeconfig.yml")) {
				Files.copy(in, file.toPath());
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		try {
			data = ConfigurationProvider.getProvider(YamlConfiguration.class)
					.load(new File(bungee.getDataFolder(), "bungeeconfig.yml"));
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public void save() {
		try {
			ConfigurationProvider.getProvider(YamlConfiguration.class).save(data,
					new File(bungee.getDataFolder(), "bungeeconfig.yml"));
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

}
