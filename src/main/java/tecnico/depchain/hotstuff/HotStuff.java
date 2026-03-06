package tecnico.depchain.hotstuff;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedList;
import java.util.List;

import javax.crypto.SecretKey;

import tecnico.depchain.broadcasts.BestEffortBroadcast;
import tecnico.depchain.broadcasts.MultiLinkBroadcast;
import tecnico.depchain.hotstuff.Message.MsgType;

public class HotStuff {
	private int replicaID, numReplicas;
	private MultiLinkBroadcast broadcast;
	private int currentView = 1;
	private TreeNode ownBranch = null;

	public HotStuff(
			int replicaID, InetSocketAddress localAddr, List<InetSocketAddress> addresses, List<SecretKey> keys
		) throws SocketException, NoSuchAlgorithmException, InvalidKeyException, IllegalArgumentException {
		this.replicaID = replicaID;
		this.numReplicas = addresses.size();

		//Remove own address
		addresses = new LinkedList<>(addresses);
		addresses.remove(replicaID);
		//Remove (and save) own key
		keys = new LinkedList<>(keys);
		SecretKey ownKey = keys.remove(replicaID);

		broadcast = new BestEffortBroadcast(this::handleMsg, this::handleMsg, localAddr, ownKey, addresses, keys);
	}

	public void createLeaf(String command) {
		TreeNode newNode = new TreeNode(ownBranch, command);
		ownBranch = newNode;
	}

	private boolean isLeader() {
		return currentView % numReplicas == replicaID;
	}

	private Message makeMessage(MsgType type, TreeNode node) {
		return new Message(type, currentView, node);
	}

	private Message makeMessage(MsgType type, TreeNode node, QuorumCertificate justify) {
		return new Message(type, currentView, node, justify);
	}

	private void handleMsg(byte[] data, InetSocketAddress remote) {
		//TODO: Split message based on kind to individual message handler
		//TODO: Make two-way map between SocketAddress and replica ID (easier to deal with IDs)
		//        Will hopefully also unscrew implementation of sendMessage()
	}

	private void sendMessage(int replica, Message msg) {
		//HACK: Find a less weird way to solve this
		//Broadcast takes 'link' which is index of the array passed in the constructor
		//which is a list with the actual replica addresses EXCEPT this one
		//so this replica parameter needs to be adjusted to account for this
		// (I'm tired)
		if (replica > replicaID)
			--replica;

		broadcast.transmit(replica, msg.serialize());
	}

	private void broadcastMessage(Message msg) {
		broadcast.broadcast(msg.serialize());
	}
}
