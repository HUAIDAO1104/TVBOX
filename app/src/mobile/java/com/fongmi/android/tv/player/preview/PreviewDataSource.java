package com.fongmi.android.tv.player.preview;

import android.media.MediaDataSource;
import android.net.Uri;
import android.os.SystemClock;
import androidx.media3.datasource.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

/** Random access only: never skip/download a movie when an origin ignores Range. */
public final class PreviewDataSource extends MediaDataSource {
    private static final int PAGE=128*1024, MAX_BYTES=8*1024*1024;
    private static final OkHttpClient HTTP=new OkHttpClient.Builder().connectTimeout(2,TimeUnit.SECONDS).readTimeout(2,TimeUnit.SECONDS).callTimeout(3,TimeUnit.SECONDS).build();
    private final Uri uri;private final Map<String,String> headers;private final DataSource.Factory local;
    private final LinkedHashMap<Long,byte[]> pages=new LinkedHashMap<>(16,.75f,true);
    private final long deadline=SystemClock.elapsedRealtime()+6000;
    private volatile boolean cancelled;private volatile Call call;private long size;private int bytes,requests;
    public PreviewDataSource(Uri uri,Map<String,String> headers,DataSource.Factory local,long size){this.uri=uri;this.headers=new HashMap<>(headers);this.local=local;this.size=size>0?size:-1;}
    private void check()throws IOException{if(cancelled||SystemClock.elapsedRealtime()>deadline||bytes>=MAX_BYTES||requests>=40)throw new IOException("Preview budget exhausted");}
    @Override public synchronized int readAt(long position,byte[] buffer,int offset,int length)throws IOException{
        check();if(position<0||offset<0||length<0||offset>buffer.length-length)throw new IOException("Invalid read");if(length==0)return 0;if(size>=0&&position>=size)return -1;
        long start=position/PAGE*PAGE;byte[] page=pages.get(start);
        if(page==null){page=fetch(start);pages.put(start,page);while(pages.size()>16)pages.remove(pages.keySet().iterator().next());}
        int within=(int)(position-start);if(within>=page.length)return -1;int count=Math.min(length,page.length-within);System.arraycopy(page,within,buffer,offset,count);return count;
    }
    private byte[] fetch(long position)throws IOException{
        check();requests++;int amount=(int)(size>0?Math.min(PAGE,size-position):PAGE);if(amount<=0)return new byte[0];
        byte[] result=new byte[amount];int count=0;
        if(local!=null){
            DataSource source=local.createDataSource();
            try{long remaining=source.open(new DataSpec.Builder().setUri(uri).setPosition(position).setLength(size>0?amount:androidx.media3.common.C.LENGTH_UNSET).build());if(size<0&&remaining>=0)size=position+remaining;
                while(count<amount){check();int n=source.read(result,count,amount-count);if(n<0)break;if(n==0)throw new IOException("Empty read");count+=n;bytes+=n;}
            }finally{source.close();}
        }else{
            Request.Builder builder=new Request.Builder().url(uri.toString());headers.forEach((k,v)->{if(!k.equalsIgnoreCase("Range")&&!k.equalsIgnoreCase("Accept-Encoding"))builder.header(k,v);});
            builder.header("Range","bytes="+position+"-"+(position+amount-1)).header("Accept-Encoding","identity");
            call=HTTP.newCall(builder.build());
            try(Response response=call.execute()){
                if(response.body()==null)throw new IOException("No preview data");
                if(response.code()==206){
                    String range=response.header("Content-Range","");java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)").matcher(range);
                    if(!matcher.matches()||Long.parseLong(matcher.group(1))!=position)throw new IOException("Invalid range");
                    long end=Long.parseLong(matcher.group(2)),total=Long.parseLong(matcher.group(3));if(end<position||total<=end)throw new IOException("Invalid range");size=total;
                }else if(response.code()==200&&position==0&&response.body().contentLength()>0&&response.body().contentLength()<=PAGE){size=response.body().contentLength();}
                else throw new IOException("Random access unavailable");
                var stream=response.body().byteStream();while(count<amount){check();int n=stream.read(result,count,amount-count);if(n<0)break;count+=n;bytes+=n;}
            }finally{call=null;}
        }
        return Arrays.copyOf(result,count);
    }
    @Override public synchronized long getSize()throws IOException{if(size<0){byte[] first=fetch(0);pages.put(0L,first);}return size;}
    public void cancel(){cancelled=true;Call current=call;if(current!=null)current.cancel();}
    @Override public void close(){cancel();}
}
