package tecnico.depchain.depchain_server.hotstuff;

import tecnico.depchain.depchain_server.blockchain.Block;

/**
 * Interface that bridges the HotStuff Consensus engine with the blockchain application layer.
 * This satisfies the "upcall" requirement from the project specification.
 */
public interface ConsensusUpcall {
    /**
     * Called when a block reaches the DECIDE phase with certainty.
     * @param blk The original Block payload that was proposed.
     */
    void onDecide(Block blk);
}
