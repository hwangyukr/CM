package kr.ac.konkuk.ccslab.cm.manager;
import java.io.*;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import kr.ac.konkuk.ccslab.cm.entity.CMRecvFileInfo;
import kr.ac.konkuk.ccslab.cm.entity.CMSendFileInfo;
import kr.ac.konkuk.ccslab.cm.entity.CMServer;
import kr.ac.konkuk.ccslab.cm.entity.CMSession;
import kr.ac.konkuk.ccslab.cm.entity.CMChannelInfo;
import kr.ac.konkuk.ccslab.cm.entity.CMGroup;
import kr.ac.konkuk.ccslab.cm.entity.CMList;
import kr.ac.konkuk.ccslab.cm.entity.CMMember;
import kr.ac.konkuk.ccslab.cm.entity.CMMessage;
import kr.ac.konkuk.ccslab.cm.entity.CMUser;
import kr.ac.konkuk.ccslab.cm.event.CMEventSynchronizer;
import kr.ac.konkuk.ccslab.cm.event.CMFileEvent;
import kr.ac.konkuk.ccslab.cm.event.CMSessionEvent;
import kr.ac.konkuk.ccslab.cm.info.CMConfigurationInfo;
import kr.ac.konkuk.ccslab.cm.info.CMEventInfo;
import kr.ac.konkuk.ccslab.cm.info.CMFileTransferInfo;
import kr.ac.konkuk.ccslab.cm.info.CMInfo;
import kr.ac.konkuk.ccslab.cm.info.CMInteractionInfo;
import kr.ac.konkuk.ccslab.cm.info.CMThreadInfo;
import kr.ac.konkuk.ccslab.cm.thread.CMRecvFileTask;
import kr.ac.konkuk.ccslab.cm.thread.CMSendFileTask;

public class CMFileTransferManager {

	public static void init(CMInfo cmInfo)
	{
		CMConfigurationInfo confInfo = cmInfo.getConfigurationInfo();
		String strPath = confInfo.getTransferedFileHome().toString();
		
		// if the default directory does not exist, create it.
		File defaultPath = new File(strPath);
		if(!defaultPath.exists() || !defaultPath.isDirectory())
		{
			boolean ret = defaultPath.mkdirs();
			if(ret)
			{
				if(CMInfo._CM_DEBUG_2)
					System.out.println("A default path is created!");
			}
			else
			{
				System.out.println("A default path cannot be created!");
				return;
			}
		}
		
		if(CMInfo._CM_DEBUG_2)
			System.out.println("A default path for the file transfer: "+strPath);
						
		return;
	}
	
	public static void terminate(CMInfo cmInfo)
	{
		// nothing to do
	}
	
	public static boolean requestPermitForPullFile(String strFileName, String strFileOwner, 
			CMInfo cmInfo)
	{
		boolean bReturn = false;
		bReturn = requestPermitForPullFile(strFileName, strFileOwner, CMInfo.FILE_DEFAULT, 
				-1, cmInfo);
		return bReturn;
	}
	
	public static boolean requestPermitForPullFile(String strFileName, String strFileOwner, 
			byte byteFileAppend, CMInfo cmInfo)
	{
		boolean bReturn = false;
		bReturn = requestPermitForPullFile(strFileName, strFileOwner, byteFileAppend, -1, 
				cmInfo);
		return bReturn;		
	}
	
	public static boolean requestPermitForPullFile(String strFileName, String strFileOwner, 
			byte byteFileAppend, int nContentID, CMInfo cmInfo)
	{
		boolean bReturn = false;
		CMUser myself = cmInfo.getInteractionInfo().getMyself();
		
		CMFileEvent fe = new CMFileEvent();
		fe.setID(CMFileEvent.REQUEST_PERMIT_PULL_FILE);
		fe.setFileSender(strFileOwner);
		fe.setFileReceiver(myself.getName());	// requester name
		fe.setFileName(strFileName);
		fe.setContentID(nContentID);
		fe.setFileAppendFlag(byteFileAppend);
		
		if(isP2PFileTransfer(fe, cmInfo))
		{
			if(CMInfo._CM_DEBUG)
			{
				System.out.println("CMFileTransferManager.requestPermitForPullFile(), "
						+ "isP2PFileTransfer() returns true.");				
			}
			
			// set event sender and receiver
			String strDefServer = cmInfo.getInteractionInfo().getDefaultServerInfo()
					.getServerName();
			fe.setSender(myself.getName());
			fe.setReceiver(strDefServer);
			
			// set distribution session and distribution group
			fe.setDistributionSession("CM_ONE_USER");
			fe.setDistributionGroup(strFileOwner);
			
			// send the event to the default server
			bReturn = CMEventManager.unicastEvent(fe, strDefServer, cmInfo);
		}
		else
		{
			if(CMInfo._CM_DEBUG)
			{
				System.out.println("CMFileTransferManager.requestPermitForPullFile(), "
						+ "isP2PFileTransfer() returns false.");				
			}

			// set event sender and receiver
			fe.setSender(myself.getName());
			fe.setReceiver(strFileOwner);
			
			bReturn = CMEventManager.unicastEvent(fe, strFileOwner, cmInfo);			
		}
		
		return bReturn;
	}
	
	public static boolean isP2PFileTransfer(CMFileEvent fe, CMInfo cmInfo)
	{
		boolean bReturn = false;
		CMConfigurationInfo confInfo = cmInfo.getConfigurationInfo();
		CMInteractionInfo interInfo = cmInfo.getInteractionInfo();
		String strFileSender = fe.getFileSender();
		String strFileReceiver = fe.getFileReceiver();
		CMUser myself = interInfo.getMyself();
		
		if(confInfo.getCommArch().contentEquals("CM_CS") &&
				confInfo.getSystemType().contentEquals("CLIENT"))
		{
			String strSession = myself.getCurrentSession();
			String strGroup = myself.getCurrentGroup();
			CMSession session = interInfo.findSession(strSession);
			if(session == null)
			{
				System.err.println("CMFileTransferManager.isP2PFileTransfer(), session("
						+strSession+") not found!");
				return false;
			}
			CMGroup group = session.findGroup(strGroup);
			if(group == null)
			{
				System.err.println("CMFileTransferManager.isP2PFileTransfer(), group("
						+strGroup+") not found!");
				return false;
			}
			CMMember groupMember = group.getGroupUsers();
			
			if(strFileSender.contentEquals(myself.getName()) && 
					groupMember.isMember(strFileReceiver))
			{
				bReturn = true;
			}
			else if(strFileReceiver.contentEquals(myself.getName()) &&
					groupMember.isMember(strFileSender))
			{
				bReturn = true;
			}
		}

		return bReturn;
	}
	
	public static boolean replyPermitForPullFile(CMFileEvent fe, int nReturnCode, 
			CMInfo cmInfo)
	{
		boolean bRet = false;
		CMFileEvent feAck = new CMFileEvent();
		feAck.setID(CMFileEvent.REPLY_PERMIT_PULL_FILE);
		feAck.setFileSender(fe.getFileSender());
		feAck.setFileReceiver(fe.getFileReceiver());
		feAck.setFileName(fe.getFileName());
		feAck.setContentID(fe.getContentID());
		feAck.setReturnCode(nReturnCode);
		
		CMUser myself = cmInfo.getInteractionInfo().getMyself();
		
		if(isP2PFileTransfer(feAck, cmInfo))
		{
			if(CMInfo._CM_DEBUG)
			{
				System.out.println("CMFileTransferManager.replyPermitForPullFile(), "
						+ "isP2PFileTransfer() returns true.");
			}
			// set event sender and receiver
			String strDefServer = cmInfo.getInteractionInfo().getDefaultServerInfo()
					.getServerName();
			feAck.setSender(myself.getName());
			feAck.setReceiver(strDefServer);
			
			// set distribution fields
			feAck.setDistributionSession("CM_ONE_USER");
			feAck.setDistributionGroup(fe.getFileReceiver());
			
			// send the event to the default server
			bRet = CMEventManager.unicastEvent(feAck, strDefServer, cmInfo);
		}
		else
		{
			if(CMInfo._CM_DEBUG)
			{
				System.out.println("CMFileTransferManager.replyPermitForPullFile(), "
						+ "isP2PFileTransfer() returns false.");
			}
			// set event sender and receiver
			feAck.setSender(myself.getName());
			feAck.setReceiver(fe.getFileReceiver());
			// send the event to the file receiver
			bRet = CMEventManager.unicastEvent(feAck, fe.getFileReceiver(), cmInfo);			
		}		
		
		if(bRet && nReturnCode == 1)
		{
			CMConfigurationInfo confInfo = cmInfo.getConfigurationInfo();
			String strFilePath = confInfo.getTransferedFileHome().toString() + 
					File.separator + fe.getFileName();
			bRet = pushFile(strFilePath, fe.getFileReceiver(), fe.getFileAppendFlag(), 
					cmInfo);
		}
		
		return bRet;
	}
		
	public static boolean cancelPullFile(String strSender, CMInfo cmInfo)
	{
		boolean bReturn = false;
		CMConfigurationInfo confInfo = cmInfo.getConfigurationInfo();
		if(confInfo.isFileTransferScheme())
			bReturn = cancelPullFileWithSepChannel(strSender, cmInfo);
		else
		{
			System.err.println("CMFileTransferManager.cancelRequestFile(); default file transfer does not support!");
		}
		
		return bReturn;		
	}

	// cancel the receiving file task with separate channels and threads
	private static boolean cancelPullFileWithSepChannel(String strSender, CMInfo cmInfo)
	{
		boolean bReturn = false;
		CMFileTransferInfo fInfo = cmInfo.getFileTransferInfo();

		if(strSender != null)
		{
			bReturn = cancelPullFileWithSepChannelForOneSender(strSender, cmInfo);
		}
		else // cancel file transfer to all senders
		{
			Set<String> keySet = fInfo.getRecvFileHashtable().keySet();
			Iterator<String> iterKeys = keySet.iterator();
			while(iterKeys.hasNext())
			{
				String iterSender = iterKeys.next();
				bReturn = cancelPullFileWithSepChannelForOneSender(iterSender, cmInfo);
			}
			// clear the sending file hash table
			bReturn = fInfo.clearRecvFileHashtable();
		}
		
		return bReturn;
	}

	// cancel the receiving file task from one sender with a separate channel and thread
	private static boolean cancelPullFileWithSepChannelForOneSender(String strSender, CMInfo cmInfo)
	{
		CMFileTransferInfo fInfo = cmInfo.getFileTransferInfo();
		CMList<CMRecvFileInfo> recvList = null;
		CMRecvFileInfo rInfo = null;
		boolean bReturn = false;
		Future<CMRecvFileInfo> recvTask = null;
		CMFileEvent fe = null;
		CMInteractionInfo interInfo = cmInfo.getInteractionInfo();
		CMConfigurationInfo confInfo = cmInfo.getConfigurationInfo();
		CMChannelInfo<Integer> blockSCInfo = null;
		SocketChannel defaultBlockSC = null;
		boolean bP2PFileTransfer = false;
		
		// find the CMRecvFile list of the strSender
		recvList = fInfo.getRecvFileList(strSender);
		if(recvList == null)
		{
			System.err.println("CMFileTransferManager.cancelRequestFileWithSepChannelForOneSender(); "
					+ "receiving file list not found for the sender("+strSender+")!");
			return false;
		}
		
		// find the current receiving file task
		rInfo = fInfo.findRecvFileInfoOngoing(strSender);
		if(rInfo == null)
		{
			System.err.println("CMFileTransferManager.cancelRequestFileWithSepChannelForOneSender(); "
					+ "ongoing receiving task not found for the sender("+strSender+")!");
			bReturn = fInfo.removeRecvFileList(strSender);
			return bReturn;
		}
		
		// request for canceling the receiving task
		recvTask = rInfo.getRecvTaskResult();
		recvTask.cancel(true);
		// wait for the thread cancellation
		try {
			recvTask.get(10L, TimeUnit.SECONDS);
		} catch(CancellationException e) {
			System.out.println("CMFileTransferManager.cancelRequestFileWithSepChannelForOneSender(); "
					+ "the receiving task cancelled.: "
					+ "sender("+strSender+"), file("+rInfo.getFileName()+"), file size("+rInfo.getFileSize()
					+ "), recv size("+rInfo.getRecvSize()+")");
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (ExecutionException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (TimeoutException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		/////////////////////// management of the closed default blocking socket channel
		
		// get the default blocking socket channel
		if(confInfo.getSystemType().equals("CLIENT"))
		{
			blockSCInfo = interInfo.getDefaultServerInfo().getBlockSocketChannelInfo();
			if(CMInfo._CM_DEBUG)
			{
				System.out.println("CMFileTransferManager.cancelRequestFileWithSepChannelForOneSender(); "
						+ "# blocking socket channel: "	+ blockSCInfo.getSize());
			}
			// get the default blocking socket channel
			defaultBlockSC = (SocketChannel) blockSCInfo.findChannel(0);	// default blocking channel
				
		}
		else	// server
		{
			CMUser receiver = interInfo.getLoginUsers().findMember(strSender);
			blockSCInfo = receiver.getBlockSocketChannelInfo();
			// get the default blocking socket channel
			defaultBlockSC = (SocketChannel) receiver.getBlockSocketChannelInfo().findChannel(0);

			if(CMInfo._CM_DEBUG)
			{
				System.out.println("CMFileTransferManager.cancelRequestFileWithSepChannelForOneSender(); "
						+ "# blocking socket channel: "	+ blockSCInfo.getSize());
			}

		}

		// close the default blocking socket channel if it is open
		// the channel is actually closed due to the interrupt exception of the receiving thread
		if(defaultBlockSC.isOpen())
		{
			if(CMInfo._CM_DEBUG)
			{
				System.out.println("CMFileTransferManager.cancelRequestFileWithSepChannelForOneSender(); "
						+ "the default channel is still open and should be closed for reconnection!");
			}
			
			try {
				defaultBlockSC.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		else
		{
			System.err.println("CMFileTransferManager.cancelRequestFileWithSepChannelForOneSender(); "
					+ "the default channel is already closed!");
		}
		
		// remove the default blocking socket channel
		blockSCInfo.removeChannel(0);

		// send the cancel event to the sender
		fe = new CMFileEvent();
		fe.setID(CMFileEvent.CANCEL_FILE_RECV_CHAN);
		fe.setFileSender(strSender);
		fe.setFileReceiver(interInfo.getMyself().getName());
		
		bP2PFileTransfer = isP2PFileTransfer(fe, cmInfo);
		
		if(bP2PFileTransfer)
		{
			if(CMInfo._CM_DEBUG)
			{
				System.out.println("CMFileTransferManager.cancelPullFileWithSepChannelForOneSender(), "
						+ "isP2PFileTransfer() returns true.");
			}
			// set event sender and receiver
			fe.setSender(interInfo.getMyself().getName());
			String strDefServer = interInfo.getDefaultServerInfo().getServerName();
			fe.setReceiver(strDefServer);
			
			// set distribution fields
			fe.setDistributionSession("CM_ONE_USER");
			fe.setDistributionGroup(strSender);
			
			// send the event to the default server
			bReturn = CMEventManager.unicastEvent(fe, strDefServer, cmInfo);
		}
		else
		{
			if(CMInfo._CM_DEBUG)
			{
				System.out.println("CMFileTransferManager.cancelPullFileWithSepChannelForOneSender(), "
						+ "isP2PFileTransfer() returns false.");
			}
			// set event sender and receiver
			fe.setSender(interInfo.getMyself().getName());
			fe.setReceiver(strSender);
			// send the event to the file sender
			bReturn = CMEventManager.unicastEvent(fe, strSender, cmInfo);			
		}
		
		if(!bReturn)
		{
			return false;
		}
		
		// remove the receiving file list of the sender
		bReturn = fInfo.removeRecvFileList(strSender);

		// if the system type is client, it recreates the default blocking socket channel to the default server
		if(confInfo.getSystemType().equals("CLIENT") && !bP2PFileTransfer)
		{
			CMServer serverInfo = interInfo.getDefaultServerInfo();
			try {
				defaultBlockSC = (SocketChannel) CMCommManager.openBlockChannel(CMInfo.CM_SOCKET_CHANNEL, 
						serverInfo.getServerAddress(), serverInfo.getServerPort(), cmInfo);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				return false;
			}
			
			if(defaultBlockSC == null)
			{
				System.err.println("CMFileTransferManager.cancelRequestFileWithSepChannelForOneSender(); recreation of "
						+ "the blocking socket channel failed!: server("+serverInfo.getServerAddress()+"), port("
						+ serverInfo.getServerPort() +")");
				return false;
			}
			bReturn = blockSCInfo.addChannel(0, defaultBlockSC);

			if(bReturn)
			{
				CMSessionEvent se = new CMSessionEvent();
				se.setID(CMSessionEvent.ADD_BLOCK_SOCKET_CHANNEL);
				se.setChannelName(interInfo.getMyself().getName());
				se.setChannelNum(0);
				bReturn = CMEventManager.unicastEvent(se, serverInfo.getServerName(), CMInfo.CM_STREAM, 0, true, cmInfo);
				se = null;

				if(bReturn)
				{
					if(CMInfo._CM_DEBUG)
					{
						System.out.println("CMFileTransferManager.cancelRequestFileWithSepChannelForOneSender(); "
								+ "successfully requested to add the blocking socket channel with the key(0) "
								+ "to the server("+serverInfo.getServerName()+")");
					}
					
				}
			}
		}
		
		//////////////////////////////////

		return bReturn;		
	}
	
	public static boolean requestPermitForPushFile(String strFilePath, 
			String strFileReceiver, int nContentID, CMInfo cmInfo)
	{
		boolean bReturn = false;
		
		// get file information (size)
		File file = new File(strFilePath);
		if(!file.exists())
		{
			System.err.println("CMFileTransferManager.requestPermitForPushFile(), file("
					+strFilePath+") does not exists.");
			return false;
		}
		long lFileSize = file.length();
		
		// get sender (my) name
		CMInteractionInfo interInfo = cmInfo.getInteractionInfo();
		String strMyName = interInfo.getMyself().getName();
		
		// make and send a REQUEST_PERMIT_PUSH_FILE event
		CMFileEvent fe = new CMFileEvent();
		fe.setID(CMFileEvent.REQUEST_PERMIT_PUSH_FILE);
		fe.setFileSender(strMyName);
		fe.setFileReceiver(strFileReceiver);
		fe.setFilePath(strFilePath);
		fe.setFileSize(lFileSize);
		fe.setContentID(nContentID);
		
		if(isP2PFileTransfer(fe, cmInfo))
		{
			if(CMInfo._CM_DEBUG)
			{
				System.out.println("CMFileTransferManager.requestPermitForPushFile(), "
						+ "isP2PFileTransfer() returns true.");
			}
			// set event sender and receiver
			fe.setSender(strMyName);
			String strDefServer = interInfo.getDefaultServerInfo().getServerName();
			fe.setReceiver(strDefServer);
			
			// set distribution fields
			fe.setDistributionSession("CM_ONE_USER");
			fe.setDistributionGroup(strFileReceiver);
			
			// send the event to the default server
			bReturn = CMEventManager.unicastEvent(fe, strDefServer, cmInfo);
		}
		else
		{
			if(CMInfo._CM_DEBUG)
			{
				System.out.println("CMFileTransferManager.requestPermitForPushFile(), "
						+ "isP2PFileTransfer() returns false.");
			}
			// set event sender and receiver
			fe.setSender(strMyName);
			fe.setReceiver(strFileReceiver);
			// send the event to the file receiver
			bReturn = CMEventManager.unicastEvent(fe, strFileReceiver, cmInfo);			
		}
		
		return bReturn;
	}
	
	public static boolean replyPermitForPushFile(CMFileEvent fe, int nReturnCode, 
			CMInfo cmInfo)
	{
		CMInteractionInfo interInfo = cmInfo.getInteractionInfo();
		String myName = interInfo.getMyself().getName();
		boolean bRet = false;
		
		CMFileEvent feAck = new CMFileEvent();
		feAck.setID(CMFileEvent.REPLY_PERMIT_PUSH_FILE);
		feAck.setFileSender(fe.getFileSender());
		feAck.setFileReceiver(fe.getFileReceiver());
		feAck.setFilePath(fe.getFilePath());
		feAck.setFileSize(fe.getFileSize());
		feAck.setContentID(fe.getContentID());
		feAck.setReturnCode(nReturnCode);
		
		if(isP2PFileTransfer(feAck, cmInfo))
		{
			if(CMInfo._CM_DEBUG)
			{
				System.out.println("CMFileTransferManager.replyPermitForPushFile(), "
						+ "isP2PFileTransfer() returns true.");
			}
			// set event sender and receiver
			feAck.setSender(myName);
			String strDefServer = interInfo.getDefaultServerInfo().getServerName();
			feAck.setReceiver(strDefServer);
			
			// set distribution fields
			feAck.setDistributionSession("CM_ONE_USER");
			feAck.setDistributionGroup(fe.getFileSender());
			
			// send event to the default server
			bRet = CMEventManager.unicastEvent(feAck, strDefServer, cmInfo);
		}
		else
		{
			if(CMInfo._CM_DEBUG)
			{
				System.out.println("CMFileTransferManager.replyPermitForPushFile(), "
						+ "isP2PFileTransfer() returns false.");
			}
			// set event sender and receiver
			feAck.setSender(myName);
			feAck.setReceiver(fe.getFileSender());
			// send the event to the file sender
			bRet = CMEventManager.unicastEvent(feAck, fe.getFileSender(), cmInfo);			
		}
		
		return bRet;
	}
	
	public static boolean pushFile(String strFilePath, String strReceiver, CMInfo cmInfo)
	{
		boolean bReturn = false;
		bReturn = pushFile(strFilePath, strReceiver, CMInfo.FILE_DEFAULT, -1, cmInfo);
		return bReturn;
	}

	public static boolean pushFile(String strFilePath, String strReceiver, byte byteFileAppend, CMInfo cmInfo)
	{
		boolean bReturn = false;
		bReturn = pushFile(strFilePath, strReceiver, byteFileAppend, -1, cmInfo);
		return bReturn;
	}

	public static boolean pushFile(String strFilePath, String strReceiver, byte byteFileAppend, 
			int nContentID, CMInfo cmInfo)
	{
		boolean bReturn = false;
		CMConfigurationInfo confInfo = cmInfo.getConfigurationInfo();
		CMFileTransferInfo fInfo = cmInfo.getFileTransferInfo();
		fInfo.setStartTime(System.currentTimeMillis());
		
		if(confInfo.isFileTransferScheme())
			bReturn = pushFileWithSepChannel(strFilePath, strReceiver, byteFileAppend, nContentID, cmInfo);
		else
			bReturn = pushFileWithDefChannel(strFilePath, strReceiver, byteFileAppend, nContentID, cmInfo);
		return bReturn;
	}

	// strFilePath: absolute or relative path to a target file
	private static boolean pushFileWithDefChannel(String strFilePath, String strFileReceiver, 
			byte byteFileAppend, int nContentID, CMInfo cmInfo)
	{
		boolean bReturn = false;
		CMFileTransferInfo fInfo = cmInfo.getFileTransferInfo();
		CMInteractionInfo interInfo = cmInfo.getInteractionInfo();
		
		// get file information (size)
		File file = new File(strFilePath);
		if(!file.exists())
		{
			System.err.println("CMFileTransferManager.pushFileWithDefChannel(), file("+strFilePath+") does not exists.");
			return false;
		}
		long lFileSize = file.length();
		
		// add send file information
		// receiver name, file path, size
		fInfo.addSendFileInfo(strFileReceiver, strFilePath, lFileSize, nContentID);
		
		// set the cancellation flag
		fInfo.setCancelSend(false);

		// get my name
		String strMyName = interInfo.getMyself().getName();

		// get file name
		String strFileName = getFileNameFromPath(strFilePath);
		System.out.println("file name: "+strFileName);
		
		// start file transfer process
		CMFileEvent fe = new CMFileEvent();
		fe.setID(CMFileEvent.START_FILE_TRANSFER);
		fe.setFileSender(strMyName);
		fe.setFileReceiver(strFileReceiver);
		fe.setFileName(strFileName);
		fe.setFileSize(lFileSize);
		fe.setContentID(nContentID);
		fe.setFileAppendFlag(byteFileAppend);
		
		if(isP2PFileTransfer(fe, cmInfo))
		{
			if(CMInfo._CM_DEBUG)
			{
				System.out.println("CMFileTransferManager.pushFileWithDefChannel(), "
						+ "isP2PFileTransfer() returns true.");
			}
			// set event sender and receiver
			fe.setSender(strMyName);
			String strDefServer = interInfo.getDefaultServerInfo().getServerName();
			fe.setReceiver(strDefServer);
			
			// set distribution fields
			fe.setDistributionSession("CM_ONE_USER");
			fe.setDistributionGroup(strFileReceiver);
			
			// send the event to the default server
			bReturn = CMEventManager.unicastEvent(fe, strDefServer, cmInfo);
		}
		else
		{
			if(CMInfo._CM_DEBUG)
			{
				System.out.println("CMFileTransferManager.pushFileWithDefChannel(), "
						+ "isP2PFileTransfer() returns false.");
			}
			// set event sender and receiver
			fe.setSender(strMyName);
			fe.setReceiver(strFileReceiver);
			// send the event to the file receiver
			bReturn = CMEventManager.unicastEvent(fe, strFileReceiver, cmInfo);			
		}
		
		if(!bReturn)
		{
			// remove send file information
			fInfo.removeSendFileInfo(strFileReceiver, strFileName, nContentID);
		}

		file = null;
		fe = null;
		return bReturn;
	}
	
	// strFilePath: absolute or relative path to a target file
	private static boolean pushFileWithSepChannel(String strFilePath, String strFileReceiver, 
			byte byteFileAppend, int nContentID, CMInfo cmInfo)
	{
		boolean bReturn = false;
		CMFileTransferInfo fInfo = cmInfo.getFileTransferInfo();
		CMInteractionInfo interInfo = cmInfo.getInteractionInfo();
		CMConfigurationInfo confInfo = cmInfo.getConfigurationInfo();

		// check the creation of the default blocking TCP socket channel
		CMChannelInfo<Integer> blockChannelList = null;
		CMChannelInfo<Integer> nonBlockChannelList = null;
		SocketChannel sc = null;
		SocketChannel dsc = null;
		if(confInfo.getSystemType().equals("CLIENT"))
		{
			blockChannelList = interInfo.getDefaultServerInfo().getBlockSocketChannelInfo();
			sc = (SocketChannel) blockChannelList.findChannel(0);	// default key for the blocking channel is 0
			nonBlockChannelList = interInfo.getDefaultServerInfo().getNonBlockSocketChannelInfo();
			dsc = (SocketChannel) nonBlockChannelList.findChannel(0); // key for the default TCP socket channel is 0
		}
		else	// SERVER
		{
			CMUser user = interInfo.getLoginUsers().findMember(strFileReceiver);
			if(user == null)
			{
				System.err.println("CMFileTransferManager.pushFileWithSepChannel(); "
						+ "user("+strFileReceiver+") not found!");
				return false;
			}
			blockChannelList = user.getBlockSocketChannelInfo();
			sc = (SocketChannel) blockChannelList.findChannel(0);	// default key for the blocking channel is 0
			nonBlockChannelList = user.getNonBlockSocketChannelInfo();
			dsc = (SocketChannel) nonBlockChannelList.findChannel(0);	// key for the default TCP socket channel is 0
		}

		if(sc == null)
		{
			System.err.println("CMFileTransferManager.pushFileWithSepChannel(); "
					+ "default blocking TCP socket channel not found!");
			return false;
		}
		else if(!sc.isOpen())
		{
			System.err.println("CMFileTransferManager.pushFileWithSepChannel(); "
					+ "default blocking TCP socket channel closed!");
			return false;
		}
		
		if(dsc == null)
		{
			System.err.println("CMFileTransferManager.pushFileWithSepChannel(); "
					+ "default TCP socket channel not found!");
			return false;
		}
		else if(!dsc.isOpen())
		{
			System.err.println("CMFileTransferManager.pushFileWithSepChannel(); "
					+ "default TCP socket channel closed!");
			return false;
		}


		// get file information (size)
		File file = new File(strFilePath);
		if(!file.exists())
		{
			System.err.println("CMFileTransferManager.pushFileWithSepChannel(), file("+strFilePath+") does not exists.");
			return false;
		}
		long lFileSize = file.length();

		// add send file information
		// sender name, receiver name, file path, size, content ID
		CMSendFileInfo sfInfo = new CMSendFileInfo();
		sfInfo.setFileSender(interInfo.getMyself().getName());
		sfInfo.setFileReceiver(strFileReceiver);
		sfInfo.setFilePath(strFilePath);
		sfInfo.setFileSize(lFileSize);
		sfInfo.setContentID(nContentID);
		sfInfo.setSendChannel(sc);
		sfInfo.setDefaultChannel(dsc);
		//boolean bResult = fInfo.addSendFileInfo(strReceiver, strFilePath, lFileSize, nContentID);
		bReturn = fInfo.addSendFileInfo(sfInfo);
		if(!bReturn)
		{
			System.err.println("CMFileTransferManager.pushFileWithSepChannel(); "
					+ "error for adding the sending file info: "
					+"receiver("+strFileReceiver+"), file("+strFilePath+"), size("
					+lFileSize+"), content ID("+nContentID+")!");
			return false;
		}

		// get my name
		String strMyName = interInfo.getMyself().getName();

		// get file name
		String strFileName = getFileNameFromPath(strFilePath);

		// start file transfer process
		CMFileEvent fe = new CMFileEvent();
		fe.setID(CMFileEvent.START_FILE_TRANSFER_CHAN);
		fe.setFileSender(strMyName);
		fe.setFileReceiver(strFileReceiver);
		fe.setFileName(strFileName);
		fe.setFileSize(lFileSize);
		fe.setContentID(nContentID);
		fe.setFileAppendFlag(byteFileAppend);
		
		if(isP2PFileTransfer(fe, cmInfo))
		{
			if(CMInfo._CM_DEBUG)
			{
				System.out.println("CMFileTransferManager.pushFileWithSepChannel(), "
						+ "isP2PFileTransfer() returns true.");
			}
			// set event sender and receiver
			fe.setSender(strMyName);
			String strDefServer = interInfo.getDefaultServerInfo().getServerName();
			fe.setReceiver(strDefServer);
			
			// set distribution fields
			fe.setDistributionSession("CM_ONE_USER");
			fe.setDistributionGroup(strFileReceiver);
			
			// send the event to the default server
			bReturn = CMEventManager.unicastEvent(fe, strDefServer, cmInfo);
		}
		else
		{
			if(CMInfo._CM_DEBUG)
			{
				System.out.println("CMFileTransferManager.pushFileWithSepChannel(), "
						+ "isP2PFileTransfer() returns false.");
			}
			// set event sender and receiver
			fe.setSender(strMyName);
			fe.setReceiver(strFileReceiver);
			// send the event to the file receiver
			bReturn = CMEventManager.unicastEvent(fe, strFileReceiver, cmInfo);			
		}

		if(!bReturn)
		{
			fInfo.removeSendFileInfo(strFileReceiver, strFileName, nContentID);
		}
		
		file = null;
		fe = null;
		return bReturn;
	}
	
	public static boolean cancelPushFile(String strReceiver, CMInfo cmInfo)
	{
		boolean bReturn = false;
		CMConfigurationInfo confInfo = cmInfo.getConfigurationInfo();
		if(confInfo.isFileTransferScheme())
			bReturn = cancelPushFileWithSepChannel(strReceiver, cmInfo);
		else
			bReturn = cancelPushFileWithDefChannel(strReceiver, cmInfo);
		
		return bReturn;
	}
	
	private static boolean cancelPushFileWithDefChannel(String strFileReceiver, CMInfo cmInfo)
	{
		boolean bReturn = false;
		CMFileTransferInfo fInfo = cmInfo.getFileTransferInfo();
		CMInteractionInfo interInfo = cmInfo.getInteractionInfo();
		CMList<CMSendFileInfo> sendList = null;
		Iterator<CMSendFileInfo> iterSendList = null;
		CMSendFileInfo sInfo = null;
		CMFileEvent fe = null;
		String strDefServer = null;

		if(strFileReceiver != null)
		{
			// find the CMSendFile list of the strReceiver
			sendList = fInfo.getSendFileList(strFileReceiver);
			if(sendList == null)
			{
				System.err.println("CMFileTransferManager.cancelPushFileWithDefChannel(); Sending file list "
						+ "not found for the receiver("+strFileReceiver+")!");
				return false;
			}			
		}
		
		// set the flag
		fInfo.setCancelSend(true);
		
		// send the cancellation event to the receiver
		// close the RandomAccessFile and remove the sending file info of the receiver
		if(strFileReceiver != null) // for the target receiver
		{
			fe = new CMFileEvent();
			fe.setID(CMFileEvent.CANCEL_FILE_SEND);
			fe.setFileSender(interInfo.getMyself().getName());
			fe.setFileReceiver(strFileReceiver);
			
			if(isP2PFileTransfer(fe, cmInfo))
			{
				if(CMInfo._CM_DEBUG)
				{
					System.out.println("CMFileTransferManager.cancelPushFileWithDefChannel(), "
							+ "isP2PFileTransfer() returns true.");
				}
				// set event sender and receiver
				fe.setSender(interInfo.getMyself().getName());
				strDefServer = interInfo.getDefaultServerInfo().getServerName();
				fe.setReceiver(strDefServer);
				
				// set distribution fields
				fe.setDistributionSession("CM_ONE_USER");
				fe.setDistributionGroup(strFileReceiver);
				
				// send the event to the default server
				CMEventManager.unicastEvent(fe, strDefServer, cmInfo);
			}
			else
			{
				if(CMInfo._CM_DEBUG)
				{
					System.out.println("CMFileTransferManager.cancelPushFileWithDefChannel(), "
							+ "isP2PFileTransfer() returns false.");
				}
				// set event sender and receiver
				fe.setSender(interInfo.getMyself().getName());
				fe.setReceiver(strFileReceiver);
				// send the event to the file receiver
				CMEventManager.unicastEvent(fe, strFileReceiver, cmInfo);				
			}
			
			// close the RandomAccessFile
			iterSendList = sendList.getList().iterator();
			while(iterSendList.hasNext())
			{
				sInfo = iterSendList.next();
				if(sInfo.getReadFile() != null)
				{
					try {
						sInfo.getReadFile().close();
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			}
			
			bReturn = fInfo.removeSendFileList(strFileReceiver);
		}
		else	// for all receivers
		{
			Set<String> keySet = fInfo.getSendFileHashtable().keySet();
			Iterator<String> iterKeys = keySet.iterator();
			while(iterKeys.hasNext())
			{
				String iterFileReceiver = iterKeys.next();
				fe = new CMFileEvent();
				fe.setID(CMFileEvent.CANCEL_FILE_SEND);
				fe.setFileSender(interInfo.getMyself().getName());
				fe.setFileReceiver(iterFileReceiver);
				
				if(isP2PFileTransfer(fe, cmInfo))
				{
					if(CMInfo._CM_DEBUG)
					{
						System.out.println("CMFileTransferManager.cancelPushFileWithDefChannel(), "
								+ "isP2PFileTransfer() returns true.");
					}
					// set event sender and receiver
					fe.setSender(interInfo.getMyself().getName());
					strDefServer = interInfo.getDefaultServerInfo().getServerName();
					fe.setReceiver(strDefServer);
					
					// set distribution fields
					fe.setDistributionSession("CM_ONE_USER");
					fe.setDistributionGroup(iterFileReceiver);
					
					// send the event to the default server
					CMEventManager.unicastEvent(fe, strDefServer, cmInfo);
				}
				else
				{
					if(CMInfo._CM_DEBUG)
					{
						System.out.println("CMFileTransferManager.cancelPushFileWithDefChannel(), "
								+ "isP2PFileTransfer() returns false.");
					}
					// set event sender and receiver
					fe.setSender(interInfo.getMyself().getName());
					fe.setReceiver(iterFileReceiver);
					// send the event to file receiver
					CMEventManager.unicastEvent(fe, iterFileReceiver, cmInfo);					
				}
				
				// close the RandomAccessFile
				sendList = fInfo.getSendFileList(iterFileReceiver);
				iterSendList = sendList.getList().iterator();
				while(iterSendList.hasNext())
				{
					sInfo = iterSendList.next();
					if(sInfo.getReadFile() != null)
					{
						try {
							sInfo.getReadFile().close();
						} catch (IOException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					}
				}
			}
			
			bReturn = fInfo.clearSendFileHashtable();
		}
		
		if(bReturn)
		{
			if(CMInfo._CM_DEBUG)
				System.out.println("CMFileTransferManager.cancelPushFileWithDefChannel(); succeeded for "
						+ "receiver("+strFileReceiver+").");
		}
		else
		{
			System.err.println("CMFileTransferManager.cancelPushFileWithDefChannel(); failed for "
					+ "receiver("+strFileReceiver+")!");
		}
		
		return bReturn;
	}
	
	// cancel the sending file task with separate channels and threads
	private static boolean cancelPushFileWithSepChannel(String strReceiver, CMInfo cmInfo)
	{
		boolean bReturn = false;
		CMFileTransferInfo fInfo = cmInfo.getFileTransferInfo();

		if(strReceiver != null)
		{
			bReturn = cancelPushFileWithSepChannelForOneReceiver(strReceiver, cmInfo);
		}
		else // cancel file transfer to all receivers
		{
			Set<String> keySet = fInfo.getSendFileHashtable().keySet();
			Iterator<String> iterKeys = keySet.iterator();
			while(iterKeys.hasNext())
			{
				String iterReceiver = iterKeys.next();
				bReturn = cancelPushFileWithSepChannelForOneReceiver(iterReceiver, cmInfo);
			}
			// clear the sending file hash table
			bReturn = fInfo.clearSendFileHashtable();
		}

		return bReturn;
	}

	// cancel the sending file task to one receiver with a separate channel and thread
	private static boolean cancelPushFileWithSepChannelForOneReceiver(String strFileReceiver, CMInfo cmInfo)
	{
		CMFileTransferInfo fInfo = cmInfo.getFileTransferInfo();
		CMList<CMSendFileInfo> sendList = null;
		CMSendFileInfo sInfo = null;
		boolean bReturn = false;
		Future<CMSendFileInfo> sendTask = null;
		CMFileEvent fe = null;
		CMInteractionInfo interInfo = cmInfo.getInteractionInfo();
		CMConfigurationInfo confInfo = cmInfo.getConfigurationInfo();
		CMChannelInfo<Integer> blockSCInfo = null;
		SocketChannel defaultBlockSC = null;
		boolean bP2PFileTransfer = false;
		
		// find the CMSendFile list of the strReceiver
		sendList = fInfo.getSendFileList(strFileReceiver);
		if(sendList == null)
		{
			System.err.println("CMFileTransferManager.cancelPushFileWithSepChannelForOneReceiver(); Sending file list "
					+ "not found for the receiver("+strFileReceiver+")!");
			return false;
		}
		
		// find the current sending file task
		sInfo = fInfo.findSendFileInfoOngoing(strFileReceiver);
		if(sInfo == null)
		{
			System.err.println("CMFileTransferManager.cancelPushFileWithSepChannelForOneReceiver(); ongoing sending task "
					+ "not found for the receiver("+strFileReceiver+")!");
			bReturn = fInfo.removeSendFileList(strFileReceiver);
			return bReturn;
		}
		
		// request for canceling the sending task
		sendTask = sInfo.getSendTaskResult();
		sendTask.cancel(true);
		// wait for the thread cancellation
		try {
			sendTask.get(10L, TimeUnit.SECONDS);
		} catch(CancellationException e) {
			System.out.println("CMFileTransferManager.cancelPushFileWithSepChannelForOneReceiver(); "
					+ "the sending task cancelled.: "
					+ "receiver("+strFileReceiver+"), file("+sInfo.getFileName()+"), file size("+sInfo.getFileSize()
					+ "), sent size("+sInfo.getSentSize()+")");
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (ExecutionException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (TimeoutException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		// remove the sending file list of the receiver
		bReturn = fInfo.removeSendFileList(strFileReceiver);			

		/////////////////////// management of the closed default blocking socket channel
		
		// get the default blocking socket channel
		if(confInfo.getSystemType().equals("CLIENT"))
		{
			blockSCInfo = interInfo.getDefaultServerInfo().getBlockSocketChannelInfo();
			if(CMInfo._CM_DEBUG)
			{
				System.out.println("CMFileTransferManager.cancelPushFileWithSepChannelForOneReceiver(); "
						+ "# blocking socket channel: "	+ blockSCInfo.getSize());
			}
			// get the default blocking socket channel
			defaultBlockSC = (SocketChannel) blockSCInfo.findChannel(0);	// default blocking channel
				
		}
		else	// server
		{
			CMUser receiver = interInfo.getLoginUsers().findMember(strFileReceiver);
			blockSCInfo = receiver.getBlockSocketChannelInfo();
			// get the default blocking socket channel
			defaultBlockSC = (SocketChannel) receiver.getBlockSocketChannelInfo().findChannel(0);

			if(CMInfo._CM_DEBUG)
			{
				System.out.println("CMFileTransferManager.cancelPushFileWithSepChannelForOneReceiver(); "
						+ "# blocking socket channel: "	+ blockSCInfo.getSize());
			}

		}

		// close the default blocking socket channel if it is open
		// the channel is actually closed due to the interrupt exception of the sending thread
		if(defaultBlockSC.isOpen())
		{
			if(CMInfo._CM_DEBUG)
			{
				System.out.println("CMFileTransferManager.cancelPushFileWithSepChannelForOneReceiver(); "
						+ "the default channel is still open and should be closed for reconnection!");
			}
			
			try {
				defaultBlockSC.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		else
		{
			System.err.println("CMFileTransferManager.cancelPushFileWithSepChannelForOneReceiver(); "
					+ "the default channel is already closed!");
		}
		
		// remove the default blocking socket channel
		blockSCInfo.removeChannel(0);

		// send the cancel event to the receiver
		fe = new CMFileEvent();
		fe.setID(CMFileEvent.CANCEL_FILE_SEND_CHAN);
		fe.setFileSender(interInfo.getMyself().getName());
		fe.setFileReceiver(strFileReceiver);
		
		bP2PFileTransfer = isP2PFileTransfer(fe, cmInfo);
		
		if(bP2PFileTransfer)
		{
			if(CMInfo._CM_DEBUG)
			{
				System.out.println("CMFileTransferManager.cancelPushFileWithSepChannelForOneReceiver(), "
						+ "isP2PFileTransfer() returns true.");
			}
			// set event sender and receiver
			fe.setSender(interInfo.getMyself().getName());
			String strDefServer = interInfo.getDefaultServerInfo().getServerName();
			fe.setReceiver(strDefServer);
			
			// set distribution fields
			fe.setDistributionSession("CM_ONE_USER");
			fe.setDistributionGroup(strFileReceiver);
			
			// send the event to the default server
			bReturn = CMEventManager.unicastEvent(fe, strDefServer, cmInfo);
		}
		else
		{
			if(CMInfo._CM_DEBUG)
			{
				System.out.println("CMFileTransferManager.cancelPushFileWithSepChannelForOneReceiver(), "
						+ "isP2PFileTransfer() returns false.");
			}
			// set event sender and receiver
			fe.setSender(interInfo.getMyself().getName());
			fe.setReceiver(strFileReceiver);
			// send the event to the file receiver
			bReturn = CMEventManager.unicastEvent(fe, strFileReceiver, cmInfo);			
		}
		
		if(!bReturn)
		{
			return false;
		}
		
		// if the system type is client, it recreates the default blocking socket channel to the default server
		if(confInfo.getSystemType().equals("CLIENT") && !bP2PFileTransfer)
		{
			CMServer serverInfo = interInfo.getDefaultServerInfo();
			try {
				defaultBlockSC = (SocketChannel) CMCommManager.openBlockChannel(CMInfo.CM_SOCKET_CHANNEL, 
						serverInfo.getServerAddress(), serverInfo.getServerPort(), cmInfo);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				return false;
			}
			
			if(defaultBlockSC == null)
			{
				System.err.println("CMFileTransferManager.cancelPushFileWithSepChannelForOneReceiver(); recreation of "
						+ "the blocking socket channel failed!: server("+serverInfo.getServerAddress()+"), port("
						+ serverInfo.getServerPort() +")");
				return false;
			}
			bReturn = blockSCInfo.addChannel(0, defaultBlockSC);

			if(bReturn)
			{
				CMSessionEvent se = new CMSessionEvent();
				se.setID(CMSessionEvent.ADD_BLOCK_SOCKET_CHANNEL);
				se.setChannelName(interInfo.getMyself().getName());
				se.setChannelNum(0);
				bReturn = CMEventManager.unicastEvent(se, serverInfo.getServerName(), CMInfo.CM_STREAM, 0, true, cmInfo);
				se = null;

				if(bReturn)
				{
					if(CMInfo._CM_DEBUG)
					{
						System.out.println("CMFileTransferManager.cancelPushFileWithSepChannelForOneReceiver(); "
								+ "successfully requested to add the blocking socket channel with the key(0) "
								+ "to the server("+serverInfo.getServerName()+")");
					}
					
				}
			}
		}
		
		//////////////////////////////////

		return bReturn;
	}

	// srcFile: reference of RandomAccessFile of source file
	// bos: reference of BufferedOutputStream of split file
	public static void splitFile(RandomAccessFile srcFile, long lOffset, long lSplitSize, String strSplitFile)
	{
		long lRemainBytes = lSplitSize;
		byte[] fileBlock = new byte[CMInfo.FILE_BLOCK_LEN];
		int readBytes;
		BufferedOutputStream bos = null;
		try {
			bos = new BufferedOutputStream(new FileOutputStream(strSplitFile));
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		try {
			// set file position
			srcFile.seek(lOffset);

			// read and write
			while( lRemainBytes > 0 )
			{
				if(lRemainBytes >= CMInfo.FILE_BLOCK_LEN)
					readBytes = srcFile.read(fileBlock);
				else
					readBytes = srcFile.read(fileBlock, 0, (int)lRemainBytes);

				if( readBytes >= CMInfo.FILE_BLOCK_LEN )
					bos.write(fileBlock);
				else
					bos.write(fileBlock, 0, readBytes);

				lRemainBytes -= readBytes;
			}
			
			bos.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		return;
	}
	
	public static long mergeFiles(String[] strSplitFiles, int nSplitNum, String strMergeFile)
	{
		long lMergeSize = -1;
		long lSrcSize = 0;
		FileInputStream srcfis = null;
		BufferedOutputStream bos = null;
		byte[] fileBlock = new byte[CMInfo.FILE_BLOCK_LEN];
		int readBytes = 0;

		if(nSplitNum != strSplitFiles.length)
		{
			System.err.println("CMFileTransferManager.mergeFiles(), the number of members in the "
					+"first parameter is different from the given second parameter!");
			return -1;
		}
		
		// open a target file
		try {
			
			bos = new BufferedOutputStream(new FileOutputStream(strMergeFile));
			
			for(int i = 0; i < nSplitNum; i++)
			{
				// open a source file
				File srcFile = new File(strSplitFiles[i]);
				srcfis = new FileInputStream(srcFile);

				// get source file size
				lSrcSize = srcFile.length();

				// concatenate a source file to a target file
				while( lSrcSize > 0 )
				{
					if( lSrcSize >= CMInfo.FILE_BLOCK_LEN )
					{
						readBytes = srcfis.read(fileBlock);
						bos.write(fileBlock, 0, readBytes);
					}
					else
					{
						readBytes = srcfis.read(fileBlock, 0, (int)lSrcSize);
						bos.write(fileBlock, 0, readBytes);
					}

					lSrcSize -= readBytes;
				}

				// close a source file
				srcfis.close();
			}
			
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			try {
				if(srcfis != null)
				{
					srcfis.close();
				}
				if(bos != null){
					bos.close();
				}
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		
		File targetFile = new File(strMergeFile);
		lMergeSize = targetFile.length();
		
		return lMergeSize;
	}
	
	public static String getFileNameFromPath(String strPath)
	{
		String strName = null;
		int index;
		String sep = File.separator;
		/*
		index = strPath.lastIndexOf("/");
		if(index == -1)
		{
			index = strPath.lastIndexOf(sep);
			if(index == -1)
				strName = strPath;
			else
				strName = strPath.substring(index+1);
		}
		else
		{
			strName = strPath.substring(index+1);
		}
		*/
		index = strPath.lastIndexOf(sep);
		if(index == -1)
			strName = strPath;
		else
			strName = strPath.substring(index+1);
		
		return strName;
	}
	
	//////////////////////////////////////////////////////////////////
	// process file event
	
	public static boolean processEvent(CMMessage msg, CMInfo cmInfo)
	{
		boolean bForward = true;
		CMFileEvent fe = new CMFileEvent(msg.m_buf);
		
		switch(fe.getID())
		{
		case CMFileEvent.REQUEST_PERMIT_PULL_FILE:
			bForward = processREQUEST_PERMIT_PULL_FILE(fe, cmInfo);
			break;
		case CMFileEvent.REPLY_PERMIT_PULL_FILE:
			bForward = processREPLY_PERMIT_PULL_FILE(fe, cmInfo);
			break;
		case CMFileEvent.REQUEST_PERMIT_PUSH_FILE:
			bForward = processREQUEST_PERMIT_PUSH_FILE(fe, cmInfo);
			break;
		case CMFileEvent.REPLY_PERMIT_PUSH_FILE:
			bForward = processREPLY_PERMIT_PUSH_FILE(fe, cmInfo);
			break;
		case CMFileEvent.START_FILE_TRANSFER:
			bForward = processSTART_FILE_TRANSFER(fe, cmInfo);
			break;
		case CMFileEvent.START_FILE_TRANSFER_ACK:
			bForward = processSTART_FILE_TRANSFER_ACK(fe, cmInfo);
			break;
		case CMFileEvent.CONTINUE_FILE_TRANSFER:
			bForward = processCONTINUE_FILE_TRANSFER(fe, cmInfo);
			break;
		case CMFileEvent.END_FILE_TRANSFER:
			bForward = processEND_FILE_TRANSFER(fe, cmInfo);
			break;
		case CMFileEvent.END_FILE_TRANSFER_ACK:
			bForward = processEND_FILE_TRANSFER_ACK(fe, cmInfo);
			break;
		case CMFileEvent.REQUEST_DIST_FILE_PROC:
			bForward = processREQUEST_DIST_FILE_PROC(fe, cmInfo);
			break;
		case CMFileEvent.REQUEST_PERMIT_PULL_FILE_CHAN:
			bForward = processREQUEST_PERMIT_PULL_FILE_CHAN(fe, cmInfo);
			break;
		case CMFileEvent.REPLY_PERMIT_PULL_FILE_CHAN:
			bForward = processREPLY_PERMIT_PULL_FILE_CHAN(fe, cmInfo);
			break;
		case CMFileEvent.START_FILE_TRANSFER_CHAN:
			bForward = processSTART_FILE_TRANSFER_CHAN(fe, cmInfo);
			break;
		case CMFileEvent.START_FILE_TRANSFER_CHAN_ACK:
			bForward = processSTART_FILE_TRANSFER_CHAN_ACK(fe, cmInfo);
			break;
		case CMFileEvent.END_FILE_TRANSFER_CHAN:
			bForward = processEND_FILE_TRANSFER_CHAN(fe, cmInfo);
			break;
		case CMFileEvent.END_FILE_TRANSFER_CHAN_ACK:
			bForward = processEND_FILE_TRANSFER_CHAN_ACK(fe, cmInfo);
			break;
		case CMFileEvent.CANCEL_FILE_SEND:
			bForward = processCANCEL_FILE_SEND(fe, cmInfo);
			break;
		case CMFileEvent.CANCEL_FILE_SEND_ACK:
			bForward = processCANCEL_FILE_SEND_ACK(fe, cmInfo);
			break;
		case CMFileEvent.CANCEL_FILE_SEND_CHAN:
			bForward = processCANCEL_FILE_SEND_CHAN(fe, cmInfo);
			break;
		case CMFileEvent.CANCEL_FILE_SEND_CHAN_ACK:
			bForward = processCANCEL_FILE_SEND_CHAN_ACK(fe, cmInfo);
			break;
		case CMFileEvent.CANCEL_FILE_RECV_CHAN:
			bForward = processCANCEL_FILE_RECV_CHAN(fe, cmInfo);
			break;
		case CMFileEvent.CANCEL_FILE_RECV_CHAN_ACK:
			bForward = processCANCEL_FILE_RECV_CHAN_ACK(fe, cmInfo);
			break;
		case CMFileEvent.ERR_RECV_FILE_CHAN:
			processERR_RECV_FILE_CHAN(fe, cmInfo);
			break;
		case CMFileEvent.ERR_SEND_FILE_CHAN:
			processERR_SEND_FILE_CHAN(fe, cmInfo);
			break;
		default:
			System.err.println("CMFileTransferManager.processEvent(), unknown event id("+fe.getID()+").");
			fe = null;
			return false;
		}
		
		fe.setFileBlock(null);
		fe = null;
		return bForward;
	}
	
	private static boolean processREQUEST_PERMIT_PULL_FILE(CMFileEvent fe, CMInfo cmInfo)
	{
		boolean bForward = true;
		CMConfigurationInfo confInfo = cmInfo.getConfigurationInfo();
		CMInteractionInfo interInfo = cmInfo.getInteractionInfo();
		String strMyName = interInfo.getMyself().getName();
		
		if(CMInfo._CM_DEBUG)
		{
			System.out.println("CMFileTransferManager.processREQUEST_PERMIT_PULL_FILE(), "
					+ "file sender("+fe.getFileSender()+"), file receiver(requester)("
					+fe.getFileReceiver()+"), file("+fe.getFileName()
					+"), contentID("+fe.getContentID()+"), append flag("
					+fe.getFileAppendFlag()+").");
		}

		// check whether this CM node is the target node of this event or not		
		if(!fe.getFileSender().contentEquals(strMyName))
		{
			if(CMInfo._CM_DEBUG)
			{
				System.err.println("This node ("+strMyName+") is not the file sender("
						+fe.getFileSender()+").");
			}
			return false;
		}

		// get the full path of the requested file
		String strFullPath = confInfo.getTransferedFileHome().toString() + 
				File.separator + fe.getFileName(); 
		// check the file existence
		File file = new File(strFullPath);
		if(!file.exists())
		{
			replyPermitForPullFile(fe, -1, cmInfo);
			bForward = false;
			return bForward;
		}		

		if(confInfo.isPermitFileTransferRequest() || 
				fe.getFileName().contentEquals("throughput-test.jpg"))
		{
			replyPermitForPullFile(fe, 1, cmInfo);
			bForward = false;
		}
		
		return bForward;
	}
	
	private static boolean processREPLY_PERMIT_PULL_FILE(CMFileEvent fe, CMInfo cmInfo)
	{
		CMEventInfo eInfo = cmInfo.getEventInfo();
		CMEventSynchronizer eventSync = eInfo.getEventSynchronizer();
		CMInteractionInfo interInfo = cmInfo.getInteractionInfo();
		String strMyName = interInfo.getMyself().getName();
		boolean bForward = true;
		
		if(CMInfo._CM_DEBUG)
		{
			System.out.println("CMFileTransferManager.processREPLY_PERMIT_PULL_FILE(), "
					+ "file sender("+fe.getFileSender()+"), file receiver("
					+ fe.getFileReceiver()+"), file("+fe.getFileName()
					+"), return code("+fe.getReturnCode()+"), contentID("
					+fe.getContentID()+").");
		}
		
		// check whether this CM node is the target node of this event or not		
		if(!fe.getFileReceiver().contentEquals(strMyName))
		{
			if(CMInfo._CM_DEBUG)
			{
				System.err.println("This node ("+strMyName+") is not the file receiver("
						+fe.getFileReceiver()+").");
			}
			bForward = false;
			return bForward;
		}
		
		if(fe.getReturnCode() == -1)
			System.err.println("The requested file does not exists!");
		else if(fe.getReturnCode() == 0)
			System.err.println("sender("+fe.getFileSender()+") rejects to send the file!");
		
		if((fe.getReturnCode() == 0 || fe.getReturnCode() == -1) 
				&& fe.getFileName().equals("throughput-test.jpg"))
		{
			synchronized(eventSync)
			{
				eventSync.setReplyEvent(fe);
				eventSync.notify();
			}
		}
		
		return bForward;
	}
	
	private static boolean processREQUEST_PERMIT_PUSH_FILE(CMFileEvent fe, CMInfo cmInfo)
	{
		boolean bForward = true;
		CMInteractionInfo interInfo = cmInfo.getInteractionInfo();
		String strMyName = interInfo.getMyself().getName();
		
		if(CMInfo._CM_DEBUG)
		{
			System.out.println("CMFileTransferManager.processREQUEST_PERMIT_PUSH_FILE(), ");
			System.out.println("file sender("+fe.getFileSender()+"), file receiver("
					+fe.getFileReceiver()+"), file("+fe.getFilePath()+"), size("
					+fe.getFileSize()+"), contentID("+fe.getContentID()+").");
		}
		
		// check whether this CM node is the target node of this event or not		
		if(!fe.getFileReceiver().contentEquals(strMyName))
		{
			if(CMInfo._CM_DEBUG)
			{
				System.err.println("This node ("+strMyName+") is not the file receiver("
						+fe.getFileReceiver()+").");
			}
			return false;
		}
		
		// check PERMIT_FILE_TRANSFER field
		CMConfigurationInfo confInfo = cmInfo.getConfigurationInfo();
		boolean bPermit = confInfo.isPermitFileTransferRequest();
		if(bPermit)
		{
			replyPermitForPushFile(fe, 1, cmInfo);  			
			bForward = false;
		}
		
		return bForward;
	}
	
	private static boolean processREPLY_PERMIT_PUSH_FILE(CMFileEvent fe, CMInfo cmInfo)
	{
		boolean bForward = true;
		CMInteractionInfo interInfo = cmInfo.getInteractionInfo();
		String strMyName = interInfo.getMyself().getName();
		
		if(CMInfo._CM_DEBUG)
		{
			System.out.println("CMFileTransferManager.processREPLY_PERMIT_PUSH_FILE(), ");
			System.out.println("file sender("+fe.getFileSender()+"), file receiver("
					+fe.getFileReceiver()+"), file("+fe.getFilePath()+"), size("
					+fe.getFileSize()+"), contentID("+fe.getContentID()+"), return code("
					+fe.getReturnCode()+").");
		}
		
		// check whether this CM node is the target node of this event or not		
		if(!fe.getFileSender().contentEquals(strMyName))
		{
			if(CMInfo._CM_DEBUG)
			{
				System.err.println("This node ("+strMyName+") is not the file sender("
						+fe.getFileSender()+").");
			}
			return false;
		}
		
		if(fe.getReturnCode() == 1)
		{
			// call pushFile()
			pushFile(fe.getFilePath(), fe.getFileReceiver(), CMInfo.FILE_DEFAULT, 
					fe.getContentID(), cmInfo);
		}
		
		return bForward;
	}
	
	private static boolean processSTART_FILE_TRANSFER(CMFileEvent fe, CMInfo cmInfo)
	{
		CMFileTransferInfo fInfo = cmInfo.getFileTransferInfo();
		CMConfigurationInfo confInfo = cmInfo.getConfigurationInfo();
		CMInteractionInfo interInfo = cmInfo.getInteractionInfo();
		boolean bForward = true;
		String strMyName = interInfo.getMyself().getName();
		
		if(CMInfo._CM_DEBUG)
		{
			System.out.println("CMFileTransferManager.processSTART_FILE_TRANSFER(),");
			System.out.println("file sender("+fe.getFileSender()
				+"), file receiver("+fe.getFileReceiver()+"), file("+fe.getFileName()
				+"), size("+fe.getFileSize()+"), contentID("+fe.getContentID()
				+"), appendFlag("+fe.getFileAppendFlag()+").");
		}
		
		// check whether this CM node is the target node of this event or not		
		if(!fe.getFileReceiver().contentEquals(strMyName))
		{
			if(CMInfo._CM_DEBUG)
			{
				System.err.println("This node ("+strMyName+") is not the file receiver("
						+fe.getFileReceiver()+").");
			}
			return false;
		}

		// set file size
		long lFileSize = fe.getFileSize();
		
		// set a path of the received file
		String strFullPath = confInfo.getTransferedFileHome().toString();
		if(confInfo.getSystemType().equals("CLIENT"))
		{
			strFullPath = strFullPath + File.separator + fe.getFileName();
		}
		else if(confInfo.getSystemType().equals("SERVER"))
		{
			// check the sub-directory and create it if it does not exist
			strFullPath = strFullPath + File.separator + fe.getFileSender();
			File subDir = new File(strFullPath);
			if(!subDir.exists() || !subDir.isDirectory())
			{
				boolean ret = subDir.mkdirs();
				if(ret)
				{
					if(CMInfo._CM_DEBUG)
						System.out.println("A sub-directory is created.");
				}
				else
				{
					System.out.println("A sub-directory cannot be created!");
					return bForward;
				}
			}
			
			strFullPath = strFullPath + File.separator + fe.getFileName();
		}
		else
		{
			System.err.println("Wrong system type!");
			return bForward;
		}
		

		
		// check the existing file
		// open a file output stream
		File file = new File(strFullPath);
		long lRecvSize = 0;
		RandomAccessFile writeFile;
		try {
			writeFile = new RandomAccessFile(strFullPath, "rw");

			if(file.exists())
			{
				if( (fe.getFileAppendFlag() == CMInfo.FILE_APPEND) || 
						((fe.getFileAppendFlag() == CMInfo.FILE_DEFAULT) && confInfo.isFileAppendScheme()) )
				{
					// init received file size
					lRecvSize = file.length();
				
					if(CMInfo._CM_DEBUG)
						System.out.println("The file ("+strFullPath+") exists with the size("+lRecvSize+" bytes).");
				
					// move the file pointer
					try {
						writeFile.seek(lRecvSize);
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
						try {
							writeFile.close();
						} catch (IOException e1) {
							// TODO Auto-generated catch block
							e1.printStackTrace();
						}
						return bForward;
					}
				}
			}

		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return bForward;
		}
		
		
		// add the received file info in the push list
		fInfo.addRecvFileInfo(fe.getFileSender(), fe.getFileName(), lFileSize, fe.getContentID(), 
				lRecvSize, writeFile);
		
		// send ack event
		CMFileEvent feAck = new CMFileEvent();
		feAck.setID(CMFileEvent.START_FILE_TRANSFER_ACK);
		feAck.setFileSender(fe.getFileSender());
		//feAck.setReceiverName(cmInfo.getInteractionInfo().getMyself().getName());
		feAck.setFileReceiver(fe.getFileReceiver());
		feAck.setFileName(fe.getFileName());
		feAck.setContentID(fe.getContentID());
		feAck.setReceivedFileSize(lRecvSize);
		
		if(isP2PFileTransfer(feAck, cmInfo))
		{
			if(CMInfo._CM_DEBUG)
			{
				System.out.println("CMFileTransferManager.processSTART_FILE_TRANSFER(), "
						+ "isP2PFileTransfer() returns true.");
			}
			// set event sender and receiver
			feAck.setSender(strMyName);
			String strDefServer = interInfo.getDefaultServerInfo().getServerName();
			feAck.setReceiver(strDefServer);

			// set distribution fields
			feAck.setDistributionSession("CM_ONE_USER");
			feAck.setDistributionGroup(fe.getFileSender());
			
			// send the event to the default server
			CMEventManager.unicastEvent(feAck, strDefServer, cmInfo);
		}
		else
		{
			if(CMInfo._CM_DEBUG)
			{
				System.out.println("CMFileTransferManager.processSTART_FILE_TRANSFER(), "
						+ "isP2PFileTransfer() returns false.");
			}
			// set event sender and receiver
			feAck.setSender(strMyName);
			feAck.setReceiver(fe.getFileSender());
			// send the event to the file sender
			CMEventManager.unicastEvent(feAck, fe.getFileSender(), cmInfo);			
		}

		feAck = null;
		return bForward;
	}
	
	private static boolean processSTART_FILE_TRANSFER_ACK(CMFileEvent recvFileEvent, CMInfo cmInfo)
	{
		String strFileReceiver = null;
		String strFileName = null;
		String strFullFileName = null;
		long lFileSize = -1;
		int nContentID = -1;
		String strFileSender = null;
		CMFileTransferInfo fInfo = cmInfo.getFileTransferInfo();
		CMSendFileInfo sInfo = null;
		long lRecvSize = 0;
		CMInteractionInfo interInfo = cmInfo.getInteractionInfo();
		String strMyName = interInfo.getMyself().getName();
		String strDefServer = null;
		boolean bForward = true;
		
		if(CMInfo._CM_DEBUG)
		{
			System.out.println("CMFileTransferManager.processSTART_FILE_TRANSFER_ACK(), "
					+ "file sender("+recvFileEvent.getFileSender()+"), "
					+ "file receiver("+recvFileEvent.getFileReceiver()+"), "
					+ "file name("+recvFileEvent.getFileName()+"), "
					+ "content ID("+recvFileEvent.getContentID()+"), "
					+ "received sized("+recvFileEvent.getReceivedFileSize()+")");
		}
		
		// check whether this CM node is the target node of this event or not		
		if(!recvFileEvent.getFileSender().contentEquals(strMyName))
		{
			if(CMInfo._CM_DEBUG)
			{
				System.err.println("This node ("+strMyName+") is not the file sender("
						+recvFileEvent.getFileSender()+").");
			}
			return false;
		}
		
		// find the CMSendFileInfo object 
		sInfo = fInfo.findSendFileInfo(recvFileEvent.getFileReceiver(), recvFileEvent.getFileName(), 
				recvFileEvent.getContentID());
		if(sInfo == null)
		{
			System.err.println("CMFileTransferManager.processSTART_FILE_TRANSFER_ACK(), sendFileInfo not found! : "
					+"receiver("+recvFileEvent.getFileReceiver()+"), file("+recvFileEvent.getFileName()
					+"), content ID("+recvFileEvent.getContentID()+")");
			return bForward;
		}
		
		strFileReceiver = sInfo.getFileReceiver();
		strFullFileName = sInfo.getFilePath();
		strFileName = getFileNameFromPath(strFullFileName);
		lFileSize = sInfo.getFileSize();
		nContentID = sInfo.getContentID();
					
		lRecvSize = recvFileEvent.getReceivedFileSize();
		
		if(CMInfo._CM_DEBUG)
			System.out.println("CMFileTransferManager.processSTART_FILE_TRANSFER_ACK(), "
					+ "Sending file("+strFileName+") to target("+strFileReceiver+") from the file position("
					+ lRecvSize +").");

		// open the file
		RandomAccessFile readFile = null;
		try {
			readFile = new RandomAccessFile(strFullFileName, "rw");
			if(lRecvSize > 0 && lRecvSize < lFileSize)	// If the receiver uses the append scheme,
			{
				try {
					readFile.seek(lRecvSize);
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
					try {
						readFile.close();
					} catch (IOException e1) {
						// TODO Auto-generated catch block
						e1.printStackTrace();
					}
					return bForward;
				}
			}
			sInfo.setReadFile(readFile);
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return bForward;
		}
		
		// set sender name
		//strSenderName = cmInfo.getInteractionInfo().getMyself().getName();
		strFileSender = recvFileEvent.getFileSender();
		
		// send blocks
		//long lRemainBytes = lFileSize;
		long lRemainBytes = lFileSize - lRecvSize;
		int nReadBytes = 0;
		byte[] fileBlock = new byte[CMInfo.FILE_BLOCK_LEN];
		CMFileEvent fe = new CMFileEvent();
		
		while(lRemainBytes > 0 && !fInfo.isCancelSend())
		{
			try {
				nReadBytes = sInfo.getReadFile().read(fileBlock);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				continue;
			}
			
			// send file block
			fe = new CMFileEvent();
			fe.setID(CMFileEvent.CONTINUE_FILE_TRANSFER);
			fe.setFileSender(strFileSender);
			fe.setFileReceiver(strFileReceiver);
			fe.setFileName(strFileName);
			fe.setFileBlock(fileBlock);
			fe.setBlockSize(nReadBytes);
			fe.setContentID(nContentID);
			
			if(isP2PFileTransfer(fe, cmInfo))
			{
				// set event sender and receiver
				fe.setSender(interInfo.getMyself().getName());
				strDefServer = interInfo.getDefaultServerInfo().getServerName();
				fe.setReceiver(strDefServer);
				
				// set distribution fields
				fe.setDistributionSession("CM_ONE_USER");
				fe.setDistributionGroup(strFileReceiver);
				
				// send the event to the default server
				CMEventManager.unicastEvent(fe, strDefServer, cmInfo);
			}
			else
			{
				// set event sender and receiver
				fe.setSender(interInfo.getMyself().getName());
				fe.setReceiver(strFileReceiver);
				// send the event to the file receiver
				CMEventManager.unicastEvent(fe, strFileReceiver, cmInfo);				
			}
			
			lRemainBytes -= nReadBytes;
		}
		
		if(lRemainBytes < 0)
		{
			System.err.println("CMFileTransferManager.processSTART_FILE_TRANSFER(); "
					+ "the receiver("+strFileReceiver+") already has "
					+ "a bigger size file("+strFileName+"); sender size("+lFileSize
					+ "), receiver size("+lRecvSize+").");
		}
		
		// close fis
		try {
			sInfo.getReadFile().close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		// reset the flag
		if(fInfo.isCancelSend())
		{
			fInfo.setCancelSend(false);
			fileBlock = null;
			fe = null;
			return bForward;
		}

		if(CMInfo._CM_DEBUG)
			System.out.println("CMFileTransferManager.processSTART_FILE_TRANSFER_ACK(), "
					+ "Ending transfer of file("+strFileName+") to target("+strFileReceiver
					+"), size("+lFileSize+") Bytes.");

		// send the end of file transfer
		fe = new CMFileEvent();
		fe.setID(CMFileEvent.END_FILE_TRANSFER);
		fe.setSender(strFileSender); // event sender
		fe.setReceiver(strFileReceiver); // event receiver
		fe.setFileSender(strFileSender);
		fe.setFileReceiver(strFileReceiver);
		fe.setFileName(strFileName);
		fe.setFileSize(lFileSize);
		fe.setContentID(nContentID);
		
		if(isP2PFileTransfer(fe, cmInfo))
		{
			if(CMInfo._CM_DEBUG)
			{
				System.out.println("CMFileTransferManager.processSTART_FILE_TRANSFER_ACK(), "
						+ "isP2PFileTransfer() returns true.");
			}
			// set event sender and receiver
			fe.setSender(interInfo.getMyself().getName());
			strDefServer = interInfo.getDefaultServerInfo().getServerName();
			fe.setReceiver(strDefServer);
			
			// set distribution fields
			fe.setDistributionSession("CM_ONE_USER");
			fe.setDistributionGroup(strFileReceiver);
			
			// send the event to the default server
			CMEventManager.unicastEvent(fe, strDefServer, cmInfo);
		}
		else
		{
			if(CMInfo._CM_DEBUG)
			{
				System.out.println("CMFileTransferManager.processSTART_FILE_TRANSFER_ACK(), "
						+ "isP2PFileTransfer() returns false.");
			}
			
			// set event sender and receiver
			fe.setSender(interInfo.getMyself().getName());
			fe.setReceiver(strFileReceiver);
			// send the event to the file receiver
			CMEventManager.unicastEvent(fe, strFileReceiver, cmInfo);			
		}
		
		fileBlock = null;
		fe = null;
		return bForward;
	}
	
	private static boolean processCONTINUE_FILE_TRANSFER(CMFileEvent fe, CMInfo cmInfo)
	{
		CMFileTransferInfo fInfo = cmInfo.getFileTransferInfo();
		CMInteractionInfo interInfo = cmInfo.getInteractionInfo();
		String strMyName = interInfo.getMyself().getName();
		boolean bForward = true;
		
		/*
		if(CMInfo._CM_DEBUG)
		{
			System.out.println("CMFileManager.processCONTINUE_FILE_TRANSFER(), sender("
					+fe.getSenderName()+"), file("+fe.getFileName()+"), "+fe.getBlockSize()
					+" Bytes, contentID("+fe.getContentID()+").");
		}
		*/
		
		// check whether this CM node is the target node of this event or not		
		if(!fe.getFileReceiver().contentEquals(strMyName))
		{
			/*
			if(CMInfo._CM_DEBUG)
			{
				System.err.println("This node ("+strMyName+") is not the file receiver("
						+fe.getFileReceiver()+").");
			}
			*/
			return false;
		}

		// find info in the recv file list
		CMRecvFileInfo recvInfo = fInfo.findRecvFileInfo(fe.getFileSender(), fe.getFileName(), fe.getContentID());
		if( recvInfo == null )
		{
			System.err.println("CMFileTransferManager.processCONTINUE_FILE_TRANSFER(), "
					+ "recv file info for sender("+fe.getFileSender()+"), file("+fe.getFileName()
					+"), content ID("+fe.getContentID()+") not found.");
			return bForward;
		}

		try {
			recvInfo.getWriteFile().write(fe.getFileBlock(), 0, fe.getBlockSize());
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return bForward;
		}
		recvInfo.setRecvSize(recvInfo.getRecvSize()+fe.getBlockSize());

		/*
		if(CMInfo._CM_DEBUG)
			System.out.println("Cumulative written file size: "+pushInfo.m_lRecvSize+" Bytes.");
		*/
		
		return bForward;
	}
	
	private static boolean processEND_FILE_TRANSFER(CMFileEvent fe, CMInfo cmInfo)
	{
		CMFileTransferInfo fInfo = cmInfo.getFileTransferInfo();
		CMInteractionInfo interInfo = cmInfo.getInteractionInfo();
		String strMyName = interInfo.getMyself().getName();
		boolean bForward = true;
		
		if(CMInfo._CM_DEBUG)
		{
			System.out.println("CMFileTransferManager.processEND_FILE_TRANSFER(), "
					+ "file sender("+fe.getFileSender()+"), file receiver("
					+fe.getFileReceiver()+"), file("+fe.getFileName()
					+"), file size("+fe.getFileSize()+"), contentID("
					+fe.getContentID()+")");
		}		

		// check whether this CM node is the target node of this event or not		
		if(!fe.getFileReceiver().contentEquals(strMyName))
		{
			if(CMInfo._CM_DEBUG)
			{
				System.err.println("This node ("+strMyName+") is not the file receiver("
						+fe.getFileReceiver()+").");
			}
			return false;
		}

		// find info from recv file list
		CMRecvFileInfo recvInfo = fInfo.findRecvFileInfo(fe.getFileSender(), fe.getFileName(), fe.getContentID());
		if(recvInfo == null)
		{
			System.err.println("CMFileTransferManager.processEND_FILE_TRANSFER(), recv file info "
					+"for sender("+fe.getFileSender()+"), file("+fe.getFileName()+"), content ID("
					+fe.getContentID()+") not found.");

			return bForward;
		}
		// close received file descriptor
		try {
			recvInfo.getWriteFile().close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		if(CMInfo._CM_DEBUG)
		{
			System.out.println("received size("+recvInfo.getRecvSize()+").");
		}

		// remove info from push file list
		fInfo.removeRecvFileInfo(fe.getFileSender(), fe.getFileName(), fe.getContentID());
		
		// send ack
		CMFileEvent feAck = new CMFileEvent();
		feAck.setID(CMFileEvent.END_FILE_TRANSFER_ACK);
		feAck.setSender(interInfo.getMyself().getName()); // event sender
		feAck.setReceiver(fe.getSender());	// event receiver
		feAck.setFileSender(fe.getFileSender());
		feAck.setFileReceiver(fe.getFileReceiver());
		feAck.setFileName(fe.getFileName());
		feAck.setFileSize(fe.getFileSize());
		feAck.setReturnCode(1);	// success
		feAck.setContentID(fe.getContentID());
		
		if(isP2PFileTransfer(feAck, cmInfo))
		{
			if(CMInfo._CM_DEBUG)
			{
				System.out.println("CMFileTransferManager.processEND_FILE_TRANSFER(), "
						+ "isP2PFileTransfer() returns true.");
			}
			// set event sender and receiver
			feAck.setSender(strMyName);
			String strDefServer = interInfo.getDefaultServerInfo().getServerName();
			feAck.setReceiver(strDefServer);
			
			// set distribution fields
			feAck.setDistributionSession("CM_ONE_USER");
			feAck.setDistributionGroup(fe.getFileSender());
			
			// send the even to the default server
			CMEventManager.unicastEvent(feAck, strDefServer, cmInfo);
		}
		else
		{
			if(CMInfo._CM_DEBUG)
			{
				System.out.println("CMFileTransferManager.processEND_FILE_TRANSFER(), "
						+ "isP2PFileTransfer() returns false.");
			}
			// set event sender and receiver
			feAck.setSender(strMyName);
			feAck.setReceiver(fe.getFileSender());
			// send the event to the file file sender
			CMEventManager.unicastEvent(feAck, fe.getFileSender(), cmInfo);			
		}
		feAck = null;
		
		CMSNSManager.checkCompleteRecvAttachedFiles(fe, cmInfo);

		return bForward;
	}
	
	private static boolean processEND_FILE_TRANSFER_ACK(CMFileEvent fe, CMInfo cmInfo)
	{
		CMFileTransferInfo fInfo = cmInfo.getFileTransferInfo();
		String strFileReceiver = fe.getFileReceiver();
		String strFileName = fe.getFileName();
		long lFileSize = fe.getFileSize();
		int nContentID = fe.getContentID();
		long lElapsedTime = System.currentTimeMillis() - fInfo.getStartTime();
		CMInteractionInfo interInfo = cmInfo.getInteractionInfo();
		String strMyName = interInfo.getMyself().getName();
		boolean bForward = true;

		if(CMInfo._CM_DEBUG)
		{
			System.out.println("CMFileTransferManager.processEND_FILE_TRANSFER_ACK(), "
					+ "file sender("+fe.getFileSender()+"), file receiver("
					+strFileReceiver+"), file("+strFileName+"), size("+lFileSize+"), return code("+fe.getReturnCode()
					+"), contentID("+nContentID+").");
			System.out.println("file-transfer time("+lElapsedTime+" ms)");
		}
		
		// check whether this CM node is the target node of this event or not		
		if(!fe.getFileSender().contentEquals(strMyName))
		{
			if(CMInfo._CM_DEBUG)
			{
				System.err.println("This node ("+strMyName+") is not the file sender("
						+fe.getFileSender()+").");
			}
			return false;
		}

		// find completed send info
		CMSendFileInfo sInfo = fInfo.findSendFileInfo(strFileReceiver, strFileName, nContentID);
		if(sInfo == null)
		{
			System.err.println("CMFileTransferManager.processEND_FILE_TRANSFER_ACK(), send info not found");
			System.err.println("receiver("+strFileReceiver+"), file("+strFileName+"), content ID("+nContentID+").");
		}
		else
		{
			// delete corresponding request from the list
			fInfo.removeSendFileInfo(strFileReceiver, strFileName, nContentID);
		}
			
		//////////////////// check the completion of sending attached file of SNS content
		//////////////////// and check the completion of prefetching an attached file of SNS content
		CMSNSManager.checkCompleteSendAttachedFiles(fe, cmInfo);

		return bForward;
	}
	
	private static boolean processREQUEST_DIST_FILE_PROC(CMFileEvent fe, CMInfo cmInfo)
	{
		CMInteractionInfo interInfo = cmInfo.getInteractionInfo();
		String strMyName = interInfo.getMyself().getName();
		boolean bForward = true;
		
		if(CMInfo._CM_DEBUG)
		{
			System.out.println("CMFileTransferManager.processREQUEST_DIST_FILE_PROC(), "
					+ "file sender(requester)("+fe.getFileSender()
					+ "), file receiver("+fe.getFileReceiver()
					+ "), content ID("+fe.getContentID()+").");
		}
		
		// check whether this CM node is the target node of this event or not		
		if(!fe.getFileReceiver().contentEquals(strMyName))
		{
			if(CMInfo._CM_DEBUG)
			{
				System.err.println("This node ("+strMyName+") is not the file receiver("
						+fe.getFileReceiver()+").");
			}
			return false;
		}
		
		return bForward;
	}
	
	private static boolean processREQUEST_PERMIT_PULL_FILE_CHAN(CMFileEvent fe, CMInfo cmInfo)
	{
		CMConfigurationInfo confInfo = cmInfo.getConfigurationInfo();
		CMInteractionInfo interInfo = cmInfo.getInteractionInfo();
		String strMyName = interInfo.getMyself().getName();
		boolean bForward = true;
		
		if(CMInfo._CM_DEBUG)
		{
			System.out.println("CMFileTransferManager.processREQUEST_PERMIT_PULL_FILE_CHAN(), "
					+ "file sender("+fe.getFileSender()+"), file receiver(requester)("
					+fe.getFileReceiver()+"), file("+fe.getFileName()
					+"), contentID("+fe.getContentID()+"), append flag("
					+fe.getFileAppendFlag()+").");
		}
		
		// check whether this CM node is the target node of this event or not		
		if(!fe.getFileSender().contentEquals(strMyName))
		{
			if(CMInfo._CM_DEBUG)
			{
				System.err.println("This node ("+strMyName+") is not the file sender("
						+fe.getFileSender()+").");
			}
			return false;
		}

		String strFileName = fe.getFileName();
		CMFileEvent feAck = new CMFileEvent();
		feAck.setID(CMFileEvent.REPLY_PERMIT_PULL_FILE_CHAN);
		feAck.setFileSender(fe.getFileSender());
		feAck.setFileReceiver(fe.getFileReceiver());
		feAck.setFileName(strFileName);

		// get the full path of the requested file
		String strFullPath = confInfo.getTransferedFileHome().toString() + File.separator + strFileName; 
		// check the file existence
		File file = new File(strFullPath);
		if(!file.exists())
		{
			feAck.setReturnCode(0);	// file not found
			CMEventManager.unicastEvent(feAck, fe.getFileReceiver(), cmInfo);
			feAck = null;
			return bForward;
		}
		
		feAck.setReturnCode(1);	// file found
		feAck.setContentID(fe.getContentID());
		
		if(isP2PFileTransfer(feAck, cmInfo))
		{
			if(CMInfo._CM_DEBUG)
			{
				System.out.println("CMFileTransferManager.processREQUEST_PERMIT_PULL_FILE_CHAN(), "
						+ "isP2PFileTransfer() returns true.");
			}
			// set event sender and receiver
			feAck.setSender(strMyName);
			String strDefServer = interInfo.getDefaultServerInfo().getServerName();
			feAck.setReceiver(strDefServer);
			
			// set distribution fields
			feAck.setDistributionSession("CM_ONE_USER");
			feAck.setDistributionGroup(fe.getFileReceiver());
			
			// send the event to the default server
			CMEventManager.unicastEvent(feAck, strDefServer, cmInfo);			
		}
		else
		{
			if(CMInfo._CM_DEBUG)
			{
				System.out.println("CMFileTransferManager.processREQUEST_PERMIT_PULL_FILE_CHAN(), "
						+ "isP2PFileTransfer() returns false.");
			}
			// set event sender and receiver
			feAck.setSender(strMyName);
			feAck.setReceiver(fe.getFileReceiver());
			// send the event to the file receiver
			CMEventManager.unicastEvent(feAck, fe.getFileReceiver(), cmInfo);
		}
		
		pushFileWithSepChannel(strFullPath, fe.getFileReceiver(), fe.getFileAppendFlag(), fe.getContentID(), cmInfo);
		return bForward;
	}
	
	private static boolean processREPLY_PERMIT_PULL_FILE_CHAN(CMFileEvent fe, CMInfo cmInfo)
	{
		CMEventSynchronizer eventSync = cmInfo.getEventInfo().getEventSynchronizer();
		CMInteractionInfo interInfo = cmInfo.getInteractionInfo();
		String strMyName = interInfo.getMyself().getName();
		boolean bForward = true;
		
		if(CMInfo._CM_DEBUG)
		{
			System.out.println("CMFileManager.processREPLY_FILE_TRANSFER_CHAN(), "
					+ "file sender("+fe.getFileSender()+"), file receiver("
					+ fe.getFileReceiver()+"), file name("+fe.getFileName()
					+"), return code("+fe.getReturnCode()+"), contentID("
					+fe.getContentID()+").");
		}

		// check whether this CM node is the target node of this event or not		
		if(!fe.getFileReceiver().contentEquals(strMyName))
		{
			if(CMInfo._CM_DEBUG)
			{
				System.err.println("This node ("+strMyName+") is not the file receiver("
						+fe.getFileReceiver()+").");
			}
			return false;
		}

		if(fe.getReturnCode() == 0 && fe.getFileName().equals("throughput-test.jpg"))
		{
			System.err.println("The requested file does not exists!");
			synchronized(eventSync)
			{
				eventSync.setReplyEvent(fe);
				eventSync.notify();
			}
		}

		return bForward;
	}
	
	private static boolean processSTART_FILE_TRANSFER_CHAN(CMFileEvent fe, CMInfo cmInfo)
	{
		CMFileTransferInfo fInfo = cmInfo.getFileTransferInfo();
		CMConfigurationInfo confInfo = cmInfo.getConfigurationInfo();
		CMInteractionInfo interInfo = cmInfo.getInteractionInfo();
		String strMyName = interInfo.getMyself().getName();
		boolean bForward = true;
		
		if(CMInfo._CM_DEBUG)
		{
			System.out.println("CMFileTransferManager.processSTART_FILE_TRANSFER_CHAN(),");
			System.out.println("file sender("+fe.getFileSender()+"), file receiver("
					+fe.getFileReceiver()+"), file name("+fe.getFileName()+"), size("
					+fe.getFileSize()+"), contentID("+fe.getContentID()+"), appendFlag("
					+fe.getFileAppendFlag()+").");
		}

		// check whether this CM node is the target node of this event or not		
		if(!fe.getFileReceiver().contentEquals(strMyName))
		{
			if(CMInfo._CM_DEBUG)
			{
				System.err.println("This node ("+strMyName+") is not the file receiver("
						+fe.getFileReceiver()+").");
			}
			return false;
		}
		
		// set file size
		long lFileSize = fe.getFileSize();
		
		// set a path of the received file
		String strFullPath = confInfo.getTransferedFileHome().toString();
		if(confInfo.getSystemType().equals("CLIENT"))
		{
			strFullPath = strFullPath + File.separator + fe.getFileName();
		}
		else if(confInfo.getSystemType().equals("SERVER"))
		{
			// check the sub-directory and create it if it does not exist
			strFullPath = strFullPath + File.separator + fe.getFileSender();
			File subDir = new File(strFullPath);
			if(!subDir.exists() || !subDir.isDirectory())
			{
				boolean ret = subDir.mkdirs();
				if(ret)
				{
					if(CMInfo._CM_DEBUG)
						System.out.println("A sub-directory is created.");
				}
				else
				{
					System.err.println("A sub-directory cannot be created!");
					return bForward;
				}
			}
			
			strFullPath = strFullPath + File.separator + fe.getFileName();
		}
		else
		{
			System.err.println("Wrong system type!");
			return bForward;
		}		
		
		// get the default blocking TCP socket channel
		SocketChannel sc = null;
		SocketChannel dsc = null;
		if(confInfo.getSystemType().equals("CLIENT"))	// CLIENT
		{
			CMServer serverInfo = cmInfo.getInteractionInfo().getDefaultServerInfo();
			sc = (SocketChannel) serverInfo.getBlockSocketChannelInfo().findChannel(0);
			dsc = (SocketChannel) serverInfo.getNonBlockSocketChannelInfo().findChannel(0);
		}
		else	// SERVER
		{
			CMUser user = cmInfo.getInteractionInfo().getLoginUsers().findMember(fe.getFileSender());
			sc = (SocketChannel) user.getBlockSocketChannelInfo().findChannel(0);
			dsc = (SocketChannel) user.getNonBlockSocketChannelInfo().findChannel(0);
		}
		
		if(sc == null)
		{
			System.err.println("CMFileTransferManager.processSTART_FILE_TRANSFER_CHAN();"
					+ "the default blocking TCP socket channel not found!");
			return bForward;
		}
		else if(!sc.isOpen())
		{
			System.err.println("CMFileTransferManager.processSTART_FILE_TRANSFER_CHAN();"
					+ "the default blocking TCP socket channel is closed!");
			return bForward;
		}
		
		if(dsc == null)
		{
			System.err.println("CMFileTransferManager.processSTART_FILE_TRANSFER_CHAN();"
					+ "the default TCP socket channel not found!");
			return bForward;
		}
		else if(!dsc.isOpen())
		{
			System.err.println("CMFileTransferManager.processSTART_FILE_TRANSFER_CHAN();"
					+ "the default TCP socket channel is closed!");
			return bForward;
		}

		// check the existing file
		File file = new File(strFullPath);
		long lRecvSize = 0;
		if(file.exists())
		{
			if( (fe.getFileAppendFlag() == CMInfo.FILE_APPEND) || 
					((fe.getFileAppendFlag() == CMInfo.FILE_DEFAULT) && confInfo.isFileAppendScheme()) )
			{
				// init received file size
				lRecvSize = file.length();
			}
		}

		// add the received file info
		boolean bResult = false;
		CMRecvFileInfo rfInfo = new CMRecvFileInfo();
		rfInfo.setFileSender(fe.getFileSender());
		rfInfo.setFileReceiver(fe.getFileReceiver());
		rfInfo.setFileName(fe.getFileName());
		rfInfo.setFilePath(strFullPath);
		rfInfo.setFileSize(lFileSize);
		rfInfo.setContentID(fe.getContentID());
		rfInfo.setRecvSize(lRecvSize);
		//rfInfo.setWriteFile(raf);
		rfInfo.setRecvChannel(sc);
		rfInfo.setDefaultChannel(dsc);
		
		bResult = fInfo.addRecvFileInfo(rfInfo);
		if(!bResult)
		{
			System.err.println("CMFileTransferManager.processSTART_FILE_TRANSFER_CHAN(); failed to add "
					+ "the receiving file info!");
			return bForward;
		}
		
		if(!fInfo.isRecvOngoing(fe.getFileSender()))
		{
			sendSTART_FILE_TRANSFER_CHAN_ACK(rfInfo, cmInfo);
		}
				
		return bForward;
	}
	
	private static boolean processSTART_FILE_TRANSFER_CHAN_ACK(CMFileEvent fe, CMInfo cmInfo)
	{
		long lRecvSize = -1;	// received size by the receiver
		CMFileTransferInfo fInfo = cmInfo.getFileTransferInfo();
		CMThreadInfo threadInfo = cmInfo.getThreadInfo();
		CMSendFileInfo sInfo = null;
		CMInteractionInfo interInfo = cmInfo.getInteractionInfo();
		String strMyName = interInfo.getMyself().getName();
		boolean bForward = true;
		
		if(CMInfo._CM_DEBUG)
		{
			System.out.println("CMFileTransferManager.processSTART_FILE_TRANSFER_CHAN_ACK(); "
					+ "file sender("+fe.getFileSender()+"), file receiver("
					+fe.getFileReceiver()+"), file name("+fe.getFileName()
					+ "), file size("+fe.getFileSize()+"), content ID("
					+fe.getContentID()+"), received file size("+fe.getReceivedFileSize()
					+").");			
		}
		
		// check whether this CM node is the target node of this event or not		
		if(!fe.getFileSender().contentEquals(strMyName))
		{
			if(CMInfo._CM_DEBUG)
			{
				System.err.println("This node ("+strMyName+") is not the file sender("
						+fe.getFileSender()+").");
			}
			return false;
		}		
		
		// find the CMSendFileInfo object 
		sInfo = fInfo.findSendFileInfo(fe.getFileReceiver(), fe.getFileName(), fe.getContentID());
		if(sInfo == null)
		{
			System.err.println("CMFileTransferManager.processSTART_FILE_TRANSFER_CHAN_ACK(), sendFileInfo "
					+ "not found! : receiver("+fe.getFileReceiver()+"), file("+fe.getFileName()
					+"), content ID("+fe.getContentID()+")");
			return bForward;
		}
				
		lRecvSize = fe.getReceivedFileSize();
		if(lRecvSize > 0)
		{
			sInfo.setSentSize(lRecvSize);	// update the sent size
			//sInfo.setAppend(true);			// set the file append scheme
		}
					
		// start a dedicated sending thread
		Future<CMSendFileInfo> future = null;
		CMSendFileTask sendFileTask = new CMSendFileTask(sInfo, cmInfo);
		future = threadInfo.getExecutorService().submit(sendFileTask, sInfo);
		sInfo.setSendTaskResult(future);		

		return bForward;		
	}
	
	private static boolean processEND_FILE_TRANSFER_CHAN(CMFileEvent fe, CMInfo cmInfo)
	{
		CMFileTransferInfo fInfo = cmInfo.getFileTransferInfo();
		CMInteractionInfo interInfo = cmInfo.getInteractionInfo();
		boolean bResult = false;
		String strMyName = interInfo.getMyself().getName();
		boolean bForward = true;

		if(CMInfo._CM_DEBUG)
		{
			System.out.println("CMFileTransferManager.processEND_FILE_TRANSFER_CHAN(), "
					+ "file sender("+fe.getFileSender()+"), file receiver("
					+fe.getFileReceiver()+"), file("+fe.getFileName()
					+"), file size("+fe.getFileSize()+"), contentID("
					+fe.getContentID()+")");
		}

		// check whether this CM node is the target node of this event or not		
		if(!fe.getFileReceiver().contentEquals(strMyName))
		{
			if(CMInfo._CM_DEBUG)
			{
				System.err.println("This node ("+strMyName+") is not the file receiver("
						+fe.getFileReceiver()+").");
			}
			return false;
		}

		// find info from recv file list
		CMRecvFileInfo recvInfo = fInfo.findRecvFileInfo(fe.getFileSender(), fe.getFileName(), fe.getContentID());
		if(recvInfo == null)
		{
			System.err.println("CMFileTransferManager.processEND_FILE_TRANSFER_CHAN(), recv file info "
					+"for sender("+fe.getFileSender()+"), file("+fe.getFileName()+"), content ID("
					+fe.getContentID()+") not found.");

			return bForward;
		}

		// wait the receiving thread
		if(!recvInfo.getRecvTaskResult().isDone())
		{
			try {
				recvInfo.getRecvTaskResult().get();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (ExecutionException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		
		// make ack event
		CMFileEvent feAck = new CMFileEvent();
		feAck.setID(CMFileEvent.END_FILE_TRANSFER_CHAN_ACK);
		feAck.setFileSender(fe.getFileSender());
		feAck.setFileReceiver(fe.getFileReceiver());
		feAck.setFileName(fe.getFileName());
		feAck.setFileSize(fe.getFileSize());
		feAck.setContentID(fe.getContentID());

		// check out whether the file is completely received
		if(recvInfo.getFileSize() <= recvInfo.getRecvSize())
		{
			feAck.setReturnCode(1);	// success
			bResult = true;
		}
		else
		{
			System.err.println("CMFileTransferManager.processEND_FILE_TRANSFER_CHAN(); incompletely received!");
			feAck.setReturnCode(0); // failure
			bResult = false;
		}
		
		// remove info from push file list
		fInfo.removeRecvFileInfo(fe.getFileSender(), fe.getFileName(), fe.getContentID());
		
		// send ack
		if(isP2PFileTransfer(feAck, cmInfo))
		{
			if(CMInfo._CM_DEBUG)
			{
				System.out.println("CMFileTransferManager.processEND_FILE_TRANSFER_CHAN(), "
						+ "isP2PFileTransfer() returns true.");
			}
			// set event sender and receiver
			feAck.setSender(strMyName);
			String strDefServer = interInfo.getDefaultServerInfo().getServerName();
			feAck.setReceiver(strDefServer);
			
			// set distribution fields
			feAck.setDistributionSession("CM_ONE_USER");
			feAck.setDistributionGroup(fe.getFileSender());
			
			// send the event to the default server
			CMEventManager.unicastEvent(feAck, strDefServer, cmInfo);
		}
		else
		{
			if(CMInfo._CM_DEBUG)
			{
				System.out.println("CMFileTransferManager.processEND_FILE_TRANSFER_CHAN(), "
						+ "isP2PFileTransfer() returns false.");
			}
			// set event sender and receiver
			feAck.setSender(strMyName);
			feAck.setReceiver(fe.getFileSender());
			// send the event to the file sender
			CMEventManager.unicastEvent(feAck, fe.getFileSender(), cmInfo);
		}
		feAck = null;

		if(bResult)
			CMSNSManager.checkCompleteRecvAttachedFiles(fe, cmInfo);

		// check whether there is a remaining receiving file info or not
		CMRecvFileInfo nextRecvInfo = fInfo.findRecvFileInfoNotStarted(fe.getFileSender());
		if(nextRecvInfo != null)
		{
			sendSTART_FILE_TRANSFER_CHAN_ACK(nextRecvInfo, cmInfo);
		}
		
		return bForward;
	}
	
	private static boolean processEND_FILE_TRANSFER_CHAN_ACK(CMFileEvent fe, CMInfo cmInfo)
	{
		CMFileTransferInfo fInfo = cmInfo.getFileTransferInfo();
		CMInteractionInfo interInfo = cmInfo.getInteractionInfo();
		String strMyName = interInfo.getMyself().getName();
		boolean bForward = true;
		String strFileReceiver = fe.getFileReceiver();
		String strFileName = fe.getFileName();
		long lFileSize = fe.getFileSize();
		int nContentID = fe.getContentID();
		long lElapsedTime = System.currentTimeMillis() - fInfo.getStartTime();
		
		if(CMInfo._CM_DEBUG)
		{
			System.out.println("CMFileTransferManager.processEND_FILE_TRANSFER_CHAN_ACK(), "
					+ "file sender("+fe.getFileSender()+"), file receiver("
					+strFileReceiver+"), file("+strFileName+"), size("
					+lFileSize+"), return code("+fe.getReturnCode()
					+"), contentID("+nContentID+").");
			System.out.println("file-transfer time("+lElapsedTime+" ms)");
		}
		
		// check whether this CM node is the target node of this event or not		
		if(!fe.getFileSender().contentEquals(strMyName))
		{
			if(CMInfo._CM_DEBUG)
			{
				System.err.println("This node ("+strMyName+") is not the file sender("
						+fe.getFileSender()+").");
			}
			return false;
		}

		// find completed send info
		CMSendFileInfo sInfo = fInfo.findSendFileInfo(strFileReceiver, strFileName, nContentID);
		if(sInfo == null)
		{
			System.err.println("CMFileTransferManager.processEND_FILE_TRANSFER_CHAN_ACK(), send info not found");
			System.err.println("receiver("+strFileReceiver+"), file("+strFileName+"), content ID("+nContentID+").");
		}
		else
		{
			// delete corresponding request from the list
			fInfo.removeSendFileInfo(strFileReceiver, strFileName, nContentID);
		}
	
		//////////////////// check the completion of sending attached file of SNS content
		//////////////////// and check the completion of prefetching an attached file of SNS content
		CMSNSManager.checkCompleteSendAttachedFiles(fe, cmInfo);

		return bForward;	
	}
	
	private static void sendSTART_FILE_TRANSFER_CHAN_ACK(CMRecvFileInfo rfInfo, CMInfo cmInfo)
	{
		CMThreadInfo threadInfo = cmInfo.getThreadInfo();
		CMInteractionInfo interInfo = cmInfo.getInteractionInfo();

		// start a dedicated thread to receive the file
		Future<CMRecvFileInfo> future = null;
		CMRecvFileTask recvFileTask = new CMRecvFileTask(rfInfo, cmInfo);
		future = threadInfo.getExecutorService().submit(recvFileTask, rfInfo);
		rfInfo.setRecvTaskResult(future);
		
		// send ack event
		CMFileEvent feAck = new CMFileEvent();
		feAck.setID(CMFileEvent.START_FILE_TRANSFER_CHAN_ACK);
		feAck.setFileSender(rfInfo.getFileSender());
		feAck.setFileReceiver(cmInfo.getInteractionInfo().getMyself().getName());
		feAck.setFileName(rfInfo.getFileName());
		feAck.setContentID(rfInfo.getContentID());
		feAck.setReceivedFileSize(rfInfo.getRecvSize());
		
		if(isP2PFileTransfer(feAck, cmInfo))
		{
			if(CMInfo._CM_DEBUG)
			{
				System.out.println("CMFileTransferManager.sendSTART_FILE_TRANSFER_CHAN_ACK(), "
						+ "isP2PFileTransfer() returns true.");
			}
			// set event sender and receiver
			feAck.setSender(interInfo.getMyself().getName());
			String strDefServer = interInfo.getDefaultServerInfo().getServerName();
			feAck.setReceiver(strDefServer);
			
			// set distribution fields
			feAck.setDistributionSession("CM_ONE_USER");
			feAck.setDistributionGroup(rfInfo.getFileSender());
			
			// send the event to the default server
			CMEventManager.unicastEvent(feAck, strDefServer, cmInfo);
		}
		else
		{
			if(CMInfo._CM_DEBUG)
			{
				System.out.println("CMFileTransferManager.sendSTART_FILE_TRANSFER_CHAN_ACK(), "
						+ "isP2PFileTransfer() returns false.");
			}
			// set event sender and receiver
			feAck.setSender(interInfo.getMyself().getName());
			feAck.setReceiver(rfInfo.getFileSender());
			// send the event to the file sender
			CMEventManager.unicastEvent(feAck, rfInfo.getFileSender(), cmInfo);			
		}

		feAck = null;
	}
	
	private static boolean processCANCEL_FILE_SEND(CMFileEvent fe, CMInfo cmInfo)
	{
		CMFileTransferInfo fInfo = cmInfo.getFileTransferInfo();
		CMInteractionInfo interInfo = cmInfo.getInteractionInfo();
		boolean bForward = true;
		String strFileSender = fe.getFileSender();
		CMList<CMRecvFileInfo> recvList = fInfo.getRecvFileList(strFileSender);
		Iterator<CMRecvFileInfo> iter = null;
		CMRecvFileInfo rInfo = null;
		CMFileEvent feAck = new CMFileEvent();
		boolean bReturn = false;
		boolean bP2PFileTransfer = false;
		
		String strMyName = interInfo.getMyself().getName();
		String strDefServer = null;
		
		if(CMInfo._CM_DEBUG)
		{
			System.out.println("CMFileTransferManager.processCANCEL_FILE_SEND(), "
					+"file sender("+fe.getFileSender()+"), file receiver("
					+fe.getFileReceiver()+").");
		}

		// check whether this CM node is the target node of this event or not		
		if(!fe.getFileReceiver().contentEquals(strMyName))
		{
			if(CMInfo._CM_DEBUG)
			{
				System.err.println("This node ("+strMyName+") is not the file receiver("
						+fe.getFileReceiver()+").");
			}
			return false;
		}
		
		// make the ack event
		feAck.setID(CMFileEvent.CANCEL_FILE_SEND_ACK);
		feAck.setFileSender(strFileSender);
		feAck.setFileReceiver(fe.getFileReceiver());
		
		bP2PFileTransfer = isP2PFileTransfer(feAck, cmInfo);
		
		// recv file info list not found
		if(recvList == null)
		{
			System.err.println("CMFileTransferManager.processCANCEL_FILE_SEND(); recv info list not found "
					+ "for sender("+strFileSender+")!");
			feAck.setReturnCode(0);
			
			if(bP2PFileTransfer)
			{
				if(CMInfo._CM_DEBUG)
				{
					System.out.println("CMFileTransferManager.processCANCEL_FILE_SEND(), "
							+ "isP2PFileTransfer() returns true.");
				}
				// set event sender and receiver
				feAck.setSender(strMyName);
				strDefServer = interInfo.getDefaultServerInfo().getServerName();
				feAck.setReceiver(strDefServer);
				
				// set distribution fields
				feAck.setDistributionSession("CM_ONE_USER");
				feAck.setDistributionGroup(strFileSender);
				
				// send the event to the default server
				CMEventManager.unicastEvent(feAck, strDefServer, cmInfo);
			}
			else
			{
				if(CMInfo._CM_DEBUG)
				{
					System.out.println("CMFileTransferManager.processCANCEL_FILE_SEND(), "
							+ "isP2PFileTransfer() returns false.");
				}
				// set event sender and receiver
				feAck.setSender(strMyName);
				feAck.setReceiver(strFileSender);
				// send the event to the file sender
				CMEventManager.unicastEvent(feAck, strFileSender, cmInfo);				
			}
			return bForward;
		}
		
		// close RandomAccessFile and remove the recv file info list
		iter = recvList.getList().iterator();
		while(iter.hasNext())
		{
			rInfo = iter.next();
			if(rInfo.getWriteFile() != null)
			{
				if(CMInfo._CM_DEBUG)
				{
					System.out.println("CMFileTransferManager.processCANCEL_FILE_SEND(); cancelled file("
							+rInfo.getFileName()+"), file size("+rInfo.getFileSize()+"), recv size("
							+rInfo.getRecvSize()+").");
				}
				
				try {
					rInfo.getWriteFile().close();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
		bReturn = fInfo.removeRecvFileList(strFileSender);
		
		if(bReturn)
			feAck.setReturnCode(1);
		else
			feAck.setReturnCode(0);
		
		if(bP2PFileTransfer)
		{
			if(CMInfo._CM_DEBUG)
			{
				System.out.println("CMFileTransferManager.processCANCEL_FILE_SEND(), "
						+ "isP2PFileTransfer() returns true.");
			}
			// set event sender and receiver
			feAck.setSender(strMyName);
			strDefServer = interInfo.getDefaultServerInfo().getServerName();
			feAck.setReceiver(strDefServer);

			// set distribution fields
			feAck.setDistributionSession("CM_ONE_USER");
			feAck.setDistributionGroup(strFileSender);
			
			// send the event to the default server
			bReturn = CMEventManager.unicastEvent(feAck, strDefServer, cmInfo);
		}
		else
		{
			if(CMInfo._CM_DEBUG)
			{
				System.out.println("CMFileTransferManager.processCANCEL_FILE_SEND(), "
						+ "isP2PFileTransfer() returns false.");
			}
			// set event sender and receiver
			feAck.setSender(strMyName);
			feAck.setReceiver(strFileSender);
			// send the event to the file sender
			bReturn = CMEventManager.unicastEvent(feAck, strFileSender, cmInfo);			
		}
		
		if(bReturn)
		{
			System.out.println("CMFileTransferManager.processCANCEL_FILE_SEND(); succeeded. sender("
					+fe.getFileSender()+"), receiver("+fe.getFileReceiver()+").");
		}
		else
		{
			System.err.println("CMFileTransferManager.processCANCEL_FILE_SEND(); failed! sender("
					+fe.getFileSender()+"), receiver("+fe.getFileReceiver()+").");
		}
		
		return bForward;
	}
	
	private static boolean processCANCEL_FILE_SEND_ACK(CMFileEvent fe, CMInfo cmInfo)
	{
		CMInteractionInfo interInfo = cmInfo.getInteractionInfo();
		String strMyName = interInfo.getMyself().getName();
		boolean bForward = true;
		
		if(CMInfo._CM_DEBUG)
		{
			System.out.println("CMFileTransferManager.processCANCEL_FILE_SEND_ACK(), "
					+ "file sender("+fe.getFileSender()+"), file receiver("
					+fe.getFileReceiver()+"), return code("+fe.getReturnCode()+").");
		}
		
		// check whether this CM node is the target node of this event or not		
		if(!fe.getFileSender().contentEquals(strMyName))
		{
			if(CMInfo._CM_DEBUG)
			{
				System.err.println("This node ("+strMyName+") is not the file sender("
						+fe.getFileSender()+").");
			}
			return false;
		}
		
		return bForward;
	}
	
	private static boolean processCANCEL_FILE_SEND_CHAN(CMFileEvent fe, CMInfo cmInfo)
	{
		CMFileTransferInfo fInfo = cmInfo.getFileTransferInfo();
		CMList<CMRecvFileInfo> recvList = null;
		CMRecvFileInfo rInfo = null;
		Future<CMRecvFileInfo> recvTask = null;
		CMFileEvent feAck = null;
		CMInteractionInfo interInfo = cmInfo.getInteractionInfo();
		CMConfigurationInfo confInfo = cmInfo.getConfigurationInfo();
		CMChannelInfo<Integer> blockSCInfo = null;
		SocketChannel defaultBlockSC = null;
		boolean bReturn = false;
		boolean bP2PFileTransfer = false;
		
		String strFileSender = fe.getFileSender();
		boolean bException = false;
		int nReturnCode = -1;
		String strMyName = interInfo.getMyself().getName();
		boolean bForward = true;

		if(CMInfo._CM_DEBUG)
		{
			System.out.println("CMFileTransferManager.processCANCEL_FILE_SEND_CHAN(), "
					+"file sender("+fe.getFileSender()+"), file receiver("
					+fe.getFileReceiver()+").");
		}

		// check whether this CM node is the target node of this event or not		
		if(!fe.getFileReceiver().contentEquals(strMyName))
		{
			if(CMInfo._CM_DEBUG)
			{
				System.err.println("This node ("+strMyName+") is not the file receiver("
						+fe.getFileReceiver()+").");
			}
			return false;
		}

		// find the CMRecvFile list of the strSender
		recvList = fInfo.getRecvFileList(strFileSender);
		if(recvList == null)
		{
			System.err.println("CMFileTransferManager.processCANCEL_FILE_SEND_CHAN(); Receiving file list "
					+ "not found for the sender("+strFileSender+")!");
			return bForward;
		}
		
		// find the current receiving file task
		rInfo = fInfo.findRecvFileInfoOngoing(strFileSender);
		if(rInfo == null)
		{
			System.err.println("CMFileTransferManager.processCANCEL_FILE_SEND_CHAN(); ongoing receiving task "
					+ "not found for the sender("+strFileSender+")!");
			fInfo.removeRecvFileList(strFileSender);
			return bForward;
		}
		
		// request for canceling the receiving task
		recvTask = rInfo.getRecvTaskResult();
		recvTask.cancel(true);
		// wait for the thread cancellation
		try {
			recvTask.get(10L, TimeUnit.SECONDS);
		} catch(CancellationException e) {
			System.out.println("CMFileTransferManager.processCANCEL_FILE_SEND_CHAN(); the receiving task cancelled.: "
					+ "sender("+strFileSender+"), file("+rInfo.getFileName()+"), file size("+rInfo.getFileSize()
					+ "), recv size("+rInfo.getRecvSize()+")");
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			bException = true;
		} catch (ExecutionException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			bException = true;
		} catch (TimeoutException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			bException = true;
		} finally {
			if(bException)
				nReturnCode = 0;
			else
				nReturnCode = 1;
		}

		// remove the receiving file list of the sender
		fInfo.removeRecvFileList(strFileSender);

		// send the cancel ack event to the sender
		feAck = new CMFileEvent();
		feAck.setID(CMFileEvent.CANCEL_FILE_SEND_CHAN_ACK);
		feAck.setFileSender(strFileSender);
		feAck.setFileReceiver(fe.getFileReceiver());
		feAck.setReturnCode(nReturnCode);
		
		bP2PFileTransfer = isP2PFileTransfer(feAck, cmInfo);
		
		if(bP2PFileTransfer)
		{
			if(CMInfo._CM_DEBUG)
			{
				System.out.println("CMFileTransferManager.processCANCEL_FILE_SEND_CHAN(), "
						+ "isP2PFileTransfer() returns true.");
			}
			// set event sender and receiver
			feAck.setSender(interInfo.getMyself().getName());
			String strDefServer = interInfo.getDefaultServerInfo().getServerName();
			feAck.setReceiver(strDefServer);
			
			// set distribution fields
			feAck.setDistributionSession("CM_ONE_USER");
			feAck.setDistributionGroup(strFileSender);
			
			// send the event to the default server
			CMEventManager.unicastEvent(feAck, strDefServer, cmInfo);
		}
		else
		{
			if(CMInfo._CM_DEBUG)
			{
				System.out.println("CMFileTransferManager.processCANCEL_FILE_SEND_CHAN(), "
						+ "isP2PFileTransfer() returns false.");
			}
			// set event sender and receiver
			feAck.setSender(interInfo.getMyself().getName());
			feAck.setReceiver(strFileSender);
			// send the event to the file sender
			CMEventManager.unicastEvent(feAck, strFileSender, cmInfo);			
		}

		//////////////////// the management of the closed default blocking socket channel
		// get the default blocking socket channel
		if(confInfo.getSystemType().equals("CLIENT"))
		{
			if(bP2PFileTransfer)
			{
				// get the default blocking socket channel of the file sender
				// not yet
			}
			else
			{
				blockSCInfo = interInfo.getDefaultServerInfo().getBlockSocketChannelInfo();
				if(CMInfo._CM_DEBUG)
				{
					System.out.println("CMFileTransferManager.processCANCEL_FILE_SEND_CHAN(); # blocking socket channel: "
							+ blockSCInfo.getSize());
				}
				// get the default blocking socket channel
				defaultBlockSC = (SocketChannel) blockSCInfo.findChannel(0);	// default blocking channel
			}
				
		}
		else	// server
		{
			CMUser sender = interInfo.getLoginUsers().findMember(strFileSender);
			blockSCInfo = sender.getBlockSocketChannelInfo();
			// get the default blocking socket channel
			defaultBlockSC = (SocketChannel) sender.getBlockSocketChannelInfo().findChannel(0);

			if(CMInfo._CM_DEBUG)
			{
				System.out.println("CMFileTransferManager.processCANCEL_FILE_SEND_CHAN(); # blocking socket channel: "
						+ blockSCInfo.getSize());
			}

		}

		// close the default blocking socket channel if it is open
		// the channel is actually closed due to the interrupt exception of the receiving thread
		if(defaultBlockSC.isOpen())
		{
			if(CMInfo._CM_DEBUG)
			{
				System.out.println("CMFileTransferManager.processCANCEL_FILE_SEND_CHAN(); the default channel is "
						+ "still open and should be closed for reconnection!");
			}
			
			try {
				defaultBlockSC.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		else
		{
			System.err.println("CMFileTransferManager.processCANCEL_FILE_SEND_CHAN(); the default channel is "
					+ "already closed!");
		}
		
		// remove the default blocking socket channel
		blockSCInfo.removeChannel(0);

		// if the system type is client, it recreates the default blocking socket channel to the default server
		if(confInfo.getSystemType().equals("CLIENT") && !bP2PFileTransfer)
		{
			CMServer serverInfo = interInfo.getDefaultServerInfo();
			try {
				defaultBlockSC = (SocketChannel) CMCommManager.openBlockChannel(CMInfo.CM_SOCKET_CHANNEL, 
						serverInfo.getServerAddress(), serverInfo.getServerPort(), cmInfo);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				return bForward;
			}
			
			if(defaultBlockSC == null)
			{
				System.err.println("CMFileTransferManager.processCANCEL_FILE_SEND_CHAN(), recreation of "
						+ "the blocking socket channel failed!: server("+serverInfo.getServerAddress()+"), port("
						+ serverInfo.getServerPort() +")");
				return bForward;
			}
			bReturn = blockSCInfo.addChannel(0, defaultBlockSC);

			if(bReturn)
			{
				CMSessionEvent se = new CMSessionEvent();
				se.setID(CMSessionEvent.ADD_BLOCK_SOCKET_CHANNEL);
				se.setChannelName(interInfo.getMyself().getName());
				se.setChannelNum(0);
				bReturn = CMEventManager.unicastEvent(se, serverInfo.getServerName(), CMInfo.CM_STREAM, 0, true, cmInfo);
				se = null;

				if(bReturn)
				{
					if(CMInfo._CM_DEBUG)
					{
						System.out.println("CMFileTransferManager.processCANCEL_FILE_SEND_CHAN(),successfully requested "
								+ "to add the blocking socket channel with the key(0) to the server("
								+serverInfo.getServerName()+")");
					}
					
				}
			}
		}
		
		/////////////////////
		
		return bForward;
	}
	
	private static boolean processCANCEL_FILE_SEND_CHAN_ACK(CMFileEvent fe, CMInfo cmInfo)
	{
		CMInteractionInfo interInfo = cmInfo.getInteractionInfo();
		String strMyName = interInfo.getMyself().getName();
		boolean bForward = true;
		
		if(CMInfo._CM_DEBUG)
		{
			System.out.println("CMFileTransferManager.processCANCEL_FILE_SEND_CHAN_ACK(), "
					+ "file sender("+fe.getFileSender()+"), receiver("
					+fe.getFileReceiver()+"), return code("+fe.getReturnCode()+").");
		}
		
		// check whether this CM node is the target node of this event or not		
		if(!fe.getFileSender().contentEquals(strMyName))
		{
			if(CMInfo._CM_DEBUG)
			{
				System.err.println("This node ("+strMyName+") is not the file sender("
						+fe.getFileSender()+").");
			}
			return false;
		}

		return bForward;
	}
	
	private static boolean processCANCEL_FILE_RECV_CHAN(CMFileEvent fe, CMInfo cmInfo)
	{
		CMFileTransferInfo fInfo = cmInfo.getFileTransferInfo();
		CMList<CMSendFileInfo> sendList = null;
		CMSendFileInfo sInfo = null;
		Future<CMSendFileInfo> sendTask = null;
		CMFileEvent feAck = null;
		CMInteractionInfo interInfo = cmInfo.getInteractionInfo();
		CMConfigurationInfo confInfo = cmInfo.getConfigurationInfo();
		CMChannelInfo<Integer> blockSCInfo = null;
		SocketChannel defaultBlockSC = null;
		boolean bReturn = false;
		
		String strFileReceiver = fe.getFileReceiver();
		boolean bException = false;
		int nReturnCode = -1;
		boolean bP2PFileTransfer = false;
		String strMyName = interInfo.getMyself().getName();
		boolean bForward = true;
		
		if(CMInfo._CM_DEBUG)
		{
			System.out.println("CMFileTransferManager.processCANCEL_FILE_RECV_CHAN(), "
					+"file sender("+fe.getFileSender()+"), file receiver("
					+fe.getFileReceiver()+").");
		}
		
		// check whether this CM node is the target node of this event or not		
		if(!fe.getFileSender().contentEquals(strMyName))
		{
			if(CMInfo._CM_DEBUG)
			{
				System.err.println("This node ("+strMyName+") is not the file sender("
						+fe.getFileSender()+").");
			}
			return false;
		}
		
		// find the CMSendFile list of the strReceiver
		sendList = fInfo.getSendFileList(strFileReceiver);
		if(sendList == null)
		{
			System.err.println("CMFileTransferManager.processCANCEL_FILE_RECV_CHAN(); sending file list "
					+ "not found for the receiver("+strFileReceiver+")!");
			return bForward;
		}
		
		// find the current sending file task
		sInfo = fInfo.findSendFileInfoOngoing(strFileReceiver);
		if(sInfo == null)
		{
			System.err.println("CMFileTransferManager.processCANCEL_FILE_RECV_CHAN(); ongoing sending task "
					+ "not found for the receiver("+strFileReceiver+")!");
			fInfo.removeSendFileList(strFileReceiver);
			return bForward;
		}
		
		// request for canceling the sending task
		sendTask = sInfo.getSendTaskResult();
		sendTask.cancel(true);
		// wait for the thread cancellation
		try {
			sendTask.get(10L, TimeUnit.SECONDS);
		} catch(CancellationException e) {
			System.out.println("CMFileTransferManager.processCANCEL_FILE_RECV_CHAN(); the sending task cancelled.: "
					+ "receiver("+strFileReceiver+"), file("+sInfo.getFileName()+"), file size("+sInfo.getFileSize()
					+ "), sent size("+sInfo.getSentSize()+")");
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			bException = true;
		} catch (ExecutionException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			bException = true;
		} catch (TimeoutException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			bException = true;
		} finally {
			if(bException)
				nReturnCode = 0;
			else
				nReturnCode = 1;
		}

		// remove the sending file list of the receiver
		fInfo.removeSendFileList(strFileReceiver);			

		// send the cancel ack event to the receiver
		feAck = new CMFileEvent();
		feAck.setID(CMFileEvent.CANCEL_FILE_RECV_CHAN_ACK);
		feAck.setFileSender(fe.getFileSender());
		feAck.setFileReceiver(strFileReceiver);
		feAck.setReturnCode(nReturnCode);
		
		bP2PFileTransfer = isP2PFileTransfer(feAck, cmInfo);
		
		if(bP2PFileTransfer)
		{
			if(CMInfo._CM_DEBUG)
			{
				System.out.println("CMFileTransferManager.processCANCEL_FILE_RECV_CHAN(), "
						+ "isP2PFileTransfer() returns true.");
			}
			// set the event sender and receiver
			feAck.setSender(interInfo.getMyself().getName());
			String strDefServer = interInfo.getDefaultServerInfo().getServerName();
			feAck.setReceiver(strDefServer);
			
			// set distribution fields
			feAck.setDistributionSession("CM_ONE_USER");
			feAck.setDistributionGroup(strFileReceiver);
			
			// send the event to the default server
			CMEventManager.unicastEvent(feAck, strDefServer, cmInfo);
		}
		else
		{
			if(CMInfo._CM_DEBUG)
			{
				System.out.println("CMFileTransferManager.processCANCEL_FILE_RECV_CHAN(), "
						+ "isP2PFileTransfer() returns false.");
			}
			// set the event sender and receiver
			feAck.setSender(interInfo.getMyself().getName());
			feAck.setReceiver(strFileReceiver);
			// send the event to the file receiver
			CMEventManager.unicastEvent(feAck, strFileReceiver, cmInfo);			
		}

		//////////////////// the management of the closed default blocking socket channel
		// get the default blocking socket channel
		if(confInfo.getSystemType().equals("CLIENT"))
		{
			if(bP2PFileTransfer)
			{
				// get the default blocking socket channel to the file receiver
				// not yet
			}
			else
			{
				blockSCInfo = interInfo.getDefaultServerInfo().getBlockSocketChannelInfo();
				if(CMInfo._CM_DEBUG)
				{
					System.out.println("CMFileTransferManager.processCANCEL_FILE_RECV_CHAN(); # blocking socket channel: "
							+ blockSCInfo.getSize());
				}
				// get the default blocking socket channel
				defaultBlockSC = (SocketChannel) blockSCInfo.findChannel(0);	// default blocking channel
			}				
		}
		else	// server
		{
			CMUser sender = interInfo.getLoginUsers().findMember(strFileReceiver);
			blockSCInfo = sender.getBlockSocketChannelInfo();
			// get the default blocking socket channel
			defaultBlockSC = (SocketChannel) sender.getBlockSocketChannelInfo().findChannel(0);

			if(CMInfo._CM_DEBUG)
			{
				System.out.println("CMFileTransferManager.processCANCEL_FILE_RECV_CHAN(); # blocking socket channel: "
						+ blockSCInfo.getSize());
			}

		}

		// close the default blocking socket channel if it is open
		// the channel is actually closed due to the interrupt exception of the receiving thread
		if(defaultBlockSC.isOpen())
		{
			if(CMInfo._CM_DEBUG)
			{
				System.out.println("CMFileTransferManager.processCANCEL_FILE_RECV_CHAN(); the default channel is "
						+ "still open and should be closed for reconnection!");
			}
			
			try {
				defaultBlockSC.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		else
		{
			System.err.println("CMFileTransferManager.processCANCEL_FILE_RECV_CHAN(); the default channel is "
					+ "already closed!");
		}
		
		// remove the default blocking socket channel
		blockSCInfo.removeChannel(0);

		// if the system type is client, it recreates the default blocking socket channel to the default server
		if(confInfo.getSystemType().equals("CLIENT") && !bP2PFileTransfer)
		{
			CMServer serverInfo = interInfo.getDefaultServerInfo();
			try {
				defaultBlockSC = (SocketChannel) CMCommManager.openBlockChannel(CMInfo.CM_SOCKET_CHANNEL, 
						serverInfo.getServerAddress(), serverInfo.getServerPort(), cmInfo);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				return bForward;
			}
			
			if(defaultBlockSC == null)
			{
				System.err.println("CMFileTransferManager.processCANCEL_FILE_RECV_CHAN(), recreation of "
						+ "the blocking socket channel failed!: server("+serverInfo.getServerAddress()+"), port("
						+ serverInfo.getServerPort() +")");
				return bForward;
			}
			bReturn = blockSCInfo.addChannel(0, defaultBlockSC);

			if(bReturn)
			{
				CMSessionEvent se = new CMSessionEvent();
				se.setID(CMSessionEvent.ADD_BLOCK_SOCKET_CHANNEL);
				se.setChannelName(interInfo.getMyself().getName());
				se.setChannelNum(0);
				bReturn = CMEventManager.unicastEvent(se, serverInfo.getServerName(), CMInfo.CM_STREAM, 0, true, cmInfo);
				se = null;

				if(bReturn)
				{
					if(CMInfo._CM_DEBUG)
					{
						System.out.println("CMFileTransferManager.processCANCEL_FILE_RECV_CHAN(),successfully requested "
								+ "to add the blocking socket channel with the key(0) to the server("
								+serverInfo.getServerName()+")");
					}
					
				}
			}
		}
		
		/////////////////////
		
		return bForward;	
	}
	
	private static boolean processCANCEL_FILE_RECV_CHAN_ACK(CMFileEvent fe, CMInfo cmInfo)
	{
		CMInteractionInfo interInfo = cmInfo.getInteractionInfo();
		String strMyName = interInfo.getMyself().getName();
		boolean bForward = true;
		
		if(CMInfo._CM_DEBUG)
		{
			System.out.println("CMFileTransferManager.processCANCEL_FILE_RECV_CHAN_ACK(), "
					+ "file sender("+fe.getFileSender()+"), file receiver("
					+fe.getFileReceiver()+"), return code("+fe.getReturnCode()+").");
		}
		
		// check whether this CM node is the target node of this event or not		
		if(!fe.getFileReceiver().contentEquals(strMyName))
		{
			if(CMInfo._CM_DEBUG)
			{
				System.err.println("This node ("+strMyName+") is not the file receiver("
						+fe.getFileReceiver()+").");
			}
			return false;
		}

		return bForward;
	}
	
	private static void processERR_RECV_FILE_CHAN(CMFileEvent fe, CMInfo cmInfo)
	{
		cancelPullFile(fe.getFileSender(), cmInfo);
	}
	
	private static void processERR_SEND_FILE_CHAN(CMFileEvent fe, CMInfo cmInfo)
	{
		cancelPushFile(fe.getFileReceiver(), cmInfo);
	}
}
