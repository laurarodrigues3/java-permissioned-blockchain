package tecnico.depchain.depchain_common;

import java.net.InetSocketAddress;

import javax.crypto.SecretKey;

public class DepchainClient {
	private InetSocketAddress address;
	private SecretKey macKey;

	public DepchainClient(InetSocketAddress address, SecretKey macKey) {
		this.address = address;
		this.macKey = macKey;
	}

	public InetSocketAddress getAddress() { return address; }
	public SecretKey getMacKey() { return macKey; }
}
