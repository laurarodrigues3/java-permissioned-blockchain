package tecnico.depchain.depchain_common;

import java.net.InetSocketAddress;
import java.security.PublicKey;

public class DepchainClient {
	private InetSocketAddress address;
	private PublicKey publicKey;

	public DepchainClient(InetSocketAddress address, PublicKey publicKey) {
		this.address = address;
		this.publicKey = publicKey;
	}

	public InetSocketAddress getAddress() { return address; }
	public PublicKey getPublicKey() { return publicKey; }
}
