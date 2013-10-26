package iie.mm.server;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

public class Handler implements Runnable{
	private ServerConf conf;
	private ConcurrentHashMap<String,BlockingQueue<WriteTask>> sq;
	private Socket s;
	
	private StorePhoto sp;
	
	private DataInputStream dis;
	private DataOutputStream dos;					//向客户端的输出流
	
	public Handler(ServerConf conf, Socket s, ConcurrentHashMap<String,BlockingQueue<WriteTask>> sq) throws IOException {
		this.conf = conf;
		this.s = s;
		s.setTcpNoDelay(true);
		this.sq = sq;
		dis = new DataInputStream(this.s.getInputStream());
		dos = new DataOutputStream(this.s.getOutputStream());
		sp = new StorePhoto(conf);
	}
	
	@Override
	public void run() {
		try {
			while(true) {
				byte[] header = new byte[4];
				
				if((dis.read(header)) == -1) {
					break;
				} else if (header[0] == ActionType.STORE) {
					int setlen = header[1];
					int md5len = header[2];
					int contentlen = dis.readInt();
					
					//一次把所有的都读出来,减少读取次数
					byte[] setmd5content = readBytes(setlen + md5len + contentlen, dis);
					String set = new String(setmd5content, 0, setlen);
					String md5 = new String(setmd5content, setlen, md5len);

					String result = sp.storePhoto(set, md5, setmd5content, setlen + md5len, contentlen);
					// 统计写入的字节数
					ServerProfile.addWrite(contentlen);
					
					//把写的结果返回给客户端
					if (result == null)		//说明结果已经在redis里存在
						dos.writeInt(-1);
					else {
						dos.writeInt(result.length());
						dos.write(result.getBytes());
					}
					dos.flush();
				} else if (header[0] == ActionType.SEARCH) {
					long start = System.currentTimeMillis();
					int infolen = header[1];

					//每次都有39 40ms延迟
					String info = new String(readBytes(infolen, dis));			

					byte[] content = sp.searchPhoto(info);
					// FIXME: ?? 有可能刚刚写进redis的时候，还无法马上读出来,这时候会无法找到图片,返回null
					if (content != null) {
						dos.writeInt(content.length);
						dos.write(content);
					} else {
						dos.writeInt(-1);
					}
					dos.flush();

					ServerProfile.addDelay(System.currentTimeMillis() - start);
				} else if (header[0] == ActionType.DELSET) {
					String set = new String(readBytes(header[1], dis));
					BlockingQueue<WriteTask> bq = sq.get(set);
					
					if(bq != null) {
						// 要删除这个集合,把在这个集合上进行写的线程停掉, null作为标志
						bq.add(new WriteTask(null, null, null, 0, 0));
						sq.remove(set);
					}
					sp.delSet(set);
					dos.write(1);			//返回一个字节1,代表删除成功
				}
			}
			if(sp != null)
				sp.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	
	/**
	 * 从输入流中读取count个字节
	 * @param count
	 * @return
	 */
	public byte[] readBytes(int count,InputStream istream)
	{
		byte[] buf = new byte[count];			
		int n = 0;
		try {
			while(count > n)
			{
				n += istream.read(buf,n,count-n);
			}
			
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return buf;
	}
}
