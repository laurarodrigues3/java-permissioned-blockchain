package tecnico.depchain.depchain_common;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;

public class Membership {
	private static DepchainMember[] members;
	private static DepchainClient[] clients;
	private static PrivateKey ownPrivateKey;
	private static boolean loaded = false;

	/**
	 * Load system membership from a static configuration file (pre-distributed PKI).
	 *
	 * @param configFilePath path to the config.properties file
	 * @param myId           the ID of the process calling this method
	 */
	public static synchronized void loadConfiguration(String configFilePath, int myId) throws IOException {
		Properties props = new Properties();
		try (FileInputStream fis = new FileInputStream(configFilePath)) {
			props.load(fis);
		}

		try {
			KeyFactory kf = KeyFactory.getInstance("Ed25519");

			// Load members (replicas)
			int numMembers = Integer.parseInt(props.getProperty("member.count"));
			members = new DepchainMember[numMembers];
			for (int i = 0; i < numMembers; i++) {
				String host = props.getProperty("member." + i + ".host");
				int port = Integer.parseInt(props.getProperty("member." + i + ".port"));
				String pubKeyB64 = props.getProperty("member." + i + ".publickey");
				PublicKey pubKey = kf.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pubKeyB64)));
				members[i] = new DepchainMember(new InetSocketAddress(host, port), pubKey);
			}

			// Load clients
			int numClients = Integer.parseInt(props.getProperty("client.count"));
			clients = new DepchainClient[numClients];
			for (int i = 0; i < numClients; i++) {
				String host = props.getProperty("client." + i + ".host");
				int port = Integer.parseInt(props.getProperty("client." + i + ".port"));
				String pubKeyB64 = props.getProperty("client." + i + ".publickey");
				PublicKey pubKey = kf.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pubKeyB64)));
				clients[i] = new DepchainClient(new InetSocketAddress(host, port), pubKey);
			}

			// Load own private key (PKCS#8, Base64-encoded)
			String privKeyB64 = props.getProperty("node." + myId + ".privatekey");
			ownPrivateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privKeyB64)));

			loaded = true;
		} catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
			throw new RuntimeException("Failed to parse Ed25519 keys from config", e);
		}
	}

	/**
	 * Programmatic initialization for testing (bypasses file loading).
	 */
	public static synchronized void initForTesting(
			DepchainMember[] testMembers, DepchainClient[] testClients, PrivateKey testOwnKey) {
		members = testMembers;
		clients = testClients;
		ownPrivateKey = testOwnKey;
		loaded = true;
	}

	public static DepchainMember[] getMembers() {
		checkLoaded();
		return members;
	}

	public static DepchainClient[] getClients() {
		checkLoaded();
		return clients;
	}

	public static PrivateKey getOwnPrivateKey() {
		checkLoaded();
		return ownPrivateKey;
	}

	public static List<InetSocketAddress> getMemberAddresses() {
		checkLoaded();
		List<InetSocketAddress> addresses = new ArrayList<>(members.length);
		for (DepchainMember member : members) {
			addresses.add(member.getAddress());
		}
		return addresses;
	}

	public static List<PublicKey> getMemberPublicKeys() {
		checkLoaded();
		List<PublicKey> keys = new ArrayList<>(members.length);
		for (DepchainMember member : members) {
			keys.add(member.getPublicKey());
		}
		return keys;
	}

	private static void checkLoaded() {
		if (!loaded)
			throw new IllegalStateException("Membership not loaded. Call loadConfiguration() or initForTesting() first.");
	}
}
