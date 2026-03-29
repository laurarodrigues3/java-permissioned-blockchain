package tecnico.depchain.depchain_common;

import java.net.InetSocketAddress;
import java.security.PublicKey;

public class DepchainClient {
	private final InetSocketAddress address;
	private final PublicKey publicKey;

	public DepchainClient(InetSocketAddress address, PublicKey publicKey) {
		this.address = address;
		this.publicKey = publicKey;
	}

	public InetSocketAddress getAddress() { return address; }
	public PublicKey getPublicKey() { return publicKey; }
}
