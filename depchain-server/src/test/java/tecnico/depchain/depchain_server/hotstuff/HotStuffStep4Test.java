package tecnico.depchain.depchain_server.hotstuff;

import static org.junit.jupiter.api.Assertions.*;

import java.security.KeyPair;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import tecnico.depchain.depchain_server.blockchain.Block;
import tecnico.depchain.depchain_server.hotstuff.Message.MsgType;

/**
 * Step 4 — Consensus Data Structure Unit Tests
 *
 * Tests TreeNode, Message serialization, and QuorumCertificate
 * with Ed25519 signature verification.
 */
public class HotStuffStep4Test {

    // ── TreeNode ────────────────────────────────────────────────────────

    @Test
    public void testTreeNodeHashDeterministic() {
        Block blk = new Block();
        TreeNode n1 = new TreeNode((TreeNode) null, blk);
        TreeNode n2 = new TreeNode((TreeNode) null, blk);
        assertArrayEquals(n1.getHash(), n2.getHash(),
                "Same parent + same block should produce identical hashes");
    }

    @Test
    public void testTreeNodeDifferentBlocksDifferentHash() {
        Block blk1 = new Block("prev1", new ArrayList<>(), null);
        Block blk2 = new Block("prev2", new ArrayList<>(), null);
        TreeNode n1 = new TreeNode((TreeNode) null, blk1);
        TreeNode n2 = new TreeNode((TreeNode) null, blk2);
        assertFalse(Arrays.equals(n1.getHash(), n2.getHash()),
                "Different blocks should produce different hashes");
    }

    @Test
    public void testTreeNodeExtendsFromParent() {
        Block blk1 = new Block();
        Block blk2 = new Block();
        TreeNode parent = new TreeNode((TreeNode) null, blk1);
        TreeNode child = new TreeNode(parent, blk2);

        assertTrue(child.extendsFrom(parent),
                "Child should extend from its parent");
    }

    @Test
    public void testTreeNodeExtendsFromGrandparent() {
        Block b1 = new Block(), b2 = new Block(), b3 = new Block();
        TreeNode grandparent = new TreeNode((TreeNode) null, b1);
        TreeNode parent = new TreeNode(grandparent, b2);
        TreeNode child = new TreeNode(parent, b3);

        assertTrue(child.extendsFrom(grandparent),
                "Grandchild should extend from grandparent");
    }

    @Test
    public void testTreeNodeDoesNotExtendFromUnrelated() {
        Block b1 = new Block("a", new ArrayList<>(), null);
        Block b2 = new Block("b", new ArrayList<>(), null);
        TreeNode node1 = new TreeNode((TreeNode) null, b1);
        TreeNode node2 = new TreeNode((TreeNode) null, b2);

        assertFalse(node2.extendsFrom(node1),
                "Unrelated nodes should not extend from each other");
    }

    @Test
    public void testTreeNodeExtendsFromNull() {
        TreeNode node = new TreeNode((TreeNode) null, new Block());
        assertTrue(node.extendsFrom(null),
                "Any node should extend from null (base case)");
    }

    @Test
    public void testTreeNodeParentHashMatchesParent() {
        Block b1 = new Block(), b2 = new Block();
        TreeNode parent = new TreeNode((TreeNode) null, b1);
        TreeNode child = new TreeNode(parent, b2);
        assertArrayEquals(parent.getHash(), child.getParentHash(),
                "Child's parentHash should match parent's hash");
    }

    // ── Message Serialization ───────────────────────────────────────────

    @Test
    public void testMessageSerializeDeserialize() {
        TreeNode node = new TreeNode((TreeNode) null, new Block());
        Message msg = new Message(MsgType.PREPARE, 5, 2, node, null);

        byte[] data = msg.serialize();
        assertNotNull(data, "Serialized message should not be null");

        Message restored = Message.deserialize(data);
        assertNotNull(restored);
        assertEquals(MsgType.PREPARE, restored.getType());
        assertEquals(5, restored.getViewNumber());
        assertEquals(2, restored.getSenderId());
        assertNotNull(restored.getTreeNode());
    }

    @Test
    public void testMessageSerializeWithQC() {
        TreeNode node = new TreeNode((TreeNode) null, new Block());
        QuorumCertificate qc = new QuorumCertificate(MsgType.PREPARE, 3, node);
        Message msg = new Message(MsgType.PRE_COMMIT, 4, 1, null, qc);

        byte[] data = msg.serialize();
        Message restored = Message.deserialize(data);
        assertNotNull(restored);
        assertEquals(MsgType.PRE_COMMIT, restored.getType());
        assertNotNull(restored.getJustify());
        assertEquals(MsgType.PREPARE, restored.getJustify().getType());
        assertEquals(3, restored.getJustify().getViewNumber());
    }

    @Test
    public void testMessageSignableBytesConsistent() {
        TreeNode node = new TreeNode((TreeNode) null, new Block());
        Message msg = new Message(MsgType.COMMIT, 7, 0, node, null);
        byte[] sig1 = msg.getSignableBytes();
        byte[] sig2 = msg.getSignableBytes();
        assertArrayEquals(sig1, sig2, "getSignableBytes should be deterministic");
    }

    @Test
    public void testMessageAllTypes() {
        for (MsgType type : MsgType.values()) {
            Message msg = new Message(type, 0, 0, null, null);
            byte[] data = msg.serialize();
            assertNotNull(data, "Serialization should work for type " + type);
            Message restored = Message.deserialize(data);
            assertEquals(type, restored.getType());
        }
    }

    // ── QuorumCertificate ───────────────────────────────────────────────

    @Test
    public void testQCVoteAccumulation() {
        TreeNode node = new TreeNode((TreeNode) null, new Block());
        QuorumCertificate qc = new QuorumCertificate(MsgType.PREPARE, 1, node);

        assertEquals(0, qc.getVoteCount());
        qc.addVote(0, new byte[]{1});
        assertEquals(1, qc.getVoteCount());
        qc.addVote(1, new byte[]{2});
        assertEquals(2, qc.getVoteCount());
    }

    @Test
    public void testQCDuplicateVoteNotCounted() {
        TreeNode node = new TreeNode((TreeNode) null, new Block());
        QuorumCertificate qc = new QuorumCertificate(MsgType.PREPARE, 1, node);

        qc.addVote(0, new byte[]{1});
        qc.addVote(0, new byte[]{2}); // same replica ID
        assertEquals(1, qc.getVoteCount(),
                "Duplicate vote from same replica should overwrite, not double-count");
    }

    @Test
    public void testQCHasQuorum() {
        TreeNode node = new TreeNode((TreeNode) null, new Block());
        QuorumCertificate qc = new QuorumCertificate(MsgType.PREPARE, 1, node);

        int quorumSize = 3; // n=4, f=1, quorum = n-f = 3
        assertFalse(qc.hasQuorum(quorumSize));
        qc.addVote(0, new byte[]{1});
        qc.addVote(1, new byte[]{2});
        assertFalse(qc.hasQuorum(quorumSize));
        qc.addVote(2, new byte[]{3});
        assertTrue(qc.hasQuorum(quorumSize),
                "QC should have quorum after 3 votes (n=4, f=1)");
    }

    @Test
    public void testQCMatchingQC() {
        TreeNode node = new TreeNode((TreeNode) null, new Block());
        QuorumCertificate qc = new QuorumCertificate(MsgType.PREPARE, 5, node);

        assertTrue(qc.matchingQC(MsgType.PREPARE, 5));
        assertFalse(qc.matchingQC(MsgType.COMMIT, 5));
        assertFalse(qc.matchingQC(MsgType.PREPARE, 6));
    }

    @Test
    public void testQCVerifyEd25519() throws Exception {
        int n = 4;
        List<KeyPair> keyPairs = CryptoService.generateKeyPairs(n);
        List<PublicKey> pubKeys = CryptoService.extractPublicKeys(keyPairs);
        CryptoService crypto0 = new CryptoService(0, keyPairs.get(0), pubKeys);

        TreeNode node = new TreeNode((TreeNode) null, new Block());
        QuorumCertificate qc = new QuorumCertificate(MsgType.PREPARE, 1, node);

        // Each replica signs the vote
        for (int i = 0; i < 3; i++) {
            CryptoService cs = new CryptoService(i, keyPairs.get(i), pubKeys);
            byte[] sig = cs.signVote(MsgType.PREPARE, 1, node.getHash());
            qc.addVote(i, sig);
        }

        assertTrue(qc.verify(crypto0, null, 3),
                "QC with 3 valid Ed25519 signatures should verify (quorum=3)");
    }

    @Test
    public void testQCRejectsInvalidSignatures() throws Exception {
        int n = 4;
        List<KeyPair> keyPairs = CryptoService.generateKeyPairs(n);
        List<PublicKey> pubKeys = CryptoService.extractPublicKeys(keyPairs);
        CryptoService crypto0 = new CryptoService(0, keyPairs.get(0), pubKeys);

        TreeNode node = new TreeNode((TreeNode) null, new Block());
        QuorumCertificate qc = new QuorumCertificate(MsgType.PREPARE, 1, node);

        // Add one valid and two garbage signatures
        CryptoService cs0 = new CryptoService(0, keyPairs.get(0), pubKeys);
        qc.addVote(0, cs0.signVote(MsgType.PREPARE, 1, node.getHash()));
        qc.addVote(1, new byte[64]); // garbage
        qc.addVote(2, new byte[64]); // garbage

        assertFalse(qc.verify(crypto0, null, 3),
                "QC with only 1 valid signature should not reach quorum of 3");
    }

    // ── CryptoService vote signing ──────────────────────────────────────

    @Test
    public void testCryptoServiceSignAndVerifyVote() throws Exception {
        List<KeyPair> keyPairs = CryptoService.generateKeyPairs(4);
        List<PublicKey> pubKeys = CryptoService.extractPublicKeys(keyPairs);
        CryptoService cs = new CryptoService(0, keyPairs.get(0), pubKeys);

        byte[] nodeHash = new byte[]{1, 2, 3, 4};
        byte[] sig = cs.signVote(MsgType.PREPARE, 5, nodeHash);
        assertTrue(cs.verifyVote(0, MsgType.PREPARE, 5, nodeHash, sig),
                "Vote should verify with correct parameters");
    }

    @Test
    public void testCryptoServiceVoteWrongViewFails() throws Exception {
        List<KeyPair> keyPairs = CryptoService.generateKeyPairs(4);
        List<PublicKey> pubKeys = CryptoService.extractPublicKeys(keyPairs);
        CryptoService cs = new CryptoService(0, keyPairs.get(0), pubKeys);

        byte[] nodeHash = new byte[]{1, 2, 3, 4};
        byte[] sig = cs.signVote(MsgType.PREPARE, 5, nodeHash);
        assertFalse(cs.verifyVote(0, MsgType.PREPARE, 6, nodeHash, sig),
                "Vote should fail with wrong view number");
    }

    @Test
    public void testCryptoServiceVoteWrongTypeFails() throws Exception {
        List<KeyPair> keyPairs = CryptoService.generateKeyPairs(4);
        List<PublicKey> pubKeys = CryptoService.extractPublicKeys(keyPairs);
        CryptoService cs = new CryptoService(0, keyPairs.get(0), pubKeys);

        byte[] nodeHash = new byte[]{1, 2, 3, 4};
        byte[] sig = cs.signVote(MsgType.PREPARE, 5, nodeHash);
        assertFalse(cs.verifyVote(0, MsgType.COMMIT, 5, nodeHash, sig),
                "Vote should fail with wrong message type");
    }

    @Test
    public void testCryptoServiceMessageSignAndVerify() throws Exception {
        List<KeyPair> keyPairs = CryptoService.generateKeyPairs(4);
        List<PublicKey> pubKeys = CryptoService.extractPublicKeys(keyPairs);
        CryptoService cs0 = new CryptoService(0, keyPairs.get(0), pubKeys);

        Message msg = new Message(MsgType.PREPARE, 3, 0, null, null);
        byte[] sig = cs0.sign(msg.getSignableBytes());
        msg.setMessageSignature(sig);

        assertTrue(cs0.verify(0, msg.getSignableBytes(), msg.getMessageSignature()),
                "Message signature should verify");
        assertFalse(cs0.verify(1, msg.getSignableBytes(), msg.getMessageSignature()),
                "Message signature should fail for wrong sender ID");
    }
}
