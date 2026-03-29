package tecnico.depchain.depchain_common.blockchain;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;

public record Transaction(
		long nonce,
		Address from,
		Address to,
		Wei gasPrice,
		Wei maxPriorityFeePerGas,
		Wei maxFeePerGas,
		long gasLimit,
		Wei value,
		Bytes data) implements Serializable {

	public byte[] serialize() {
		ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
		try {
			ObjectOutputStream objectStream = new ObjectOutputStream(byteStream);
			objectStream.writeObject(this);
			objectStream.flush();
			byteStream.flush();
		}
		catch (IOException e) {
			return null; //Should not happen
		}

		return byteStream.toByteArray();
	}

	/**
     * Produces a canonical byte representation of a Transaction for hashing.
     * This must be deterministic and include all semantically relevant fields.
     */
    public byte[] canonicalBytes() {
        StringBuilder sb = new StringBuilder();
        sb.append(nonce).append('|');
        sb.append(from != null ? from.toHexString() : "null").append('|');
        sb.append(to != null ? to.toHexString() : "null").append('|');
        sb.append(gasPrice != null ? gasPrice.toBigInteger().toString() : "0").append('|');
        sb.append(gasLimit).append('|');
        sb.append(value != null ? value.toBigInteger().toString() : "0").append('|');
        sb.append(data != null ? data.toHexString() : "");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}
