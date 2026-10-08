package com.aquasuper.smsgateway;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.telephony.SmsManager;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class SmsGatewayService extends Service {
    public static volatile boolean isRunning=false;
    private static final int PORT=8765;
    private static final String CHANNEL="sms_gateway";
    private ServerSocket server;
    private ExecutorService pool;
    private String apiKey;

    @Override public void onCreate(){
        super.onCreate();
        apiKey=getSharedPreferences("gateway",MODE_PRIVATE).getString("apiKey","");
        createChannel();
        startForeground(1001, notification("Starting…"));
        startServer();
    }

    private void startServer(){
        try{
            server=new ServerSocket(PORT);
            pool=Executors.newCachedThreadPool();
            isRunning=true;
            updateNotification();
            pool.execute(this::acceptLoop);
        }catch(Exception e){
            isRunning=false;
            updateNotification();
            stopSelf();
        }
    }

    private void acceptLoop(){
        while(isRunning){
            try{
                final Socket s=server.accept();
                pool.execute(()->handle(s));
            }catch(Exception e){
                if(isRunning) { }
            }
        }
    }

    private void handle(Socket s){
        try{
            s.setSoTimeout(10000);
            BufferedReader r=new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.UTF_8));
            String request=r.readLine();
            if(request==null){s.close();return;}
            int len=0;
            String line;
            while((line=r.readLine())!=null && !line.isEmpty()){
                String lower=line.toLowerCase(Locale.US);
                if(lower.startsWith("content-length:")){
                    try{len=Integer.parseInt(line.substring(15).trim());}catch(Exception ignored){}
                }
            }
            char[] buf=new char[len];
            int got=0;
            while(got<len){
                int n=r.read(buf,got,len-got);
                if(n<0)break;
                got+=n;
            }
            String body=new String(buf,0,got);
            String method=request.split(" ")[0];
            String response;
            if("OPTIONS".equalsIgnoreCase(method)){
                response="{\"ok\":true}";
            }else if("GET".equalsIgnoreCase(method)){
                response="{\"ok\":true,\"service\":\"Aqua Super SMS Gateway\",\"running\":true}";
            }else if("POST".equalsIgnoreCase(method)){
                response=process(body);
            }else{
                response="{\"ok\":false,\"error\":\"method not allowed\"}";
            }
            writeResponse(s,response);
        }catch(Exception e){
            try{writeResponse(s,"{\"ok\":false,\"error\":\"server error\"}");}catch(Exception ignored){}
        }
    }

    private String process(String body){
        try{
            String key=param(body,"key");
            String phone=param(body,"phone").replaceAll("[^0-9+]","");
            String msg=param(body,"message");
            if(!apiKey.equals(key)) return "{\"ok\":false,\"error\":\"unauthorized\"}";
            if(phone.length()<10 || msg.trim().isEmpty()) return "{\"ok\":false,\"error\":\"phone/message required\"}";
            if(Build.VERSION.SDK_INT>=23 && checkSelfPermission(android.Manifest.permission.SEND_SMS)!=PackageManager.PERMISSION_GRANTED)
                return "{\"ok\":false,\"error\":\"SEND_SMS permission missing\"}";

            SmsManager.getDefault().sendTextMessage(phone,null,msg,null,null);
            return "{\"ok\":true,\"queued\":true}";
        }catch(Exception e){
            return "{\"ok\":false,\"error\":\"sms failed\"}";
        }
    }

    private String param(String body,String name){
        for(String p:body.split("&")){
            String[] a=p.split("=",2);
            if(a.length==2 && a[0].equals(name)){
                try{return URLDecoder.decode(a[1],"UTF-8");}catch(Exception ignored){}
            }
        }
        return "";
    }

    private void writeResponse(Socket s,String body)throws IOException{
        byte[] data=body.getBytes(StandardCharsets.UTF_8);
        String h="HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n"+
                "Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, POST, OPTIONS\r\n"+
                "Access-Control-Allow-Headers: Content-Type\r\nContent-Length: "+data.length+
                "\r\nConnection: close\r\n\r\n";
        OutputStream o=s.getOutputStream();
        o.write(h.getBytes(StandardCharsets.UTF_8));
        o.write(data);
        o.flush();
        s.close();
    }

    private void createChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel c=new NotificationChannel(CHANNEL,"Aqua Super SMS Gateway",NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Keeps the SMS gateway available for Aqua Super.");
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }

    private Notification notification(String text){
        Notification.Builder b=Build.VERSION.SDK_INT>=26
                ?new Notification.Builder(this,CHANNEL)
                :new Notification.Builder(this);
        return b.setContentTitle("Aqua Super SMS Gateway")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.sym_action_email)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(){
        String ip=localIp();
        String text=isRunning ? "Running • http://"+ip+":"+PORT : "Stopped";
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(1001,notification(text));
    }

    private String localIp(){
        try{
            Enumeration<NetworkInterface> ns=NetworkInterface.getNetworkInterfaces();
            while(ns.hasMoreElements()){
                NetworkInterface n=ns.nextElement();
                Enumeration<InetAddress> as=n.getInetAddresses();
                while(as.hasMoreElements()){
                    InetAddress a=as.nextElement();
                    if(!a.isLoopbackAddress() && a instanceof Inet4Address) return a.getHostAddress();
                }
            }
        }catch(Exception ignored){}
        return "unknown";
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        return START_STICKY;
    }

    @Override public void onDestroy(){
        isRunning=false;
        try{if(server!=null)server.close();}catch(Exception ignored){}
        if(pool!=null)pool.shutdownNow();
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).cancel(1001);
        super.onDestroy();
    }

    @Override public android.os.IBinder onBind(Intent intent){return null;}
}