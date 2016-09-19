package com.l33tnetwork.xensync;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;

import net.milkbowl.vault.permission.Permission;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public class XenSync extends JavaPlugin {

	static String uri, username, password;

	// Server group name, Forum group name
	HashMap<String, String> groupNameConversions = new HashMap<String, String>();

	// List of player names to skip
	ArrayList<String> playerExceptions = new ArrayList<String>();

	Permission permission = null;

	XenSync instance;

	String profileValue;

	boolean useMemberFeature, requireValidEmail, debugEnabled, useUsername;
	String memCommand, defaultGroupName;

	public void onEnable() {
		saveDefaultConfig();

		uri = getConfig().getString("xenforo-mysql-uri");
		username = getConfig().getString("xenforo-mysql-user");
		password = getConfig().getString("xenforo-mysql-pass");

		profileValue = getConfig().getString("minecraft-name-field");
        	useUsername = getConfig().getBoolean("use-username");
		useMemberFeature = getConfig().getBoolean("member-feature.enable");
		requireValidEmail = getConfig().getBoolean("member-feature.require-valid-email");
		memCommand = getConfig().getString("member-feature.command-to-run");
		defaultGroupName = getConfig().getString("member-feature.default-group-name");
		debugEnabled = getConfig().getBoolean("debug");

		setupDatabase();

		setupConversionFile();
		setupExceptionFile();

		setupPermissions();

		this.getServer().getPluginManager().registerEvents(new Listener() {
			@EventHandler
			public void onPlayerJoin(PlayerJoinEvent event) {
				final Player player = event.getPlayer();

				if (!playerExceptions.contains(player.getName())) {

					if (debugEnabled == true) { info("Checking the vault group of " + player.getName()); }
					final String forumGroup = groupNameConversions.get(permission.getPrimaryGroup(player));

					if (forumGroup != null) {

						Bukkit.getScheduler().runTaskAsynchronously(instance,
							new Runnable() {
								public void run() {
									synchronisePlayer(getUserIDFromName(player.getName()),getGroupIDFromName(forumGroup));
									if (debugEnabled == true) { info("Updated " + player.getName() + "'s group on the forum to " + forumGroup); }
								}
							}
						);
					}
				}
				else {
					info("Skipping " + player.getName() + " as they are on the exception list");
				}
			}
		}, this);
		
		instance = this;
	}

	private boolean setupPermissions() {
		RegisteredServiceProvider<Permission> permissionProvider = getServer().getServicesManager().getRegistration(net.milkbowl.vault.permission.Permission.class);
		if (permissionProvider != null) {
			permission = permissionProvider.getProvider();
		}
		if (debugEnabled == true) { info("Found and registered Vault"); }
		return (permission != null);
	}

	private void setupConversionFile() {
		try {
			File cFile = new File(this.getDataFolder(), "groupconversions.txt");

			if (!cFile.exists()) {
				cFile.createNewFile();
				if (debugEnabled == true) { info("groupconversions.txt file was not found and has been created"); }
			}

			BufferedReader br = new BufferedReader(new FileReader(cFile));
			String line;
			while ((line = br.readLine()) != null) {
				groupNameConversions.put(line.split(":")[0], line.split(":")[1]);
			}
			if (debugEnabled == true) { info("groupconversions.txt file has been read and parsed"); }
			br.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void setupExceptionFile() {
		try {
			File eFile = new File(this.getDataFolder(), "playerexceptions.txt");

			if (!eFile.exists()) {
				eFile.createNewFile();
				if (debugEnabled == true) { info("playerexceptions.txt file was not found and has been created"); }
			}
			
			BufferedReader br = new BufferedReader(new FileReader(eFile));
			String line;
			while ((line = br.readLine()) != null) {
				playerExceptions.add(line);
			}
			br.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private static Connection getConnection() throws SQLException {
		return DriverManager.getConnection("jdbc:mysql://" + uri, username, password);
	}

	private void setupDatabase() {
		try {
			if (!isDriverLoaded()) {
				Class.forName("com.mysql.jdbc.Driver").newInstance();
			}
		} catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Always call asynchronously
	 * 
	 * @param xenforo
	 *            userid
	 * @param xenforo
	 *            groupid
	 */
	protected void synchronisePlayer(int userid, int groupid) {
		try {
			Connection con = getConnection();
			con.createStatement().executeUpdate("UPDATE `xf_user` SET `user_group_id` = '" + groupid + "' WHERE `user_id` = '" + userid + "'");
			con.createStatement().executeUpdate("UPDATE `xf_user` SET `display_style_group_id` = '" + groupid + "' WHERE `user_id` = '" + userid + "'");
			con.close();
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	protected int getUserIDFromName(String name) {
		try {
			Connection con = getConnection();
			int id;
			ResultSet rs;
			
			if (useUsername == true) {
				rs = con.createStatement().executeQuery("SELECT `user_id` FROM `xf_user` WHERE (`username` = '" + name + "')");
			}
			else {
				rs = con.createStatement().executeQuery("SELECT `user_id` FROM `xf_user_field_value` WHERE (`field_id` = '" + profileValue + "' AND `field_value` = '" + name + "')");
			}
			if (rs.first()) {
				if (!rs.next()) {
					rs.first();
					id = rs.getInt("user_id");
				} else {
					warn("Two or more forum users with the minecraft name of: " + name);
					id = -1;
				}
			} else {
				warn("No forum users with minecraft name of: " + name);
				id = -1;
			}
			rs.close();
			con.close();
			return id;
		} catch (SQLException e) {
			e.printStackTrace();
			return -1;
		}
	}

	protected int getGroupIDFromName(String name) {
		try {
			Connection con = getConnection();
			ResultSet rs = con.createStatement().executeQuery("SELECT `user_group_id` FROM `xf_user_group` WHERE `title` = '" + name + "'");
			if (rs.first()) {
				if (!rs.next()) {
					rs.first();
					return rs.getInt("user_group_id");
				} else {
					warn("Two or more forum groups with the name of: " + name);
					return -1;
				}
			} else {
				warn("No forum groups with name of: " + name);
				return -1;
			}
		} catch (SQLException e) {
			e.printStackTrace();
			return -1;
		}
	}

	private boolean isDriverLoaded() {
		boolean loaded = false;
		Enumeration<Driver> e = DriverManager.getDrivers();
		while (e.hasMoreElements()) {
			String name = e.nextElement().getClass().getName();
			if (name.equalsIgnoreCase("com.mysql.jdbc.Driver")) {
				loaded = true;
			}
		}
		return loaded;
	}

	private void warn(final String str) {
		Bukkit.getScheduler().runTask(this, new Runnable() {
			public void run() {
				Bukkit.getLogger().warning("XenSync: " + str);
			}
		});
	}
	
	private void info(final String str) {
		Bukkit.getScheduler().runTask(this, new Runnable() {
			public void run() {
				Bukkit.getLogger().info("XenSync: " + str);
			}
		});
	}

	/**
	 * Always call asynchronously
	 * 
	 * @param name
	 * @return
	 */
	private boolean isEligableForMember(int userid) {
		try {
			Connection con = getConnection();
			ResultSet rs = con.createStatement().executeQuery("SELECT `user_state` FROM `xf_user` WHERE `user_id` = '" + userid + "'");
			if (rs.first()) {
				if (!rs.next()) {
					rs.first();
					boolean valid = rs.getString("user_state").equalsIgnoreCase("valid") ? true : false;
					rs.close();
					con.close();
					return requireValidEmail ? valid : true;
				}
			}
			rs.close();
			con.close();
		} catch (SQLException e) {
			e.printStackTrace();
		}
		return false;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command,
			String label, String[] args) {
		if(useMemberFeature) {
		if (sender instanceof Player) {
			final Player player = (Player) sender;
			if (permission.getPrimaryGroup(player).equalsIgnoreCase(
					defaultGroupName)) {
				Bukkit.getScheduler().runTaskAsynchronously(this, new Runnable() {
					public void run() {
						if(isEligableForMember(getUserIDFromName(player.getName()))) {
							Bukkit.getScheduler().runTask(instance, new Runnable() {
								public void run() {
									Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), memCommand.replace("/", "").replace("%player%", player.getName()));
									player.sendMessage("Success!");
								}
							});
						} else {
							player.sendMessage(new String[] {
									"You are not eligable to become a member yet!",
									"You must create an account on our website",
									"enter your minecraft name into the designated field",
									"and verify your email",
									"If you are still havng problems, message a moderator."
							});
						}
					}
				});
				return true;
			} else {
				sender.sendMessage("You are already at least a member.");
				return true;
			}
		} else {
			sender.sendMessage("You must be a member to perform this command.");
			return true;
		}
		} else {
			sender.sendMessage("The member system is not enabled.");
			return true;
		}
	}
}
