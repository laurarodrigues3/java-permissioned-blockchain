package tecnico.depchain.depchain_common;

import java.net.InetSocketAddress;
import java.security.PublicKey;

import org.hyperledger.besu.datatypes.Address;

public class DepchainMember {
	private InetSocketAddress netAddress;
	private PublicKey publicKey;
	private Address depchainAddress;

	public DepchainMember(InetSocketAddress netAddress, PublicKey publicKey, Address depchainAddress) {
		this.netAddress = netAddress;
		this.publicKey = publicKey;
		this.depchainAddress = depchainAddress;
	}

	public PublicKey getPublicKey() { return publicKey; }
	public InetSocketAddress getNetAddress() { return netAddress; }
	public Address getDepchainAddress() { return depchainAddress; }
}
