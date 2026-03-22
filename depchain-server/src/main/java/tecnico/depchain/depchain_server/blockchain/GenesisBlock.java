package tecnico.depchain.depchain_server.blockchain;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public record GenesisBlock(
	String blockHash,
	String prevBlockHash,
	List<Transaction> transactions,
	List<AccountState> accountStates) {

	public static GenesisBlock fromJson(String json) {
		JsonObject jsonObject = JsonParser.parseString(json).getAsJsonObject();

		//Block itself
		String blockHash = jsonObject.get("block_hash").getAsString();
		String prevBlockHash = jsonObject.get("previous_block_hash").getAsString();

		//Transaction stuff
		List<Transaction> transactions = jsonObject.get("transactions")
			.getAsJsonArray()
			.asList()
			.stream()
			.map((JsonElement element) -> {
				var obj = element.getAsJsonObject();

				BigInteger nonce = BigInteger.valueOf(Long.parseLong(obj.get("nonce").getAsString()));
				Address from = Address.fromHexString(obj.get("from").getAsString());
				Address to = Address.fromHexString(obj.get("to").getAsString());

				return new Transaction(
					nonce,
					from,
					to,
					Wei.ZERO,
					Wei.ZERO,
					Wei.ZERO,
					0,
					Wei.ZERO,
					Bytes.EMPTY,
					BigInteger.ZERO,
					BigInteger.ZERO,
					BigInteger.ZERO);
			}
		).toList();

		//State stuff
		List<AccountState> accountStates = jsonObject.get("state")
			.getAsJsonObject()
			.asMap()
			.entrySet()
			.parallelStream()
			.map((Map.Entry<String, JsonElement> entry) -> {
				Address address = Address.fromHexString(entry.getKey());
				var state = entry.getValue().getAsJsonObject();

				// Genesis block will only contain EOAs in it's state
				Wei balance = Wei.of(state.get("balance").getAsBigInteger());
				BigInteger nonce = state.get("nonce").getAsBigInteger();

				AccountState accountState = new EOAState(address, balance, nonce);
				return accountState;
			}
		).toList();

		//Build and return
		return new GenesisBlock(blockHash, prevBlockHash, transactions, accountStates);
	}
}
