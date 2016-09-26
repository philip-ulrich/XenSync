package com.l33tnetwork.xensync;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URLConnection;
import java.net.URL;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Map;
import java.util.HashMap; 
import java.util.Random;
import org.apache.commons.io.IOUtils;
import org.apache.commons.validator.routines.EmailValidator;
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
	static String[] passwordList = { "Anvil" , "Apple" , "Armor" , "Arrow" , "Bucket" , "Cactus" , "Charcoal" , "Cookie" , "Diamond" , "Emerald" , "Enchantment" , "Firework" , "Furnace" , "Hopper" , "Jukebox" , "Ladder" , "Painting" , "Pumpkin" , "Quartz" , "Rabbit" , "Redstone" , "Saddle" , "Snowball" , "Sunflower" , "Trapdoor" };
	static Integer maxPasswordNum;

	// Server group name, Forum group name
	HashMap<String, String> groupNameConversions = new HashMap<String, String>();

	// List of player names to skip
	ArrayList<String> playerExceptions = new ArrayList<String>();

	Permission permission = null;

	XenSync instance;

	String profileValue;

	boolean useMemberFeature, requireValidEmail, debugEnabled, useUsername, regEnabled;
	String memCommand, defaultGroupName, apiLoc, apiKey;

	public void onEnable() {
		saveDefaultConfig();

		uri = getConfig().getString("database-info.xenforo-mysql-uri");
		username = getConfig().getString("database-info.xenforo-mysql-user");
		password = getConfig().getString("database-info.xenforo-mysql-pass");
		
		regEnabled = getConfig().getBoolean("xenforo-registration.enable");
		apiLoc = getConfig().getString("xenforo-registration.api-location");
		apiKey = getConfig().getString("xenforo-registration.api-key");

		profileValue = getConfig().getString("options.username-option.custom-field");
        useUsername = getConfig().getBoolean("options.username-option.use-username");
		debugEnabled = getConfig().getBoolean("options.debug");
		
		useMemberFeature = getConfig().getBoolean("member-feature.enable");
		requireValidEmail = getConfig().getBoolean("member-feature.require-valid-email");
		memCommand = getConfig().getString("member-feature.command-to-run");
		defaultGroupName = getConfig().getString("member-feature.default-group-name");
		maxPasswordNum = 99999;
		
		
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
	public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
		if (sender instanceof Player) {
			if (cmd.getName().equalsIgnoreCase("member")) {
				if(useMemberFeature) {
					final Player player = (Player) sender;
					if (permission.getPrimaryGroup(player).equalsIgnoreCase(defaultGroupName)) {
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
									player.sendMessage("You are not eligable to become a member yet! You must create an account with /register and verify your email. If you are still having problems, message a moderator.");
								}
							}
						});
						return true;
					} else {
						sender.sendMessage("You are already at least a member.");
						return true;
					}
				} else {
					sender.sendMessage("The member feature is not enabled");
					return true;
				}
			}
			if (cmd.getName().equalsIgnoreCase("register")) {
				if (args.length == 1 || args.length == 2) {
					String json = "";
					final Player player = (Player) sender;
		        	String email = args[0];
		        	boolean validEmail = EmailValidator.getInstance().isValid(email);
		        	String password = passwordList[(int) (Math.random() * passwordList.length)] + (int) (Math.random() * maxPasswordNum);
		        	
		        	if (useUsername == true && args.length == 2) {
                		sender.sendMessage("/register [email]");
                		return true;
		            } else if (useUsername == false && args.length == 1) {
		            	sender.sendMessage("/register [email] [username]");
                		return true;
		            }
		        	
		        	if (validEmail) {
		        		URL url = null;
		                URLConnection connection = null;
		                String inputLine = "";
		                String outputLine = "";
		                
		                
		                if (useUsername) {
			                try {
			                	String site = apiLoc + "?action=register&hash=" + apiKey + "&username=" + player.getName() + "&password=" + password + "&email=" + email;
			           
			                	url = new URL(site);
			                    connection = url.openConnection();
	
			                    DataInputStream inStream = new DataInputStream(connection.getInputStream());
	
			                    while ((outputLine = inStream.readLine()) != null) {
			                    	inputLine = inputLine + outputLine;
			                    }
			                    inStream.close();
			                } catch (MalformedURLException me) {
			                    System.err.println("MalformedURLException: " + me);
			                } catch (IOException ioe) {
			                	InputStream error = ((HttpURLConnection) connection).getErrorStream();
	
			                    try {
			                        int data = error.read();
			                        while (data != -1) {
			                            inputLine = inputLine + (char)data;
			                            data = error.read();
			                        }
			                        error.close();
			                    } catch (Exception ex) {
			                        try {
			                            if (error != null) {
			                                error.close();
			                            }
			                        } catch (Exception e) {
	
			                        }
			                    }
			                }
		                } else {
		                	String username = args[1];
		                	try {
			                	String site = apiLoc + "?action=register&hash=" + apiKey + "&username=" + username + "&password=" + password + "&email=" + email + "&custom_fields=" + profileValue + "=" + player.getName();
			           
			                	url = new URL(site);
			                    connection = url.openConnection();
	
			                    DataInputStream inStream = new DataInputStream(connection.getInputStream());
	
			                    while ((outputLine = inStream.readLine()) != null) {
			                    	inputLine = inputLine + outputLine;
			                    }
			                    inStream.close();
			                } catch (MalformedURLException me) {
			                    System.err.println("MalformedURLException: " + me);
			                } catch (IOException ioe) {
			                	InputStream error = ((HttpURLConnection) connection).getErrorStream();
	
			                    try {
			                        int data = error.read();
			                        while (data != -1) {
			                            inputLine = inputLine + (char)data;
			                            data = error.read();
			                        }
			                        error.close();
			                    } catch (Exception ex) {
			                        try {
			                            if (error != null) {
			                                error.close();
			                            }
			                        } catch (Exception e) {
	
			                        }
			                    }
			                }
		                }
		               
        				Gson gson = new Gson(); 
		        		Type type = new TypeToken<Map<String, String>>(){}.getType();
		        		Map<String, String> response = gson.fromJson(inputLine, type);
		        		
		        		if (response.containsKey("error")) {
		        			if (response.get("error").equals("7")) {
		        				if (response.containsKey("user_error_id")) {
				        			if (response.get("user_error_id").equals("0")) { sender.sendMessage("§cERROR: Unknown error processing registration. Please try again."); info(player.getName() + " experienced an unknown error processing registration."); }
				        			else if (response.get("user_error_id").equals("30")) { sender.sendMessage("§cERROR: Some registration fields were missing. Check your input and try again."); info(player.getName() + " could not register due to missing fields."); }
				        			else if (response.get("user_error_id").equals("32")) { sender.sendMessage("§cERROR: Your username is too short for the forums settings. If you believe you are seeing this message in error, please inform a moderator."); info(player.getName() + " could not register due to having too short of a username."); }
				        			else if (response.get("user_error_id").equals("33")) { sender.sendMessage("§cERROR: Your username is too long for the forums settings. If you believe you are seeing this message in error, please inform a moderator."); info(player.getName() + " could not register due to having too long of a username."); }
				        			else if (response.get("user_error_id").equals("34") || response.get("user_error_id").equals("35") || response.get("user_error_id").equals("36") || response.get("user_error_id").equals("37") || response.get("user_error_id").equals("38") || response.get("user_error_id").equals("39")) { sender.sendMessage("§cERROR: Your username contains characters or words that are not allowed. If you believe you are seeing this message in error, please inform a moderator."); }
				        			else if (response.get("user_error_id").equals("40")) { sender.sendMessage("§cERROR: You have already registered or someone else is currently registered as you. If you believe you are seeing this message in error, please inform a moderator."); info(player.getName() + " could not register because they are already registered or someone is using their name."); } 
				        			else if (response.get("user_error_id").equals("41") || response.get("user_error_id").equals("42") || response.get("user_error_id").equals("43")) { sender.sendMessage("§cERROR: The email you specified either is invalid, not allowed, or already being used. If you believe you are seeing this message in error, please inform a moderator."); info(player.getName() + " could not register because they are using an invalid email, one that is registered, or one that is banned.");}
				        			else { warn("An error was found in the response, but could not find a recognizable error code. Contact the developer."); }
				        			return true;
		        				}
		        				else { warn("An error was found in the response, but could not find an error code to tell us what went wrong. Contact the developer."); return true; }
		        			}
		        			else { warn("An error was found in the response, but none that was recognizable. Contact the developer."); return true; }
		        		}
		        		
		        		sender.sendMessage(new String[] {"§eYour username has been registered.","","§e§nHere is your registration information:","","  §8- §dUsername: §7" + response.get("username"), "  §8- §dEmail Addr: §7" + email, "  §8- §dPassword: §7" + password, "", "§eYou will be receiving an email shortly to confirm.", "§cPlease change your password ASAP."});
						return true;				        			
		        	}
		        	else {
		        		sender.sendMessage("§cERROR: The email you provided is not valid. Please try again.");
						return true;
		        	}
		        }
				if (useUsername == true) {
					sender.sendMessage("/register [email]");
					return true;
				} else if (useUsername == false) {
					sender.sendMessage("/register [email] [username]");
					return true;
				}
				return true;
			}
		} else {
			sender.sendMessage("§cERROR: This command can only be run by a player.");
			return true;
		}
		return false;
	}
}
