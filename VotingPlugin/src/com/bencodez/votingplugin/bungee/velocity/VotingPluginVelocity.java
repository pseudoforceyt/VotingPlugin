package com.bencodez.votingplugin.bungee.velocity;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.security.CodeSource;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.bstats.charts.SimplePie;
import org.bstats.velocity.Metrics;
import org.slf4j.Logger;

import com.bencodez.advancedcore.api.misc.ArrayUtils;
import com.bencodez.advancedcore.api.misc.encryption.EncryptionHandler;
import com.bencodez.advancedcore.api.misc.jsonparser.JsonParser;
import com.bencodez.advancedcore.api.user.usercache.value.DataValue;
import com.bencodez.advancedcore.api.user.usercache.value.DataValueInt;
import com.bencodez.advancedcore.api.user.usercache.value.DataValueString;
import com.bencodez.advancedcore.api.user.userstorage.Column;
import com.bencodez.advancedcore.bungeeapi.mysql.VelocityMySQL;
import com.bencodez.advancedcore.bungeeapi.sockets.ClientHandler;
import com.bencodez.advancedcore.bungeeapi.sockets.SocketHandler;
import com.bencodez.advancedcore.bungeeapi.sockets.SocketReceiver;
import com.bencodez.votingplugin.bungee.BungeeMessageData;
import com.bencodez.votingplugin.bungee.BungeeMethod;
import com.bencodez.votingplugin.bungee.BungeeVersion;
import com.bencodez.votingplugin.bungee.OfflineBungeeVote;
import com.bencodez.votingplugin.bungee.TimeHandle;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.vexsoftware.votifier.velocity.event.VotifierEvent;

import lombok.Getter;
import ninja.leaping.configurate.ConfigurationNode;
import ninja.leaping.configurate.yaml.YAMLConfigurationLoader;

@Plugin(id = "votingplugin", name = "VotingPlugin", version = "1.0", url = "https://www.spigotmc.org/resources/votingplugin.15358/", description = "VotingPlugin Velocity Version", authors = {
		"BenCodez" }, dependencies = { @Dependency(id = "nuvotifier", optional = true) })
public class VotingPluginVelocity {

	private static final ChannelIdentifier CHANNEL = MinecraftChannelIdentifier.create("vp", "vp");
	private HashMap<String, ArrayList<OfflineBungeeVote>> cachedOnlineVotes = new HashMap<String, ArrayList<OfflineBungeeVote>>();
	private HashMap<RegisteredServer, ArrayList<OfflineBungeeVote>> cachedVotes = new HashMap<RegisteredServer, ArrayList<OfflineBungeeVote>>();

	private HashMap<String, ClientHandler> clientHandles;

	@Getter
	private Config config;
	private final Path dataDirectory;
	private EncryptionHandler encryptionHandler;
	@Getter
	private final Logger logger;

	@Getter
	private BungeeMethod method;

	private final Metrics.Factory metricsFactory;

	@Getter
	private VelocityMySQL mysql;

	private NonVotedPlayersCache nonVotedPlayersCache;

	private final ProxyServer server;

	private SocketHandler socketHandler;

	private VoteCache voteCacheFile;

	private Timer timer;

	@Getter
	private TimeHandle timeHandle;

	@Inject
	public VotingPluginVelocity(ProxyServer server, Logger logger, Metrics.Factory metricsFactory,
			@DataDirectory Path dataDirectory) {
		this.server = server;
		this.logger = logger;
		this.dataDirectory = dataDirectory;
		this.metricsFactory = metricsFactory;
		timer = new Timer();
	}

	public synchronized void checkCachedVotes(RegisteredServer serverToCheck) {
		if (!serverToCheck.getPlayersConnected().isEmpty()) {
			if (cachedVotes.containsKey(serverToCheck)
					&& !config.getBlockedServers().contains(serverToCheck.getServerInfo().getName())) {
				ArrayList<OfflineBungeeVote> c = cachedVotes.get(serverToCheck);
				ArrayList<OfflineBungeeVote> newSet = new ArrayList<OfflineBungeeVote>();
				if (!c.isEmpty()) {
					for (OfflineBungeeVote cache : c) {
						boolean toSend = true;
						if (getConfig().getWaitForUserOnline()) {
							Player p = null;
							if (server.getPlayer(UUID.fromString(cache.getUuid())).isPresent()) {
								p = server.getPlayer(UUID.fromString(cache.getUuid())).get();
							}
							if (p == null || !p.isActive()) {
								toSend = false;
							} else if (p != null && p.isActive() && !p.getCurrentServer().get().getServerInfo()
									.getName().equals(serverToCheck.getServerInfo().getName())) {
								toSend = false;
							}

						}
						if (toSend) {
							sendPluginMessageServer(serverToCheck, "Vote", cache.getPlayerName(), cache.getUuid(),
									cache.getService(), "" + cache.getTime(), Boolean.FALSE.toString(),
									"" + cache.isRealVote(), cache.getText(), "" + getConfig().getBungeeManageTotals(),
									"" + BungeeVersion.getPluginMessageVersion(), "" + config.getBroadcast());
						} else {
							debug("Not sending vote because user isn't on server " + serverToCheck + ": "
									+ cache.toString());
							newSet.add(cache);
						}
					}
					cachedVotes.put(serverToCheck, newSet);
				}
			}
		}

	}

	public synchronized void checkOnlineVotes(Player player, String uuid, RegisteredServer serverToCheck) {
		if (player != null && player.isActive() && cachedOnlineVotes.containsKey(uuid)) {
			ArrayList<OfflineBungeeVote> c = cachedOnlineVotes.get(uuid);
			if (!c.isEmpty()) {
				if (serverToCheck == null) {
					serverToCheck = player.getCurrentServer().get().getServer();
				}
				if (!config.getBlockedServers().contains(serverToCheck.getServerInfo().getName())) {
					for (OfflineBungeeVote cache : c) {
						sendPluginMessageServer(serverToCheck, "VoteOnline", cache.getPlayerName(), cache.getUuid(),
								cache.getService(), "" + cache.getTime(), Boolean.FALSE.toString(),
								"" + cache.isRealVote(), cache.getText(), "" + getConfig().getBungeeManageTotals(),
								"" + BungeeVersion.getPluginMessageVersion(), "" + config.getBroadcast());
					}
					cachedOnlineVotes.put(uuid, new ArrayList<OfflineBungeeVote>());
				}
			}
		}
	}

	public void debug(String msg) {
		if (config.getDebug()) {
			logger.info("Debug: " + msg);
		}
	}

	public UUID fetchUUID(String playerName) throws Exception {
		// Get response from Mojang API
		URL url = new URL("https://api.mojang.com/users/profiles/minecraft/" + playerName);
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();
		connection.connect();

		if (connection.getResponseCode() == 400) {
			logger.info("There is no player with the name \"" + playerName + "\"!");
			return null;
		}

		InputStream inputStream = connection.getInputStream();
		BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));

		// Parse JSON response and get UUID
		JsonElement element = JsonParser.parseReader(bufferedReader);
		JsonObject object = element.getAsJsonObject();
		String uuidAsString = object.get("id").getAsString();

		// Return UUID
		return parseUUIDFromString(uuidAsString);
	}

	public String getProperName(String uuid, String currentName) {
		if (server.getPlayer(UUID.fromString(uuid)).isPresent()) {
			Player p = server.getPlayer(UUID.fromString(uuid)).get();
			if (p != null && p.isActive()) {
				return p.getUsername();
			}
		}
		return currentName;
	}

	public String getUUID(String playerName) {
		if (server.getPlayer(playerName).isPresent()) {
			Player p = server.getPlayer(playerName).get();
			if (p != null && p.isActive()) {
				return p.getUniqueId().toString();
			}
		}
		if (mysql != null) {
			String str = mysql.getUUID(playerName);
			if (str != null) {
				return str;
			}
		}
		if (nonVotedPlayersCache != null) {
			return nonVotedPlayersCache.playerExists(playerName);
		}
		return "";
	}

	private int getValue(ArrayList<Column> cols, String column, int toAdd) {
		for (Column d : cols) {
			if (d.getName().equalsIgnoreCase(column)) {

				DataValue value = d.getValue();
				int num = 0;
				if (value.isInt()) {
					num = value.getInt();
				} else if (value.isString()) {
					try {
						num = Integer.parseInt(value.getString());
					} catch (Exception e) {
					}
				}
				return num + toAdd;
			}
		}
		return toAdd;
	}

	private void loadMysql() {
		mysql = new VelocityMySQL("VotingPlugin_Users", config) {

			@Override
			public void severe(String str) {
				getLogger().error(str);
			}

			@Override
			public void debug(SQLException e) {
				if (config.getDebug()) {
					e.printStackTrace();
				}
			}
		};
		// column types
		getMysql().alterColumnType("TopVoterIgnore", "VARCHAR(5)");
		getMysql().alterColumnType("CheckWorld", "VARCHAR(5)");
		getMysql().alterColumnType("Reminded", "VARCHAR(5)");
		getMysql().alterColumnType("DisableBroadcast", "VARCHAR(5)");
		getMysql().alterColumnType("LastOnline", "VARCHAR(20)");
		getMysql().alterColumnType("PlayerName", "VARCHAR(30)");
		getMysql().alterColumnType("DailyTotal", "INT DEFAULT '0'");
		getMysql().alterColumnType("WeeklyTotal", "INT DEFAULT '0'");
		getMysql().alterColumnType("DayVoteStreak", "INT DEFAULT '0'");
		getMysql().alterColumnType("BestDayVoteStreak", "INT DEFAULT '0'");
		getMysql().alterColumnType("WeekVoteStreak", "INT DEFAULT '0'");
		getMysql().alterColumnType("BestWeekVoteStreak", "INT DEFAULT '0'");
		getMysql().alterColumnType("VotePartyVotes", "INT DEFAULT '0'");
		getMysql().alterColumnType("MonthVoteStreak", "INT DEFAULT '0'");
		getMysql().alterColumnType("Points", "INT DEFAULT '0'");
		getMysql().alterColumnType("HighestDailyTotal", "INT DEFAULT '0'");
		getMysql().alterColumnType("AllTimeTotal", "INT DEFAULT '0'");
		getMysql().alterColumnType("HighestMonthlyTotal", "INT DEFAULT '0'");
		getMysql().alterColumnType("MilestoneCount", "INT DEFAULT '0'");
		getMysql().alterColumnType("MonthTotal", "INT DEFAULT '0'");
		getMysql().alterColumnType("HighestWeeklyTotal", "INT DEFAULT '0'");
		getMysql().alterColumnType("LastMonthTotal", "INT DEFAULT '0'");
		getMysql().alterColumnType("OfflineRewards", "MEDIUMTEXT");
		getMysql().alterColumnType("DayVoteStreakLastUpdate", "MEDIUMTEXT");
	}

	@Subscribe
	public void onPluginMessagingReceived(PluginMessageEvent event) {
		if (event.getIdentifier().getId().equals(CHANNEL.getId())) {
			ByteArrayInputStream instream = new ByteArrayInputStream(event.getData());
			DataInputStream in = new DataInputStream(instream);
			try {
				ByteArrayOutputStream outstream = new ByteArrayOutputStream();
				DataOutputStream out = new DataOutputStream(outstream);
				String subchannel = in.readUTF();
				int size = in.readInt();

				// check for status message returns
				if (subchannel.equalsIgnoreCase("statusokay")) {
					String server = in.readUTF();
					logger.info("Status okay for " + server);
					return;
				} else if (subchannel.equalsIgnoreCase("login")) {
					String player = in.readUTF();
					debug("Login: " + player);
					if (server.getPlayer(player).isPresent()) {
						Player p = server.getPlayer(player).get();
						if (p.getCurrentServer().isPresent()) {
							final RegisteredServer server = p.getCurrentServer().get().getServer();
							final Player p1 = p;
							timer.schedule(new TimerTask() {

								@Override
								public void run() {
									checkCachedVotes(server);
									checkOnlineVotes(p1, p.getUniqueId().toString(), server);
								}
							}, 0);

						}
					}
					return;
				} else if (subchannel.equalsIgnoreCase("timeupdate")) {
					String str = in.readUTF();
					String[] data = str.split(Pattern.quote("//"));
					if (data.length == 3) {
						timeHandle.setMonth(data[0]);
						timeHandle.setDay(Integer.parseInt(data[1]));
						timeHandle.setWeek(Integer.parseInt(data[2]));
					}
				} else {

					// reforward message
					out.writeUTF(subchannel);
					out.writeInt(size);
					for (int i = 0; i < size; i++) {
						out.writeUTF(in.readUTF());
					}
					for (RegisteredServer send : server.getAllServers()) {

						if (send.getPlayersConnected().size() > 0) {
							send.sendPluginMessage(CHANNEL, outstream.toByteArray());
						}
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	@Subscribe
	public void onProxyDisable(ProxyShutdownEvent event) {
		if (method.equals(BungeeMethod.PLUGINMESSAGING)) {
			logger.info("VotingPlugin saving vote cache: " + cachedVotes.size() + "/" + cachedOnlineVotes.size());
			for (Entry<RegisteredServer, ArrayList<OfflineBungeeVote>> entry : cachedVotes.entrySet()) {
				RegisteredServer server = entry.getKey();
				int num = 0;
				for (OfflineBungeeVote voteData : entry.getValue()) {
					voteCacheFile.addVote(server.getServerInfo().getName(), num, voteData);
					num++;
				}
			}
			for (Entry<String, ArrayList<OfflineBungeeVote>> entry : cachedOnlineVotes.entrySet()) {
				String name = entry.getKey();
				int num = 0;
				for (OfflineBungeeVote voteData : entry.getValue()) {
					voteCacheFile.addVoteOnline(name, num, voteData);
					num++;
				}
			}
		}
		timeHandle.save();
		voteCacheFile.save();
		nonVotedPlayersCache.save();
		if (mysql != null) {
			mysql.shutDown();
		}
		logger.info("VotingPlugin disabled");
	}

	@Subscribe
	public void onProxyInitialization(ProxyInitializeEvent event) {
		File configFile = new File(dataDirectory.toFile(), "bungeeconfig.yml");
		configFile.getParentFile().mkdirs();
		if (!configFile.exists()) {
			try {
				configFile.createNewFile();
			} catch (IOException e) {
				e.printStackTrace();
			}

			InputStream toCopyStream = VotingPluginVelocity.class.getClassLoader()
					.getResourceAsStream("bungeeconfig.yml");

			try (FileOutputStream fos = new FileOutputStream(configFile)) {
				byte[] buf = new byte[2048];
				int r;
				while (-1 != (r = toCopyStream.read(buf))) {
					fos.write(buf, 0, r);
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		config = new Config(configFile);
		server.getChannelRegistrar().register(CHANNEL);
		method = BungeeMethod.getByName(config.getBungeeMethod());
		boolean mysqlLoaded = true;
		try {
			if (!config.getString(config.getNode("Host"), "").isEmpty()) {
				loadMysql();
			} else {
				mysqlLoaded = false;
				logger.error("MySQL settings not set in bungeeconfig.yml");
			}
		} catch (Exception e) {
			mysqlLoaded = false;
			e.printStackTrace();
		}

		CommandMeta meta = server.getCommandManager().metaBuilder("votingpluginbungee")
				// Specify other aliases (optional)
				.aliases("vpb").build();

		server.getCommandManager().register(meta, new VotingPluginVelocityCommand(this));

		if (mysqlLoaded) {
			voteCacheFile = new VoteCache(new File(dataDirectory.toFile(), "votecache.yml"));
			nonVotedPlayersCache = new NonVotedPlayersCache(
					new File(dataDirectory.toFile(), "nonvotedplayerscache.yml"), this);

			timeHandle = new TimeHandle(voteCacheFile.getNode("Time", "Month").getString(""),
					voteCacheFile.getNode("Time", "Day").getInt(), voteCacheFile.getNode("Time", "Week").getInt()) {

				@Override
				public void save() {
					voteCacheFile.getNode("Time", "Month").setValue(timeHandle.getMonth());
					voteCacheFile.getNode("Time", "Day").setValue(timeHandle.getDay());
					voteCacheFile.getNode("Time", "Week").setValue(timeHandle.getWeek());
				}
			};

			if (method.equals(BungeeMethod.PLUGINMESSAGING)) {
				try {
					for (String serverToCheck : voteCacheFile.getServers()) {
						ArrayList<OfflineBungeeVote> vote = new ArrayList<OfflineBungeeVote>();
						for (String num : voteCacheFile.getServerVotes(serverToCheck)) {
							ConfigurationNode data = voteCacheFile.getServerVotes(serverToCheck, num);

							vote.add(new OfflineBungeeVote(data.getNode("Name").getString(),
									data.getNode("UUID").getString(), data.getNode("Service").getString(),
									data.getNode("Time").getLong(), data.getNode("Real").getBoolean(),
									data.getNode("TEXT").getString()));
						}
						cachedVotes.put(server.getServer(serverToCheck).get(), vote);
					}
				} catch (Exception e) {
					e.printStackTrace();
				}

				try {
					for (String player : voteCacheFile.getPlayers()) {
						ArrayList<OfflineBungeeVote> vote = new ArrayList<OfflineBungeeVote>();
						for (String num : voteCacheFile.getOnlineVotes(player)) {
							ConfigurationNode data = voteCacheFile.getOnlineVotes(player, num);
							vote.add(new OfflineBungeeVote(data.getNode("Name").getString(),
									data.getNode("UUID").getString(), data.getNode("Service").getString(),
									data.getNode("Time").getLong(), data.getNode("Real").getBoolean(),
									data.getNode("TEXT").getString()));
						}
						cachedOnlineVotes.put(player, vote);
					}
				} catch (Exception e) {
					e.printStackTrace();
				}

				voteCacheFile.clearData();

				server.getScheduler().buildTask(this, () -> {
					for (RegisteredServer server : cachedVotes.keySet()) {
						checkCachedVotes(server);
					}

					for (String player : cachedOnlineVotes.keySet()) {
						if (server.getPlayer(UUID.fromString(player)).isPresent()) {
							checkOnlineVotes(server.getPlayer(UUID.fromString(player)).get(), player, null);
						}
					}
				}).delay(15L, TimeUnit.SECONDS).repeat(30l, TimeUnit.SECONDS).schedule();

				server.getScheduler().buildTask(this, () -> {
					if (nonVotedPlayersCache != null) {
						debug("Checking nonvotedplayerscache.yml...");
						nonVotedPlayersCache.check();
					}
				}).delay(1L, TimeUnit.MINUTES).repeat(60l, TimeUnit.MINUTES).schedule();
			} else if (method.equals(BungeeMethod.SOCKETS)) {
				encryptionHandler = new EncryptionHandler(new File(dataDirectory.toFile(), "secretkey.key"));

				socketHandler = new SocketHandler(
						server.getPluginManager().getPlugin("votingplugin").get().getDescription().getVersion().get(),
						config.getBungeeHost(), config.getBungeePort(), encryptionHandler, config.getDebug());

				socketHandler.add(new SocketReceiver() {

					@Override
					public void onReceive(String[] data) {
						if (data.length > 1) {
							if (data.length > 2) {
								if (data[0].equalsIgnoreCase("Broadcast")) {
									sendServerMessage(data);
								}
							}
						}

					}
				});

				socketHandler.add(new SocketReceiver() {

					@Override
					public void onReceive(String[] data) {
						if (data.length > 1) {
							if (data[0].equalsIgnoreCase("StatusOkay")) {
								String server = data[1];
								logger.info("Voting communicaton okay with " + server);
							}
						}

					}
				});

				clientHandles = new HashMap<String, ClientHandler>();
				List<String> l = config.getBlockedServers();
				for (ConfigurationNode d : config.getSpigotServers()) {
					String s = d.getKey().toString();
					if (!l.contains(s)) {
						clientHandles.put(s, new ClientHandler(d.getNode("Host").getString("0.0.0.0"),
								d.getNode("Port").getInt(1298), encryptionHandler, config.getDebug()));
					}
				}
			}
		}

		currentVotePartyVotesRequired = getConfig().getVotePartyVotesRequired()
				+ voteCacheFile.getVotePartyInreaseVotesRequired();
		votePartyVotes = voteCacheFile.getVotePartyCurrentVotes();

		try {
			getVersionFile();
			if (versionFile != null) {
				versionFile.delete();
				versionFile.getParentFile().delete();
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

		Metrics metrics = metricsFactory.make(this, 11547);

		metrics.addCustomChart(new SimplePie("bungee_method", () -> getConfig().getBungeeMethod().toString()));

		metrics.addCustomChart(new SimplePie("sendtoallservers", () -> "" + getConfig().getSendVotesToAllServers()));

		metrics.addCustomChart(new SimplePie("allowunjoined", () -> "" + getConfig().getAllowUnJoined()));

		metrics.addCustomChart(new SimplePie("pointsonvote", () -> "" + getConfig().getPointsOnVote()));

		metrics.addCustomChart(new SimplePie("bungeemanagetotals", () -> "" + getConfig().getBungeeManageTotals()));

		metrics.addCustomChart(new SimplePie("waitforuseronline", () -> "" + getConfig().getWaitForUserOnline()));

		metrics.addCustomChart(new SimplePie("plugin_version", () -> "" + version));

		if (!buildNumber.equals("NOTSET")) {
			metrics.addCustomChart(new SimplePie("dev_build_number", () -> "" + buildNumber));
		}

		logger.info("VotingPlugin velocity loaded, method: " + method.toString() + ", PluginMessagingVersion: "
				+ BungeeVersion.getPluginMessageVersion() + ", Internal Jar Version: " + version);
		if (!buildNumber.equals("NOTSET")) {
			logger.info("Detected using dev build number: " + buildNumber);
		}
	}

	private String version = "";
	private File versionFile;
	private String buildNumber = "NOTSET";

	private void getVersionFile() {
		try {
			CodeSource src = this.getClass().getProtectionDomain().getCodeSource();
			if (src != null) {
				URL jar = src.getLocation();
				ZipInputStream zip = null;
				zip = new ZipInputStream(jar.openStream());
				while (true) {
					ZipEntry e = zip.getNextEntry();
					if (e != null) {
						String name = e.getName();
						if (name.equals("votingpluginversion.yml")) {
							Reader defConfigStream = new InputStreamReader(zip);
							if (defConfigStream != null) {
								versionFile = new File(dataDirectory.toFile(),
										"tmp" + File.separator + "votingpluginversion.yml");
								if (!versionFile.exists()) {
									versionFile.getParentFile().mkdirs();
									versionFile.createNewFile();
								}
								FileWriter fileWriter = new FileWriter(versionFile);

								int charVal;
								while ((charVal = defConfigStream.read()) != -1) {
									fileWriter.append((char) charVal);
								}

								fileWriter.close();
								YAMLConfigurationLoader loader = YAMLConfigurationLoader.builder().setFile(versionFile)
										.build();
								defConfigStream.close();
								ConfigurationNode node = loader.load();
								if (node != null) {
									version = node.getNode("version").getString("");
									buildNumber = node.getNode("buildnumber").getString("NOTSET");
								}
								return;
							}
						}
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Subscribe
	public void onVotifierEvent(VotifierEvent event) {
		final String serviceSiteVote = event.getVote().getServiceName();
		final String name = event.getVote().getUsername();
		timer.schedule(new TimerTask() {

			@Override
			public void run() {
				String serviceSite = serviceSiteVote;
				logger.info("Vote received " + name + " from service site " + serviceSite);
				if (serviceSite.isEmpty()) {
					serviceSite = "Empty";
				}
				vote(name, serviceSite, true);
			}
		}, 0);

	}

	private UUID parseUUIDFromString(String uuidAsString) {
		String[] parts = { "0x" + uuidAsString.substring(0, 8), "0x" + uuidAsString.substring(8, 12),
				"0x" + uuidAsString.substring(12, 16), "0x" + uuidAsString.substring(16, 20),
				"0x" + uuidAsString.substring(20, 32) };

		long mostSigBits = Long.decode(parts[0]).longValue();
		mostSigBits <<= 16;
		mostSigBits |= Long.decode(parts[1]).longValue();
		mostSigBits <<= 16;
		mostSigBits |= Long.decode(parts[2]).longValue();

		long leastSigBits = Long.decode(parts[3]).longValue();
		leastSigBits <<= 48;
		leastSigBits |= Long.decode(parts[4]).longValue();

		return new UUID(mostSigBits, leastSigBits);
	}

	@Subscribe
	public void playerServerConnected(ServerConnectedEvent event) {
		checkCachedVotes(event.getServer());
		checkOnlineVotes(event.getPlayer(), event.getPlayer().getUniqueId().toString(), event.getServer());
	}

	public void reload(boolean loadMySQL) {
		config.reload();
		currentVotePartyVotesRequired = getConfig().getVotePartyVotesRequired()
				+ voteCacheFile.getVotePartyInreaseVotesRequired();
		if (loadMySQL) {
			if (!config.getString(config.getNode("Host"), "").isEmpty()) {
				loadMysql();
			} else {
				logger.error("MySQL settings not set in bungeeconfig.yml");
			}
		}
	}

	public void sendPluginMessageServer(RegisteredServer s, String channel, String... messageData) {
		ByteArrayOutputStream byteOutStream = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(byteOutStream);
		try {
			out.writeUTF(channel);
			out.writeInt(messageData.length);
			for (String message : messageData) {
				out.writeUTF(message);
			}
			if (s.getPlayersConnected().size() > 0) {
				s.sendPluginMessage(CHANNEL, byteOutStream.toByteArray());
			}
			out.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
		debug("Sending plugin message " + s.getServerInfo().getName() + " " + channel + " "
				+ ArrayUtils.getInstance().makeStringList(ArrayUtils.getInstance().convert(messageData)));

	}

	public void sendServerMessage(String... messageData) {
		for (ClientHandler h : clientHandles.values()) {
			h.sendMessage(messageData);
		}
	}

	public void sendServerMessageServer(String server, String... messageData) {
		if (clientHandles.containsKey(server)) {
			clientHandles.get(server).sendMessage(messageData);
		}
	}

	public void sendSocketVote(String name, String service, BungeeMessageData text) {
		String uuid = getUUID(name);

		if (config.getSendVotesToAllServers()) {
			sendServerMessage("bungeevote", uuid, name, service, text.toString(),
					"" + getConfig().getBungeeManageTotals());
			if (config.getBroadcast()) {
				sendServerMessage("BungeeBroadcast", service, uuid, name);
			}
		} else {
			// online server only
			Player p = server.getPlayer(name).get();

			String server = "";
			if (p != null && p.isActive()) {
				server = p.getCurrentServer().get().getServerInfo().getName();
			} else {
				server = config.getFallBack();
			}
			if (config.getBlockedServers().contains(server)) {
				server = config.getFallBack();
			}

			sendServerMessageServer(server, "bungeevoteonline", uuid, name, service, text.toString(),
					"" + getConfig().getBungeeManageTotals());
			if (config.getBroadcast()) {
				sendServerMessage("BungeeBroadcast", service, uuid, name);
			}
			sendServerMessage("BungeeUpdate");
		}

	}

	public void status() {
		if (method.equals(BungeeMethod.SOCKETS)) {
			sendServerMessage("status");
		} else if (method.equals(BungeeMethod.PLUGINMESSAGING)) {
			for (RegisteredServer s : server.getAllServers()) {
				if (!config.getBlockedServers().contains(s.getServerInfo().getName())) {
					if (s.getPlayersConnected().size() == 0) {
						getLogger().info("No players on server " + s + " to send test status message");
					} else {
						// send
						getLogger().info("Sending request for status message on " + s);
						sendPluginMessageServer(s, "Status", s.getServerInfo().getName());
					}
				} else {
					getLogger().info("Ignoring blocked server " + s);
				}
			}
		}
	}

	public synchronized void vote(String player, String service, boolean realVote) {
		try {
			if (player == null || player.isEmpty()) {
				logger.info("No name from vote on " + service);
				return;
			}

			String uuid = getUUID(player);
			if (uuid.isEmpty()) {
				if (config.getAllowUnJoined()) {
					debug("Fetching UUID online, since allowunjoined is enabled");
					UUID u = null;
					try {
						u = fetchUUID(player);
					} catch (Exception e) {
						if (getConfig().getDebug()) {
							e.printStackTrace();
						}
					}
					if (u == null) {
						debug("Failed to get uuid for " + player);
						return;
					}
					uuid = u.toString();
				} else {
					logger.info("Ignoring vote from " + player + " since player hasn't joined before");
					return;
				}
			}

			player = getProperName(uuid, player);

			BungeeMessageData text = null;

			addVoteParty();

			if (getConfig().getBungeeManageTotals()) {

				if (mysql == null) {
					logger.error("Mysql is not loaded correctly, stopping vote processing");
					return;
				}

				if (!mysql.getUuids().contains(uuid)) {
					mysql.update(uuid, "PlayerName", new DataValueString(player));
				}

				ArrayList<Column> data = mysql.getExactQuery(new Column("uuid", new DataValueString(uuid)));

				int allTimeTotal = getValue(data, "AllTimeTotal", 1);
				int monthTotal = getValue(data, "MonthTotal", 1);
				int weeklyTotal = getValue(data, "WeeklyTotal", 1);
				int dailyTotal = getValue(data, "DailyTotal", 1);
				int points = getValue(data, "Points", getConfig().getPointsOnVote());
				int milestoneCount = getValue(data, "MilestoneCount", 1);
				text = new BungeeMessageData(allTimeTotal, monthTotal, weeklyTotal, dailyTotal, points, milestoneCount,
						votePartyVotes, currentVotePartyVotesRequired);
				ArrayList<Column> update = new ArrayList<Column>();
				update.add(new Column("AllTimeTotal", new DataValueInt(allTimeTotal)));
				update.add(new Column("MonthTotal", new DataValueInt(monthTotal)));
				update.add(new Column("WeeklyTotal", new DataValueInt(weeklyTotal)));
				update.add(new Column("DailyTotal", new DataValueInt(dailyTotal)));
				update.add(new Column("Points", new DataValueInt(points)));
				update.add(new Column("MilestoneCount", new DataValueInt(milestoneCount)));
				debug("Setting totals " + text.toString());
				mysql.update(uuid, update);
			} else {
				text = new BungeeMessageData(0, 0, 0, 0, 0, 0, votePartyVotes, currentVotePartyVotesRequired);
			}

			/*
			 * String text = mysqlUpdate(data, uuid, "AllTimeTotal", 1) + "//" +
			 * mysqlUpdate(data, uuid, "MonthTotal", 1) + "//" + mysqlUpdate(data, uuid,
			 * "WeeklyTotal", 1) + "//" + mysqlUpdate(data, uuid, "DailyTotal", 1) + "//" +
			 * mysqlUpdate(data, uuid, "Points", 1) + "//" + mysqlUpdate(data, uuid,
			 * "MilestoneCount", 1);
			 */

			long time = LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

			if (method.equals(BungeeMethod.PLUGINMESSAGING)) {

				if (config.getSendVotesToAllServers()) {
					for (RegisteredServer s : server.getAllServers()) {
						if (!config.getBlockedServers().contains(s.getServerInfo().getName())) {
							boolean forceCache = false;
							Player p = null;
							if (server.getPlayer(UUID.fromString(uuid)).isPresent()) {
								p = server.getPlayer(UUID.fromString(uuid)).get();
							}
							if ((p == null || !p.isActive()) && getConfig().getWaitForUserOnline()) {
								forceCache = true;
								debug("Forcing vote to cache");
							}
							if (s.getPlayersConnected().isEmpty() || forceCache) {
								// cache
								if (!cachedVotes.containsKey(s)) {
									cachedVotes.put(s, new ArrayList<OfflineBungeeVote>());
								}
								ArrayList<OfflineBungeeVote> list = cachedVotes.get(s);
								list.add(new OfflineBungeeVote(player, uuid, service, time, realVote, text.toString()));
								cachedVotes.put(s, list);

								debug("Caching vote for " + player + " on " + service + " for " + s);

							} else {
								// send
								sendPluginMessageServer(s, "Vote", player, uuid, service, "" + time,
										Boolean.TRUE.toString(), "" + realVote, text.toString(),
										"" + getConfig().getBungeeManageTotals(),
										"" + BungeeVersion.getPluginMessageVersion(), "" + config.getBroadcast());
							}
							if (config.getBroadcast()) {
								sendPluginMessageServer(s, "VoteBroadcast", uuid, player, service);
							}
						}
					}
				} else {
					Player p = null;
					if (server.getPlayer(UUID.fromString(uuid)).isPresent()) {
						p = server.getPlayer(UUID.fromString(uuid)).get();
					}
					if (p != null && p.isActive() && !config.getBlockedServers()
							.contains(p.getCurrentServer().get().getServerInfo().getName())) {
						sendPluginMessageServer(p.getCurrentServer().get().getServer(), "VoteOnline", player, uuid,
								service, "" + time, Boolean.TRUE.toString(), "" + realVote, text.toString(),
								"" + getConfig().getBungeeManageTotals(), "" + BungeeVersion.getPluginMessageVersion(),
								"" + config.getBroadcast());
					} else {
						if (!cachedOnlineVotes.containsKey(uuid)) {
							cachedOnlineVotes.put(uuid, new ArrayList<OfflineBungeeVote>());
						}
						ArrayList<OfflineBungeeVote> list = cachedOnlineVotes.get(uuid);
						if (list == null) {
							list = new ArrayList<OfflineBungeeVote>();
						}
						list.add(new OfflineBungeeVote(player, uuid, service, time, realVote, text.toString()));
						cachedOnlineVotes.put(uuid, list);
						debug("Caching online vote for " + player + " on " + service);
					}

					for (RegisteredServer s : server.getAllServers()) {
						sendPluginMessageServer(s, "VoteUpdate", uuid, "" + votePartyVotes,
								"" + currentVotePartyVotesRequired);
						if (config.getBroadcast()) {
							sendPluginMessageServer(s, "VoteBroadcast", uuid, player, service);
						}
					}
				}
			} else if (method.equals(BungeeMethod.SOCKETS)) {
				sendSocketVote(player, service, text);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private int votePartyVotes = 0;
	private int currentVotePartyVotesRequired = 0;

	public void addCurrentVotePartyVotes(int amount) {
		votePartyVotes += amount;
		voteCacheFile.setVotePartyCurrentVotes(votePartyVotes);
		debug("Current vote party total: " + votePartyVotes);
	}

	public void addVoteParty() {
		if (getConfig().getVotePartyEnabled()) {
			addCurrentVotePartyVotes(1);

			if (votePartyVotes >= currentVotePartyVotesRequired) {
				debug("Current vote party total: " + votePartyVotes);
				addCurrentVotePartyVotes(-currentVotePartyVotesRequired);

				currentVotePartyVotesRequired += getConfig().getVotePartyIncreaseVotesRequired();
				voteCacheFile.setVotePartyInreaseVotesRequired(voteCacheFile.getVotePartyInreaseVotesRequired()
						+ getConfig().getVotePartyIncreaseVotesRequired());

				if (!getConfig().getVotePartyBroadcast().isEmpty()) {
					for (RegisteredServer server : getVotePartyServers()) {
						if (method.equals(BungeeMethod.PLUGINMESSAGING)) {
							sendPluginMessageServer(server, "VotePartyBroadcast", config.getVotePartyBroadcast());
						} else if (method.equals(BungeeMethod.SOCKETS)) {
							sendServerMessageServer(server.getServerInfo().getName(), config.getVotePartyBroadcast());
						}
					}
				}

				for (String command : getConfig().getVotePartyBungeeCommands()) {
					server.getCommandManager().executeAsync(server.getConsoleCommandSource(), command);
				}

				if (method.equals(BungeeMethod.PLUGINMESSAGING)) {
					for (RegisteredServer server : getVotePartyServers()) {
						sendVoteParty(server);
					}
				}
			}
			voteCacheFile.save();
		}
	}

	public Collection<RegisteredServer> getVotePartyServers() {
		if (getConfig().getVotePartySendToAllServers()) {
			return server.getAllServers();
		}
		Collection<RegisteredServer> list = new ArrayList<RegisteredServer>();
		for (String server : getConfig().getVotePartyServersToSend()) {
			if (this.server.getServer(server).isPresent()) {
				list.add(this.server.getServer(server).get());
			}
		}
		return list;
	}

	public void sendVoteParty(RegisteredServer server) {
		if (!server.getPlayersConnected().isEmpty()) {
			if (method.equals(BungeeMethod.PLUGINMESSAGING)) {
				sendPluginMessageServer(server, "VotePartyBungee");
			} else if (method.equals(BungeeMethod.SOCKETS)) {
				sendServerMessageServer(server.getServerInfo().getName(), "VotePartyBungee");
			}
		}
	}
}
