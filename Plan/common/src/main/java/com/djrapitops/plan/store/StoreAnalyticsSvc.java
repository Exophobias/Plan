package com.djrapitops.plan.store;

import com.djrapitops.plan.SubSystem;
import com.djrapitops.plan.delivery.web.ResolverSvc;
import com.djrapitops.plan.identification.ServerInfo;
import com.djrapitops.plan.storage.database.*;
import javax.inject.*;
import java.util.*;
import java.util.concurrent.*;

/** Read-only financial analytics; only the trusted adapter imports, and HTTP reads cached aggregates. */
@Singleton
public class StoreAnalyticsSvc implements StoreAnalyticsService,SubSystem {
    private final DBSystem databases;private final ServerInfo serverInfo;private final ResolverSvc resolver;
    private volatile boolean enabled,paused=true;private volatile long generation;
    private ScheduledExecutorService executor;
    private volatile Map<String,Map<String,Object>> cache=Map.of();private volatile long cacheAt;
    private StoreReport.Dataset data=empty();private long loadedCursor=-1,loadedReceived=-1,loadedDay=-1;
    @Inject public StoreAnalyticsSvc(DBSystem databases,ServerInfo serverInfo,ResolverSvc resolver){this.databases=databases;this.serverInfo=serverInfo;this.resolver=resolver;}
    @Override public void enable(){enabled=true;setPaused(true);Holder.set(this);resolver.registerPermission("page.server.store","manage.groups");
        executor=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"Plan-store-analytics");t.setDaemon(true);return t;});
        executor.scheduleWithFixedDelay(this::refresh,1,30,TimeUnit.SECONDS);}
    @Override public void disable(){enabled=false;setPaused(true);Holder.set(null);if(executor!=null)executor.shutdownNow();cache=Map.of();}
    @Override public synchronized void setPaused(boolean value){if(value!=paused){paused=value;generation++;}}
    private Database database(){Database db=databases.getDatabase();if(!enabled||db==null||db.getState()!=Database.State.OPEN)throw new IllegalStateException("Store analytics unavailable");return db;}
    private void local(UUID server){if(server==null||!serverInfo.getServerUUID().asUUID().equals(server))throw new IllegalArgumentException("Store source must be this server");}
    @Override public Checkpoint getCheckpoint(UUID server){local(server);return database().queryOptional("SELECT stream_id,cursor_value FROM "+StoreTables.FEED+" WHERE server_uuid=?",
            r->new Checkpoint(r.getString(1),r.getLong(2)),server.toString()).orElse(new Checkpoint(null,0));}
    @Override public CompletionStage<Void> applyBatch(UUID server,Batch batch){local(server);long admitted=generation;
        Runnable check=()->{if(!enabled||paused||generation!=admitted)throw new IllegalStateException("Store import is paused");};check.run();
        return StoreTables.commit(database(),new StoreJournal.Apply(server,batch,System.currentTimeMillis()/1000,check));}
    private void refresh(){
        UUID server=serverInfo.getServerUUID().asUUID();long now=System.currentTimeMillis()/1000;boolean failed=false,catchingUp=false;
        try {
            long[] state=database().queryOptional("SELECT cursor_value,received_at,pending FROM "+StoreTables.FEED+" WHERE server_uuid=?",r->new long[]{r.getLong(1),r.getLong(2),r.getLong(3)},server.toString()).orElse(new long[]{0,0,0});
            catchingUp=state[2]!=0;
            if(!catchingUp && (state[0]!=loadedCursor||now/86400!=loadedDay)) {
                data=StoreReport.load(database(),server,now);loadedCursor=state[0];loadedReceived=state[1];loadedDay=now/86400;
            } else if(!catchingUp && state[1]!=loadedReceived) {
                StoreReport.Feed feed=database().queryOptional("SELECT source_as_of,received_at,capture_started,legacy_at,legacy_records FROM "+StoreTables.FEED+" WHERE server_uuid=? AND pending=0 AND cursor_value=?",
                        r->new StoreReport.Feed(r.getLong(1),r.getLong(2),r.getLong(3),r.getLong(4),r.getLong(5)),server.toString(),loadedCursor).orElse(data.feed());
                data=new StoreReport.Dataset(data.latest(),data.history(),feed);loadedReceived=feed.received();
            }
        }catch(RuntimeException failure){failed=true;}
        try {
            Map<String,Map<String,Object>> prepared=new HashMap<>();List<String> currencies=new ArrayList<>(StoreReport.currencies(data));currencies.add(null);
            for(int days:List.of(30,90))for(String currency:currencies){
                Map<String,Object> report=StoreReport.calculate(server,now,days,currency,data,paused,failed);
                if(catchingUp&&!failed&&!paused){@SuppressWarnings("unchecked") Map<String,Object> coverage=(Map<String,Object>)report.get("coverage");coverage.put("status","collecting");}
                prepared.put(key(days,currency),report);
            }
            cache=Map.copyOf(prepared);cacheAt=now;
        }catch(RuntimeException failure){cache=Map.of();}
    }
    public Map<String,Object> report(UUID server,int days,String currency){
        local(server);if(days!=30&&days!=90||currency!=null&&!currency.matches("[A-Z]{3}"))throw new IllegalArgumentException("Invalid Store report filters");
        long now=System.currentTimeMillis()/1000;Map<String,Object> report=cache.get(key(days,currency));
        if(report!=null&&now-cacheAt<=300)return report;
        // Empty response work is bounded by 90 days, never a database scan on a web thread.
        return StoreReport.calculate(server,now,days,currency,empty(),paused,true);
    }
    private static String key(int days,String currency){return days+":"+(currency==null?"all":currency);}
    private static StoreReport.Dataset empty(){return new StoreReport.Dataset(List.of(),List.of(),new StoreReport.Feed(0,0,0,0,0));}
}
