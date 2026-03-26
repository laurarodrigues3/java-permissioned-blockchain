package tecnico.depchain.depchain_common.blockchain;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.math.BigInteger;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;

public record Transaction(
		BigInteger nonce,
		Address from,
		Address to,
		Wei gasPrice,
		Wei maxPriorityFeePerGas,
		Wei maxFeePerGas,
		long gasLimit,
		Wei value,
		Bytes data,
		BigInteger v,
		BigInteger r,
		BigInteger s) implements Serializable {

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
}
