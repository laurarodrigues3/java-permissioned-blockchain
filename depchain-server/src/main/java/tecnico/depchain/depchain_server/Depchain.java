package tecnico.depchain.depchain_server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import tecnico.depchain.depchain_common.DepchainClient;
import tecnico.depchain.depchain_common.DepchainMember;
import tecnico.depchain.depchain_common.Membership;
import tecnico.depchain.depchain_common.links.AuthenticatedPerfectLink;
import tecnico.depchain.depchain_common.messages.ConfirmMessage;
import tecnico.depchain.depchain_common.messages.StringMessage;
import tecnico.depchain.depchain_server.hotstuff.CryptoService;
import tecnico.depchain.depchain_server.hotstuff.DepChainService;

public class Depchain {
	private static DepChainService service;
	private static Map<InetSocketAddress, AuthenticatedPerfectLink> links = new HashMap<>();
	private static Map<String, InetSocketAddress> requestSenderMap = new HashMap<>();
	private static Map<String, Long> requestIdMap = new HashMap<>();

	public static void main(String[] args)
		throws SocketException, NoSuchAlgorithmException, InvalidKeyException, IllegalArgumentException, IOException {
		if (args.length < 2) {
			System.err.print("Usage: java <class_path> <replicaID> <configFilePath>");
			System.exit(1);
		}

		int replicaID = Integer.parseInt(args[0]);
		String configPath = args[1];

		// Load membership from static configuration (pre-distributed PKI)
		Membership.loadConfiguration(configPath, replicaID);

		DepchainMember[] members = Membership.getMembers();
		DepchainClient[] clients = Membership.getClients();
		int numReplicas = members.length;
		PrivateKey ownKey = Membership.getOwnPrivateKey();
		List<PublicKey> publicKeys = Membership.getMemberPublicKeys();

		InetSocketAddress local = new InetSocketAddress("0.0.0.0", members[replicaID].getAddress().getPort());

		// Build CryptoService from the loaded PKI
		KeyPair ownKeyPair = new KeyPair(publicKeys.get(replicaID), ownKey);
		CryptoService crypto = new CryptoService(replicaID, ownKeyPair, publicKeys);

		service = new DepChainService(replicaID, "localhost", 42069, numReplicas, ownKey, publicKeys, crypto, null);
		service.setOnDecide(Depchain::onDecide);
		service.start();

		for (DepchainClient cli : clients) {
			InetSocketAddress addr = cli.getAddress();
			links.put(addr, new AuthenticatedPerfectLink(Depchain::rxHandler, local, addr, ownKey, cli.getPublicKey()));
		}

		while (true);
		//service.stop();
	}

	//Client msg handler
	private static void rxHandler(byte[] data, InetSocketAddress sender) {

		StringMessage msg = StringMessage.deserialize(data);
		if (msg == null) return;

		int clientId = msg.getClientId();
		if (clientId < 0 || clientId >= Membership.getClients().length) {
			System.err.println("[Server] Invalid client ID: " + clientId);
			return;
		}

		PublicKey clientKey = Membership.getClients()[clientId].getPublicKey();
		if (!msg.verify(clientKey)) {
			System.err.println("[Server] Invalid signature for client request from " + clientId);
			return;
		}

		//Map response to client
		synchronized (requestSenderMap)
		{
			requestSenderMap.put(msg.getContent(), sender);
			requestIdMap.put(msg.getContent(), msg.getSeqNum());
		}
		service.handleClientRequest(msg.getContent());

	}

	private static void onDecide(String command) {
		long id = requestIdMap.get(command);
		InetSocketAddress requester = requestSenderMap.get(command);
		AuthenticatedPerfectLink link = links.get(requester);

		ConfirmMessage msg = new ConfirmMessage(id, true);
		link.transmit(msg.serialize());
	}
}
