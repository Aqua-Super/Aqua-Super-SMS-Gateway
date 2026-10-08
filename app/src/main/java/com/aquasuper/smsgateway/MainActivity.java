package com.aquasuper.smsgateway;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.widget.*;
import android.view.View;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static final int PORT=8765, REQ_SMS=10;
    private ServerSocket server; private ExecutorService pool; private TextView status; private TextView keyView; private String apiKey;
    private volatile boolean running=false;
    @Override public void onCreate(Bundle b){super.onCreate(b); apiKey=UUID.randomUUID().toString().replace("-","").substring(0,16); buildUi(); if(checkSelfPermission(Manifest.permission.SEND_SMS)!=PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.SEND_SMS},REQ_SMS);}
    private void buildUi(){
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(40,50,40,40);
        TextView title=new TextView(this); title.setText("Aqua Super SMS Gateway"); title.setTextSize(24); box.addView(title);
        TextView info=new TextView(this); info.setText("This phone sends SMS through its SIM. Keep the gateway running when laptop SMS is needed.\n\nAPI Key:"); box.addView(info);
        keyView=new TextView(this); keyView.setText(apiKey); keyView.setTextSize(18); box.addView(keyView);
        status=new TextView(this); status.setPadding(0,30,0,30); box.addView(status);
        Button start=new Button(this); start.setText("Start Gateway"); box.addView(start); start.setOnClickListener(v->startServer());
        Button stop=new Button(this); stop.setText("Stop Gateway"); box.addView(stop); stop.setOnClickListener(v->stopServer());
        setContentView(box); status.setText("Stopped");
    }
    private void startServer(){if(running)return; if(checkSelfPermission(Manifest.permission.SEND_SMS)!=PackageManager.PERMISSION_GRANTED){Toast.makeText(this,"SMS permission required",Toast.LENGTH_LONG).show();return;} try{server=new ServerSocket(PORT); pool=Executors.newCachedThreadPool(); running=true; status.setText("Running\nPhone IP: "+localIp()+"\nPort: "+PORT+"\n\nAqua Super can use this gateway while the phone and laptop are on the same network."); pool.execute(()->acceptLoop());}catch(Exception e){status.setText("Start failed: "+e.getMessage());}}
    private void acceptLoop(){while(running){try{Socket s=server.accept(); pool.execute(()->handle(s));}catch(Exception e){if(running){} }}}
    private void handle(Socket s){try{BufferedReader r=new BufferedReader(new InputStreamReader(s.getInputStream())); String line=r.readLine(); if(line==null){s.close();return;} int len=0; String contentType=""; while(!(line=r.readLine()).isEmpty()){String l=line.toLowerCase(Locale.US); if(l.startsWith("content-length:"))len=Integer.parseInt(l.substring(15).trim()); if(l.startsWith("content-type:"))contentType=l.substring(13).trim();}
            char[] buf=new char[len]; int got=0; while(got<len){int n=r.read(buf,got,len-got); if(n<0)break; got+=n;} String body=new String(buf,0,got); String response=process(line,body); writeResponse(s,response); }catch(Exception e){try{writeResponse(s,"{\"ok\":false,\"error\":\"server error\"}");}catch(Exception ignored){}}}
    private String process(String unused,String body){try{String key=param(body,"key"); String phone=param(body,"phone"); String msg=param(body,"message"); if(!apiKey.equals(key))return "{\"ok\":false,\"error\":\"unauthorized\"}"; if(phone.length()<10||msg.isEmpty())return "{\"ok\":false,\"error\":\"phone/message required\"}"; SmsManager.getDefault().sendTextMessage(phone,null,msg,null,null); return "{\"ok\":true}";}catch(Exception e){return "{\"ok\":false,\"error\":\"sms failed\"}";}}
    private String param(String body,String name){for(String p:body.split("&")){String[] a=p.split("=",2); if(a.length==2&&a[0].equals(name))try{return URLDecoder.decode(a[1],"UTF-8");}catch(Exception e){}} return "";}
    private void writeResponse(Socket s,String body)throws IOException{String h="HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: POST, OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type\r\nContent-Length: "+body.getBytes("UTF-8").length+"\r\nConnection: close\r\n\r\n"; OutputStream o=s.getOutputStream(); o.write((h+body).getBytes("UTF-8")); o.flush(); s.close();}
    private String localIp(){try{Enumeration<NetworkInterface> ns=NetworkInterface.getNetworkInterfaces(); while(ns.hasMoreElements()){NetworkInterface n=ns.nextElement(); Enumeration<InetAddress> as=n.getInetAddresses(); while(as.hasMoreElements()){InetAddress a=as.nextElement(); if(!a.isLoopbackAddress()&&a instanceof Inet4Address)return a.getHostAddress();}}}catch(Exception e){} return "unknown";}
    private void stopServer(){running=false;try{if(server!=null)server.close();}catch(Exception e){} if(pool!=null)pool.shutdownNow();status.setText("Stopped");}
    @Override protected void onDestroy(){stopServer();super.onDestroy();}
}
