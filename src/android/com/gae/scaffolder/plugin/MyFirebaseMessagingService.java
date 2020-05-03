package com.gae.scaffolder.plugin;

import androidx.core.app.NotificationCompat;
import android.app.NotificationChannel;
import androidx.core.app.NotificationManagerCompat;

import android.graphics.drawable.Icon;
import android.os.Build;
import java.util.ArrayList;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.media.RingtoneManager;
import android.net.Uri;
import android.util.Log;
import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import android.R;
import android.content.res.Resources;
import android.content.Intent;
import java.security.SecureRandom;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public class MyFirebaseMessagingService extends FirebaseMessagingService {

    private static final String TAG = "FCMPlugin";

    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        Log.d(TAG, "New token: " + token);
    }

    /**
     * Called when message is received.
     *
     * @param remoteMessage Object representing the message received from Firebase Cloud Messaging.
     */
    // [START receive_message]
    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        // TODO(developer): Handle FCM messages here.
        // If the application is in the foreground handle both data and notification messages here.
        // Also if you intend on generating your own notifications as a result of a received FCM
        // message, here is where that should be initiated. See sendNotification method below.
        Log.d(TAG, "==> MyFirebaseMessagingService onMessageReceived");
        Bundle extras = new Bundle();
        Context context = getApplicationContext();
        if (remoteMessage.getNotification() != null) {
            Log.d(TAG, "\tNotification Title: " + remoteMessage.getNotification().getTitle());
            Log.d(TAG, "\tNotification Message: " + remoteMessage.getNotification().getBody());
        }

        Map<String, Object> data = new HashMap<String, Object>();
        data.put("wasTapped", false);

        if (remoteMessage.getNotification() != null) {
            data.put("title", remoteMessage.getNotification().getTitle());
            data.put("body", remoteMessage.getNotification().getBody());
        }

        for (String key : remoteMessage.getData().keySet()) {
            Object value = remoteMessage.getData().get(key);
            Log.d(TAG, "\tKey: " + key + " Value: " + value);
            data.put(key, value);
            extras.putString(key, value.toString());
        }

        Log.d(TAG, "\tNotification Data: " + data.toString());

        if(FCMPlugin.isInForeground()){
            FCMPlugin.sendPushPayload(data);
        } else {
            showNotification(context,extras);
        }
    }
    // [END receive_message]

    /*
     * Parse bundle into normalized keys.
     */
    private Bundle normalizeExtras(Context context, Bundle extras, String messageKey, String titleKey) {
        Log.d(TAG, "normalize extras");
        Iterator<String> it = extras.keySet().iterator();
        Bundle newExtras = new Bundle();

        while (it.hasNext()) {
            String key = it.next();

            Log.d(TAG, "key = " + key);

            // If normalizeKeythe key is "data" or "message" and the value is a json object extract
            // This is to support parse.com and other services. Issue #147 and pull #218
            if (key.equals("data") || key.equals("message") || key.equals(messageKey)) {
                Object json = extras.get(key);
                // Make sure data is json object stringified
                if (json instanceof String && ((String) json).startsWith("{")) {
                    Log.d(TAG, "extracting nested message data from key = " + key);
                    try {
                        // If object contains message keys promote each value to the root of the bundle
                        JSONObject data = new JSONObject((String) json);
                        if (data.has("alert") || data.has("message") || data.has("body") || data.has("title") || data.has(messageKey)
                                || data.has(titleKey)) {
                            Iterator<String> jsonIter = data.keys();
                            while (jsonIter.hasNext()) {
                                String jsonKey = jsonIter.next();

                                Log.d(TAG, "key = data/" + jsonKey);

                                String value = data.getString(jsonKey);
                                jsonKey = normalizeKey(jsonKey, messageKey, titleKey, newExtras);
                                value = localizeKey(context, jsonKey, value);

                                newExtras.putString(jsonKey, value);
                            }
                        } else if (data.has("locKey") || data.has("locData")) {
                            String newKey = normalizeKey(key, messageKey, titleKey, newExtras);
                            Log.d(TAG, "replace key " + key + " with " + newKey);
                            replaceKey(context, key, newKey, extras, newExtras);
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "normalizeExtras: JSON exception");
                    }
                } else {
                    String newKey = normalizeKey(key, messageKey, titleKey, newExtras);
                    Log.d(TAG, "replace key " + key + " with " + newKey);
                    replaceKey(context, key, newKey, extras, newExtras);
                }
            } else if (key.equals(("notification"))) {
                Bundle value = extras.getBundle(key);
                Iterator<String> iterator = value.keySet().iterator();
                while (iterator.hasNext()) {
                    String notifkey = iterator.next();

                    Log.d(TAG, "notifkey = " + notifkey);
                    String newKey = normalizeKey(notifkey, messageKey, titleKey, newExtras);
                    Log.d(TAG, "replace key " + notifkey + " with " + newKey);

                    String valueData = value.getString(notifkey);
                    valueData = localizeKey(context, newKey, valueData);

                    newExtras.putString(newKey, valueData);
                }
                continue;
                // In case we weren't working on the payload data node or the notification node,
                // normalize the key.
                // This allows to have "message" as the payload data key without colliding
                // with the other "message" key (holding the body of the payload)
                // See issue #1663
            } else {
                String newKey = normalizeKey(key, messageKey, titleKey, newExtras);
                Log.d(TAG, "replace key " + key + " with " + newKey);
                replaceKey(context, key, newKey, extras, newExtras);
            }

        } // while

        return newExtras;
    }

    /*
     * Replace alternate keys with our canonical value
     */
    private String normalizeKey(String key, String messageKey, String titleKey, Bundle newExtras) {
        if (key.equals("body") || key.equals("alert") || key.equals("mp_message") || key.equals("gcm.notification.body")
                || key.equals("twi_body") || key.equals(messageKey) || key.equals("pinpoint.notification.body")) {
            return "message";
        } else if (key.equals("twi_title") || key.equals("subject") || key.equals(titleKey)) {
            return "title";
        } else if (key.equals("msgcnt") || key.equals("badge")) {
            return "count";
        } else if (key.equals("soundname") || key.equals("twi_sound")) {
            return "sound";
        } else if (key.equals("pinpoint.notification.imageUrl")) {
            newExtras.putString("style", "picture");
            return "picture";
        } else if (key.startsWith("gcm.notification")) {
            return key.substring("gcm.notification".length() + 1, key.length());
        } else if (key.startsWith("gcm.n.")) {
            return key.substring("gcm.n.".length() + 1, key.length());
        } else if (key.startsWith("com.urbanairship.push")) {
            key = key.substring("com.urbanairship.push".length() + 1, key.length());
            return key.toLowerCase();
        } else if (key.startsWith("pinpoint.notification")) {
            return key.substring("pinpoint.notification".length() + 1, key.length());
        } else {
            return key;
        }
    }

    /*
     * Normalize localization for key
     */
    private String localizeKey(Context context, String key, String value) {
        if (key.equals("title") || key.equals("message") || key.equals("summaryText")) {
            try {
                JSONObject localeObject = new JSONObject(value);

                String localeKey = localeObject.getString("locKey");

                ArrayList<String> localeFormatData = new ArrayList<String>();
                if (!localeObject.isNull("locData")) {
                    String localeData = localeObject.getString("locData");
                    JSONArray localeDataArray = new JSONArray(localeData);
                    for (int i = 0; i < localeDataArray.length(); i++) {
                        localeFormatData.add(localeDataArray.getString(i));
                    }
                }

                String packageName = context.getPackageName();
                Resources resources = context.getResources();

                int resourceId = resources.getIdentifier(localeKey, "string", packageName);

                if (resourceId != 0) {
                    return resources.getString(resourceId, localeFormatData.toArray());
                } else {
                    Log.d(TAG, "can't find resource for locale key = " + localeKey);

                    return value;
                }
            } catch (JSONException e) {
                Log.d(TAG, "no locale found for key = " + key + ", error " + e.getMessage());

                return value;
            }
        }

        return value;
    }

    /*
     * Change a values key in the extras bundle
     */
    private void replaceKey(Context context, String oldKey, String newKey, Bundle extras, Bundle newExtras) {
        Object value = extras.get(oldKey);
        if (value != null) {
            if (value instanceof String) {
                value = localizeKey(context, newKey, (String) value);

                newExtras.putString(newKey, (String) value);
            } else if (value instanceof Boolean) {
                newExtras.putBoolean(newKey, (Boolean) value);
            } else if (value instanceof Number) {
                newExtras.putDouble(newKey, ((Number) value).doubleValue());
            } else {
                newExtras.putString(newKey, String.valueOf(value));
            }
        }
    }

    private void showNotification(Context context, Bundle extras){

        final NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        extras = normalizeExtras(context, extras, "message", "title");
        int notId = parseInt("notId", extras);

        Intent notificationIntent = new Intent(this, FCMPluginActivity.class);
        notificationIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        notificationIntent.putExtras(extras);
        notificationIntent.putExtra("notId", notId);

        SecureRandom random = new SecureRandom();
        int requestCode = random.nextInt();
        PendingIntent contentIntent = PendingIntent.getActivity(this, requestCode, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT);

        int iconId = 0;
        String icon = "fcm_push_push";
        iconId = context.getResources().getIdentifier(icon,"DRAWABLE",context.getPackageName());
        if(iconId == 0){
            Log.d(TAG, "icon mipmap");
            iconId = context.getResources().getIdentifier(icon,"mipmap",context.getPackageName());
        }
        if(iconId == 0){
            Log.d(TAG, "get application info icon");
            iconId = context.getApplicationInfo().icon;
        }
        String message = extras.getString("message");
        String title = extras.getString("title");
        Integer count = Integer.parseInt(extras.getString("unreadAlertCount"));
        NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext(), "JMobile_DEFAULT_CHANNEL_ID")
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(iconId)
                .setContentIntent(contentIntent)
                .setNumber(count)
                .setPriority(NotificationCompat.PRIORITY_HIGH);

        // notificationId is a unique int for each notification that you must define
        notificationManager.notify(notId, builder.build());
    }

    private int parseInt(String value, Bundle extras) {
        int retval = 0;

        try {
            retval = Integer.parseInt(extras.getString(value));
        } catch (NumberFormatException e) {
            Log.e(TAG, "Number format exception - Error parsing " + value + ": " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Number format exception - Error parsing " + value + ": " + e.getMessage());
        }

        return retval;
    }
}
