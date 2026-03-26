package tecnico.depchain.depchain_server.blockchain;

import org.hyperledger.besu.datatypes.Hash;

public record BlockHeader(
	Hash parentHash,
	Hash stateRoot,
	Hash txRoot,
	long number) {
}
