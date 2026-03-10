package tecnico.depchain.depchain_server;

import java.net.InetSocketAddress;
import java.net.SocketException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.SecretKey;

import tecnico.depchain.depchain_common.DepchainClient;
import tecnico.depchain.depchain_common.Membership;
import tecnico.depchain.depchain_common.links.AuthenticatedPerfectLink;
import tecnico.depchain.depchain_common.messages.StringMessage;
import tecnico.depchain.depchain_server.hotstuff.DepChainService;

public class Depchain {
	private static DepChainService service;
	private static Map<InetSocketAddress, AuthenticatedPerfectLink> links = new HashMap<>();

	public static void main(String[] args)
		throws SocketException, NoSuchAlgorithmException, InvalidKeyException, IllegalArgumentException {
		if (args.length != 1) {
			System.err.print("Usage: java <class_path> <replicaID>");
			System.exit(1);
		}

		int replicaID = Integer.parseInt(args[0]);
		int numReplicas = Membership.members.length;
		InetSocketAddress local = new InetSocketAddress("0.0.0.0", Membership.members[replicaID].getAddress().getPort());
		SecretKey ownKey = Membership.members[replicaID].getMacKey();

		// FIXME: Not pass NULL arguments
		service = new DepChainService(replicaID, "localhost", 42069, numReplicas, Membership.getMemberMacs(), null, null);
		service.setOnDecide(Depchain::onDecide);
		service.start();

		for (DepchainClient cli : Membership.clients) {
			InetSocketAddress addr = cli.getAddress();
			links.put(addr, new AuthenticatedPerfectLink(Depchain::rxHandler, local, addr, ownKey, cli.getMacKey()));
		}

		while (true);
		//service.stop();
	}

	//Client msg handler
	private static void rxHandler(byte[] data, InetSocketAddress address) {
		//HACK: Assumes clients only send StringMesssage

		StringMessage msg = StringMessage.deserialize(data);
		service.handleClientRequest(msg.getContent());

		//TODO: Map this content to reply to client
	}

	private static void onDecide(String command) {
		//TODO: Reply to mapped client
	}
}
