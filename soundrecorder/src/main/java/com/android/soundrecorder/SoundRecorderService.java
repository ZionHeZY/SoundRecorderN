package com.android.soundrecorder;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.support.v4.content.FileProvider;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.io.File;
import java.io.IOException;

import static com.android.internal.app.IntentForwarderActivity.TAG;


public class SoundRecorderService extends Service implements MediaRecorder.OnErrorListener, MediaRecorder.OnInfoListener {

    public final static String ACTION_NAME = "action_type";

    public final static int ACTION_INVALID = 0;

    public final static int ACTION_START_RECORDING = 1;

    public final static int ACTION_STOP_RECORDING = 2;

    public final static int ACTION_ENABLE_MONITOR_REMAIN_TIME = 3;

    public final static int ACTION_DISABLE_MONITOR_REMAIN_TIME = 4;

    public final static int ACTION_PAUSE_RECORDING = 5;

    public final static int ACTION_RESUME_RECORDING = 6;

    public final static String ACTION_PARAM_FORMAT = "format";

    public final static String ACTION_PARAM_CHANNELS = "channels";

    public final static String ACTION_PARAM_SAMPLINGRATE = "samplingrate";

    public final static String ACTION_PARAM_BITRATE = "bitrate";

    public final static String ACTION_PARAM_PATH = "path";

    public final static String ACTION_PARAM_MAXDURATION = "maxduration";

    public final static String ACTION_PARAM_AUDIOSOURCETYPE = "audiosourcetype";

    public final static String ACTION_PARAM_CODECTYPE = "codectype";

    public final static String RECORDER_SERVICE_BROADCAST_NAME = "com.android.soundrecorder.broadcast";

    public final static String RECORDER_SERVICE_BROADCAST_STATE = "is_recording";

    public final static String RECORDER_SERVICE_BROADCAST_ERROR = "error_code";

    public final static int NOTIFICATION_ID = 62343234;

    private static MediaRecorder mRecorder = null;

    private static String mFilePath = null;

    private static long mStartTime = 0;

    private RemainingTimeCalculator mRemainingTimeCalculator;

    private NotificationManager mNotifiManager;

    private Notification mLowStorageNotification;

    private TelephonyManager mTeleManager;

    private WakeLock mWakeLock;

    private KeyguardManager mKeyguardManager;

    static Context mContext = null;

    private BroadcastReceiver mScreenOnOffReceiver = null;

    private final PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
        @Override
        public void onCallStateChanged(int state, String incomingNumber) {
            if (state != TelephonyManager.CALL_STATE_IDLE) {
                Log.d("______________", "CALL_STATE_IDLE");
                localStopRecording();
            }
        }
    };

    private final Handler mHandler = new Handler();

    private Runnable mUpdateRemainingTime = new Runnable() {
        public void run() {
            if (mRecorder != null && mNeedUpdateRemainingTime) {
                updateRemainingTime();
            }
        }
    };

    private boolean mNeedUpdateRemainingTime;

    MediaRecorder.OnErrorListener mMRErrorListener = new MediaRecorder.OnErrorListener() {
        public void onError(MediaRecorder mr, int what, int extra) {
            localStopRecording();
            sendErrorBroadcast(Recorder.RECORD_INTERRUPTED);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d("_____++++++______", "ServiceonCreate");
        //mRecorder = null;
        mLowStorageNotification = null;
        mRemainingTimeCalculator = new RemainingTimeCalculator(this);
        mNeedUpdateRemainingTime = false;
        mNotifiManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mTeleManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        mTeleManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, this.getClass().getName());
        mWakeLock.setReferenceCounted(false);

        mKeyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);

        registerScreenOnOffListener();
    }

    public void registerScreenOnOffListener() {
        if (mScreenOnOffReceiver == null) {
            mScreenOnOffReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (action.equals(Intent.ACTION_SCREEN_ON)) {
                        Log.d("+++++++++++", "ACTION_SCREEN_ON Intent received");
                        if (null != mWakeLock) {
                            mWakeLock.release();
                        }
                    } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                        Log.d("+++++++++++", "ACTION_SCREEN_OFF Intent received");
                        Log.d("___________", "mWakeLock " + mWakeLock);
                        if (null == mWakeLock) {
                            Log.d("+++++++++++", "mWakeLock " + mWakeLock);
                            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, TAG);
                            if (null != mWakeLock) {
                                mWakeLock.acquire();
                            }
                        }

                    }
                }
            };
            IntentFilter iFilter = new IntentFilter();
            iFilter.addAction(Intent.ACTION_SCREEN_ON);
            iFilter.addAction(Intent.ACTION_SCREEN_OFF);
            registerReceiver(mScreenOnOffReceiver, iFilter);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            Bundle bundle = intent.getExtras();
            if (bundle != null && bundle.containsKey(ACTION_NAME)) {
                switch (bundle.getInt(ACTION_NAME, ACTION_INVALID)) {
                    case ACTION_START_RECORDING:
                        Log.d("_____++++++______", "ACTION_START_RECORDING");
                        localStartRecording(mContext,
                                bundle.getInt(ACTION_PARAM_FORMAT),
                                bundle.getInt(ACTION_PARAM_CHANNELS),
                                bundle.getInt(ACTION_PARAM_SAMPLINGRATE),
                                bundle.getInt(ACTION_PARAM_BITRATE),
                                bundle.getString(ACTION_PARAM_PATH),
                                bundle.getInt(ACTION_PARAM_MAXDURATION),
                                bundle.getInt(ACTION_PARAM_AUDIOSOURCETYPE),
                                bundle.getInt(ACTION_PARAM_CODECTYPE)
                        );
                        break;
                    case ACTION_STOP_RECORDING:
                        localStopRecording();
                        break;
                    case ACTION_PAUSE_RECORDING:
                        localPauseRecording();
                        break;
                    case ACTION_RESUME_RECORDING:
                        localResumeRecording();
                        break;
                    case ACTION_ENABLE_MONITOR_REMAIN_TIME:
                        if (mRecorder != null) {
                            mNeedUpdateRemainingTime = true;
                            mHandler.post(mUpdateRemainingTime);
                        }
                        break;
                    case ACTION_DISABLE_MONITOR_REMAIN_TIME:
                        mNeedUpdateRemainingTime = false;
                        if (mRecorder != null) {
                            showRecordingNotification();
                        }
                        break;
                    default:
                        break;
                }
                return START_STICKY;
            }
        }

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        Log.d("_____++++++______", "ServiceonDestroy");
        mTeleManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE);
        if (mWakeLock.isHeld()) {
            mWakeLock.release();
        }
        if (isRecording()) {
            sendErrorBroadcast(Recorder.RECORD_INTERRUPTED);
        }
        mWakeLock.release();

        if (mScreenOnOffReceiver != null) {
            unregisterReceiver(mScreenOnOffReceiver);
            mScreenOnOffReceiver = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onLowMemory() {
        localStopRecording();
        super.onLowMemory();
    }

    private void localStartRecording(Context context,
                                     int outputfileformat,
                                     int mChannels,
                                     int mSamplingRate,
                                     int mBitRate,
                                     String path,
                                     int mMaxDuration,
                                     int audiosourcetype,
                                     int codectype) {
        if (mRecorder == null) {
            mRecorder = new MediaRecorder();
            mRecorder.setAudioSource(audiosourcetype);
            //set channel for surround sound recording.
            if (mChannels > 0) {
                mRecorder.setAudioChannels(mChannels);
            }
            if (mSamplingRate > 0) {
                mRecorder.setAudioSamplingRate(mSamplingRate);
            }
            if (mBitRate > 0) {
                mRecorder.setAudioEncodingBitRate(mBitRate);
            }

            mRecorder.setOutputFormat(outputfileformat);
            mRecorder.setOnErrorListener(mMRErrorListener);

            mRecorder.setMaxDuration(mMaxDuration);
            mRecorder.setOnInfoListener(this);

            try {
                mRecorder.setAudioEncoder(codectype);
            } catch (RuntimeException exception) {
                sendErrorBroadcast(Recorder.UNSUPPORTED_FORMAT);
                mRecorder.reset();
                mRecorder.release();
                /*if (mSampleFile != null) mSampleFile.delete();
                mSampleFile = null;
                mSampleLength = 0;*/
                mRecorder = null;
                return;
            }

            mRecorder.setOutputFile(path);
            Log.d("+++++++++++++++++++", "path+" + path);
            // Handle IOException
            try {
                mRecorder.prepare();
            } catch (IOException exception) {
                sendErrorBroadcast(Recorder.INTERNAL_ERROR);
                Log.d("+++++++++++++++++++", "INTERNAL_ERROR");
                mRecorder.reset();
                mRecorder.release();
            /*if (mSampleFile != null) mSampleFile.delete();
            mSampleFile = null;
            mSampleLength = 0;*/
                mRecorder = null;
                return;
            }
            // Handle RuntimeException if the recording couldn't start
            try {
                Log.d("_____++++++______", "mRecorder.start");
                mRecorder.start();
            } catch (RuntimeException exception) {
                AudioManager audioMngr = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                boolean isInCall = ((audioMngr.getMode() == AudioManager.MODE_IN_CALL) ||
                        (audioMngr.getMode() == AudioManager.MODE_IN_COMMUNICATION));
                if (isInCall) {
                    sendErrorBroadcast(Recorder.IN_CALL_RECORD_ERROR);
                } else {
                    sendErrorBroadcast(Recorder.INTERNAL_ERROR);
                }
                mRecorder.reset();
                mRecorder.release();
                mRecorder = null;
                return;
            }
            mFilePath = path;
            mStartTime = System.currentTimeMillis();
            mWakeLock.acquire();
            mNeedUpdateRemainingTime = false;
            sendStateBroadcast();
            showRecordingNotification();
        }
    }

    private void localStopRecording() {
        if (mRecorder != null) {
            mNeedUpdateRemainingTime = false;
            try {
                mRecorder.stop();
            } catch (RuntimeException exception) {
                sendErrorBroadcast(Recorder.INTERNAL_ERROR);
                Log.e(TAG, "Stop Failed");
            }

            mRecorder.release();
            mRecorder = null;
            sendStateBroadcast();
            showStoppedNotification();
        }
        stopSelf();
    }

    private void localPauseRecording() {
        if (mRecorder == null) {
            return;
        }
        try {
            mRecorder.pause();
        } catch (RuntimeException exception) {
            sendErrorBroadcast(Recorder.INTERNAL_ERROR);
            mRecorder.release();
            mRecorder = null;
            Log.e(TAG, "Pause Failed");
        }
    }

    private void localResumeRecording() {
        Log.d("_____++++++______", "localResumeRecording");
        if (mRecorder == null) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                mRecorder.resume();
            } else {
                mRecorder.start();
            }
        } catch (RuntimeException exception) {
            sendErrorBroadcast(Recorder.INTERNAL_ERROR);
            mRecorder.release();
            mRecorder = null;
            Log.e(TAG, "Resume Failed");
        }
    }

    private void showRecordingNotification() {

        NotificationManager manager = (NotificationManager) this.getSystemService(Context.NOTIFICATION_SERVICE);
        PendingIntent pendingIntent;
        pendingIntent = PendingIntent
                .getActivity(this, 0, new Intent(this, SoundRecorder.class), 0);
        Notification.Builder builder = new Notification.Builder(this).setContentTitle(getString(R.string.notification_recording))
                .setSmallIcon(R.drawable.stat_sys_call_record).setContentIntent(pendingIntent).setContentText(getString(R.string.app_name));
        Notification notification = builder.getNotification();
        notification.flags |= Notification.FLAG_ONGOING_EVENT;
        manager.notify(NOTIFICATION_ID, notification);

        /*Notification notification = new Notification(R.drawable.stat_sys_call_record,
                getString(R.string.notification_recording), System.currentTimeMillis());
        notification.flags = Notification.FLAG_ONGOING_EVENT;
        PendingIntent pendingIntent;
        pendingIntent = PendingIntent
                .getActivity(this, 0, new Intent(this, SoundRecorder.class), 0);

        notification.setLatestEventInfo(this, getString(R.string.app_name),
                getString(R.string.notification_recording), pendingIntent);

        startForeground(NOTIFICATION_ID, notification);*/
    }

    private void showLowStorageNotification(int minutes) {
        if (mKeyguardManager.inKeyguardRestrictedInputMode()) {
            // it's not necessary to show this notification in lock-screen
            return;
        }
        NotificationManager manager = (NotificationManager) this.getSystemService(Context.NOTIFICATION_SERVICE);
        PendingIntent pendingIntent;
        pendingIntent = PendingIntent
                .getActivity(this, 0, new Intent(this, SoundRecorder.class), 0);

        if (mLowStorageNotification == null) {
            Notification.Builder builder = new Notification.Builder(this).setContentTitle(getString(R.string.notification_recording))
                    .setSmallIcon(R.drawable.stat_sys_call_record_full).setContentIntent(pendingIntent);
            Notification mLowStorageNotification = builder.getNotification();
            mLowStorageNotification.flags |= Notification.FLAG_ONGOING_EVENT;

            /*mLowStorageNotification = new Notification(R.drawable.stat_sys_call_record_full,
                    getString(R.string.notification_recording), System.currentTimeMillis());
            mLowStorageNotification.flags = Notification.FLAG_ONGOING_EVENT;*/
        }

        Notification.Builder builder = new Notification.Builder(this).setContentTitle(getString(R.string.notification_warning, minutes))
                .setSmallIcon(R.drawable.stat_sys_call_record_full).setContentIntent(pendingIntent).setContentText(getString(R.string.app_name));
        mLowStorageNotification = builder.getNotification();
        manager.notify(NOTIFICATION_ID, mLowStorageNotification);

        /*mLowStorageNotification.setLatestEventInfo(this, getString(R.string.app_name),
                getString(R.string.notification_warning, minutes), pendingIntent);
        startForeground(NOTIFICATION_ID, mLowStorageNotification);*/
    }

    private void showStoppedNotification() {
        stopForeground(true);
        mLowStorageNotification = null;
        NotificationManager manager = (NotificationManager) this.getSystemService(Context.NOTIFICATION_SERVICE);
        /*Notification notification = new Notification(R.drawable.stat_sys_call_record,
                getString(R.string.notification_stopped), System.currentTimeMillis());
        notification.flags = Notification.FLAG_AUTO_CANCEL;*/
        Intent intent = new Intent(Intent.ACTION_VIEW);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Uri contentUri = FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".fileProvider", new File(mFilePath));
            intent.setDataAndType(contentUri, "application/vnd.android.package-archive");
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.setType("audio/*");
            intent.setDataAndType(Uri.fromFile(new File(mFilePath)), "audio/*");
        }

        PendingIntent pendingIntent;
        pendingIntent = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder builder = new Notification.Builder(this).setContentTitle(getString(R.string.notification_stopped))
                .setSmallIcon(R.drawable.stat_sys_call_record).setContentIntent(pendingIntent).setContentText(getString(R.string.app_name));
        Notification notification = builder.getNotification();
        notification.flags |= Notification.FLAG_AUTO_CANCEL;
        manager.notify(NOTIFICATION_ID, notification);

        /*notification.setLatestEventInfo(this, getString(R.string.app_name),
                getString(R.string.notification_stopped), pendingIntent);
        mNotifiManager.notify(NOTIFICATION_ID, notification);*/
    }

    private void sendStateBroadcast() {
        Intent intent = new Intent(RECORDER_SERVICE_BROADCAST_NAME);
        intent.putExtra(RECORDER_SERVICE_BROADCAST_STATE, mRecorder != null);
        sendBroadcast(intent);
    }

    private void sendErrorBroadcast(int error) {
        Intent intent = new Intent(RECORDER_SERVICE_BROADCAST_NAME);
        intent.putExtra(RECORDER_SERVICE_BROADCAST_ERROR, error);
        sendBroadcast(intent);
    }

    public void updateRemainingTime() {
        long t = mRemainingTimeCalculator.timeRemaining();
        if (t <= 0) {
            localStopRecording();
            return;
        } else if (t <= 1800
                && mRemainingTimeCalculator.currentLowerLimit() != RemainingTimeCalculator.FILE_SIZE_LIMIT) {
            // less than half one hour
            showLowStorageNotification((int) Math.ceil(t / 60.0));
        }

        if (mRecorder != null && mNeedUpdateRemainingTime) {
            mHandler.postDelayed(mUpdateRemainingTime, 500);
        }
    }

    public static boolean isRecording() {
        return mRecorder != null;
    }

    public static String getFilePath() {
        return mFilePath;
    }

    public static long getStartTime() {
        return mStartTime;
    }

    public static void startRecording(Context context,
                                      int outputfileformat,
                                      int channels,
                                      int samplingRate,
                                      int bitRate,
                                      String path,
                                      int maxDuration,
                                      int audiosourcetype,
                                      int codectype) {
        mContext = context;
        Intent intent = new Intent(context, SoundRecorderService.class);
        intent.putExtra(ACTION_NAME, ACTION_START_RECORDING);
        intent.putExtra(ACTION_PARAM_FORMAT, outputfileformat);
        intent.putExtra(ACTION_PARAM_CHANNELS, channels);
        intent.putExtra(ACTION_PARAM_SAMPLINGRATE, samplingRate);
        intent.putExtra(ACTION_PARAM_BITRATE, bitRate);
        intent.putExtra(ACTION_PARAM_MAXDURATION, maxDuration);
        intent.putExtra(ACTION_PARAM_PATH, path);
        intent.putExtra(ACTION_PARAM_AUDIOSOURCETYPE, audiosourcetype);
        intent.putExtra(ACTION_PARAM_CODECTYPE, codectype);
        Log.d("+++++++++++++++++++", "startService");
        context.startService(intent);
    }

    public static void stopRecording(Context context) {
        Intent intent = new Intent(context, SoundRecorderService.class);
        intent.putExtra(ACTION_NAME, ACTION_STOP_RECORDING);
        context.startService(intent);
    }

    public static void pauseRecording(Context context) {
        Intent intent = new Intent(context, SoundRecorderService.class);
        intent.putExtra(ACTION_NAME, ACTION_PAUSE_RECORDING);
        context.startService(intent);
    }

    public static void resumeRecording(Context context) {
        Intent intent = new Intent(context, SoundRecorderService.class);
        intent.putExtra(ACTION_NAME, ACTION_RESUME_RECORDING);
        context.startService(intent);
    }

    public static int getMaxAmplitude() {
        Log.d("+++++++++++++++++++", "getMaxAmplitude" + mRecorder);
        return mRecorder == null ? 0 : mRecorder.getMaxAmplitude();
    }

    @Override
    public void onError(MediaRecorder mr, int what, int extra) {
        sendErrorBroadcast(Recorder.INTERNAL_ERROR);
        localStopRecording();
    }

    @Override
    public void onInfo(MediaRecorder mr, int what, int extra) {

    }
}
