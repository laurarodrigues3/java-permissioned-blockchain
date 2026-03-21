package tecnico.depchain.depchain_server.blockchain;

import java.util.List;

//TODO: Add missing fields
public record Block(
	List<Transaction> transactions) {
}
