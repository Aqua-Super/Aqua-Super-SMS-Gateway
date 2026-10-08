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
    private final ConcurrentHashMap<String,String> smsStatus=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Long> smsTime=new ConcurrentHashMap<>();

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
            }catch(Exception e){}
        }
    }

    private void handle(Socket s){
        try{
            s.setSoTimeout(15000);
            BufferedReader r=new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.UTF_8));
            String request=r.readLine();
            if(request==null){s.close();return;}
            String[] parts=request.split(" ");
            String method=parts.length>0?parts[0]:"";
            String target=parts.length>1?parts[1]:"/";
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
            String response;
            if("OPTIONS".equalsIgnoreCase(method)){
                response="{\"ok\":true}";
            }else if("GET".equalsIgnoreCase(method)){
                if(target.startsWith("/?action=status")){
                    response=statusJson(target);
                }else{
                    response="{\"ok\":true,\"service\":\"Aqua Super SMS Gateway\",\"running\":true}";
                }
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

            String id=UUID.randomUUID().toString().replace("-","");
            smsStatus.put(id,"QUEUED");
            smsTime.put(id,System.currentTimeMillis());

            final BroadcastReceiver[] holder=new BroadcastReceiver[2];
            IntentFilter sentFilter=new IntentFilter("com.aquasuper.smsgateway.SMS_SENT."+id);
            IntentFilter deliveredFilter=new IntentFilter("com.aquasuper.smsgateway.SMS_DELIVERED."+id);

            BroadcastReceiver sentReceiver=new BroadcastReceiver(){
                @Override public void onReceive(Context c,Intent intent){
                    if(getResultCode()==Activity.RESULT_OK){
                        smsStatus.put(id,"SENT");
                    }else{
                        smsStatus.put(id,"FAILED");
                    }
                    unregisterLater(holder[0]);
                }
            };
            BroadcastReceiver deliveredReceiver=new BroadcastReceiver(){
                @Override public void onReceive(Context c,Intent intent){
                    if(getResultCode()==Activity.RESULT_OK){
                        smsStatus.put(id,"DELIVERED");
                    }else{
                        smsStatus.put(id,"DELIVERY_FAILED");
                    }
                    unregisterLater(holder[1]);
                }
            };
            holder[0]=sentReceiver;
            holder[1]=deliveredReceiver;
            if(Build.VERSION.SDK_INT>=33){
                registerReceiver(sentReceiver,sentFilter,Context.RECEIVER_NOT_EXPORTED);
                registerReceiver(deliveredReceiver,deliveredFilter,Context.RECEIVER_NOT_EXPORTED);
            }else{
                registerReceiver(sentReceiver,sentFilter);
                registerReceiver(deliveredReceiver,deliveredFilter);
            }

            Intent si=new Intent("com.aquasuper.smsgateway.SMS_SENT."+id);
            si.setPackage(getPackageName());
            Intent di=new Intent("com.aquasuper.smsgateway.SMS_DELIVERED."+id);
            di.setPackage(getPackageName());
            int flags=PendingIntent.FLAG_UPDATE_CURRENT;
            if(Build.VERSION.SDK_INT>=23) flags|=PendingIntent.FLAG_IMMUTABLE;
            PendingIntent sentPI=PendingIntent.getBroadcast(this,Math.abs(id.hashCode()),si,flags);
            PendingIntent deliveredPI=PendingIntent.getBroadcast(this,Math.abs(id.hashCode()+1),di,flags);

            SmsManager sms=SmsManager.getDefault();
            ArrayList<String> parts=sms.divideMessage(msg);
            if(parts.size()>1){
                ArrayList<PendingIntent> sentList=new ArrayList<>();
                ArrayList<PendingIntent> deliveredList=new ArrayList<>();
                for(int i=0;i<parts.size();i++){
                    sentList.add(sentPI);
                    deliveredList.add(deliveredPI);
                }
                sms.sendMultipartTextMessage(phone,null,parts,sentList,deliveredList);
            }else{
                sms.sendTextMessage(phone,null,msg,sentPI,deliveredPI);
            }

            return "{\"ok\":true,\"queued\":true,\"statusId\":\""+id+"\"}";
        }catch(Exception e){
            return "{\"ok\":false,\"error\":\"sms failed\"}";
        }
    }

    private void unregisterLater(final BroadcastReceiver r){
        if(r==null)return;
        try{new Handler(Looper.getMainLooper()).postDelayed(()->{
            try{unregisterReceiver(r);}catch(Exception ignored){}
        },5000);}catch(Exception ignored){}
    }

    private String statusJson(String target){
        String id="";
        int q=target.indexOf("id=");
        if(q>=0){
            id=target.substring(q+3).split("&")[0];
            try{id=URLDecoder.decode(id,"UTF-8");}catch(Exception ignored){}
        }
        String st=smsStatus.get(id);
        if(st==null)st="UNKNOWN";
        Long t=smsTime.get(id);
        if(t!=null && System.currentTimeMillis()-t>120000){
            smsStatus.remove(id); smsTime.remove(id);
        }
        return "{\"ok\":true,\"status\":\""+st+"\"}";
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

    @Override public int onStartCommand(Intent intent,int flags,int startId){return START_STICKY;}

    @Override public void onDestroy(){
        isRunning=false;
        try{if(server!=null)server.close();}catch(Exception ignored){}
        if(pool!=null)pool.shutdownNow();
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).cancel(1001);
        super.onDestroy();
    }

    @Override public android.os.IBinder onBind(Intent intent){return null;}
}