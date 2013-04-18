import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Random;

import org.apache.hadoop.hive.metastore.api.Node;
import org.apache.hadoop.hive.metastore.api.SFile;
import org.apache.hadoop.hive.metastore.api.SFileLocation;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntField;
import org.apache.lucene.document.LongField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.index.Term;
import org.apache.lucene.sandbox.queries.regex.RegexQuery;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.NumericRangeQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;

import iie.index.lucene.SFDirectory;
import devmap.DevMap;

public class Test {

	/** Index all text files under a directory. */
	public static void IndexFileTest(SFDirectory dir) {
		int numDocs = 100;
		boolean create = true;

		Date start = new Date();
		try {
			System.out.println("Indexing to directory '" + dir.getFullPath()
					+ "'...");

			Analyzer analyzer = new StandardAnalyzer(Version.LUCENE_CURRENT);
			IndexWriterConfig iwc = new IndexWriterConfig(
					Version.LUCENE_CURRENT, analyzer);

			if (create) {
				// Create a new index in the directory, removing any
				// previously indexed documents:
				iwc.setOpenMode(OpenMode.CREATE);
			} else {
				// Add new documents to an existing index:
				iwc.setOpenMode(OpenMode.CREATE_OR_APPEND);
			}

			// Optional: for better indexing performance, if you
			// are indexing many documents, increase the RAM
			// buffer. But if you do this, increase the max heap
			// size to the JVM (eg add -Xmx512m or -Xmx1g):
			//
			// iwc.setRAMBufferSizeMB(256.0);

			IndexWriter writer = new IndexWriter(dir, iwc);
			indexDocs(writer, numDocs);

			// NOTE: if you want to maximize search performance,
			// you can optionally call forceMerge here. This can be
			// a terribly costly operation, so generally it's only
			// worth it when your index is relatively static (ie
			// you're done adding documents to it):
			//
			// writer.forceMerge(1);

			writer.close();

			Date end = new Date();
			System.out.println(end.getTime() - start.getTime()
					+ " total milliseconds");

		} catch (IOException e) {
			System.out.println(" caught a " + e.getClass()
					+ "\n with message: " + e.getMessage());
		}
	}

	static void indexDocs(IndexWriter writer, int numDocs)
			throws IOException {
		if (numDocs <= 0)
			return;

		Random rand = new Random();
		System.out.println("Generate " + numDocs + " KV pairs...");
		for (int i = 0; i < numDocs; i++) {
			Document doc = new Document();
			IntField nf = new IntField("foo", rand.nextInt(),
					Field.Store.YES);

			// doc.add(new NumericField("foo").setIntValue(rand.nextInt()));
			doc.add(nf);
			doc.add(new Field("bar", "hello, world!", Field.Store.YES,
					Field.Index.ANALYZED));
			String vv = "\"" + nf.stringValue() + "\",\"hello, world!\"";
			doc.add(new Field("content", vv, Field.Store.YES,
					Field.Index.NO));

			writer.addDocument(doc);
		}
	}
	
	/** Simple command-line based search demo. */
	public static void ReadFiles(SFDirectory dir) throws Exception {
		String field = "contents";

		System.out.println("----- Scan it -----");
		IndexReader reader = IndexReader.open(dir);

		for (int i = 0; i < reader.maxDoc(); i++) {
			Document doc = reader.document(i);
			System.out.println("Key: " + doc.get("foo") + ", Value: " + doc.get("bar") + ", Content: " + doc.get("content"));
		}

		reader.close();
		System.out.println("-----Search it------(foo>0)");

		IndexSearcher searcher = new IndexSearcher(IndexReader.open(dir));

		Query q = NumericRangeQuery.newIntRange("foo", new Integer("0"), null, false, false);
		ScoreDoc[] hits = searcher.search(q, 100).scoreDocs;
		System.out.println("Hits -> " + hits.length);
		for (int i = 0; i < hits.length; i++) {
			Document doc = searcher.doc(hits[i].doc);
			System.out.println("Key: " + doc.get("foo") + ", Value: " + doc.get("bar"));
		}

		System.out.println("-----Regex it------(bar like hello.*)");
		searcher = new IndexSearcher(DirectoryReader.open(dir));

		q = new RegexQuery(new Term("bar", "hello.*"));
		hits = searcher.search(q, 100).scoreDocs;
		System.out.println("Hits -> " + hits.length);
		for (int i = 0; i < hits.length; i++) {
			Document doc = searcher.doc(hits[i].doc);
			System.out.println("Key: " + doc.get("foo") + ", Value: " + doc.get("bar"));
		}

		System.out.println("-----BooleanQuery it ------(bar: hello and foo<=0)");
		searcher = new IndexSearcher(DirectoryReader.open(dir));

		BooleanQuery bq = new BooleanQuery();
		q = new TermQuery(new Term("bar", "hello"));
		bq.add(q, BooleanClause.Occur.SHOULD);
		q = NumericRangeQuery.newIntRange("foo", 0, null, false, false);
		bq.add(q, BooleanClause.Occur.MUST_NOT);
		hits = searcher.search(bq, 100).scoreDocs;
		System.out.println("Hits -> " + hits.length);
		for (int i = 0; i < hits.length; i++) {
			Document doc = searcher.doc(hits[i].doc);
			System.out.println("Key: " + doc.get("foo") + ", Value: " + doc.get("bar"));
		}
    }
  
    public static void main(String[] args) {

        System.out.println("Begin DevMap Test ...");
        if (DevMap.isValid()) {
            System.out.println("DevMap is valid!");
        } else {
            System.out.println("Invalid!");
        }

        DevMap dm = new DevMap();

        dm.refreshDevMap();
        System.out.print(dm.dumpDevMap());
        System.out.println("End  DevMap Test.");

        System.out.println("Begin IIE Test ...");
        List<SFileLocation> locations = new ArrayList<SFileLocation>();
        locations.add(new SFileLocation("abc", 10, "dev-hello", "testdir", 0, 90, 2, "yyyxx"));
        SFile file = new SFile(10, 100, 2, 1, "xxxxfa", 100, 200, locations);
        List<String> ips = new ArrayList<String>();
        ips.add("192.168.11.7");
        Node node = new Node("abc", ips, 50);
        try {
			SFDirectory dir = new SFDirectory(file, node, SFDirectory.DirType.AUTO, null);
			IndexFileTest(dir);
			ReadFiles(dir);
			dir.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
        System.out.println("End   IIE Test ...");
    }
}
