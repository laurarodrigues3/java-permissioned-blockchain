package tecnico.depchain.depchain_server.blockchain;

import java.io.Serializable;
import java.math.BigInteger;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;

public record Transaction(
		BigInteger nonce,
		Wei gasPrice,
		Wei maxPriorityFeePerGas,
		Wei maxFeePerGas,
		long gasLimit,
		Address to,
		Wei value,
		Bytes data,
		BigInteger v,
		BigInteger r,
		BigInteger s,
		Address sender) implements Serializable {
}
