package com.l33tnetwork.xensync;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

import org.apache.commons.validator.routines.EmailValidator;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import net.milkbowl.vault.permission.Permission;

public class XenSync extends JavaPlugin {
	
	private static final Random RANDOM = new Random();
	private static final JsonParser PARSER = new JsonParser();
	
	private static final int MAX_PASSWORD_NUM = 99999;
	
	private static String uri, username, password;
	private static final String[] PASSWORD_LIST = { "Anvil" , "Apple" , "Armor" , "Arrow" , "Bucket" , "Cactus" , "Charcoal" , "Cookie" , "Diamond" , "Emerald" , "Enchantment" , "Firework" , "Furnace" , "Hopper" , "Jukebox" , "Ladder" , "Painting" , "Pumpkin" , "Quartz" , "Rabbit" , "Redstone" , "Saddle" , "Snowball" , "Sunflower" , "Trapdoor" };
	
	// Server group name, Forum group name
	private Map<String, String> groupNameConversions = new HashMap<String, String>();

	// List of player names to skip
	private ArrayList<String> playerExceptions = new ArrayList<String>();

	private Permission permissionProvider = null;

	private static XenSync instance;

	private String profileValue;

	private boolean useMemberFeature, useRegistrationFeature, requireValidEmail, debugEnabled, useUsername;
	private String memCommand, defaultGroupName, apiLoc, apiKey;

	@Override
	public void onEnable() {
		instance = this;
		
		// Data initialization
		this.saveDefaultConfig();
		this.setupPermissions();
		this.setupDatabase();

		// Configuration fields
		uri = getConfig().getString("database-info.xenforo-mysql-uri");
		username = getConfig().getString("database-info.xenforo-mysql-user");
		password = getConfig().getString("database-info.xenforo-mysql-pass");

		this.useRegistrationFeature = getConfig().getBoolean("xenforo-registration.enable");
		this.apiLoc = getConfig().getString("xenforo-registration.api-location");
		this.apiKey = getConfig().getString("xenforo-registration.api-key");

		this.profileValue = getConfig().getString("options.username-option.custom-field");
		this.useUsername = getConfig().getBoolean("options.username-option.use-username");
		this.debugEnabled = getConfig().getBoolean("options.debug");
		
		this.useMemberFeature = getConfig().getBoolean("member-feature.enable");
		this.requireValidEmail = getConfig().getBoolean("member-feature.require-valid-email");
		this.memCommand = getConfig().getString("member-feature.command-to-run").replace("/", "");
		this.defaultGroupName = getConfig().getString("member-feature.default-group-name");

		// Setup files
		this.initializeFile("playerexceptions.txt", line -> playerExceptions.add(line));
		this.initializeFile("groupconversions.txt", line -> {
			String[] data = line.split(":");
			if (data.length != 2) return false;
			
			groupNameConversions.put(data[0], data[1]);
			return true;
		});

		// Join listener
		this.getServer().getPluginManager().registerEvents(new Listener() {
			@EventHandler
			public void onPlayerJoin(PlayerJoinEvent event) {
				final Player player = event.getPlayer();
				final String playerName = player.getName();
				
				if (playerExceptions.contains(playerName)) {
					getLogger().info("Skipping " + playerName + " as they are on the exception list");
					return;
				}

				if (debugEnabled) getLogger().info("Checking the vault group of " + playerName);
				final String forumGroup = groupNameConversions.get(permissionProvider.getPrimaryGroup(player));

				if (forumGroup != null) {
					Bukkit.getScheduler().runTaskAsynchronously(instance, () -> {
						synchronisePlayer(getUserIDFromName(playerName),getGroupIDFromName(forumGroup));
						if (debugEnabled) getLogger().info("Updated " + playerName + "'s group on the forum to " + forumGroup);
					});
				}
			}
		}, this);
	}

	private boolean setupPermissions() {
		RegisteredServiceProvider<Permission> permissionProvider = getServer().getServicesManager().getRegistration(Permission.class);
		if (permissionProvider != null) {
			this.permissionProvider = permissionProvider.getProvider();
		}
		
		if (debugEnabled) this.getLogger().info("Found and registered Vault");
		return (this.permissionProvider != null);
	}
	
	private void initializeFile(String fileName, Function<String, Boolean> inputHandler) {
		try {
			File cFile = new File(this.getDataFolder(), fileName);

			if (!cFile.exists()) {
				cFile.createNewFile();
				if (debugEnabled) this.getLogger().info(fileName + " file was not found and has been created");
			}

			BufferedReader br = new BufferedReader(new FileReader(cFile));
			String line;
			while ((line = br.readLine()) != null) {
				if (!inputHandler.apply(line)) this.getLogger().info("Invalid information provided to " + fileName);
			}
			
			if (debugEnabled) this.getLogger().info(fileName + " file has been read and parsed");
			br.close();
		} catch (IOException e){ e.printStackTrace(); }
	}

	private Connection getConnection() throws SQLException {
		return DriverManager.getConnection("jdbc:mysql://" + uri, username, password);
	}

	private void setupDatabase() {
		try {
			if (!isDriverLoaded()) {
				Class.forName("com.mysql.jdbc.Driver");
			}
		} catch (ClassNotFoundException e) {
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
			ResultSet rs = con.createStatement().executeQuery(useUsername
					? "SELECT `user_id` FROM `xf_user` WHERE (`username` = '" + name + "')"
					: "SELECT `user_id` FROM `xf_user_field_value` WHERE (`field_id` = '" + profileValue + "' AND `field_value` = '" + name + "')");
			
			if (rs.first()) {
				if (!rs.next()) {
					rs.first();
					id = rs.getInt("user_id");
				} else {
					this.getLogger().warning("Two or more forum users with the minecraft name of: " + name);
					id = -1;
				}
			} else {
				this.getLogger().warning("No forum users with minecraft name of: " + name);
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
					this.getLogger().warning("Two or more forum groups with the name of: " + name);
					return -1;
				}
			} else {
				this.getLogger().warning("No forum groups with name of: " + name);
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
	public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
		if (!(sender instanceof Player)) {
			sender.sendMessage(ChatColor.RED + "ERROR: This command can only be run by a player.");
			return true;
		}
		
		Player player = (Player) sender;
		
		if (cmd.getName().equalsIgnoreCase("member")) {
			if (!useMemberFeature) {
				sender.sendMessage(ChatColor.RED + "The member feature is not enabled");
				return true;
			}
			
			if (!permissionProvider.getPrimaryGroup(player).equalsIgnoreCase(defaultGroupName)) {
				sender.sendMessage(ChatColor.RED + "You are already at least a member.");
				return true;
			}
			
			Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
				if (!isEligableForMember(getUserIDFromName(player.getName()))) {
					player.sendMessage(ChatColor.RED + "You are not eligable to become a member yet! You must create an account with /register and verify your email. If you are still having problems, message a moderator.");
					return;
				}
				
				Bukkit.getScheduler().runTask(instance, () -> {
					Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), memCommand.replace("%player%", player.getName()));
					player.sendMessage(ChatColor.GREEN + "Success!");
				});
			});
		}
		
		else if (cmd.getName().equalsIgnoreCase("register")) {
			if (!useRegistrationFeature) {
				sender.sendMessage(ChatColor.RED + "The registration feature is not enabled");
				return true;
			}
			
			if (args.length == 1 || args.length == 2) {
	        	String email = args[0];
	        	boolean validEmail = EmailValidator.getInstance().isValid(email);
	        	String password = PASSWORD_LIST[RANDOM.nextInt(PASSWORD_LIST.length)] + RANDOM.nextInt(MAX_PASSWORD_NUM);
	        	
	        	if (useUsername && args.length == 2) {
            		sender.sendMessage(ChatColor.RED + "/register [email]");
            		return true;
	            } else if (!useUsername && args.length == 1) {
	            	sender.sendMessage(ChatColor.RED + "/register [email] [username]");
            		return true;
	            }
	        	
	        	if (!validEmail) {
	        		sender.sendMessage(ChatColor.RED + "ERROR: The email you provided is not valid. Please try again.");
					return true;
	        	}
	        	
	        	Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
		        	String site = useUsername
		        			? apiLoc + "?action=register&hash=" + apiKey + "&username=" + player.getName() + "&password=" + password + "&email=" + email
		        			: apiLoc + "?action=register&hash=" + apiKey + "&username=" + username + "&password=" + password + "&email=" + email + "&custom_fields=" + profileValue + "=" + player.getName();
		        	StringBuilder input = new StringBuilder();
		        	
	        		try (BufferedReader reader = new BufferedReader(new InputStreamReader(new URL(site).openStream()))) {
	        			reader.lines().forEach(l -> input.append(l));
	                } catch (IOException e) { 
	        			player.sendMessage(ChatColor.RED + "Could not connect to the web server (is it offline?). Contact an administrator!");
	        			
	        			if (debugEnabled) e.printStackTrace();
	        			return;
	                }
	        		
	        		JsonObject response = null;
	        		try {
	        			response = PARSER.parse(input.toString().trim()).getAsJsonObject();
	        		} catch (JsonSyntaxException e) {
	        			player.sendMessage(ChatColor.RED + "Could not parse JSON response data. Contact an administrator!");
	        			
	        			if (debugEnabled) e.printStackTrace();
	        			return;
	        		}
	        		
	        		if (response.has("error")) {
	        			if (response.get("error").getAsString().equals("7")) {
	        				if (response.has("user_error_id")) {
	        					String errorID = response.get("user_error_id").getAsString();
	        					String playerName = player.getName();
	        					
			        			if (errorID.equals("0")) { sender.sendMessage(ChatColor.RED + "ERROR: Unknown error processing registration. Please try again."); this.getLogger().info(playerName + " experienced an unknown error processing registration."); }
			        			else if (errorID.equals("30")) { sender.sendMessage(ChatColor.RED + "ERROR: Some registration fields were missing. Check your input and try again."); this.getLogger().info(playerName + " could not register due to missing fields."); }
			        			else if (errorID.equals("32")) { sender.sendMessage(ChatColor.RED + "ERROR: Your username is too short for the forums settings. If you believe you are seeing this message in error, please inform a moderator."); this.getLogger().info(playerName + " could not register due to having too short of a username."); }
			        			else if (errorID.equals("33")) { sender.sendMessage(ChatColor.RED + "ERROR: Your username is too long for the forums settings. If you believe you are seeing this message in error, please inform a moderator."); this.getLogger().info(playerName + " could not register due to having too long of a username."); }
			        			else if (errorID.equals("34") || errorID.equals("35") || errorID.equals("36") || errorID.equals("37") || errorID.equals("38") || errorID.equals("39")) { sender.sendMessage(ChatColor.RED + "ERROR: Your username contains characters or words that are not allowed. If you believe you are seeing this message in error, please inform a moderator."); }
			        			else if (errorID.equals("40")) { sender.sendMessage(ChatColor.RED + "ERROR: You have already registered or someone else is currently registered as you. If you believe you are seeing this message in error, please inform a moderator."); this.getLogger().info(playerName + " could not register because they are already registered or someone is using their name."); } 
			        			else if (errorID.equals("41") || errorID.equals("42") || errorID.equals("43")) { sender.sendMessage(ChatColor.RED + "ERROR: The email you specified either is invalid, not allowed, or already being used. If you believe you are seeing this message in error, please inform a moderator."); this.getLogger().info(playerName + " could not register because they are using an invalid email, one that is registered, or one that is banned.");}
			        			else { this.getLogger().warning("An error was found in the response, but could not find a recognizable error code. Contact the developer."); }
			        			return;
	        				}
	        				else { this.getLogger().warning("An error was found in the response, but could not find an error code to tell us what went wrong. Contact the developer."); return; }
	        			}
	        			else { this.getLogger().warning("An error was found in the response, but none that was recognizable. Contact the developer."); return; }
	        		}
	        		
	        		sender.sendMessage(new String[] {ChatColor.YELLOW + "Your username has been registered.","", ChatColor.YELLOW.toString() + ChatColor.UNDERLINE + "Here is your registration information:","", ChatColor.DARK_GRAY + "  - §dUsername: " + ChatColor.GRAY + response.get("username"), ChatColor.DARK_GRAY + "  - §dEmail Addr: " + ChatColor.GRAY + email, ChatColor.DARK_GRAY + "  - §dPassword: " + ChatColor.GRAY + password, "", ChatColor.YELLOW + "You will be receiving an email shortly to confirm.", ChatColor.RED + "Please change your password ASAP."});
					return;				        			
	        	});
	        }
			
			sender.sendMessage("/register [email]" + (!useUsername ? "[username]" : ""));
			return true;
		}
		return true;
	}
}