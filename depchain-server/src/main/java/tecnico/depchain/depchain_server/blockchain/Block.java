package tecnico.depchain.depchain_server.blockchain;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.List;

import com.google.gson.Gson;

import tecnico.depchain.depchain_common.blockchain.Transaction;

public record Block(
	BlockHeader header,
	List<Transaction> transactions) {

	public String toJson() {
		Gson gson = new Gson();
		return gson.toJson(this);
	}

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
