package tecnico.depchain.hotstuff;

import java.net.InetSocketAddress;
import java.net.SocketException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

import javax.crypto.SecretKey;

import tecnico.depchain.broadcasts.BestEffortBroadcast;
import tecnico.depchain.hotstuff.Message.MsgType;

public class HotStuff {
	private final int replicaID;
	private final int numReplicas;
	private final int quorumSize; // n - f
	private final BestEffortBroadcast broadcast;

	// Protocol state (Algorithm 2)
	private int currentView = 1;
	private QuorumCertificate prepareQC = null;  // highest QC voted pre-commit
	private QuorumCertificate lockedQC = null;    // lock for safety

	// Tree of proposals
	private final Map<String, TreeNode> nodeStore = new HashMap<>(); // hash(hex) -> node

	// Decided commands (the "blockchain")
	private final List<String> decidedCommands = new ArrayList<>();

	// Pending commands to propose (set by upper layer)
	private final BlockingQueue<String> pendingCommands = new LinkedBlockingQueue<>();

	// Message queue for sequential processing
	private final BlockingQueue<Message> messageQueue = new LinkedBlockingQueue<>();
	// Buffer for out-of-order messages (e.g., from the future)
	private final List<Message> outOfOrderBuffer = new ArrayList<>();

	// Callback for decided commands
	private Consumer<String> onDecide = null;

	// Protocol thread
	private Thread protocolThread;
	private volatile boolean running = false;

	/**
	 * @param replicaID  This replica's ID (0-indexed)
	 * @param basePort   Base port for the system. Replica r uses ports [basePort + r*n .. basePort + r*n + n-2]
	 * @param host       Host address for all replicas (e.g., "127.0.0.1")
	 * @param numReplicas Total number of replicas
	 * @param keys       List of n shared keys (index i = shared key with replica i)
	 */
	public HotStuff(
			int replicaID, String host, int basePort, int numReplicas, List<SecretKey> keys
		) throws SocketException, NoSuchAlgorithmException, InvalidKeyException, IllegalArgumentException {
		this.replicaID = replicaID;
		this.numReplicas = numReplicas;
		// f = (n-1)/3, quorum = n - f
		int f = (numReplicas - 1) / 3;
		this.quorumSize = numReplicas - f;

		// Build per-link local and remote addresses
		// Port scheme: port(sender, receiver) = basePort + receiver * n + linkIndex(sender, receiver)
		// where linkIndex(s, r) = s < r ? s : s - 1
		List<InetSocketAddress> locals = new ArrayList<>();
		List<InetSocketAddress> remotes = new ArrayList<>();
		List<SecretKey> peerKeys = new ArrayList<>();

		for (int j = 0; j < numReplicas; j++) {
			if (j == replicaID) continue;

			// Local port: the port where this replica (replicaID) listens for messages from replica j
			int localPort = basePort + replicaID * numReplicas + (j < replicaID ? j : j - 1);
			locals.add(new InetSocketAddress(host, localPort));

			// Remote port: the port where replica j listens for messages from this replica (replicaID)
			int remotePort = basePort + j * numReplicas + (replicaID < j ? replicaID : replicaID - 1);
			remotes.add(new InetSocketAddress(host, remotePort));

			peerKeys.add(keys.get(j));
		}

		SecretKey ownKey = keys.get(replicaID);
		broadcast = new BestEffortBroadcast(this::handleMsg, this::handleMsg, locals, ownKey, remotes, peerKeys);
	}

	// --- Public interface ---

	public void start() {
		running = true;
		protocolThread = new Thread(this::protocolLoop, "HotStuff-" + replicaID);
		protocolThread.setDaemon(true);
		protocolThread.start();
	}

	public void stop() {
		running = false;
		if (protocolThread != null)
			protocolThread.interrupt();
	}

	public void propose(String command) {
		this.pendingCommands.offer(command);
	}

	public List<String> getDecidedCommands() {
		return new ArrayList<>(decidedCommands);
	}

	public void setOnDecide(Consumer<String> callback) {
		this.onDecide = callback;
	}

	// --- Network layer ---

	/**
	 * Called by BestEffortBroadcast when a message arrives.
	 * Data has a 1-byte prefix from BEB (P2P=0, BROADCAST=1).
	 */
	private void handleMsg(byte[] data, InetSocketAddress remote) {
		// Strip the 1-byte BEB prefix
		byte[] msgBytes = new byte[data.length - 1];
		System.arraycopy(data, 1, msgBytes, 0, msgBytes.length);

		Message msg = Message.deserialize(msgBytes);
		if (msg != null) {
			messageQueue.offer(msg);
		}
	}

	private void sendMessage(int replica, Message msg) {
		if (replica == replicaID) {
			// Self-send: enqueue directly
			messageQueue.offer(msg);
			return;
		}
		// Adjust index: broadcast's internal list excludes our own address
		int link = replica > replicaID ? replica - 1 : replica;
		broadcast.transmit(link, msg.serialize());
	}

	private void broadcastMessage(Message msg) {
		broadcast.broadcast(msg.serialize());
	}

	// --- Protocol helpers ---

	private Message pullMessage(MsgType type, int viewNumber) throws InterruptedException {
		for (int i = 0; i < outOfOrderBuffer.size(); i++) {
			Message msg = outOfOrderBuffer.get(i);
			if (msg.getType() == type && msg.getViewNumber() == viewNumber) {
				return outOfOrderBuffer.remove(i);
			}
		}
		while (true) {
			Message msg = messageQueue.take();
			if (msg.getType() == type && msg.getViewNumber() == viewNumber) {
				return msg;
			}
			if (msg.getViewNumber() >= currentView - 1) {
				outOfOrderBuffer.add(msg);
			}
		}
	}

	private int getLeader(int view) {
		return view % numReplicas;
	}

	private boolean isLeader() {
		return getLeader(currentView) == replicaID;
	}

	private Message makeMsg(MsgType type, TreeNode node, QuorumCertificate qc) {
		return new Message(type, currentView, replicaID, node, qc);
	}

	/**
	 * safeNode predicate (Algorithm 1, lines 25-27).
	 * Returns true if node is safe to accept:
	 *   - Safety rule: node extends from lockedQC.node
	 *   - Liveness rule: qc.viewNumber > lockedQC.viewNumber
	 */
	private boolean safeNode(TreeNode node, QuorumCertificate qc) {
		if (lockedQC == null) return true;

		// Safety rule: the proposed node extends the locked branch
		boolean extendsLocked = node.extendsFrom(lockedQC.getNode());

		// Liveness rule: the justification has a higher view than our lock
		boolean higherView = qc != null && qc.getViewNumber() > lockedQC.getViewNumber();

		return extendsLocked || higherView;
	}

	private TreeNode createLeaf(TreeNode parent, String command) {
		TreeNode leaf = new TreeNode(parent, command);
		storeNode(leaf);
		return leaf;
	}

	private void storeNode(TreeNode node) {
		nodeStore.put(hashHex(node.getHash()), node);
	}

	/**
	 * Link a deserialized node to its in-memory parent (if we have it).
	 */
	private void linkParent(TreeNode node) {
		if (node == null) return;
		TreeNode parent = nodeStore.get(hashHex(node.getParentHash()));
		if (parent != null) {
			node.setParent(parent);
		}
	}

	private static String hashHex(byte[] hash) {
		StringBuilder sb = new StringBuilder();
		for (byte b : hash) sb.append(String.format("%02x", b));
		return sb.toString();
	}

	// --- Protocol main loop ---

	private void protocolLoop() {
		while (running) {
			try {
				if (isLeader()) {
					runLeaderPhase();
				} else {
					runReplicaPhase();
				}
				currentView++;
			} catch (InterruptedException e) {
				break;
			}
		}
	}

	private void runLeaderPhase() throws InterruptedException {
		// === PREPARE phase (leader) ===
		// Collect (n-f) NEW_VIEW messages (or bootstrap for view 1)
		QuorumCertificate highQC = null;

		if (currentView == 1) {
			// Bootstrap: no NEW_VIEW messages needed, highQC is null
		} else {
			// Wait for (n-f) NEW_VIEW messages
			Map<Integer, Message> newViews = new HashMap<>();

			while (newViews.size() < quorumSize) {
				Message msg = pullMessage(MsgType.NEW_VIEW, currentView - 1);
				newViews.put(msg.getSenderId(), msg);
			}

			// Pick highest prepareQC among new-view messages
			for (Message m : newViews.values()) {
				QuorumCertificate qc = m.getJustify();
				if (qc != null) {
					if (highQC == null || qc.getViewNumber() > highQC.getViewNumber())
						highQC = qc;
				}
			}
		}

		// Create new proposal
		TreeNode parentNode = highQC != null ? highQC.getNode() : null;
		if (parentNode != null) linkParent(parentNode);

		// Wait for a command to propose
		String cmd = waitForCommand();

		TreeNode proposal = createLeaf(parentNode, cmd);
		broadcastMessage(makeMsg(MsgType.PREPARE, proposal, highQC));

		// Also process our own PREPARE vote (leader is also a replica)
		boolean selfVote = safeNode(proposal, highQC);

		// === Collect PREPARE votes → form prepareQC ===
		QuorumCertificate newPrepareQC = new QuorumCertificate(MsgType.PREPARE, currentView, proposal);
		if (selfVote) newPrepareQC.addVoter(replicaID);

		while (!newPrepareQC.hasQuorum(quorumSize)) {
			Message msg = pullMessage(MsgType.PREPARE, currentView);
			newPrepareQC.addVoter(msg.getSenderId());
		}

		// === PRE-COMMIT phase ===
		prepareQC = newPrepareQC;
		broadcastMessage(makeMsg(MsgType.PRE_COMMIT, null, prepareQC));

		// Self vote for pre-commit
		QuorumCertificate newPrecommitQC = new QuorumCertificate(MsgType.PRE_COMMIT, currentView, proposal);
		newPrecommitQC.addVoter(replicaID);

		while (!newPrecommitQC.hasQuorum(quorumSize)) {
			Message msg = pullMessage(MsgType.PRE_COMMIT, currentView);
			newPrecommitQC.addVoter(msg.getSenderId());
		}

		// === COMMIT phase ===
		lockedQC = newPrecommitQC;
		broadcastMessage(makeMsg(MsgType.COMMIT, null, newPrecommitQC));

		// Self vote for commit
		QuorumCertificate newCommitQC = new QuorumCertificate(MsgType.COMMIT, currentView, proposal);
		newCommitQC.addVoter(replicaID);

		while (!newCommitQC.hasQuorum(quorumSize)) {
			Message msg = pullMessage(MsgType.COMMIT, currentView);
			newCommitQC.addVoter(msg.getSenderId());
		}

		// === DECIDE phase ===
		broadcastMessage(makeMsg(MsgType.DECIDE, null, newCommitQC));
		executeDecision(proposal);

		// Send NEW_VIEW for next view
		sendMessage(getLeader(currentView + 1), makeMsg(MsgType.NEW_VIEW, null, prepareQC));
	}

	private void runReplicaPhase() throws InterruptedException {
		int leader = getLeader(currentView);

		// === PREPARE phase (replica) ===
		// Wait for PREPARE from leader
		Message prepareMsg = waitForMessage(MsgType.PREPARE, currentView);
		TreeNode proposal = prepareMsg.getTreeNode();

		// Store and link the received node
		if (proposal != null) {
			storeNode(proposal);
			linkParent(proposal);
		}

		QuorumCertificate justifyQC = prepareMsg.getJustify();

		// Link the justify QC node too
		if (justifyQC != null && justifyQC.getNode() != null) {
			storeNode(justifyQC.getNode());
			linkParent(justifyQC.getNode());
			// Also link proposal to justify node if it's the parent
			if (proposal != null && justifyQC.getNode() != null) {
				if (Arrays.equals(proposal.getParentHash(), justifyQC.getNode().getHash())) {
					proposal.setParent(justifyQC.getNode());
				}
			}
		}

		// Check safeNode
		if (proposal != null && safeNode(proposal, justifyQC)) {
			sendMessage(leader, makeMsg(MsgType.PREPARE, proposal, null));
		}

		// === PRE-COMMIT phase (replica) ===
		Message preCommitMsg = waitForMessage(MsgType.PRE_COMMIT, currentView);
		QuorumCertificate rcvPrepareQC = preCommitMsg.getJustify();
		if (rcvPrepareQC != null) {
			prepareQC = rcvPrepareQC;
		}
		sendMessage(leader, makeMsg(MsgType.PRE_COMMIT, proposal, null));

		// === COMMIT phase (replica) ===
		Message commitMsg = waitForMessage(MsgType.COMMIT, currentView);
		QuorumCertificate rcvPrecommitQC = commitMsg.getJustify();
		if (rcvPrecommitQC != null) {
			lockedQC = rcvPrecommitQC;
		}
		sendMessage(leader, makeMsg(MsgType.COMMIT, proposal, null));

		// === DECIDE phase (replica) ===
		Message decideMsg = waitForMessage(MsgType.DECIDE, currentView);
		QuorumCertificate commitQC = decideMsg.getJustify();
		if (commitQC != null && commitQC.getNode() != null) {
			executeDecision(commitQC.getNode());
		} else if (proposal != null) {
			executeDecision(proposal);
		}

		// Send NEW_VIEW for next view
		sendMessage(getLeader(currentView + 1), makeMsg(MsgType.NEW_VIEW, null, prepareQC));
	}

	/**
	 * Wait for a specific message type and view from the queue.
	 * Discards non-matching messages.
	 */
	private Message waitForMessage(MsgType type, int view) throws InterruptedException {
		return pullMessage(type, view);
	}

	/**
	 * Wait for a command to propose. Busy-waits with short sleeps.
	 */
	private String waitForCommand() throws InterruptedException {
		return pendingCommands.take();
	}

	private void executeDecision(TreeNode node) {
		if (node == null) return;
		String cmd = node.getCommand();
		if (cmd != null && !decidedCommands.contains(cmd)) {
			decidedCommands.add(cmd);
			if (onDecide != null) {
				onDecide.accept(cmd);
			}
		}
	}
}
