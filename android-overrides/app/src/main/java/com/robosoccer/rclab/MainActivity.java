package com.robosoccer.rclab;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.*;
import android.hardware.usb.*;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.webkit.*;
import android.widget.Toast;
import com.hoho.android.usbserial.driver.*;
import com.hoho.android.usbserial.util.SerialInputOutputManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity {
  private static final String USB_PERMISSION = "com.robosoccer.rclab.USB_PERMISSION";
  private WebView web;
  private UsbManager usb;
  private UsbSerialPort port;
  private UsbDeviceConnection connection;
  private UsbSerialDriver driver;
  private SerialInputOutputManager io;
  private PendingIntent permissionIntent;
  private final ExecutorService pool = Executors.newCachedThreadPool();
  private boolean receiverRegistered;

  private final BroadcastReceiver usbEvents = new BroadcastReceiver() {
    @Override public void onReceive(Context c, Intent i) {
      String a = i.getAction();
      UsbDevice d = deviceExtra(i);
      if (USB_PERMISSION.equals(a)) {
        if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && d != null) openDevice(d);
        else js("onUsbError", JSONObject.quote("USB permission denied"));
      } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)) scanUsb();
      else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
        if (d != null && driver != null && d.getDeviceId() == driver.getDevice().getDeviceId()) {
          closePort(); js("onUsbDisconnected", JSONObject.quote("USB device disconnected"));
        }
        scanUsb();
      }
    }
  };

  private UsbDevice deviceExtra(Intent i) {
    if (Build.VERSION.SDK_INT >= 33) return i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
    //noinspection deprecation
    return i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
  }

  @Override public void onCreate(Bundle b) {
    super.onCreate(b);
    getWindow().setStatusBarColor(android.graphics.Color.rgb(11,15,23));
    getWindow().setNavigationBarColor(android.graphics.Color.rgb(8,12,18));
    usb = (UsbManager)getSystemService(USB_SERVICE);
    permissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(USB_PERMISSION).setPackage(getPackageName()),
      PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
    IntentFilter f = new IntentFilter(); f.addAction(USB_PERMISSION); f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
    if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbEvents, f, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(usbEvents, f);
    receiverRegistered = true;
    web = new WebView(this); web.setBackgroundColor(0xff0b0f17);
    WebSettings s = web.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setDatabaseEnabled(true);
    s.setAllowFileAccess(true); s.setAllowContentAccess(false); s.setAllowFileAccessFromFileURLs(false); s.setAllowUniversalAccessFromFileURLs(false);
    s.setSupportZoom(true); s.setBuiltInZoomControls(true); s.setDisplayZoomControls(false);
    web.addJavascriptInterface(new Bridge(), "AndroidBridge"); web.setWebViewClient(new WebViewClient());
    web.setWebChromeClient(new WebChromeClient() { @Override public boolean onConsoleMessage(ConsoleMessage m) { android.util.Log.d("RoboSoccerWeb", m.message()+" ("+m.sourceId()+":"+m.lineNumber()+")"); return true; }});
    setContentView(web); if (b == null) web.loadUrl("file:///android_asset/index.html"); else web.restoreState(b);
  }
  @Override protected void onResume() { super.onResume(); scanUsb(); }
  @Override protected void onSaveInstanceState(Bundle b) { super.onSaveInstanceState(b); if(web!=null) web.saveState(b); }
  @Override protected void onDestroy() { closePort(); if(receiverRegistered) try { unregisterReceiver(usbEvents); } catch(Exception ignored){} pool.shutdownNow(); if(web!=null) web.destroy(); super.onDestroy(); }
  @Override public void onBackPressed() { if(web!=null && web.canGoBack()) web.goBack(); else super.onBackPressed(); }

  private String safe(String s) { return s == null ? "" : s; }
  private void js(String fn, String jsonArg) { runOnUiThread(() -> { if(web!=null) web.evaluateJavascript("window.RoboSoccerNative && window.RoboSoccerNative."+fn+"("+jsonArg+")", null); }); }
  private void jsText(String fn, String value) { js(fn, JSONObject.quote(value == null ? "" : value)); }
  private void scanUsb() {
    try {
      JSONArray a = new JSONArray(); List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usb);
      for(UsbSerialDriver d: drivers) { UsbDevice x=d.getDevice(); JSONObject o=new JSONObject();
        o.put("deviceId",x.getDeviceId()); o.put("vendorId",x.getVendorId()); o.put("productId",x.getProductId());
        o.put("manufacturer",safe(x.getManufacturerName())); o.put("product",safe(x.getProductName())); o.put("driver",d.getClass().getSimpleName());
        o.put("permission",usb.hasPermission(x)); o.put("connected",port!=null && port.isOpen() && driver!=null && driver.getDevice().getDeviceId()==x.getDeviceId()); a.put(o);
      }
      JSONObject p=new JSONObject(); p.put("devices",a); p.put("connected",port!=null && port.isOpen()); js("onUsbScan",p.toString());
    } catch(Exception e) { jsText("onUsbError","USB scan failed: "+e.getMessage()); }
  }
  private void connectUsb(int id) {
    UsbSerialDriver found=null; for(UsbSerialDriver d:UsbSerialProber.getDefaultProber().findAllDrivers(usb)) if(d.getDevice().getDeviceId()==id) {found=d;break;}
    if(found==null) { jsText("onUsbError","No supported USB serial adapter found"); scanUsb(); return; }
    if(usb.hasPermission(found.getDevice())) openDevice(found.getDevice()); else usb.requestPermission(found.getDevice(),permissionIntent);
  }
  private void openDevice(UsbDevice device) {
    pool.execute(() -> {
      try {
        UsbSerialDriver d=null; for(UsbSerialDriver x:UsbSerialProber.getDefaultProber().findAllDrivers(usb)) if(x.getDevice().getDeviceId()==device.getDeviceId()) {d=x;break;}
        if(d==null) throw new Exception("USB serial adapter is not supported");
        closePort(); UsbDeviceConnection c=usb.openDevice(device); if(c==null) throw new Exception("Could not open USB device; check OTG/data cable and permission");
        UsbSerialPort p=d.getPorts().get(0); p.open(c); p.setParameters(115200,8,UsbSerialPort.STOPBITS_1,UsbSerialPort.PARITY_NONE);
        driver=d; connection=c; port=p;
        io=new SerialInputOutputManager(p,new SerialInputOutputManager.Listener(){
          @Override public void onNewData(byte[] data){ jsText("onUsbData",new String(data,StandardCharsets.UTF_8)); }
          @Override public void onRunError(Exception e){ jsText("onUsbError","Serial read stopped: "+e.getMessage()); closePort(); }
        }); io.start();
        JSONObject info=new JSONObject(); info.put("deviceId",device.getDeviceId()); info.put("vendorId",device.getVendorId()); info.put("productId",device.getProductId());
        info.put("manufacturer",safe(device.getManufacturerName())); info.put("product",safe(device.getProductName())); info.put("driver",d.getClass().getSimpleName());
        js("onUsbConnected",info.toString()); scanUsb();
      } catch(Exception e) { closePort(); jsText("onUsbError","USB connect failed: "+e.getMessage()); scanUsb(); }
    });
  }
  private synchronized void closePort() {
    try { if(io!=null) io.stop(); } catch(Exception ignored){} io=null;
    try { if(port!=null) port.close(); } catch(Exception ignored){} port=null;
    try { if(connection!=null) connection.close(); } catch(Exception ignored){} connection=null; driver=null;
  }
  private boolean safePackageUrl(String u) {
    return u!=null && u.startsWith("https://") && (u.startsWith("https://downloads.arduino.cc/") || u.startsWith("https://espressif.github.io/arduino-esp32/") || u.startsWith("https://github.com/") || u.startsWith("https://raw.githubusercontent.com/"));
  }
  private byte[] httpBytes(String address,int max) throws Exception {
    if(!safePackageUrl(address)) throw new SecurityException("Package URL is not from an approved source");
    HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection(); c.setConnectTimeout(20000); c.setReadTimeout(45000); c.setInstanceFollowRedirects(true); c.setRequestProperty("User-Agent","RoboSoccer-RC-Lab-Android/1.1");
    try(InputStream in=c.getInputStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()) { byte[] b=new byte[16384]; int n; while((n=in.read(b))!=-1){ if(out.size()+n>max) throw new Exception("Download exceeds size limit"); out.write(b,0,n);} return out.toByteArray(); } finally { c.disconnect(); }
  }
  private void deleteTree(File f){ if(f==null||!f.exists())return; if(f.isDirectory()){File[] a=f.listFiles();if(a!=null)for(File x:a)deleteTree(x);} //noinspection ResultOfMethodCallIgnored
    f.delete(); }
  private void unzipLibrary(byte[] bytes,File dest) throws Exception {
    try(ZipInputStream z=new ZipInputStream(new ByteArrayInputStream(bytes))){ ZipEntry e; byte[] b=new byte[8192];
      while((e=z.getNextEntry())!=null){ String n=e.getName().replace('\\','/'); if(n.startsWith("/")||n.contains("../")||n.equals(".."))throw new SecurityException("Unsafe archive path");
        String[] parts=n.split("/",2); String rel=parts.length==2?parts[1]:parts[0]; if(rel.isEmpty()){z.closeEntry();continue;}
        File out=new File(dest,rel); if(!out.getCanonicalPath().startsWith(dest.getCanonicalPath()+File.separator))throw new SecurityException("Archive path escaped install directory");
        if(e.isDirectory())out.mkdirs(); else {File p=out.getParentFile();if(p!=null)p.mkdirs();try(FileOutputStream os=new FileOutputStream(out)){int k;while((k=z.read(b))!=-1)os.write(b,0,k);}}
        z.closeEntry();
      }
    }
  }

  public class Bridge {
    @JavascriptInterface public void scanUsbDevices(){ runOnUiThread(MainActivity.this::scanUsb); }
    @JavascriptInterface public void connectUsb(int id){ runOnUiThread(()->connectUsb(id)); }
    @JavascriptInterface public void disconnectUsb(){ closePort(); jsText("onUsbDisconnected","USB serial disconnected"); scanUsb(); }
    @JavascriptInterface public void setSerialBaudRate(int baud){ pool.execute(()->{try{if(port!=null&&port.isOpen()){port.setParameters(Math.max(300,Math.min(2000000,baud)),8,UsbSerialPort.STOPBITS_1,UsbSerialPort.PARITY_NONE);jsText("onUsbInfo","Baud rate set to "+baud);}}catch(Exception e){jsText("onUsbError",e.getMessage());}}); }
    @JavascriptInterface public void serialWrite(String s){ pool.execute(()->{try{if(port==null||!port.isOpen())throw new Exception("USB board disconnected");port.write((s==null?"":s).getBytes(StandardCharsets.UTF_8),1500);}catch(Exception e){jsText("onUsbError","Serial write failed: "+e.getMessage());}}); }
    @JavascriptInterface public void fetchCatalog(String url,String kind){ pool.execute(()->{try{byte[] b=httpBytes(url,"library".equals(kind)?35*1024*1024:8*1024*1024);String data=new String(b,StandardCharsets.UTF_8);js("onCatalogData","{\"kind\":"+JSONObject.quote(kind)+",\"data\":"+JSONObject.quote(data)+"}");}catch(Exception e){js("onCatalogError","{\"kind\":"+JSONObject.quote(kind)+",\"message\":"+JSONObject.quote(e.getMessage())+"}");}}); }
    @JavascriptInterface public void installLibrary(String name,String url){ pool.execute(()->{try{byte[] b=httpBytes(url,40*1024*1024);File root=new File(getFilesDir(),"arduino/libraries");if(!root.exists())root.mkdirs();String safe=name==null?"Library":name.replaceAll("[^A-Za-z0-9_.-]","_");File dest=new File(root,safe);deleteTree(dest);if(!dest.mkdirs())throw new Exception("Could not create library directory");unzipLibrary(b,dest);JSONObject o=new JSONObject();o.put("name",safe);o.put("path",dest.getAbsolutePath());js("onLibraryInstalled",o.toString());}catch(Exception e){jsText("onLibraryInstallError",e.getMessage());}}); }
    @JavascriptInterface public void downloadBoardArchive(String name,String url){ pool.execute(()->{try{byte[] b=httpBytes(url,180*1024*1024);File dir=new File(getFilesDir(),"arduino/board-archives");if(!dir.exists())dir.mkdirs();String safe=name==null?"board-package":name.replaceAll("[^A-Za-z0-9_.-]","_");String low=url.toLowerCase();String ext=low.contains(".zip")?".zip":low.contains(".tar.bz2")?".tar.bz2":".tar.gz";File f=new File(dir,safe+ext);try(FileOutputStream os=new FileOutputStream(f)){os.write(b);}jsText("onBoardArchiveDownloaded",f.getAbsolutePath());}catch(Exception e){jsText("onBoardArchiveError",e.getMessage());}}); }
    @JavascriptInterface public void saveFile(String name,String content,String mime){ runOnUiThread(()->{OutputStream out=null;try{String n=name==null?"export.txt":name.replace('/','_').replace('\\','_');String type=mime==null||mime.isEmpty()?"text/plain":mime.split(";")[0];ContentValues v=new ContentValues();v.put(MediaStore.Downloads.DISPLAY_NAME,n);v.put(MediaStore.Downloads.MIME_TYPE,type);v.put(MediaStore.Downloads.RELATIVE_PATH,"Download/RoboSoccer RC Lab");Uri u=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);if(u==null)throw new Exception("Could not create download file");out=getContentResolver().openOutputStream(u);if(out==null)throw new Exception("Could not open download file");out.write((content==null?"":content).getBytes(StandardCharsets.UTF_8));out.flush();Toast.makeText(MainActivity.this,"Saved to Downloads/RoboSoccer RC Lab/"+n,Toast.LENGTH_LONG).show();}catch(Exception e){Toast.makeText(MainActivity.this,"Export failed: "+e.getMessage(),Toast.LENGTH_LONG).show();}finally{try{if(out!=null)out.close();}catch(Exception ignored){}}}); }
    @JavascriptInterface public void copyText(String s){runOnUiThread(()->{android.content.ClipboardManager c=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);c.setPrimaryClip(android.content.ClipData.newPlainText("RoboSoccer",s));Toast.makeText(MainActivity.this,"Copied to clipboard",Toast.LENGTH_SHORT).show();});}
  }
}
