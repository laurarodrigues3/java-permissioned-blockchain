package tecnico.depchain.depchain_common;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.SecretKey;

public class Membership {
	public static final DepchainClient[] clients = {
		new DepchainClient(new InetSocketAddress("localhost", 6900), null),
	};

	public static final DepchainMember[] members = {
		new DepchainMember(new InetSocketAddress("localhost", 42069), null),
		new DepchainMember(new InetSocketAddress("localhost", 42070), null),
		new DepchainMember(new InetSocketAddress("localhost", 42071), null),
		new DepchainMember(new InetSocketAddress("localhost", 42072), null),
	};

	public static List<InetSocketAddress> getMemberAddresses() {
		List<InetSocketAddress> addresses = new ArrayList<>(members.length);

		for (DepchainMember member : members) {
			addresses.add(member.getAddress());
		}

		return addresses;
	}

	public static List<SecretKey> getMemberMacs() {
		List<SecretKey> keys = new ArrayList<>(members.length);

		for (DepchainMember member : members) {
			keys.add(member.getMacKey());
		}

		return keys;
	}
}
