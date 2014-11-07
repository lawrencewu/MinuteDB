package com.ctriposs.tsdb.common;

import java.io.File;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.ctriposs.tsdb.InternalKey;
import com.ctriposs.tsdb.iterator.FileSeekIterator;
import com.ctriposs.tsdb.iterator.LevelSeekIterator;
import com.ctriposs.tsdb.manage.FileManager;
import com.ctriposs.tsdb.storage.FileMeta;
import com.ctriposs.tsdb.storage.Head;
import com.ctriposs.tsdb.util.FileUtil;

public abstract class Level {

	public final static int MAX_SIZE = 3;
	public final static long FILE_SIZE = 256 * 1024 * 1024L;
	public final static int THREAD_COUNT = 3;
	
	protected ExecutorService executor = Executors.newFixedThreadPool(2);
	protected Task[] tasks;
	
	protected volatile boolean run = false;
	protected FileManager fileManager;
	protected int level;
	protected long interval;

    /** The list change lock. */
    private final Lock changeLock = new ReentrantLock();
    
    /** The empty list remove lock. */
    protected final Lock deleteLock = new ReentrantLock();

    protected ConcurrentSkipListMap<Long, ConcurrentSkipListSet<FileMeta>> timeFileMap = new ConcurrentSkipListMap<Long, ConcurrentSkipListSet<FileMeta>>(new Comparator<Long>() {
        @Override
        public int compare(Long o1, Long o2) {
            return (int) (o1 - o2);
        }
    });

	public Level(FileManager fileManager, int level, long interval, int threads) {
		this.fileManager = fileManager;
		this.level = level;
		this.interval = interval;
		this.tasks = new Task[threads];
	}
	
	public void recoveryData() throws IOException {
		List<File> list = FileUtil.listFiles(new File(fileManager.getStoreDir()), level + "-dat");
		
		for(File file:list){
			IStorage storage = new MapFileStorage(file);
			byte[] bytes = new byte[Head.HEAD_SIZE];
			storage.get(0, bytes);
			Head head = new Head(bytes);
			String name[] = file.getName().split("[-|.]");
			long time = Long.parseLong(name[0]);
			long fileNumber = Long.parseLong(name[1]);
			FileMeta fileMeta = new FileMeta(fileNumber, file, head.getSmallest(),head.getLargest());
			add(time, fileMeta);
			storage.close();
			fileManager.upateFileNumber(fileNumber);
		}

	}
	
	public void start() {
		if(!run) {
            run = true;
            for (Task task : tasks) {
                executor.submit(task);
            }
		}
	}

	public void stop() {
		if(run) {
			run = false;
			executor.shutdownNow();
		}
	}
	
	public LevelSeekIterator iterator(){
		return new LevelSeekIterator(fileManager, this, interval);
	}
	
	public void add(long time, FileMeta fileMeta) {
		ConcurrentSkipListSet<FileMeta> list = timeFileMap.get(time);
		if(list == null) {
			try{
				changeLock.lock();
				list = timeFileMap.get(time);
				if(list == null) {
					list = new ConcurrentSkipListSet<FileMeta>(fileManager.getFileMetaComparator());
					timeFileMap.put(time, list);
				}
			} finally {
				changeLock.unlock();
			}
		}
		list.add(fileMeta);
	}
	
	public ConcurrentSkipListSet<FileMeta> getFiles(long time){
		return timeFileMap.get(format(time, interval));
	}
	
	public int getFileSize(){
		return timeFileMap.size();
	}

	public long format(long time, long interval) {
		return time/interval*interval;
	}
	
    public ConcurrentSkipListMap<Long, ConcurrentSkipListSet<FileMeta>> getTimeFileMap() {
        return timeFileMap;
    }
	
    public int getLevelNum(){
    	return level;
    }

    public long getLevelInterval() {
        return interval;
    }

	public void delete(long afterTime) throws IOException {
		for(Entry<Long, ConcurrentSkipListSet<FileMeta>> entry : timeFileMap.entrySet()) {
			if(entry.getKey() < afterTime) {
				ConcurrentSkipListSet<FileMeta> list = entry.getValue();
				boolean OK = true;
				try{
					for(FileMeta meta : list){
						try{
							fileManager.delete(meta.getFile());
							list.remove(meta);
						}catch(IOException e){
							OK = false;
							throw e;
						}
					}
				}finally{
					if(OK){
						timeFileMap.remove(entry.getKey());
					}
				}
			}
		}
	}
	
	protected byte[] getValueFromFile(InternalKey key)throws IOException{
		long ts = key.getTime();
		ConcurrentSkipListSet<FileMeta> list = getFiles(format(ts, interval));
		if(list != null) {
			for(FileMeta fileMeta : list) {
				if(fileMeta.contains(key)){
					IStorage storage = new PureFileStorage(fileMeta.getFile());
					FileSeekIterator it = new FileSeekIterator(storage);
					it.seekToFirst(key.getCode());

					while(it.hasNext()){
						it.next();
						int diff = fileManager.compare(key,it.key());
						if(0==diff){
							return it.value();
						}else if(diff < 0){
							break;
						}
					}
				}
			}
		}
		return null;
	}
	
	public abstract class Task implements Runnable {

		protected int num;

		public Task(int num) {
			this.num = num;
		}
		
		@Override
		public void run() {
			while(run) {
				try {
					incrementStoreCount();
					process();
					Thread.sleep(500);
				} catch (Throwable e) {
					//TODO
					e.printStackTrace();
					incrementStoreError();
				}
			}
		}

        public abstract byte[] getValue(InternalKey key);

		public abstract void process() throws Exception;

	}
	
	public abstract void incrementStoreError();
	
	public abstract void incrementStoreCount();
	
	public abstract long getStoreErrorCounter();
	
	public abstract long getStoreCounter();

	public abstract byte[] getValue(InternalKey key) throws IOException;

}
