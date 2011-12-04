
package com.traceper.android.services;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.security.spec.EncodedKeySpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import javax.xml.parsers.FactoryConfigurationError;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.json.JSONException;
import org.json.JSONObject;
import org.xml.sax.SAXException;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.Toast;

import com.traceper.R;
import com.traceper.android.CameraController;
import com.traceper.android.Configuration;
import com.traceper.android.interfaces.IAppService;
import com.traceper.android.tools.XMLHandler;

public class AppService extends Service implements IAppService{

	private ConnectivityManager conManager = null; 
	private LocationManager locationManager = null;
	private String deviceId;
	private boolean isUserAuthenticated = false;

	private NotificationManager mManager;
	private static int NOTIFICATION_ID = 0;

	/**
	 * this list stores the locations couldnt be sent to server due to lack of network connectivity
	 */
	private ArrayList<Location> pendingLocations = new ArrayList<Location>();

	private final IBinder mBinder = new IMBinder();

	//	private NotificationManager mNM;
	private String email;
	private String password;
	private String authenticationServerAddress;
	private Long lastLocationSentTime;

	private LocationHandler locationHandler;
	private int minDataSentInterval = Configuration.MIN_GPS_DATA_SEND_INTERVAL;
	private int minDistanceInterval = Configuration.MIN_GPS_DISTANCE_INTERVAL;
	//	private XMLHandler xmlHandler;
	private BroadcastReceiver networkStateReceiver;
//	private boolean gps_enabled = false;
//	private boolean network_enabled = false;
	private String cookie = null;
	private boolean configurationChanged = false;


	public class IMBinder extends Binder {
		public IAppService getService() {
			return AppService.this;
		}		
	}

	public void onCreate() 
	{   	
		conManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
		locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
		deviceId = ((TelephonyManager) getSystemService(TELEPHONY_SERVICE)).getDeviceId();
		//		xmlHandler = new XMLHandler();
		locationHandler = new LocationHandler();

		networkStateReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				// when connection comes, send pending locations
				if (isNetworkConnected() == true) {
					sendPendingLocations();
				}
			}
		};

		IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);        
		registerReceiver(networkStateReceiver, filter);
	}

	public void setAutoCheckin(boolean enable){
		sendLocation(enable, locationHandler, minDataSentInterval, minDistanceInterval);
	}

	public void sendLocationNow(boolean enable){
		sendLocation(enable, locationOneTime, 0, 0);
		
	}
	
	public void sendLocation(boolean enable, final LocationListener locationListener, final int datasentInterval, final int distanceInterval) {

		if (enable == true) {

			mManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

			final Notification notification = new Notification(R.drawable.icon, getString(R.string.ApplicationName), System.currentTimeMillis());

			final PendingIntent contentIntent = PendingIntent.getActivity(getApplicationContext(), 0, null, 0);

			notification.setLatestEventInfo(AppService.this,
					getString(R.string.ApplicationName), getString(R.string.waiting_location), contentIntent);	

			mManager.notify(NOTIFICATION_ID , notification);

			locationManager.removeUpdates(locationListener);
			boolean gps_enabled = false;
			try {
				gps_enabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
				if (gps_enabled == true) {
					Thread locationUpdates = new Thread() {
						public void run() {
							Looper.prepare();

							locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, datasentInterval, distanceInterval, 
									locationListener);				       

							Looper.loop();
						}
					};
					locationUpdates.start();

				}
			} catch (Exception ex) {
				
			}
			
			boolean network_enabled = false;
			try {
				network_enabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
				if (gps_enabled == false && network_enabled == true) {
					Thread locationUpdates = new Thread() {
						public void run() {
							Looper.prepare();

							locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, datasentInterval, distanceInterval, 
									locationListener);				       

							Looper.loop();
						}
					};
					locationUpdates.start();
				}				
				
			} catch (Exception e) {
				
			}
			if (network_enabled == false && gps_enabled == false){
				notification.setLatestEventInfo(AppService.this,
						getString(R.string.ApplicationName), getString(R.string.no_location_provider), contentIntent);	

				mManager.notify(NOTIFICATION_ID , notification);
			}
		}

	}



	private void sendPendingLocations(){
		Iterator<Location> iterator = pendingLocations.iterator();
		while (iterator.hasNext()) {
			Location location = (Location) iterator.next();
			String result = sendLocationDataAndParseResult(location);
			if (result.equals("1")) {
				iterator.remove();
			}
		}
	}

	public IBinder onBind(Intent intent) 
	{
		return mBinder;
	}

	//TODO: edit the traceper protocol file
	private String sendLocationData(String emailText, String passwordText, Location loc) 
	{		
		double latitude = 0;
		double longitude = 0;
		double altitude = 0;
		if (loc != null) {
			latitude = loc.getLatitude();
			longitude = loc.getLongitude();
			altitude = loc.getLongitude();
		}
		String[] name = new String[8];
		String[] value = new String[8];
		name[0] = "r";
		name[1] = "email";
		name[2] = "password";
		name[3] = "latitude";
		name[4] = "longitude";
		name[5] = "altitude";
		name[6] = "deviceId";
		name[7] = "time";

		value[0] = "users/takeMyLocation";
		value[1] = emailText;
		value[2] = passwordText;
		value[3] = String.valueOf(latitude);
		value[4] = String.valueOf(longitude);
		value[5] = String.valueOf(altitude);
		value[6] = this.deviceId;
		value[7] = String.valueOf((int)(loc.getTime()/1000)); // convert milliseconds to seconds

		String httpRes = this.sendHttpRequest(name, value, null, null);

		String result = getString(R.string.unknown_error_occured);

		try {
			JSONObject jsonObject = new JSONObject(httpRes);
			result = jsonObject.getString("result");
			if (result.equals("1")) 
			{		
				int dataSentInterval = Integer.parseInt(jsonObject.getString("minDataSentInterval"));
				int distanceInterval = Integer.parseInt(jsonObject.getString("minDistanceInterval"));
				if (dataSentInterval != this.minDataSentInterval || distanceInterval != this.minDistanceInterval){
					this.configurationChanged  = true;
					this.minDataSentInterval = dataSentInterval;
					this.minDistanceInterval = distanceInterval;
				}

				lastLocationSentTime = System.currentTimeMillis();
				Intent i = new Intent(IAppService.LAST_LOCATION_DATA_SENT_TIME);
				i.setAction(IAppService.LAST_LOCATION_DATA_SENT_TIME);
				i.putExtra(IAppService.LAST_LOCATION_DATA_SENT_TIME, lastLocationSentTime);
				i.putExtra(IAppService.LAST_LOCATION, loc);
				sendBroadcast(i);
				Log.i("broadcast sent", "sendLocationData broadcast sent");		

			}

		} catch (JSONException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}


		return result;	
	}

	public String sendImage(byte[] image, boolean publicData)
	{
		Location locationGPS = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
		Location locationNetwork = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
		Location loc = null;
		double latitude = 0;
		double longitude = 0;
		double altitude = 0;
		if (locationGPS == null && locationNetwork != null) {
			loc = locationNetwork;
		}
		else if (locationGPS != null && locationNetwork == null) {
			loc = locationGPS;
		}
		else if (locationGPS != null && locationNetwork != null) {
			if (locationGPS.getTime() > locationNetwork.getTime()) {
				loc = locationGPS;
			}
			else {
				loc = locationNetwork;
			}
		}
		if (loc != null) {
			latitude = loc.getLatitude();
			longitude = loc.getLongitude();
			altitude = loc.getLongitude();
		}
		String params;
		//		try {
		String[] name = new String[7];
		String[] value = new String[7];
		name[0] = "r";
		name[1] = "email";
		name[2] = "password";
		name[3] = "latitude";
		name[4] = "longitude";
		name[5] = "altitude";
		name[6] = "publicData";

		value[0] = "image/upload";
		value[1] = this.email;
		value[2] = this.password;
		value[3] = String.valueOf(latitude);
		value[4] = String.valueOf(longitude);
		value[5] = String.valueOf(altitude);
		int publicDataInt = 0;
		if (publicData == true) {
			publicDataInt = 1; 
		} 
		value[6] = String.valueOf(publicDataInt);


		String img = new String(image);
		String httpRes = this.sendHttpRequest(name, value, "image", image);
		Log.i("img length: ", String.valueOf(img.length()) );
		String result = getString(R.string.unknown_error_occured);

		try {
			JSONObject jsonObject = new JSONObject(httpRes);
			result = jsonObject.getString("result");
		} catch (JSONException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		//		int result = this.evaluateResult(httpRes);

		return result;		
	}

	public boolean isNetworkConnected() {
		boolean connected = false;
		NetworkInfo networkInfo = conManager.getActiveNetworkInfo();
		if (networkInfo != null) {
			connected = networkInfo.isConnected();
		}		
		return connected; 
	}

	public void onDestroy() {
		Log.i("Traceper-AppService is being destroyed", "...");
		locationManager.removeUpdates(locationHandler);
		unregisterReceiver(networkStateReceiver);
		super.onDestroy();
	}

	private String sendHttpRequest(String[] name, String[] value, String filename, byte[] file){
		final String end = "\r\n";
		final String twoHyphens = "--";
		final String boundary = "*****++++++************++++++++++++";
		URL url;
		String result = new String();
		try {
			url = new URL(this.authenticationServerAddress);
			HttpURLConnection conn = (HttpURLConnection)url.openConnection();
			conn.setDoInput(true);
			conn.setDoOutput(true);
			conn.setUseCaches(false);
			conn.setRequestMethod("POST");

			conn.setRequestProperty("Connection", "Keep-Alive");
			conn.setRequestProperty("Charset", "UTF-8");
			conn.setRequestProperty("Content-Type", "multipart/form-data;boundary="+ boundary);
			if (cookie != null) {
				conn.setRequestProperty("Cookie", cookie);
			}

			DataOutputStream ds = new DataOutputStream(conn.getOutputStream());

			for (int i = 0; i < value.length; i++) {
				ds.writeBytes(twoHyphens + boundary + end);
				ds.writeBytes("Content-Disposition: form-data; name=\""+ name[i] +"\""+end+end+ value[i] +end);
			}
			if (filename != null && file != null){
				ds.writeBytes(twoHyphens + boundary + end);
				ds.writeBytes("Content-Disposition: form-data; name=\"image\";filename=\"" + filename +"\"" + end + end);
				ds.write(file);
				ds.writeBytes(end);
			}			
			ds.writeBytes(twoHyphens + boundary + twoHyphens + end);
			ds.flush();
			ds.close();

			cookie  = conn.getHeaderField("set-cookie");
			if (conn.getResponseCode() == HttpURLConnection.HTTP_MOVED_PERM ||
					conn.getResponseCode() == HttpURLConnection.HTTP_MOVED_TEMP)
			{
				conn.disconnect();
				this.authenticationServerAddress += "/";
				return sendHttpRequest(name, value, null, null);				
			}
			else
			{
				BufferedReader in = new BufferedReader(
						new InputStreamReader(conn.getInputStream()));
				String inputLine;

				while ((inputLine = in.readLine()) != null) {
					result = result.concat(inputLine);				
				}
				in.close();	

			}

		} catch (MalformedURLException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}

		if (result.length() >= 0){
			return result;
		}
		return null;		
	}

	public void exit() {
		this.stopSelf();	
	}

	public String getUsername() {		
		return this.email;
	}

	public boolean isUserAuthenticated() {
		return this.isUserAuthenticated;
	}

	public String registerUser(String password, String email, String realname) 
	{
		String[] name = new String[6];
		String[] value = new String[6];
		name[0] = "r";
		name[1] = "RegisterForm[email]";
		name[2] = "RegisterForm[password]";
		name[3] = "RegisterForm[passwordAgain]";
		name[4] = "RegisterForm[name]";
		name[5] = "client";

		value[0] = "site/register";
		value[1] = email;
		value[2] = password;
		value[3] = password;
		value[4] = realname;
		value[5] = "mobile";

		String httpRes = this.sendHttpRequest(name, value, null, null);	

		String result = getString(R.string.unknown_error_occured);

		try {
			JSONObject jsonObject = new JSONObject(httpRes);
			result = jsonObject.getString("result");

		} catch (JSONException e) {
			e.printStackTrace();
		}

		return result;
	}


	public String authenticateUser(String email, String password) 
	{			
		this.password = password;
		this.email = email;

		String[] name = new String[6];
		String[] value = new String[6];
		/*		
		name[0] = "action";
		name[1] = "email";
		name[2] = "password";
		name[3] = "deviceId";

		value[0] = HTTP_ACTION_AUTHENTICATE_ME;
		value[1] = this.email;
		value[2] = this.password;
		value[3] = this.deviceId;
		 */
		name[0] = "r";
		name[1] = "LoginForm[email]";
		name[2] = "LoginForm[password]";
		name[3] = "deviceId";
		name[4] = "LoginForm[rememberMe]";
		name[5] = "client";

		value[0] = "site/login";
		value[1] = this.email;
		value[2] = this.password;
		value[3] = this.deviceId;
		value[4] = "1";
		value[5] = "mobile";

		String httpRes = this.sendHttpRequest(name, value, null, null);

		//		String result = this.evaluateResult(httpRes); // this.sendLocationData(this.email, this.password, locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER));	
		String result = getString(R.string.unknown_error_occured);

		try {
			JSONObject jsonObject = new JSONObject(httpRes);
			result = jsonObject.getString("result");
			if (result.equals("1")) 
			{			
				this.isUserAuthenticated = true;
				this.minDataSentInterval = Integer.parseInt(jsonObject.getString("minDataSentInterval"));
				this.minDistanceInterval = Integer.parseInt(jsonObject.getString("minDistanceInterval")); 
			}
			else {
				this.isUserAuthenticated = false;
			}
		} catch (JSONException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return result;
	}	

	private int evaluateResult(String result)
	{
		int iresult = HTTP_RESPONSE_ERROR_UNKNOWN_RESPONSE;

		JSONObject jsonObject;
		try {
			jsonObject = new JSONObject(result);
			String operationResult = jsonObject.getString("result");
			if (operationResult.equals("1")){
				iresult = HTTP_RESPONSE_SUCCESS;
			}
		} catch (JSONException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}


		/*
		int iresult = HTTP_RESPONSE_ERROR_UNKNOWN_RESPONSE;
		if (result == null){
			iresult = HTTP_REQUEST_FAILED;
		}
		else {
			SAXParser sp;
			try {
				sp = SAXParserFactory.newInstance().newSAXParser();
				sp.parse(new ByteArrayInputStream(result.getBytes()), xmlHandler);
			} catch (ParserConfigurationException e) {
				e.printStackTrace();
			} catch (SAXException e) {
				e.printStackTrace();
			} catch (FactoryConfigurationError e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}		

			iresult = xmlHandler.getActionResult();
			switch (iresult)
			{
			case HTTP_RESPONSE_SUCCESS:
				Log.i("HTTP_RESPONSE", "successfull.");
				break;
			case HTTP_REQUEST_FAILED:
				Log.w("HTTP_RESPONSE", "failed: http request failed.");
				break;
			case HTTP_RESPONSE_ERROR_MISSING_PARAMETER:
				Log.w("HTTP_RESPONSE", "failed: http request failed.");
				break;
			case HTTP_RESPONSE_ERROR_UNAUTHORIZED_ACCESS:
				Log.w("HTTP_RESPONSE", "failed: unauthorized access");				
				break;
			case HTTP_RESPONSE_ERROR_UNKNOWN:
				Log.w("HTTP_RESPONSE", "failed: unknown error");
				break;
			case HTTP_RESPONSE_ERROR_UNSUPPORTED_ACTION:
				Log.w("HTTP_RESPONSE", "failed: unsupported action");
				break;
			case HTTP_RESPONSE_ERROR_EMAIL_EXISTS:
				Log.w("HTTP_RESPONSE", "failed registration: email already exists");
				break;
			case HTTP_RESPONSE_ERROR_EMAIL_NOT_VALID:
				Log.w("HTTP_RESPONSE", "failed registration: email is not valid");
				break;
			default:
				iresult = HTTP_RESPONSE_ERROR_UNKNOWN_RESPONSE;
				Log.w("HTTP_RESPONSE", "failed: unknown response returned from server");
				break;
			}			
		}
		 */

		return iresult;
	}

	public void CancelRegularUpdate(){
		locationManager.removeUpdates(locationHandler);
	}

	public void setAuthenticationServerAddress(String address) {
		this.authenticationServerAddress = address;
	}

	public Long getLastLocationSentTime() {
		return lastLocationSentTime;
	}

	private String sendLocationDataAndParseResult(Location loc) {
		String result = AppService.this.sendLocationData(AppService.this.email, AppService.this.password, loc);	

		//		int dataSentInterval = AppService.this.xmlHandler.getGpsMinDataSentInterval();
		//		int distanceInterval = AppService.this.xmlHandler.getGpsMinDistanceInterval();

		// if configuration is changed in server, then arrange itself below by
		// adding using new params in locationManager. requestLocationUpdates
		//		if (dataSentInterval != AppService.this.minDataSentInterval ||
		//				distanceInterval != AppService.this.minDistanceInterval)
		if (configurationChanged == true)
		{
			configurationChanged = false;
			locationManager.removeUpdates(locationHandler);
			Thread locationUpdates = new Thread() {
				public void run() {
					Looper.prepare();

					locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 
							minDataSentInterval, 
							minDistanceInterval, 
							locationHandler);			       

					Looper.loop();
				}

			};		      

			locationUpdates.start();
		}
		return result;
	}

	private class LocationHandler implements LocationListener{
		public void onLocationChanged(Location loc){	
			if (loc != null) {

				Log.i("location listener", "onLocationChanged");
				boolean connected = isNetworkConnected();
				String result = null;
				if (connected == true) {
					mManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

					Notification notification = new Notification(R.drawable.icon, getString(R.string.ApplicationName), System.currentTimeMillis());

					PendingIntent contentIntent = PendingIntent.getActivity(getApplicationContext(), 0, null, 0);

					notification.setLatestEventInfo(getApplicationContext(), getString(R.string.ApplicationName), getString(R.string.sending_location_data), contentIntent);
					mManager.notify(NOTIFICATION_ID, notification);
					// send pending locations if any...
					sendPendingLocations();
					// send last location data
					result = sendLocationDataAndParseResult(loc);	
					String processResult = getString(R.string.sending_location_data_failed);
					if (result.equals("1")) {
						processResult = getString(R.string.sending_location_data_successfull);
					}
					notification.setLatestEventInfo(getApplicationContext(), getString(R.string.ApplicationName), processResult, contentIntent);
					mManager.notify(NOTIFICATION_ID, notification);
				}
				if (connected == false || result.equals("1") == false){
					pendingLocations.add(loc);
				}

			}
		}
		public void onProviderDisabled(String provider){
			Log.i("location listener", "onProviderDisabled");	
		}
		public void onProviderEnabled(String provider){					
			Log.i("location listener", "onProviderEnabled");	
		}
		public void onStatusChanged(String provider, int status, Bundle extras){															
			Log.i("location listener", "onProviderEnabled");	
		}	

	}

	LocationListener locationOneTime = new LocationHandler(){

		public void onLocationChanged(Location loc) {
			super.onLocationChanged(loc);
			locationManager.removeUpdates(locationOneTime);	

		};

	};
	/*
	LocationListener locationOneTime = new LocationListener() {
		public void onLocationChanged(Location location) {

			if (location != null) {

				Log.i("location listener", "onLocationChanged");
				boolean connected = isNetworkConnected();
				String result = null;
				if (connected == true) {
					// send pending locations if any...
					sendPendingLocations();
					// send last location data
					result = sendLocationDataAndParseResult(location);	
					// remove location update



					mManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

					final Notification notification = new Notification(R.drawable.icon, getString(R.string.ApplicationName), System.currentTimeMillis());

					final PendingIntent contentIntent = PendingIntent.getActivity(getApplicationContext(), 0, null, 0);


					notification.setLatestEventInfo(getApplicationContext(), getString(R.string.ApplicationName), getString(R.string.sent_succes), contentIntent);
					mManager.notify(NOTIFICATION_ID, notification);

				}
				if (connected == false || result.equals("1") == false){
					pendingLocations.add(location);
				}

			}


		}
		public void onProviderDisabled(String provider) {}
		public void onProviderEnabled(String provider) {}
		public void onStatusChanged(String provider, int status, Bundle extras) {}
	};
	 */
}
