package com.adobe.phonegap.push;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.os.Bundle;
import android.util.Log;

import com.google.android.gms.gcm.GcmPubSub;
import com.google.android.gms.iid.InstanceID;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.*;

import me.leolin.shortcutbadger.ShortcutBadger;

public class PushPlugin extends CordovaPlugin implements PushConstants {

    public static final String LOG_TAG = "PushPlugin";

    private static CallbackContext pushContext;
    private static CordovaWebView gWebView;
    private static Bundle gCachedExtras = null;
    private static boolean gForeground = false;

    /**
     * Gets the application context from cordova's main activity.
     * @return the application context
     */
    private Context getApplicationContext() {
		return this.cordova.getActivity().getApplicationContext();
    }

    @Override
    public boolean execute(final String action, final JSONArray data, final CallbackContext callbackContext) {
        Log.v(LOG_TAG, "execute: action=" + action);
        gWebView = this.webView;

        if (INITIALIZE.equals(action)) {
            cordova.getThreadPool().execute(new Runnable() {
                public void run() {
                    pushContext = callbackContext;
                    JSONObject jo = null;

                    Log.v(LOG_TAG, "execute: data=" + data.toString());
                    SharedPreferences sharedPref = getApplicationContext().getSharedPreferences(COM_ADOBE_PHONEGAP_PUSH, Context.MODE_PRIVATE);
                    String token = null;
                    String senderID = null;

                    try {
                        jo = data.getJSONObject(0).getJSONObject(ANDROID);

                        Log.v(LOG_TAG, "execute: jo=" + jo.toString());

                        senderID = jo.getString(SENDER_ID);

                        Log.v(LOG_TAG, "execute: senderID=" + senderID);

                        String savedSenderID = sharedPref.getString(SENDER_ID, "");
                        String savedRegID = sharedPref.getString(REGISTRATION_ID, "");
						
						/*Added by AYOTTA team 
						Reason : To initialize badge count to zero on first install */
						SharedPreferences settings = getApplicationContext().getSharedPreferences(PushPlugin.COM_ADOBE_PHONEGAP_PUSH, Context.MODE_PRIVATE);
						if(!settings.contains("badge"))
						{
							SharedPreferences.Editor editor = getApplicationContext().getSharedPreferences(PushPlugin.COM_ADOBE_PHONEGAP_PUSH, Context.MODE_PRIVATE).edit();
							setApplicationIconBadgeNumber(getApplicationContext(), 0);
							editor.putInt("badge", 0);
							editor.apply();
						}

                        //Added by AYOTTA team
						
                        // first time run get new token
                        if ("".equals(savedRegID)) {
                            token = InstanceID.getInstance(getApplicationContext()).getToken(senderID, GCM);
                        }
                        // new sender ID, re-register
                        else if (!savedSenderID.equals(senderID)) {
                            token = InstanceID.getInstance(getApplicationContext()).getToken(senderID, GCM);
                        }
                        // use the saved one
                        else {
                            token = sharedPref.getString(REGISTRATION_ID, "");
                        }

                        if (!"".equals(token)) {
                            JSONObject json = new JSONObject().put(REGISTRATION_ID, token);

                            Log.v(LOG_TAG, "onRegistered: " + json.toString());

                            JSONArray topics = jo.optJSONArray(TOPICS);
                            subscribeToTopics(topics, token);

                            PushPlugin.sendEvent( json );
                        } else {
                            callbackContext.error("Empty registration ID received from GCM");
                            return;
                        }
                    } catch (JSONException e) {
                        Log.e(LOG_TAG, "execute: Got JSON Exception " + e.getMessage());
                        callbackContext.error(e.getMessage());
                    } catch (IOException e) {
                        Log.e(LOG_TAG, "execute: Got JSON Exception " + e.getMessage());
                        callbackContext.error(e.getMessage());
                    }

                    if (jo != null) {
                        SharedPreferences.Editor editor = sharedPref.edit();
                        try {
                            editor.putString(ICON, jo.getString(ICON));
                        } catch (JSONException e) {
                            Log.d(LOG_TAG, "no icon option");
                        }
                        try {
                            editor.putString(ICON_COLOR, jo.getString(ICON_COLOR));
                        } catch (JSONException e) {
                            Log.d(LOG_TAG, "no iconColor option");
                        }
                        editor.putBoolean(SOUND, jo.optBoolean(SOUND, true));
                        editor.putBoolean(VIBRATE, jo.optBoolean(VIBRATE, true));
                        editor.putBoolean(CLEAR_NOTIFICATIONS, jo.optBoolean(CLEAR_NOTIFICATIONS, true));
                        editor.putBoolean(FORCE_SHOW, jo.optBoolean(FORCE_SHOW, false));
                        editor.putString(SENDER_ID, senderID);
                        editor.putString(REGISTRATION_ID, token);
                        editor.commit();
                    }

                    if (gCachedExtras != null) {
                        Log.v(LOG_TAG, "sending cached extras");
                        sendExtras(gCachedExtras);
                        gCachedExtras = null;
                    }
                }
            });
        } else if (UNREGISTER.equals(action)) {
            cordova.getThreadPool().execute(new Runnable() {
                public void run() {
                    try {
                        SharedPreferences sharedPref = getApplicationContext().getSharedPreferences(COM_ADOBE_PHONEGAP_PUSH, Context.MODE_PRIVATE);
                        String token = sharedPref.getString(REGISTRATION_ID, "");
                        JSONArray topics = data.optJSONArray(0);
                        if (topics != null && !"".equals(token)) {
                            unsubscribeFromTopics(topics, token);
                        } else {
                            InstanceID.getInstance(getApplicationContext()).deleteInstanceID();
                            Log.v(LOG_TAG, "UNREGISTER");

                            // Remove shared prefs
                            SharedPreferences.Editor editor = sharedPref.edit();
                            editor.remove(SOUND);
                            editor.remove(VIBRATE);
                            editor.remove(CLEAR_NOTIFICATIONS);
                            editor.remove(FORCE_SHOW);
                            editor.remove(SENDER_ID);
                            editor.remove(REGISTRATION_ID);
                            editor.commit();
                        }

                        callbackContext.success();
                    } catch (IOException e) {
                        Log.e(LOG_TAG, "execute: Got JSON Exception " + e.getMessage());
                        callbackContext.error(e.getMessage());
                }
            }
            });
        } else if (FINISH.equals(action)) {
            callbackContext.success();
        } else if (HAS_PERMISSION.equals(action)) {
            cordova.getThreadPool().execute(new Runnable() {
                public void run() {
                    JSONObject jo = new JSONObject();
                    try {
                        jo.put("isEnabled", PermissionUtils.hasPermission(getApplicationContext(), "OP_POST_NOTIFICATION"));
                        PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, jo);
                        pluginResult.setKeepCallback(true);
                        callbackContext.sendPluginResult(pluginResult);
                    } catch (UnknownError e) {
                        callbackContext.error(e.getMessage());
                    } catch (JSONException e) {
                        callbackContext.error(e.getMessage());
                    }
                }
            });
        } else if (SET_APPLICATION_ICON_BADGE_NUMBER.equals(action)) {
            cordova.getThreadPool().execute(new Runnable() {
                public void run() {
                    Log.v(LOG_TAG, "setApplicationIconBadgeNumber: data=" + data.toString());
                    try {
                        setApplicationIconBadgeNumber(getApplicationContext(), data.getJSONObject(0).getInt(BADGE));
                    } catch (JSONException e) {
                        callbackContext.error(e.getMessage());
                    }
                    callbackContext.success();
                }
            });
        } else if (CLEAR_ALL_NOTIFICATIONS.equals(action)) {
            cordova.getThreadPool().execute(new Runnable() {
                public void run() {
                    Log.v(LOG_TAG, "clearAllNotifications");
                    clearAllNotifications();
                    callbackContext.success();
                }
            });
        } else {
            Log.e(LOG_TAG, "Invalid action : " + action);
            callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.INVALID_ACTION));
            return false;
        }

        return true;
    }

    public static void sendEvent(JSONObject _json) {
        PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, _json);
        pluginResult.setKeepCallback(true);
        if (pushContext != null) {
            pushContext.sendPluginResult(pluginResult);
        }
    }

    public static void sendError(String message) {
        PluginResult pluginResult = new PluginResult(PluginResult.Status.ERROR, message);
        pluginResult.setKeepCallback(true);
        if (pushContext != null) {
            pushContext.sendPluginResult(pluginResult);
        }
    }

    /*
     * Sends the pushbundle extras to the client application.
     * If the client application isn't currently active, it is cached for later processing.
     */
    public static void sendExtras(Bundle extras) {
        if (extras != null) {
            if (gWebView != null) {
                sendEvent(convertBundleToJson(extras));
            } else {
                Log.v(LOG_TAG, "sendExtras: caching extras to send at a later time.");
                gCachedExtras = extras;
            }
        }
    }

    public static void setApplicationIconBadgeNumber(Context context, int badgeCount) {
		
		/*Added by AYOTTA team 
		Reason : To save the value when called by app directly  */
		SharedPreferences.Editor editor = context.getSharedPreferences(PushPlugin.COM_ADOBE_PHONEGAP_PUSH, Context.MODE_PRIVATE).edit();
		editor.putInt("badge", badgeCount);
		editor.apply();
		//Added by AYOTTA team
		
        if (badgeCount > 0) {
            ShortcutBadger.applyCount(context, badgeCount);
        } else {
            ShortcutBadger.removeCount(context);
        }
    }

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
        gForeground = true;
    }

    @Override
    public void onPause(boolean multitasking) {
        super.onPause(multitasking);
        gForeground = false;

        SharedPreferences prefs = getApplicationContext().getSharedPreferences(COM_ADOBE_PHONEGAP_PUSH, Context.MODE_PRIVATE);
        if (prefs.getBoolean(CLEAR_NOTIFICATIONS, true)) {
            clearAllNotifications();
        }
    }

    @Override
    public void onResume(boolean multitasking) {
        super.onResume(multitasking);
        gForeground = true;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        gForeground = false;
        gWebView = null;
    }

    private void clearAllNotifications() {
        final NotificationManager notificationManager = (NotificationManager) cordova.getActivity().getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancelAll();
    }

    private void subscribeToTopics(JSONArray topics, String registrationToken) {
        if (topics != null) {
            String topic = null;
            for (int i=0; i<topics.length(); i++) {
                try {
                    topic = topics.optString(i, null);
                    if (topic != null) {
                        Log.d(LOG_TAG, "Subscribing to topic: " + topic);
                        GcmPubSub.getInstance(getApplicationContext()).subscribe(registrationToken, "/topics/" + topic, null);
                    }
                } catch (IOException e) {
                    Log.e(LOG_TAG, "Failed to subscribe to topic: " + topic, e);
                }
            }
        }
    }

    private void unsubscribeFromTopics(JSONArray topics, String registrationToken) {
        if (topics != null) {
            String topic = null;
            for (int i=0; i<topics.length(); i++) {
                try {
                    topic = topics.optString(i, null);
                    if (topic != null) {
                        Log.d(LOG_TAG, "Unsubscribing to topic: " + topic);
                        GcmPubSub.getInstance(getApplicationContext()).unsubscribe(registrationToken, "/topics/" + topic);
                    }
                } catch (IOException e) {
                    Log.e(LOG_TAG, "Failed to unsubscribe to topic: " + topic, e);
                }
            }
        }
    }

    /*
     * serializes a bundle to JSON.
     */
    private static JSONObject convertBundleToJson(Bundle extras) {
        Log.d(LOG_TAG, "convert extras to json");
        try {
            JSONObject json = new JSONObject();
            JSONObject additionalData = new JSONObject();

            // Add any keys that need to be in top level json to this set
            HashSet<String> jsonKeySet = new HashSet();
            Collections.addAll(jsonKeySet, TITLE,MESSAGE,COUNT,SOUND,IMAGE);

            Iterator<String> it = extras.keySet().iterator();
            while (it.hasNext()) {
                String key = it.next();
                Object value = extras.get(key);

                Log.d(LOG_TAG, "key = " + key);

                if (jsonKeySet.contains(key)) {
                    json.put(key, value);
                }
                else if (key.equals(COLDSTART)) {
                    additionalData.put(key, extras.getBoolean(COLDSTART));
                }
                else if (key.equals(FOREGROUND)) {
                    additionalData.put(key, extras.getBoolean(FOREGROUND));
                }
                else if ( value instanceof String ) {
                    String strValue = (String)value;
                    try {
                        // Try to figure out if the value is another JSON object
                        if (strValue.startsWith("{")) {
                            additionalData.put(key, new JSONObject(strValue));
                        }
                        // Try to figure out if the value is another JSON array
                        else if (strValue.startsWith("[")) {
                            additionalData.put(key, new JSONArray(strValue));
                        }
                        else {
                            additionalData.put(key, value);
                        }
                    } catch (Exception e) {
                        additionalData.put(key, value);
                    }
                }
            } // while

            json.put(ADDITIONAL_DATA, additionalData);
            Log.v(LOG_TAG, "extrasToJSON: " + json.toString());

             return json;
        }
        catch( JSONException e) {
            Log.e(LOG_TAG, "extrasToJSON: JSON exception");
        }
        return null;
    }
	
	/*Added by AYOTTA team
	Reason : Overriding existing function for different return type */
	public static void convertBundleToJsonDB(Context context, Bundle extras) {
        Log.d(LOG_TAG, "convert extras to json");
        try {
            JSONObject json = new JSONObject();
            JSONObject additionalData = new JSONObject();

            // Add any keys that need to be in top level json to this set
            HashSet<String> jsonKeySet = new HashSet();
            Collections.addAll(jsonKeySet, TITLE,MESSAGE,COUNT,SOUND,IMAGE);

            Iterator<String> it = extras.keySet().iterator();
            while (it.hasNext()) {
                String key = it.next();
                Object value = extras.get(key);

                Log.d(LOG_TAG, "key = " + key);

                if (jsonKeySet.contains(key)) {
                    json.put(key, value);
                }
                else if (key.equals(COLDSTART)) {
                    additionalData.put(key, extras.getBoolean(COLDSTART));
                }
                else if (key.equals(FOREGROUND)) {
                    additionalData.put(key, extras.getBoolean(FOREGROUND));
                }
                else if ( value instanceof String ) {
                    String strValue = (String)value;
                    try {
                        // Try to figure out if the value is another JSON object
                        if (strValue.startsWith("{")) {
                            additionalData.put(key, new JSONObject(strValue));
                        }
                        // Try to figure out if the value is another JSON array
                        else if (strValue.startsWith("[")) {
                            additionalData.put(key, new JSONArray(strValue));
                        }
                        else {
                            additionalData.put(key, value);
                        }
                    } catch (Exception e) {
                        additionalData.put(key, value);
                    }
                }
            } // while

            json.put(ADDITIONAL_DATA, additionalData);
            Log.v(LOG_TAG, "extrasToJSON: " + json.toString());

            //Added by AYOTTA team
			if(json.has(TITLE) && json.has(MESSAGE))
			{
				 insertNotificationToDb(context,json);
			}
			else
			{
				insertUpdateToDb(json);
			}
			
            

            
        }
        catch( JSONException e) {
            Log.e(LOG_TAG, "extrasToJSON: JSON exception");
        }
        
    }
	//Added by AYOTTA team

    /*Added by AYOTTA team
	Reason : To save notification to db  */
    private static void insertUpdateToDb(JSONObject json)
    {
       try {
		   
		    Log.d(LOG_TAG, "DB Start");
			File dbFile = new File("/data/user/0/pes.pesu/databases/PESU.db");
            Log.d(LOG_TAG, "dbFile : " + dbFile);
			SQLiteDatabase myDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null);
            Log.d(LOG_TAG, "myDb : " + myDb);

            //Create

            SQLiteStatement myStatement = myDb.compileStatement("CREATE TABLE IF NOT EXISTS UPDATES (id integer primary key, keyWord text, updateUrl text, isDone integer)");
            Log.d(LOG_TAG, "myStatement : " + myStatement);
            myStatement.execute();
			
			//Select
			
			JSONObject additionalData = (JSONObject) json.get(ADDITIONAL_DATA);

            Cursor cur = null;
            int rowCount = 0;
            cur = myDb.rawQuery("SELECT * FROM UPDATES WHERE keyWord = \""+additionalData.get("keyWord")+"\"",new String[0]);
            Log.d(LOG_TAG, "cur : " + cur);

            if (cur != null && cur.moveToFirst()) {

                rowCount = cur.getCount();
                Log.d(LOG_TAG, "if already exist ?? ...count : " + rowCount);

            }
			
			if(rowCount == 0)
			{
				cur = myDb.rawQuery("SELECT * FROM UPDATES",new String[0]);
				Log.d(LOG_TAG, "cur : " + cur);

				if (cur != null && cur.moveToFirst()) {

					rowCount = cur.getCount();
					Log.d(LOG_TAG, "Doesn't exist ?? ...count : " + rowCount);

				}
				
				rowCount++;
			
				
				
				//Insert
				
				myStatement = myDb.compileStatement("INSERT INTO UPDATES (id,keyWord,updateUrl,isDone) VALUES ("+rowCount+",\""+additionalData.get("keyWord")+"\",\""+additionalData.get("updateUrl")+"\",0)");
				Log.d(LOG_TAG, "myStatement : " + myStatement);
				long insertId = -1; // (invalid)
				insertId = myStatement.executeInsert();
				Log.d(LOG_TAG, "insertId2 : " + insertId);
			}
			
			else
			{
				//Update
				
				myStatement = myDb.compileStatement("UPDATE UPDATES SET isDone = 0 WHERE keyWord  = \""+additionalData.get("keyWord")+"\"");
				Log.d(LOG_TAG, "myStatement : " + myStatement);
				myStatement.execute();
			}

            
		  

        } catch (Exception ex) {
            // report error result with the error message
            // could be constraint violation or some other error
            ex.printStackTrace();
            String errorMessage = ex.getMessage();
            Log.d(LOG_TAG, "Error=" + errorMessage);
        }
    }
    //Added by AYOTTA team
	
	/*Added by AYOTTA team
	Reason : To save notification to db  */
    private static void insertNotificationToDb(Context context, JSONObject json)
    {
       try {
		   
		   /*Added by AYOTTA team 
			Reason : To increase the badge count every time a notification is received  */
			SharedPreferences settings = context.getSharedPreferences(PushPlugin.COM_ADOBE_PHONEGAP_PUSH, Context.MODE_PRIVATE);
			int badge = settings.getInt("badge", 0);
			badge++;
			setApplicationIconBadgeNumber(context, badge);
			//Added by AYOTTA team 

            Log.d(LOG_TAG, "DB Start");
			File dbFile = new File("/data/user/0/pes.pesu/databases/PESU.db");
            Log.d(LOG_TAG, "dbFile : " + dbFile);
			SQLiteDatabase myDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null);
            Log.d(LOG_TAG, "myDb : " + myDb);

            //Create

            SQLiteStatement myStatement = myDb.compileStatement("CREATE TABLE IF NOT EXISTS NOTIFICATION (id integer primary key, notId integer, title text, message text, date text, time text, type int, button text, response text, responseId text)");
            Log.d(LOG_TAG, "myStatement : " + myStatement);
            myStatement.execute();

            //Select

            Cursor cur = null;
            int rowCount = 0;
            cur = myDb.rawQuery("SELECT * FROM NOTIFICATION",new String[0]);
            Log.d(LOG_TAG, "cur : " + cur);

            if (cur != null && cur.moveToFirst()) {

                rowCount = cur.getCount();
                Log.d(LOG_TAG, "count : " + rowCount);

            }

            rowCount++;

            JSONObject additionalData = (JSONObject) json.get(ADDITIONAL_DATA);
            int type = 0;
			String buttonData = "";

            if(additionalData.has("responses"))
            {
                type = 1;
				buttonData = additionalData.get("responses").toString().replaceAll("\\[", "%5B").replaceAll("\\{", "%7B").replaceAll("\"","%22").replaceAll("\\]","%5D").replaceAll("\\}","%7D");
            } 

            //Date + Time
            Date curDate = new Date();
			Calendar cal = Calendar.getInstance();
			cal.setTime(curDate);
			
			Log.d(LOG_TAG, "curDate : " + curDate);
			Log.d(LOG_TAG, "cal : " + cal);
			

            int dd = cal.get(Calendar.DAY_OF_MONTH);
            int mm = cal.get(Calendar.MONTH);
            int yyyy = cal.get(Calendar.YEAR);
            int hh = curDate.getHours();
            int min = curDate.getMinutes();
            int sec = curDate.getSeconds();
			
			Log.d(LOG_TAG, "mm : " + cal.get(Calendar.MONTH));
			Log.d(LOG_TAG, "mm : " + mm);
			
			String date = "";
            String time = "";

            if (dd < 10) {
                date += '0';
            }
			date += dd + "-";
			
			if (mm < 10) {
                date += '0';
            }
			date += mm + "-" + yyyy;
			
			if (hh < 10) {
                time += '0';
            }
			time += hh + ":";
			
			if (min < 10) {
                time += '0';
            }
			time += min + ":";
			
			if (sec < 10) {
                time += '0';
            }
			time += sec;

            //Insert

            myStatement = myDb.compileStatement("INSERT INTO NOTIFICATION (id,notId,title,message,date,time,type,button,response,responseId) VALUES ("+rowCount+",\""+additionalData.get("notId")+"\",\""+json.get(TITLE)+"\",\""+json.get(MESSAGE)+"\",\""+date+"\",\""+time+"\","+type+",\""+buttonData+"\",\"\",\"\")");
            Log.d(LOG_TAG, "myStatement : " + myStatement);
            long insertId = -1; // (invalid)
            insertId = myStatement.executeInsert();
            Log.d(LOG_TAG, "insertId2 : " + insertId);

        } catch (Exception ex) {
            // report error result with the error message
            // could be constraint violation or some other error
            ex.printStackTrace();
            String errorMessage = ex.getMessage();
            Log.d(LOG_TAG, "Error=" + errorMessage);
        }
    }
    //Added by AYOTTA team

    public static boolean isInForeground() {
      return gForeground;
    }

    public static boolean isActive() {
        return gWebView != null;
    }
}
