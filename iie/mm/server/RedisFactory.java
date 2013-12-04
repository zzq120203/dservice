package iie.mm.server;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisSentinelPool;
import redis.clients.jedis.exceptions.JedisConnectionException;
public class RedisFactory {
	private static ServerConf conf;
	private static JedisSentinelPool jsp = null;

	public RedisFactory(ServerConf conf) {
		RedisFactory.conf = conf;
	}
	
	// 从配置文件中读取redis的地址和端口,以此创建jedis对象
	public Jedis getDefaultInstance() {
		switch (conf.getRedisMode()) {
		case STANDALONE:
			return new Jedis(conf.getRedisHost(), conf.getRedisPort());
		case SENTINEL:
		{
			try{
				if (jsp != null)
					return jsp.getResource();
				else {
					jsp = new JedisSentinelPool("mymaster", conf.getSentinels());
					return jsp.getResource();
				}
				
			}catch(JedisConnectionException e){
				//如果出现这个异常，表明要与master建立一个新的连接时失败了
				//此时就会反复的递归调用，直到一个新的master被选举出来
				System.out.println("Could not get a resource from the pool");
				System.out.println("wait and then retry.");
				try{
					Thread.sleep(10*1000);
				}catch(InterruptedException ex){}
				return this.getDefaultInstance();
			}
		}
		}
		return null;
	}
	
	public static synchronized Jedis putInstance(Jedis j) {
		if (j == null)
			return null;
		switch (conf.getRedisMode()) {
		case STANDALONE:
			break;
		case SENTINEL:
			jsp.returnResource(j);
		}
		return null;
	}
	
	public static synchronized Jedis putBrokenInstance(Jedis j) {
		if (j == null)
			return null;
		switch (conf.getRedisMode()) {
		case STANDALONE:
			break;
		case SENTINEL:
			jsp.returnBrokenResource(j);
		}
		return null;
	}
//	public static ServerConf getServerConf(){
//		return this.conf;
//	}
//	
//	public static void setServerConf(ServerConf conf){
//		this.conf = conf;
//	}
}
