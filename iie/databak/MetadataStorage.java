package iie.databak;

import iie.metastore.MetaStoreClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.metastore.api.FileOperationException;
import org.apache.hadoop.hive.metastore.api.GlobalSchema;
import org.apache.hadoop.hive.metastore.api.Index;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.NoSuchObjectException;
import org.apache.hadoop.hive.metastore.api.Node;
import org.apache.hadoop.hive.metastore.api.NodeGroup;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.PrivilegeBag;
import org.apache.hadoop.hive.metastore.api.SFile;
import org.apache.hadoop.hive.metastore.api.SFileLocation;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.metastore.msg.MSGFactory.DDLMsg;
import org.apache.hadoop.hive.metastore.msg.MSGType;
import org.apache.thrift.TException;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisConnectionException;

public class MetadataStorage {

	private static MetaStoreClient msClient;
	private Jedis jedis;
	private RedisFactory rf;
	private DatabakConf conf;
	private static boolean initialized = false;
	
	private static ConcurrentHashMap<String, Database> databaseHm = new ConcurrentHashMap<String, Database>();
	private static ConcurrentHashMap<String, PrivilegeBag> privilegeBagHm = new ConcurrentHashMap<String, PrivilegeBag>();
	private static ConcurrentHashMap<String, Partition> partitionHm = new ConcurrentHashMap<String, Partition>();
	private static ConcurrentHashMap<String, Node> nodeHm = new ConcurrentHashMap<String, Node>();
	private static ConcurrentHashMap<String, NodeGroup> nodeGroupHm = new ConcurrentHashMap<String, NodeGroup>();
	private static ConcurrentHashMap<String, GlobalSchema> globalSchemaHm = new ConcurrentHashMap<String, GlobalSchema>();
	private static ConcurrentHashMap<String, Table> tableHm = new ConcurrentHashMap<String, Table>();
	private static Map<String, SFile> sFileHm;
	private static ConcurrentHashMap<String, Index> indexHm = new ConcurrentHashMap<String, Index>();
	private static ConcurrentHashMap<String, SFileLocation> sflHm = new ConcurrentHashMap<String, SFileLocation>();
	
	public MetadataStorage(DatabakConf conf) {
		this.conf = conf;
		rf = new RedisFactory(conf);
		
		initialize();
	}
	
	//初始化与类相关的静态属性，为的是这些属性只初始化一次
	private void initialize()
	{
		synchronized (this.getClass()) {
			if(!initialized)
			{
				try {
					//thread-safe, but need to be in a synchronized block during iteration
					sFileHm = Collections.synchronizedMap(new LRUMap<String, SFile>(conf.getFcs()));
					msClient = new MetaStoreClient(conf.getMshost(), conf.getMsport());
					
					//metastoreclient在初始化时要调get_local_attribution，get_all_attributions
					Database localdb = msClient.client.get_local_attribution();
					writeObject(ObjectType.DATABASE, localdb.getName(), localdb);
					conf.setLocalDbName(localdb.getName());
					List<Database> dbs = msClient.client.get_all_attributions();
					for(Database db : dbs)
					{
						writeObject(ObjectType.DATABASE, db.getName(), db);
					}
					
					//每次系统启动时，从redis中读取已经持久化的对象到内存缓存中(SFile和SFileLocation除外)
					readAll(ObjectType.DATABASE);
					readAll(ObjectType.GLOBALSCHEMA);
					readAll(ObjectType.INDEX);
					readAll(ObjectType.NODE);
					readAll(ObjectType.NODEGROUP);
					readAll(ObjectType.PARTITION);
					readAll(ObjectType.PRIVILEGE);
					readAll(ObjectType.TABLE);
				} catch (MetaException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (TException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (JedisConnectionException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (ClassNotFoundException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			initialized = true;
		}
	}

	public int handleMsg(DDLMsg msg) throws JedisConnectionException, IOException, NoSuchObjectException, TException, ClassNotFoundException {
		
		int eventid = (int) msg.getEvent_id();
		switch (eventid) {
			case MSGType.MSG_NEW_DATABESE: 
			case MSGType.MSG_ALTER_DATABESE:
			case MSGType.MSG_ALTER_DATABESE_PARAM:
			{
				String dbName = (String) msg.getMsg_data().get("db_name");
				try{
					Database db = msClient.client.getDatabase(dbName);
					writeObject(ObjectType.DATABASE, dbName, db);
				}catch(NoSuchObjectException e ){
					e.printStackTrace();
				}
				break;
				
			}
			case MSGType.MSG_DROP_DATABESE:
			{
				String dbname = (String) msg.getMsg_data().get("db_name");
				removeObject(ObjectType.DATABASE, dbname);
				break;
			}
			case MSGType.MSG_ALT_TALBE_NAME:
			{
				String dbName = (String) msg.getMsg_data().get("db_name");
				String tableName = (String) msg.getMsg_data().get("table_name");
				String oldTableName = (String) msg.getMsg_data().get("old_table_name");
				String oldKey = dbName + "." + oldTableName;
				String newKey = dbName + "." + tableName;
				if(tableHm.remove(oldKey) != null){
					removeObject(ObjectType.TABLE, oldKey);
				}
				Table tbl = msClient.client.getTable(dbName, tableName);
				TableImage ti = TableImage.generateTableImage(tbl);
				tableHm.put(newKey, tbl);
				writeObject(ObjectType.TABLE, newKey, ti);
				break;
			}
			
			case MSGType.MSG_NEW_TALBE:
			case MSGType.MSG_ALT_TALBE_DISTRIBUTE:
			case MSGType.MSG_ALT_TALBE_PARTITIONING:
			case MSGType.MSG_ALT_TABLE_SPLITKEYS:
			case MSGType.MSG_ALT_TALBE_DEL_COL:
			case MSGType.MSG_ALT_TALBE_ADD_COL:
			case MSGType.MSG_ALT_TALBE_ALT_COL_NAME:
			case MSGType.MSG_ALT_TALBE_ALT_COL_TYPE:
			case MSGType.MSG_ALT_TABLE_PARAM:
			{
				String dbName = (String) msg.getMsg_data().get("db_name");
				String tableName = (String) msg.getMsg_data().get("table_name");
				String key = dbName + "." + tableName;
				Table tbl = msClient.client.getTable(dbName, tableName);
				TableImage ti = TableImage.generateTableImage(tbl);
				tableHm.put(key, tbl);
				writeObject(ObjectType.TABLE, key, ti);
				for(int i = 0; i < ti.getNgKeys().size();i++){
					String action = (String) msg.getMsg_data().get("action");
					if((action != null && !action.equals("delng")) || action == null){
						if(!nodeGroupHm.containsKey(ti.getNgKeys().get(i))){
							List<String> ngNames = new ArrayList<String>();
							ngNames.add(ti.getNgKeys().get(i));
							List<NodeGroup> ngs = msClient.client.listNodeGroups(ngNames);
							NodeGroup ng = ngs.get(0);
							NodeGroupImage ngi = NodeGroupImage.generateNodeGroupImage(ng);
							nodeGroupHm.put(ng.getNode_group_name(), ng);
							writeObject(ObjectType.NODEGROUP, ng.getNode_group_name(), ngi);
							for(int j = 0; j<ngi.getNodeKeys().size();j++){
								if(!nodeHm.containsKey(ngi.getNodeKeys().get(j))){
									Node node = msClient.client.get_node(ngi.getNodeKeys().get(j));
									writeObject(ObjectType.NODE, ngi.getNodeKeys().get(j), node);
								}
							}
						}
					}
				}
				break;
			}
			case MSGType.MSG_DROP_TABLE:
			{
				String dbName = (String) msg.getMsg_data().get("db_name");
				String tableName = (String) msg.getMsg_data().get("table_name");
				String key = dbName + "." + tableName;
				if(tableHm.remove(key) != null){
					removeObject(ObjectType.TABLE, key);
				}
				break;
			}
			
			case MSGType.MSG_REP_FILE_CHANGE:
			case MSGType.MSG_STA_FILE_CHANGE:
			case MSGType.MSG_REP_FILE_ONOFF:
			case MSGType.MSG_CREATE_FILE:
			{
				Object id = msg.getMsg_data().get("f_id");
				if(id == null)		//
				{
					break;
				}
				long fid = Long.parseLong(id.toString());
				SFile sf = null;
				try{
					sf = msClient.client.get_file_by_id(fid);
				}catch(FileOperationException e)
				{
					//Can not find SFile by FID ...
					System.out.println(e.getMessage());
					if(sf == null)
						break;
				}
				SFileImage sfi = SFileImage.generateSFileImage(sf);
				sFileHm.put(fid+"", sf);
				writeObject(ObjectType.SFILE, fid+"", sfi);
				for(int i = 0;i<sfi.getSflkeys().size();i++)
				{
					writeObject(ObjectType.SFILELOCATION, sfi.getSflkeys().get(i), sf.getLocations().get(i));
				}
				
				break;
			}
			//在删除文件时，会在之前发几个1307,然后才是4002
			case MSGType.MSG_DEL_FILE:
			{
				long fid = Long.parseLong(msg.getMsg_data().get("f_id").toString());
				SFile sf = (SFile)readObject(ObjectType.SFILE, fid+"");
				if(sf != null)
				{
					if(sf.getLocations() != null)
					{
						for(SFileLocation sfl : sf.getLocations())
						{
							String key = SFileImage.generateSflkey(sfl.getLocation(),sfl.getDevid());
							removeObject(ObjectType.SFILELOCATION, key);
						}
					}
					removeObject(ObjectType.SFILE, fid+"");
				}
				break;
			}
			
			case MSGType.MSG_NEW_INDEX:
			case MSGType.MSG_ALT_INDEX:
			case MSGType.MSG_ALT_INDEX_PARAM:
			{
				String dbName = (String)msg.getMsg_data().get("db_name");
				String tblName = (String)msg.getMsg_data().get("table_name");
				String indexName = (String)msg.getMsg_data().get("index_name");
				if(dbName == null || tblName == null || indexName == null)
					break;
				Index ind = msClient.client.getIndex(dbName, tblName, indexName);
				String key = dbName + "." + tblName + "." + indexName;
				writeObject(ObjectType.INDEX, key, ind);
				break;
			}
			case MSGType.MSG_DEL_INDEX:
			{
				String dbName = (String)msg.getMsg_data().get("db_name");
				String tblName = (String)msg.getMsg_data().get("table_name");
				String indexName = (String)msg.getMsg_data().get("index_name");
				//Index ind = msClient.client.getIndex(dbName, tblName, indexName);
				String key = dbName + "." + tblName + "." + indexName;
				if(indexHm.remove(key) != null)
					removeObject(ObjectType.INDEX, key);
				break;
			}
			
			case MSGType.MSG_NEW_NODE:
			case MSGType.MSG_FAIL_NODE:
			case MSGType.MSG_BACK_NODE:
			{
				String nodename = (String)msg.getMsg_data().get("node_name");
				Node node = msClient.client.get_node(nodename);
				writeObject(ObjectType.NODE, nodename, node);
				break;
			}
			
			case MSGType.MSG_DEL_NODE:
			{
				String nodename = (String)msg.getMsg_data().get("node_name");
				removeObject(ObjectType.NODE, nodename);
				break;
			}
			
			case MSGType.MSG_CREATE_SCHEMA:
			case MSGType.MSG_MODIFY_SCHEMA_DEL_COL:
			case MSGType.MSG_MODIFY_SCHEMA_ADD_COL:
			case MSGType.MSG_MODIFY_SCHEMA_ALT_COL_NAME:
			case MSGType.MSG_MODIFY_SCHEMA_ALT_COL_TYPE:
			case MSGType.MSG_MODIFY_SCHEMA_PARAM:
			{
				String schema_name = (String)msg.getMsg_data().get("schema_name");
				try{
					GlobalSchema s = msClient.client.getSchemaByName(schema_name);
					writeObject(ObjectType.GLOBALSCHEMA, schema_name, s);
				}catch(NoSuchObjectException e){
					e.printStackTrace();
				}
				
				break;
			}
			
			case MSGType.MSG_MODIFY_SCHEMA_NAME:
			{
				String old_schema_name = (String)msg.getMsg_data().get("old_schema_name");
				String schema_name = (String)msg.getMsg_data().get("schema_name");
				GlobalSchema gs = globalSchemaHm.get(old_schema_name);
				if(gs != null)
				{
					removeObject(ObjectType.GLOBALSCHEMA, old_schema_name);
					writeObject(ObjectType.GLOBALSCHEMA, schema_name, gs);
				}
				else{
					try{
						GlobalSchema ngs = msClient.client.getSchemaByName(schema_name);
						writeObject(ObjectType.GLOBALSCHEMA, schema_name, ngs);
					}
					catch(NoSuchObjectException e)
					{
						e.printStackTrace();
					}
				
				}
			}
			
			case MSGType.MSG_DEL_SCHEMA:
			{
				String schema_name = (String)msg.getMsg_data().get("schema_name");
				removeObject(ObjectType.GLOBALSCHEMA, schema_name);
				break;
			}
			
			case MSGType.MSG_NEW_NODEGROUP:
			{
				String nodeGroupName = (String)msg.getMsg_data().get("nodegroup_name");
				List<String> ngNames = new ArrayList<String>();
				ngNames.add(nodeGroupName);
				List<NodeGroup> ngs = msClient.client.listNodeGroups(ngNames);
				NodeGroup ng = ngs.get(0);
				NodeGroupImage ngi = NodeGroupImage.generateNodeGroupImage(ng);
				nodeGroupHm.put(ng.getNode_group_name(), ng);
				writeObject(ObjectType.NODEGROUP, ng.getNode_group_name(), ngi);
				for(int i = 0; i<ngi.getNodeKeys().size();i++){
					if(!nodeHm.containsKey(ngi.getNodeKeys().get(i))){
						Node node = msClient.client.get_node(ngi.getNodeKeys().get(i));
						writeObject(ObjectType.NODE, ngi.getNodeKeys().get(i), node);
					}
				}
				break;
			}
			//case MSGType.MSG_ALTER_NODEGROUP:
			case MSGType.MSG_DEL_NODEGROUP:{
				String nodeGroupName = (String)msg.getMsg_data().get("nodegroup_name");
				removeObject(ObjectType.NODEGROUP, nodeGroupName);
				break;
			}
			
			
			//what can I do...
		    case MSGType.MSG_GRANT_GLOBAL:
		    case MSGType.MSG_GRANT_DB:
		    case MSGType.MSG_GRANT_TABLE:
		    case MSGType.MSG_GRANT_SCHEMA:
		    case MSGType.MSG_GRANT_PARTITION:
		    case MSGType.MSG_GRANT_PARTITION_COLUMN:
		    case MSGType.MSG_GRANT_TABLE_COLUMN:
	
		    case MSGType.MSG_REVOKE_GLOBAL:
		    case MSGType.MSG_REVOKE_DB:
		    case MSGType.MSG_REVOKE_TABLE:
		    case MSGType.MSG_REVOKE_PARTITION:
		    case MSGType.MSG_REVOKE_SCHEMA:
		    case MSGType.MSG_REVOKE_PARTITION_COLUMN:
		    case MSGType.MSG_REVOKE_TABLE_COLUMN:
		    {
//		    	msClient.client.
		    	break;
		    }
			default:
			{
				System.out.println("unhandled msg : "+msg.toJson());
				break;
			}
		}
		return 1;
	}

	private void writeObject(String key, String field, Object o)throws JedisConnectionException, IOException {
		reconnectJedis();
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		ObjectOutputStream oos = new ObjectOutputStream(baos);
		oos.writeObject(o);
		jedis.hset(key.getBytes(), field.getBytes(), baos.toByteArray());
		
		if(key.equals(ObjectType.DATABASE))
			databaseHm.put(field, (Database)o);
//		if(key.equals(ObjectType.TABLE))
//			tableHm.put(field, (Table)o);
		//对于sfile，函数参数是sfileimage
//		if(key.equals(ObjectType.SFILE))
//			sFileHm.put(field, (SFile)o);
		if(key.equals(ObjectType.SFILELOCATION))
			sflHm.put(field, (SFileLocation)o);
		if(key.equals(ObjectType.INDEX))
			indexHm.put(field, (Index)o);
		if(key.equals(ObjectType.NODE))
			nodeHm.put(field, (Node)o);
//		if(key.equals(ObjectType.NODEGROUP))
//			nodeGroupHm.put(field, (NodeGroup)o);
		if(key.equals(ObjectType.GLOBALSCHEMA))
			globalSchemaHm.put(field, (GlobalSchema)o);
		if(key.equals(ObjectType.PRIVILEGE))
			privilegeBagHm.put(field, (PrivilegeBag)o);
		if(key.equals(ObjectType.PARTITION))
			partitionHm.put(field, (Partition)o);
	}

	public Object readObject(String key, String field)throws JedisConnectionException, IOException,ClassNotFoundException {
		Object o = null;
		if(key.equals(ObjectType.DATABASE))
			o = databaseHm.get(field);
		if(key.equals(ObjectType.TABLE))
			o = tableHm.get(field);
		if(key.equals(ObjectType.SFILE))
			o = sFileHm.get(field);
		if(key.equals(ObjectType.SFILELOCATION))
			o = sflHm.get(field);
		if(key.equals(ObjectType.INDEX))
			o = indexHm.get(field);
		if(key.equals(ObjectType.NODE))
			o = nodeHm.get(field);
		if(key.equals(ObjectType.NODEGROUP))
			o = nodeGroupHm.get(field);
		if(key.equals(ObjectType.GLOBALSCHEMA))
			o = globalSchemaHm.get(field);
		if(key.equals(ObjectType.PRIVILEGE))
			o = privilegeBagHm.get(field);
		if(key.equals(ObjectType.PARTITION))
			o = partitionHm.get(field);
		
		if(o != null)
		{
			System.out.println("in function readObject: read "+key+":"+field+" from cache.");
			return o;
		}
		reconnectJedis();

		byte[] buf = jedis.hget(key.getBytes(), field.getBytes());
		if(buf == null)
			return null;
		ByteArrayInputStream bais = new ByteArrayInputStream(buf);
		ObjectInputStream ois = new ObjectInputStream(bais);
		o = ois.readObject();
		//SFile 要特殊处理
		if(key.equals(ObjectType.SFILE))
		{
			SFileImage sfi = (SFileImage)o;
			List<SFileLocation> locations = new ArrayList<SFileLocation>();
			for(int i = 0;i<sfi.getSflkeys().size();i++)
			{
				SFileLocation sfl = (SFileLocation)readObject(ObjectType.SFILELOCATION, sfi.getSflkeys().get(i));
				if(sfl != null)
					locations.add(sfl);
			}
			SFile sf =  new SFile(sfi.getFid(),sfi.getDbName(),sfi.getTableName(),sfi.getStore_status(),sfi.getRep_nr(),
					sfi.getDigest(),sfi.getRecord_nr(),sfi.getAll_record_nr(),locations,sfi.getLength(),
					sfi.getRef_files(),sfi.getValues(),sfi.getLoad_status());
			sFileHm.put(field, sf);
			o = sf;
		}
		
		//table
		if(key.equals(ObjectType.TABLE))
		{
			TableImage ti = (TableImage)o;
			List<NodeGroup> ngs = new ArrayList<NodeGroup>();
			for(int i = 0;i<ti.getNgKeys().size();i++)
			{
				NodeGroup ng = (NodeGroup)readObject(ObjectType.NODEGROUP, ti.getNgKeys().get(i));
				if(ng != null)
					ngs.add(ng);
			}
			Table t = new Table(ti.getTableName(),ti.getDbName(),ti.getSchemaName(),
					ti.getOwner(),ti.getCreateTime(),ti.getLastAccessTime(),ti.getRetention(),
					ti.getSd(),ti.getPartitionKeys(),ti.getParameters(),ti.getViewOriginalText(),
					ti.getViewExpandedText(),ti.getTableType(),ngs,ti.getFileSplitKeys());
			tableHm.put(ObjectType.TABLE, t);
			o = t;
		}
		
		//nodegroup
		if(key.equals(ObjectType.NODEGROUP))
		{
			NodeGroupImage ngi = (NodeGroupImage)o;
			Set<Node> nodes = new HashSet<Node>();
			for(String s : ngi.getNodeKeys())
			{
				Node n = (Node)readObject(ObjectType.NODE, s);
				if(n != null)
					nodes.add(n);
			}
			NodeGroup ng = new NodeGroup(ngi.getNode_group_name(), ngi.getComment(), ngi.getStatus(), nodes);
			nodeGroupHm.put(field, ng);
			o = ng;
		}
		
		if(key.equals(ObjectType.DATABASE))
			databaseHm.put(field, (Database)o);
		if(key.equals(ObjectType.SFILELOCATION))
			sflHm.put(field, (SFileLocation)o);
		if(key.equals(ObjectType.INDEX))
			indexHm.put(field, (Index)o);
		if(key.equals(ObjectType.NODE))
			nodeHm.put(field, (Node)o);
		if(key.equals(ObjectType.GLOBALSCHEMA))
			globalSchemaHm.put(field, (GlobalSchema)o);
		if(key.equals(ObjectType.PRIVILEGE))
			privilegeBagHm.put(field, (PrivilegeBag)o);
		if(key.equals(ObjectType.PARTITION))
			partitionHm.put(field, (Partition)o);
		
		System.out.println("in function readObject: read "+key+":"+field+" from redis.");
		return o;
	}

	private void removeObject(String key, String field)
	{
		if(key.equals(ObjectType.DATABASE))
			databaseHm.remove(field);
		if(key.equals(ObjectType.TABLE))
			tableHm.remove(field);
		if(key.equals(ObjectType.SFILE))
			sFileHm.remove(field);
		if(key.equals(ObjectType.SFILELOCATION))
			sflHm.remove(field);
		if(key.equals(ObjectType.INDEX))
			indexHm.remove(field);
		if(key.equals(ObjectType.NODE))
			nodeHm.remove(field);
		if(key.equals(ObjectType.NODEGROUP))
			nodeGroupHm.remove(field);
		if(key.equals(ObjectType.GLOBALSCHEMA))
			globalSchemaHm.remove(field);
		if(key.equals(ObjectType.PRIVILEGE))
			privilegeBagHm.remove(field);
		if(key.equals(ObjectType.PARTITION))
			partitionHm.remove(field);
		
		reconnectJedis();
		jedis.hdel(key, field);
	}
	
	private void readAll(String key) throws JedisConnectionException, IOException, ClassNotFoundException
	{
		reconnectJedis();
		Set<String> fields = jedis.hkeys(key);		
		if(fields != null)
		{
			System.out.println("read "+fields.size()+" "+key+" from redis into cache.");
			for(String field : fields)
			{
				readObject(key, field);
			}
		}
	}
	
	private void reconnectJedis() throws JedisConnectionException {
		if (jedis == null) {
			jedis = rf.getDefaultInstance();
		}
		if (jedis == null)
			throw new JedisConnectionException("Connection to redis Server failed.");
	}

	
	public static ConcurrentHashMap<String, Database> getDatabaseHm() {
		return databaseHm;
	}

	public static MetaStoreClient getMsClient() {
		return msClient;
	}

	public RedisFactory getRf() {
		return rf;
	}

	public DatabakConf getConf() {
		return conf;
	}

	public static ConcurrentHashMap<String, PrivilegeBag> getPrivilegeBagHm() {
		return privilegeBagHm;
	}

	public static ConcurrentHashMap<String, Partition> getPartitionHm() {
		return partitionHm;
	}

	public static ConcurrentHashMap<String, Node> getNodeHm() {
		return nodeHm;
	}

	public static ConcurrentHashMap<String, NodeGroup> getNodeGroupHm() {
		return nodeGroupHm;
	}

	public static ConcurrentHashMap<String, GlobalSchema> getGlobalSchemaHm() {
		return globalSchemaHm;
	}

	public static ConcurrentHashMap<String, Table> getTableHm() {
		return tableHm;
	}

	public static Map<String, SFile> getsFileHm() {
		return sFileHm;
	}

	public static ConcurrentHashMap<String, Index> getIndexHm() {
		return indexHm;
	}

	public static ConcurrentHashMap<String, SFileLocation> getSflHm() {
		return sflHm;
	}
	
	
}
