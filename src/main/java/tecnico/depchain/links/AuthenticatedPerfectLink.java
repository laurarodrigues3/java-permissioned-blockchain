package tecnico.depchain.links;

import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;

import javax.crypto.Mac;
import javax.crypto.SecretKey;

// Authenticated Perfect link implementation
// Prepends MACs to messages for authenticity validation
// NOTE: Delivery order is NOT implemented here. Consider adding it?

public class AuthenticatedPerfectLink extends P2PLink {
	private StubbornLink lower;
	private Mac outgoing_mac, incoming_mac;

	public AuthenticatedPerfectLink(InetSocketAddress remote, SecretKey ownKey, PublicKey remoteKey)
			throws SocketException, NoSuchAlgorithmException, InvalidKeyException {
		super(remote);

		lower = new StubbornLink(remote);
		lower.rxHandler = this::internalRxHandler;

		outgoing_mac = Mac.getInstance("HmacSHA256");
		incoming_mac = Mac.getInstance("HmacSHA256");
		outgoing_mac.init(ownKey);
		incoming_mac.init(remoteKey);

		// Make sure MAC size is the expected
		if (outgoing_mac.getMacLength() != 32)
			throw new RuntimeException("I fucked up");
	}

	@Override
	public void Transmit(byte[] data) {
		byte[] mac_bytes = outgoing_mac.doFinal(data);

		var buffer = ByteBuffer.allocate(32 + data.length);
		buffer.put(mac_bytes);
		buffer.put(data);

		lower.Transmit(buffer.array());
	}

	// Handler for lower receive
	private void internalRxHandler(byte[] bytes, P2PLink _unused) {
		// Ignore too small to contain MAC
		// Prevents crash on malformed messages (by bizantine nodes)
		if (bytes.length < 32)
			return;

		byte[] received_mac = new byte[32];
		byte[] payload = new byte[bytes.length - 32];
		var buffer = ByteBuffer.wrap(bytes);
		buffer.get(received_mac);
		buffer.get(payload);

		byte[] calculated_mac = incoming_mac.doFinal(payload);

		for (int i = 0; i < calculated_mac.length; ++i) {
			if (calculated_mac[i] != received_mac[i])
				return; // Ignore bad message
		}

		// TODO: Deduplicate messages

		rxHandler.accept(payload, this);
	}
}
