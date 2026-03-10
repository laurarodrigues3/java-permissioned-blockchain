package tecnico.depchain.depchain_common;

import java.net.InetSocketAddress;

import javax.crypto.SecretKey;

public class DepchainMember {
	private InetSocketAddress address;
	private SecretKey macKey;

	public DepchainMember(InetSocketAddress address, SecretKey macKey) {
		this.address = address;
		this.macKey = macKey;
	}

	public SecretKey getMacKey() { return macKey; }
	public InetSocketAddress getAddress() { return address; }
}
