package com.bencodez.votingplugin.bungee.velocity;

import java.io.File;
import java.util.Collection;

import com.bencodez.advancedcore.bungeeapi.velocity.VelocityYMLFile;
import com.bencodez.votingplugin.bungee.OfflineBungeeVote;

import ninja.leaping.configurate.ConfigurationNode;

public class VoteCache extends VelocityYMLFile {

	public VoteCache(File file) {
		super(file);
	}

	public int getVotePartyCurrentVotes() {
		return getInt(getNode("VoteParty").getNode("CurrentVotes"), 0);
	}

	public int getVotePartyInreaseVotesRequired() {
		return getInt(getNode("VoteParty").getNode("IncreaseVotes"), 0);
	}

	public void setVotePartyCurrentVotes(int amount) {
		getNode("VoteParty").getNode("CurrentVotes").setValue(amount);
	}

	public void setVotePartyInreaseVotesRequired(int amount) {
		getNode("VoteParty").getNode("IncreaseVotes").setValue(amount);
	}

	public void setVotePartyCache(String server, int amount) {
		getNode("VoteParty").getNode("Cache").getNode(server).setValue(amount);
	}

	public int getVotePartyCache(String server) {
		return getNode("VoteParty").getNode("Cache").getNode(server).getInt(0);
	}

	public void addVote(String server, int num, OfflineBungeeVote voteData) {
		String[] path = new String[] { "VoteCache", server, "" + num };

		getNode(path, "Name").setValue(voteData.getPlayerName());
		getNode(path, "Service").setValue(voteData.getService());
		getNode(path, "UUID").setValue(voteData.getUuid());
		getNode(path, "Time").setValue(voteData.getTime());
		getNode(path, "Real").setValue(voteData.isRealVote());
		getNode(path, "Text").setValue(voteData.getText());
	}

	public void addVoteOnline(String player, int num, OfflineBungeeVote voteData) {
		String[] path = new String[] { "OnlineCache", player, "" + num };

		getNode(path, "Name").setValue(voteData.getPlayerName());
		getNode(path, "Service").setValue(voteData.getService());
		getNode(path, "UUID").setValue(voteData.getUuid());
		getNode(path, "Time").setValue(voteData.getTime());
		getNode(path, "Real").setValue(voteData.isRealVote());
		getNode(path, "Text").setValue(voteData.getText());
	}

	public void clearData() {
		getNode("VoteCache").setValue(null);
		getNode("OnlineCache").setValue(null);
		save();
	}

	public Collection<String> getOnlineVotes(String name) {
		return getKeys(getNode("OnlineCache", name));
	}

	public ConfigurationNode getOnlineVotes(String name, String num) {
		return getNode("OnlineCache", name, num);
	}

	public Collection<String> getPlayers() {
		return getKeys(getNode("OnlineCache"));
	}

	public Collection<String> getServers() {
		return getKeys(getNode("VoteCache"));
	}

	public Collection<String> getServerVotes(String server) {
		return getKeys(getNode("OnlineCache", server));
	}

	public ConfigurationNode getServerVotes(String server, String num) {
		return getNode("VoteCache", server, num);
	}

}
