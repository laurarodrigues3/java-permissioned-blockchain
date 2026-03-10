package tecnico.depchain.depchain_server.hotstuff;

import java.net.SocketException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import javax.crypto.SecretKey;

/**
 * Stage 1 Blockchain logic. Acts as a bridge between the client requests
 * and the BFT consensus engine, maintaining an append-only in-memory blockchain.
 */
public class DepChainService implements ConsensusUpcall {
    private final int replicaID;
    private final int numReplicas;
    private final HotStuff hotStuff;
	private Consumer<String> onDecide = null;

    // In-memory array of append-only strings representing the blockchain
    private final List<String> blockchain = Collections.synchronizedList(new ArrayList<>());

    public DepChainService(
            int replicaID, String host, int basePort, int numReplicas,
            List<SecretKey> keys, CryptoService crypto, ThresholdCrypto thresholdCrypto)
            throws SocketException, NoSuchAlgorithmException, InvalidKeyException, IllegalArgumentException {
        this.replicaID = replicaID;
        this.numReplicas = numReplicas;
        // Pass "this" as the ConsensusUpcall
        this.hotStuff = new HotStuff(replicaID, host, basePort, numReplicas, keys, crypto, thresholdCrypto, this);
    }

    public void start() {
        this.hotStuff.start();
    }

    public void stop() {
        this.hotStuff.stop();
    }

    public void setBaseTimeout(long timeoutMs) {
        this.hotStuff.setBaseTimeout(timeoutMs);
    }

    public void setOnDecide(Consumer<String> callback) {
		this.onDecide = callback;
	}

    /**
     * Upcall triggered by the HotStuff layer when a block reaches DECIDE.
     * @param payload The original string added by the client.
     */
    @Override
    public void onDecide(String payload) {
        blockchain.add(payload);
        System.out.println("Bloco decidido e adicionado à blockchain: " + payload);
        if (onDecide != null)
            onDecide(payload);
    }

    /**
     * Handles a request originating from the client.
     * Evaluates if this node is the current leader and proposes to consensus.
     */
   public void handleClientRequest(String requestPayload) {
    // 1. Defesa de Idempotência: O servidor protege-se de clientes que fazem spam (retries UDP)
    if (this.blockchain.contains(requestPayload)) {
        System.err.println("[DepChainService-" + replicaID + "] Pedido ignorado. A transação já está na blockchain: '" + requestPayload + "'");
        // Nota para o Passo 6: Aqui o teu servidor deve re-enviar o ACK UDP ao cliente
        // para o avisar que a transação já foi processada com sucesso no passado.
        return;
    }

    // 2. Lógica de encaminhamento para o Líder
    int currentLeader = hotStuff.getCurrentView() % numReplicas;
    if (replicaID == currentLeader) {
        System.err.println("[DepChainService-" + replicaID + "] Proposing client request '" + requestPayload + "' for view " + hotStuff.getCurrentView());
        this.hotStuff.propose(requestPayload);
    } else {
        // In a real application, the client request would be gossiped or redirected to the leader.
        // For this phase, we just log and ignore.
        System.err.println("[DepChainService-" + replicaID + "] Ignored client request '" + requestPayload + "' (not the leader for view " + hotStuff.getCurrentView() + ").");
    }
}

    public List<String> getBlockchain() {
        return new ArrayList<>(blockchain);
    }

    public HotStuff getHotStuff() {
        return hotStuff;
    }
}
