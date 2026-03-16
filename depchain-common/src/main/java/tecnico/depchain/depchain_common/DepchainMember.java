package tecnico.depchain.depchain_common;

import java.net.InetSocketAddress;
import java.security.PublicKey;

public class DepchainMember {
	private InetSocketAddress address;
	private PublicKey publicKey;

	public DepchainMember(InetSocketAddress address, PublicKey publicKey) {
		this.address = address;
		this.publicKey = publicKey;
	}

	public PublicKey getPublicKey() { return publicKey; }
	public InetSocketAddress getAddress() { return address; }
}
