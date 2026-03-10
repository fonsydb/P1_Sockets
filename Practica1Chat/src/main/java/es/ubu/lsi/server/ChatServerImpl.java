package es.ubu.lsi.server;

import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;

import es.ubu.lsi.common.ChatMessage;
import es.ubu.lsi.common.ChatMessage.MessageType;

/**
 * Chat server. Based on code available at: 
 * http://www.dreamincode.net/forums/topic/259777-a-simple-chat-program-with-clientserver-gui-optional/
 * 
 * Modified by Alfonso Domingo Blanco
 * Date: 03/2026
 * 
 * Modified by Raúl Marticorena & Joaquín P- Seco
 * 
 * @author http://www.dreamincode.net
 * @author Raúl Marticorena
 * @author Joaquin P. Seco
 *
 *
 * 
 */
public class ChatServerImpl implements ChatServer {

	/** Default port. */
	private static final int DEFAULT_PORT = 1500;
	
	/** Unique ID for each connection.*/	
	private static int clientId;
	
	/** Client list. */
	private List<ServerThreadForClient> clients;
	
	
// === MODIFICACIÓN: HashMap para usuarios y puertos ===
	/** Map to store username -> port for connected clients. */
	private Map<String, Integer> userPorts;
	
	
// === MODIFICACIÓN: HashMap para banLists ===
	/** Ban lists: map of blocker username -> set of blocked usernames */
	private Map<String, Set<String>> banLists;

// === MODIFICACIÓN: Añadimos author name for sponsorship ===
	/** Author name for message sponsorship (requirement) */
	private static final String AUTHOR_NAME = "Alfonso Domingo";
	
	/** Util class to display time. */
	private static SimpleDateFormat sdf;
	
	/** Port number to listen for connection. */
	private int port;
	
	/** Flag will be turned of to stop the server. */
	private boolean alive;
	
	/** Server socket. */
	private ServerSocket serverSocket; 
	
	static {
		 sdf = new SimpleDateFormat("HH:mm:ss"); // to display hh:mm:ss
	}

	/**
	 * Server constructor that receive the port to listen to for connection.
	 * 
	 * @param port port to listen
	 */
	public ChatServerImpl(int port) {
		this.port = port;
		// List for the Client list
		clients = new ArrayList<ServerThreadForClient>();
		
		// === MODIFICACIÓN: Inicializamos los HashMaps ===
		// Inicializamos userPorts map
		userPorts = new HashMap<>();
			
		// Inicializamos banLists map
		banLists = new HashMap<>();
	}
	
	/**
	 * Starts the server.
	 */
	@Override
	public void startup() {
		alive = true;
		/* create socket server and wait for connection requests */
		try {
			// the socket used by the server
			serverSocket = new ServerSocket(port);

			// infinite loop to wait for connections
			while (alive) {
				// format message saying we are waiting
				show("Server waiting for Clients on port " + port + ".");

				Socket socket = serverSocket.accept(); // accept connection
				// if I was asked to stop
				if (!alive)
					break;
				ServerThreadForClient t = new ServerThreadForClient(socket); 
				// make a thread of it				
				clients.add(t); // save it in the ArrayList				
				t.start();
			}
			shutdown();
		}
		// something went bad
		catch (IOException e) {
			String msg = sdf.format(new Date())
					+ " ServerSocket: " + e + "\n";
			show(msg);
		}
	}

	/**
	 * Closes server.
	 *
	 */
	@Override
	public synchronized void shutdown() {
		try {
			serverSocket.close();
			for (int i = 0; i < clients.size(); ++i) {
				ServerThreadForClient tc = clients.get(i);
				try {
					tc.sInput.close();
					tc.sOutput.close();
					tc.socket.close();
				} catch (IOException ioE) {
					System.err.printf("Error closing streams and socket for client %d", i);
				}
			}
		} catch (Exception e) {
			show("Exception closing the server and clients: " + e);
		}
	}
	

	/**
	 * Shows an event (not a message) to the console.
	 * 
	 * @param event event
	 */
	private void show(String event) {
		String time = sdf.format(new Date()) + " " + event;
		System.out.println(time);
	}

	/**
	 * Broadcasts a message to all clients.
	 * 
	 * @param message message
	 */
	@Override
	public synchronized void broadcast(ChatMessage message) {
		// add HH:mm:ss and \n to the message
		String time = sdf.format(new Date());
		
// === MODIFICACIÓN: Añadir patrocinador ===
	    // Requirement: each message must start with "<nombre> patrocina el mensaje:"
		String sponsorText = " " + AUTHOR_NAME + " patrocina el mensaje: ";	
		// String messageLf = time + " " + message.getMessage() + "\n";
	    String messageLf = time + sponsorText + message.getMessage() + "\n";
	    
		message.setMessage(messageLf);
		
		// display message on console
		System.out.print(messageLf);
		
// === MODIFICACIÓN: Filtrar bloqueos ===
		// Get the sender information
		ServerThreadForClient sender = findClientById(message.getId());
		String senderName = (sender != null) ? sender.getUsername() : "Unknown";	    
		
		// we loop in reverse order in case we would have to remove a Client
		// because it has disconnected
		for (int i = clients.size(); --i >= 0;) {
			ServerThreadForClient ct = clients.get(i);
			
// === MODIFICACIÓN: Omitir mensaje ===
	        // Skip sending to clients who have banned this sender
			if (isBanned(ct.getUsername(), senderName)) {
	            continue; // Do not send message to this client
	        }
	        
			// try to write to the Client if it fails remove it from the list
			if (!ct.sendMessage(message)) {
				clients.remove(i);
				show("Disconnected Client " + ct.username
						+ " removed from list.");
				// === MODIFICACIÓN: Eliminar del HashMap cuando se desconecta ===
				// Remove from userPorts map when client disconnects
				if (ct.username != null) {
					userPorts.remove(ct.username);
					show("Removed " + ct.username + " from userPorts map.");
				}

			}
		}
	}

	/**
	 * Removes a logout client.
	 * 
	 * @param id client id
	 */
	@Override
	public synchronized void remove(int id) {
		// scan the array list until we found the Id
		for (int i = 0; i < clients.size(); ++i) {
			ServerThreadForClient ct = clients.get(i);
			// found it
			if (ct.id == id) {
				
				// === MODIFICACIÓN: Eliminar del HashMap cuando se desconecta ===
				// Remove from userPorts map
				if (ct.username != null) {
					userPorts.remove(ct.username);
					show("Removed " + ct.username + " from userPorts map.");
				}
				
				clients.remove(i);
				return;
			}
		}
	}

// === MODIFICACIÓN: Métodos auxiliares para ban/unban ===
	
	/**
	 * Finds a client by its ID.
	 * 
	 * @param id client id
	 * @return ServerThreadForClient or null if not found
	 */
	private ServerThreadForClient findClientById(int id) {
		for (ServerThreadForClient ct : clients) {
			if (ct.getClientId() == id) { // Se cambia de getId a getClientId por conflicto con Thread
				return ct;
			}
		}
		return null;
	}

	/**
	 * Checks if a user has banned another user.
	 * 
	 * @param blocker the user who might have banned someone
	 * @param blocked the user who might be banned
	 * @return true if blocked, false otherwise
	 */
	private boolean isBanned(String blocker, String blocked) {
		Set<String> bans = banLists.get(blocker);
		return bans != null && bans.contains(blocked);
	}

	/**
	 * Handles a ban request.
	 * 
	 * @param bannerId id of the user who is banning
	 * @param bannedUsername username of the user to be banned
	 */
	private synchronized void handleBan(int bannerId, String bannedUsername) {
		ServerThreadForClient banner = findClientById(bannerId);
		if (banner == null) return;
		
		String bannerName = banner.getUsername();
		
		// === CORRECCIÓN: putIfAbsent no está disponible en versiones antiguas ===
		// Initialize ban list if not exists (compatible with older Java versions)
		Set<String> bans = banLists.get(bannerName);
		if (bans == null) {
			bans = new HashSet<>();
			banLists.put(bannerName, bans);
		}

		
		// Initialize ban list if not exists
		//banLists.putIfAbsent(bannerName, new HashSet<>());
		
		// Add to banned list
		//banLists.get(bannerName).add(bannedUsername);
		bans.add(bannedUsername);
		
		// Create notification message
		String notification = bannerName + " ha baneado a " + bannedUsername;
		ChatMessage notifyMsg = new ChatMessage(-1, MessageType.MESSAGE, notification);
		
		// Send notification only to the banner
		banner.sendMessage(notifyMsg);
		show(notification);
	}

	/**
	 * Handles an unban request.
	 * 
	 * @param unbannerId id of the user who is unbanning
	 * @param unbannedUsername username of the user to be unbanned
	 */
	private synchronized void handleUnban(int unbannerId, String unbannedUsername) {
		ServerThreadForClient unbanner = findClientById(unbannerId);
		if (unbanner == null) return;
		
		String unbannerName = unbanner.getUsername();
		
		// Remove from banned list
		Set<String> bans = banLists.get(unbannerName);
		if (bans != null) {
			bans.remove(unbannedUsername);
		}
		
		// Create notification message
		String notification = unbannerName + " ha desbaneado a " + unbannedUsername;
		ChatMessage notifyMsg = new ChatMessage(-1, MessageType.MESSAGE, notification);
		
		// Send notification only to the unbanner
		unbanner.sendMessage(notifyMsg);
		show(notification);
	}	
	
	/** 
	 * Runs the server with a default port if is not specified as argument.
	 * 
	 * @param args arguments
	 *
	 */
	public static void main(String[] args) {
		// start server on port 1500 unless a PortNumber is specified
		int portNumber = DEFAULT_PORT;
		switch (args.length) {
		case 1:
			try {
				portNumber = Integer.parseInt(args[0]);
			} catch (Exception e) {
				System.err.println("Invalid port number.");
				System.err.println("Usage is: > java Server [portNumber]");
				return;
			}
		case 0:
			break;
		default:
			System.out.println("Usage is: > java Server [portNumber]");
			return;

		}
		// create a server object and start it
		ChatServer server = new ChatServerImpl(portNumber);
		server.startup();
	}

	/** 
	 * One instance of this thread will run for each client. 
	 */
	private class ServerThreadForClient extends Thread {
		
		/** Socket where to listen/talk. */
		private Socket socket;
		
		/** Stream input. */
		private ObjectInputStream sInput;
		
		/** Stream output. */
		private ObjectOutputStream sOutput;

		/**
		 * Unique id (easier for disconnection).
		 */
		private int id;
		
		/** Username. */
		private String username;

		/**
		 * Constructor. 
		 * 
		 * @param socket socket
		 */
		private ServerThreadForClient(Socket socket) {
			// a unique id
			id = ++clientId;
			this.socket = socket;
			/* Creating both Data Stream */
			System.out
					.println("Server thread trying to create I/O streams for client");
			try {
				// create output first
				sOutput = new ObjectOutputStream(socket.getOutputStream());
				sInput = new ObjectInputStream(socket.getInputStream());
				// read the username
				username = (String) sInput.readObject();

				// === MODIFICACIÓN: Añadimos al HashMap de usuarios y puertos ===
				// Store username and port in the HashMap (like the example image)
				int clientPort = socket.getPort();
				userPorts.put(username, clientPort);
				show("Added " + username + " to userPorts map with port: " + clientPort);
				
				// Display all connected users (like in the example)
				show("Current connected users: " + userPorts.keySet());

				sOutput.writeInt(id);
				sOutput.flush();
				show(username + " just connected.");	
				show("connected with id:" +id);	
				ChatMessage chatMessage = new ChatMessage(id, MessageType.MESSAGE,username + " now connected");
				broadcast(chatMessage);
			} catch (IOException e) {
				show("Exception creating new I/O Streams: " + e);
				return;
			}
			catch (ClassNotFoundException e) {
				close(); // organized panic case...
				throw new RuntimeException("Wrong message type", e);
			}
		}

		/**
		 * Run method.
		 */
		public void run() {
			// to loop until LOGOUT
			boolean runningThread = true;
			ChatMessage chatMessage = null;
			while (runningThread) {
				// read a String (which is an object)
				try {
					chatMessage = (ChatMessage) sInput.readObject();
				} catch (IOException e) {
					show(username + " Exception reading Streams: " + e);
					break;
				} catch (ClassNotFoundException e2) {
					close(); // organized panic case...
					throw new RuntimeException("Wrong message type", e2);
				}

				// Switch on the type of message received
				switch (chatMessage.getType()) {
				case SHUTDOWN:
					show(username + " shutdown chat system.");
					runningThread = false;
					alive = false;					
					break;
				case MESSAGE:
					chatMessage.setMessage(username + ": " + chatMessage.getMessage());
					broadcast(chatMessage);
					break;
				case LOGOUT:
					show(username + " disconnected with a LOGOUT message.");
					chatMessage.setMessage(username + " leaving chat room!");
					broadcast(chatMessage);
					runningThread = false;
					break;
					
				// === MODIFICACIÓN: Añadimos casos para BAN y UNBAN ===
				case BAN:
					String bannedUser = chatMessage.getMessage();
					handleBan(id, bannedUser);
					break;
				case UNBAN:
					String unbannedUser = chatMessage.getMessage();
					handleUnban(id, unbannedUser);
					break;	
				} // switch
			}
			// remove myself from the arrayList containing the list of the
			// connected Clients			
			remove(id);
			show("Removing " + username + " with id: " + id);
			close();
			
			if (!alive) { // if was a shutdown close server
				shutdown();
			}
		}		
		

		/**
		 * Write a message to the client output stream.
		 * 
		 * @param msg message
		 * @return true if is correctly sent, false in other case.
		 */
		private boolean sendMessage(ChatMessage msg) {
			// if Client is still connected send the message to it
			if (!socket.isConnected()) {
				close();
				return false;
			}
			// write the message to the stream
			try {
				sOutput.writeObject(msg);
			}
			// if an error occurs, do not abort just inform the user
			catch (IOException e) {
				show("Error sending message to " + username);
				show(e.toString());
				return false;
			}
			return true;
		} // writeMsg
		
		/**
		 * Close streams and socket for client.
		 */
		private void close() {
			// try to close the connection
			try {				
				if (sOutput != null)
					sOutput.close();
				if (sInput != null)
					sInput.close();
				if (socket != null && socket.isConnected() && !socket.isClosed())
					socket.close();

			} catch (Exception e) {
				show("Closed streams and socket");
			}
		}

// === MODIFICACIÓN: Añadimos getters ===
		
		/**
		 * Gets the username.
		 * 
		 * @return username
		 */
		public String getUsername() {
			return username;
		}
		
		/**
		 * Gets the client id.
		 * 
		 * @return id
		 */
		public int getClientId() {
			return id;
		}
		
	}
}
